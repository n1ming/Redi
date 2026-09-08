package com.redi.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redi.config.AgentConfig;
import com.redi.llm.LlmClient;
import com.redi.llm.LlmMessage;
import com.redi.llm.ToolCall;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 引擎:接收玩家任务 → 组装系统提示与工具 → LLM 工具调用循环 → 结果写回
 * {@link ChatModel}。全部异步,UI 只管读 ChatModel。
 *
 * <p>引擎用固定单线程 Executor 排队执行。发给模型的对话历史按“轮”组织
 * (一轮 = 一条 user,或一条 assistant(含 tool_calls)加上它的全部 tool 结果),
 * 历史不设本地上限、全量发送(玩家要求);只有服务商因超出其上下文窗口返回 400 时,
 * 才由 {@link #trimRoundsForProvider()} 自适配裁剪兜底。system 每次请求重建,不占历史槽位。</p>
 *
 * <p>健壮性:模型返回空内容(无工具调用且无文本)时用相同请求自动重试一次,
 * 再空则以 NOTE 提示玩家,绝不静默结束;同一“工具+参数”连续重复到第 3 次
 * 不再真正执行,直接提示模型基于已有信息作答,防止空转;被 max_tokens 掐断的回答
 * 自动发“继续”接写。每个工具执行期间会在 ChatModel 上更新活动描述供手机界面展示。</p>
 */
public final class AgentEngine {
    private static final AgentEngine INSTANCE = new AgentEngine();

    /** 同一“工具+参数”签名连续出现到第几次时跳过真正执行。 */
    private static final int REPEAT_SKIP_AT = 3;

    public static AgentEngine get() {
        return INSTANCE;
    }

    private AgentEngine() {
    }

    /** 引擎单线程:LLM 请求与工具执行都在这里排队跑。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "redi-engine");
        t.setDaemon(true);
        return t;
    });

    /** 对话历史(不含 system),按轮组织;引擎线程写,读取处加锁。 */
    private final ArrayDeque<List<LlmMessage>> rounds = new ArrayDeque<>();

    /** 取消标志:工具循环每轮、每个工具调用前检查。 */
    private volatile boolean cancelled = false;

    /** 当前任务在用的 LLM 客户端(cancel 时即时打断在途 HTTP 请求)。 */
    private volatile com.redi.llm.LlmClient activeClient;

    /** 本任务内“工具+参数”签名 → 出现次数(引擎线程内访问,无需并发保护;新任务清空)。 */
    private final java.util.HashMap<String, Integer> sigCounts = new java.util.HashMap<>();

    /** 当前任务原文(buildRequest 兜底补 user 开头用,引擎线程内访问)。 */
    private String currentTask = "";

    /** 工具名的中文标注(UI 展示用:调用工具时在名字后面加括号给玩家看)。 */
    private static final Map<String, String> TOOL_ZH = buildToolZh();

    private static Map<String, String> buildToolZh() {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("player_context", "玩家状态");
        m.put("get_target", "准星目标");
        m.put("inspect_item", "查物品");
        m.put("find_recipes", "查配方");
        m.put("craft_item", "自动合成");
        m.put("smelt_item", "自动冶炼");
        m.put("list_items", "列物品");
        m.put("read_lang", "读语言文件");
        m.put("read_resource", "读资源文件");
        m.put("kb_search", "查本地知识库");
        m.put("command_usage", "查指令语法");
        m.put("list_mods", "列模组");
        m.put("list_classes", "列模组类");
        m.put("inspect_class", "看类结构");
        m.put("mod_overview", "模组速览");
        m.put("send_chat", "发聊天");
        m.put("send_command", "执行指令");
        m.put("read_guidebook", "读模组手册");
        m.put("web_search", "联网搜索");
        m.put("web_read", "读网页");
        return m;
    }

    /** 工具显示名:name(中文标注);未登记的工具返回原名。 */
    private static String toolLabel(String name) {
        String zh = TOOL_ZH.get(name);
        return zh == null ? name : name + "(" + zh + ")";
    }

    /** 提交一条玩家消息,异步执行;状态与结果都反映在 ChatModel 上。 */
    public void submit(String userText) {
        ChatModel chat = ChatModel.get();
        if (chat.busy()) {
            chat.append(ChatModel.Role.NOTE, "上一个任务还在进行中,请等它结束或点「■」停止。");
            return;
        }
        if (userText == null || userText.isBlank()) return;
        String text = userText.trim();
        chat.append(ChatModel.Role.USER, text);
        if (!AgentConfig.get().isConfigured()) {
            String msg = I18n.get("redi.chat.not_configured");
            if (msg.equals("redi.chat.not_configured")) {
                msg = "尚未配置模型接口。请点右上角齿轮进设置,填好 base_url、API Key 和模型名。";
            }
            chat.append(ChatModel.Role.ERROR, msg);
            return;
        }
        cancelled = false;
        sigCounts.clear();
        chat.setBusy(true);
        executor.execute(() -> runTask(text));
    }

    /** 请求中止当前任务:即时打断在途 LLM 请求 + 工具循环逐轮退出。 */
    public void cancel() {
        cancelled = true;
        com.redi.llm.LlmClient c = activeClient;
        if (c != null) {
            c.abortInFlight();
        }
    }

    /** 开启新对话(清空发给模型的上下文;不清显示记录)。 */
    public void resetConversation() {
        synchronized (rounds) {
            rounds.clear();
        }
        ChatModel.get().append(ChatModel.Role.NOTE, "已开启新对话(模型记忆已清空,聊天记录保留)。");
    }

    /**
     * 结构化对话历史深拷贝快照(会话存档用:切换历史会话时回灌,模型才能“记得”那段对话)。
     */
    public List<List<LlmMessage>> snapshotRounds() {
        synchronized (rounds) {
            List<List<LlmMessage>> out = new ArrayList<>(rounds.size());
            for (List<LlmMessage> r : rounds) {
                out.add(new ArrayList<>(r));
            }
            return out;
        }
    }

    /** 回灌历史会话的结构化上下文(rounds 为空/旧格式存档则不动当前上下文);quiet=不追加提示(自动恢复用)。 */
    public void restoreRounds(List<List<LlmMessage>> restored, boolean quiet) {
        if (restored == null || restored.isEmpty()) {
            return;
        }
        synchronized (rounds) {
            rounds.clear();
            for (List<LlmMessage> r : restored) {
                if (r != null && !r.isEmpty()) {
                    rounds.addLast(new ArrayList<>(r));
                }
            }
        }
        if (!quiet) {
            ChatModel.get().append(ChatModel.Role.NOTE,
                    "已载入历史会话,模型上下文已恢复(" + restored.size() + " 轮),可以直接“继续”。");
        }
    }

    /** 回灌并显示提示(历史列表手动载入用)。 */
    public void restoreRounds(List<List<LlmMessage>> restored) {
        restoreRounds(restored, false);
    }

    // ------------------------------------------------------------------

    /** 引擎主流程:工具调用循环。在引擎线程执行。 */
    private void runTask(String userText) {
        ChatModel chat = ChatModel.get();
        AgentConfig cfg = AgentConfig.get();
        try {
            if (!cfg.isConfigured()) {
                chat.append(ChatModel.Role.ERROR, "尚未配置模型接口,无法执行。");
                return;
            }
            // 新任务清空“重复调用”计数
            sigCounts.clear();
            currentTask = userText;
            synchronized (rounds) {
                rounds.addLast(List.of(LlmMessage.user(userText)));
            }
            LlmClient client = new LlmClient(cfg);
            activeClient = client;
            // 步数不设限(玩家明确要求无限):任务一直跑到模型自己说完、或玩家按「■」停止;
            // settings 里 maxToolIterations > 0 时才启用自定义上限
            int hardCap = cfg.maxToolIterations > 0 ? cfg.maxToolIterations : Integer.MAX_VALUE;
            List<String> think = new ArrayList<>();
            for (int iter = 0; ; iter++) {
                if (cancelled) {
                    chat.append(ChatModel.Role.NOTE, "已停止。");
                    return;
                }
                if (iter >= hardCap) {
                    chat.append(ChatModel.Role.NOTE,
                            "任务步数过多,已暂停;可以继续追问或开新对话。");
                    return;
                }
                chat.setActivity("思考中…");
                LlmClient.Response resp = request(client, chat);
                if (resp == null) return; // 请求失败,已在 request() 里向玩家报错
                // 模型的原始思考文本(reasoning_content)实时进思考块,像主流 agent 一样可展开回看
                String reasoning = resp.reasoning() == null ? "" : resp.reasoning().trim();
                if (!reasoning.isEmpty()) {
                    if (think.isEmpty()) {
                        chat.beginThink();
                    }
                    chat.addThinkStep("思考:" + reasoning);
                    think.add("思考:" + reasoning);
                }
                String content = resp.content() == null ? "" : resp.content().trim();
                if (!resp.hasToolCalls() && content.isEmpty()) {
                    // 空响应:用完全相同的请求自动重试一次,避免聊天界面静默空白
                    resp = request(client, chat);
                    if (resp == null) return;
                    content = resp.content() == null ? "" : resp.content().trim();
                    if (!resp.hasToolCalls() && content.isEmpty()) {
                        chat.append(ChatModel.Role.NOTE,
                                "模型没有返回内容,已自动重试仍为空。请换个说法再试,或点「■」停止。");
                        return;
                    }
                }
                if (!resp.hasToolCalls()) {
                    // 没有工具调用 = 模型给出最终回答。被 max_tokens 掐断(length)时不结束:
                    // 把已生成的部分照常上屏,再补一条“继续”用户轮接着写,直到真正写完
                    if (!think.isEmpty()) {
                        chat.endThink();
                        think.clear();
                    }
                    if (!content.isEmpty()) {
                        chat.append(ChatModel.Role.ASSISTANT, content);
                    }
                    addRound(List.of(LlmMessage.assistant(content, null)));
                    if (!resp.truncatedByLength()) {
                        return;
                    }
                    chat.setActivity("回答被截断,自动续写…");
                    addRound(List.of(LlmMessage.user(
                            "(继续:刚才的回答因长度被截断,从中断处接着输出,不要重复已经说过的内容;完成即停)")));
                    continue;
                }
                // 一轮 = assistant(含 tool_calls) + 每个 call 对应的 tool 结果
                List<LlmMessage> round = new ArrayList<>();
                round.add(LlmMessage.assistant(content, resp.toolCalls()));
                for (ToolCall call : resp.toolCalls()) {
                    if (cancelled) {
                        // 消息序列要保持合法:每个 tool_call 都必须有 tool 结果
                        round.add(LlmMessage.tool(call.id(), "(玩家已停止本任务)"));
                        continue;
                    }
                    if (think.isEmpty()) {
                        chat.beginThink(); // 第一个工具调用时插入实时思考块
                    }
                    chat.setActivity("调用 " + toolLabel(call.name()) + "…");
                    String out = runTool(call);
                    chat.addThinkStep(toolLabel(call.name()) + (out.startsWith("工具执行出错") ? " ✗" : " ✓"));
                    think.add(toolLabel(call.name()) + (out.startsWith("工具执行出错") ? " ✗" : " ✓"));
                    com.redi.plugin.AgentContext.SHARED.emit("tool.called",
                            call.name() + "|" + (out.startsWith("工具执行出错") ? "err" : "ok"));
                    round.add(LlmMessage.tool(call.id(), out));
                }
                addRound(round);
            }
        } finally {
            chat.setActivity("");
            chat.endThink();
            chat.setBusy(false);
            // 任务结束事件:存档等后续逻辑由插件监听执行(会话持久化插件)
            com.redi.plugin.AgentContext.SHARED.emit("task.finished", userText);
        }
    }

    /**
     * 发一次请求;失败时向玩家报错并返回 null。
     * 400(服务商对超长/超深对话的消息校验限制)→ 裁掉最早的一半轮次后自动重试一次。
     * 瞬时故障(超时/连接中断)→ 自动重试一次(上下文很大时服务商处理可能超过一分钟)。
     */
    private LlmClient.Response request(LlmClient client, ChatModel chat) {
        try {
            try {
                return client.chat(buildRequest(), ToolRegistry.schemas());
            } catch (Exception first) {
                if (first instanceof com.redi.llm.LlmClient.BadRequestException || cancelled
                        || !isTransient(first)) {
                    throw first;
                }
                chat.setActivity("请求超时(上下文较大处理较慢),自动重试…");
                Thread.sleep(800);
                if (cancelled) {
                    throw first;
                }
                return client.chat(buildRequest(), ToolRegistry.schemas());
            }
        } catch (com.redi.llm.LlmClient.BadRequestException e) {
            // 400 自适配重试(全程备份,失败即恢复原对话,不污染后续):
            // ①结构化裁剪(保用户锚,最多 6 轮) ②恢复完整历史 + 切 content 回显格式(null/省略)
            // ③恢复完整 + 裁剪 + 切回
            java.util.List<java.util.List<LlmMessage>> backup;
            synchronized (rounds) {
                backup = new java.util.ArrayList<>(rounds);
            }
            try {
                trimRoundsForProvider();
                if (cancelled) {
                    chat.append(ChatModel.Role.NOTE, "已停止。");
                    return null;
                }
                return client.chat(buildRequest(), ToolRegistry.schemas());
            } catch (Exception e1) {
                // ①失败:恢复完整历史,切换回显格式
                synchronized (rounds) {
                    rounds.clear();
                    rounds.addAll(backup);
                }
                client.echoToolCallContent = !client.echoToolCallContent;
            }
            try {
                if (cancelled) {
                    chat.append(ChatModel.Role.NOTE, "已停止。");
                    return null;
                }
                return client.chat(buildRequest(), ToolRegistry.schemas());
            } catch (com.redi.llm.LlmClient.BadRequestException e2) {
                // ②仍 400:恢复完整历史 + 裁剪 + 翻回格式
                synchronized (rounds) {
                    rounds.clear();
                    rounds.addAll(backup);
                    trimRoundsForProvider();
                }
                client.echoToolCallContent = !client.echoToolCallContent;
                try {
                    if (cancelled) {
                        chat.append(ChatModel.Role.NOTE, "已停止。");
                        return null;
                    }
                    return client.chat(buildRequest(), ToolRegistry.schemas());
                } catch (Exception e4) {
                    // 全部尝试失败:恢复完整历史(绝不留下裁剪后的残缺状态毒化下一个任务)
                    synchronized (rounds) {
                        rounds.clear();
                        rounds.addAll(backup);
                    }
                    if (cancelled) {
                        chat.append(ChatModel.Role.NOTE, "已停止。");
                    } else {
                        chat.append(ChatModel.Role.ERROR,
                                "模型请求失败(已尝试格式切换与上下文裁剪): " + e4.getMessage());
                    }
                    return null;
                }
            } catch (Exception e3) {
                if (cancelled) {
                    chat.append(ChatModel.Role.NOTE, "已停止。");
                } else {
                    chat.append(ChatModel.Role.ERROR, "模型请求失败: " + e3.getMessage());
                }
                return null;
            }
        } catch (Exception e) {
            if (cancelled) {
                chat.append(ChatModel.Role.NOTE, "已停止。");
            } else {
                chat.append(ChatModel.Role.ERROR, "模型请求失败: " + describe(e));
            }
            return null;
        }
    }

    /** 瞬时故障判断:超时/连接类异常(上下文大时服务商处理慢,值得自动重试)。 */
    private static boolean isTransient(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getSimpleName();
            String m = String.valueOf(c.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (n.contains("Timeout") || n.contains("ConnectException") || m.contains("timed out")
                    || m.contains("timeout") || m.contains("connection reset")
                    || m.contains("connection refused")) {
                return true;
            }
        }
        return false;
    }

    /** 异常 → 玩家能看懂的中文(超时给上下文相关提示)。 */
    private static String describe(Throwable t) {
        if (isTransient(t)) {
            return "请求超时或网络中断(当前对话上下文很大,服务商处理较慢)。"
                    + "可再发一次重试;若反复出现,点「新对话」缩短上下文后会明显变快。";
        }
        return String.valueOf(t.getMessage() == null ? t : t.getMessage());
    }

    /** 执行单个工具调用,永不抛出(任何异常都转成中文错误文本回填给模型)。 */
    private String runTool(ToolCall call) {
        AgentTool tool = ToolRegistry.byName(call.name());
        if (tool == null) {
            return "工具执行出错: 未知工具 " + call.name();
        }
        ChatModel.get().setActivity("调用 " + toolLabel(tool.name()) + "…");
        JsonObject args;
        try {
            String raw = call.argumentsJson();
            args = JsonParser.parseString(raw == null || raw.isBlank() ? "{}" : raw).getAsJsonObject();
        } catch (Exception e) {
            return "工具执行出错: 参数不是合法 JSON(" + e.getMessage() + ")";
        }
        // 重复调用守卫:同一“工具+参数”在本任务内累计出现到第 3 次(不要求连续——
        // 模型常在中间夹别的调用后回来重试同一失败调用)时,不再真正执行,
        // 直接把提示文本回填给模型,逼它把失败原因告诉玩家而不是空转。
        String sig = call.name() + "|" + canonicalArgs(args);
        int n = sigCounts.merge(sig, 1, Integer::sum);
        if (n >= REPEAT_SKIP_AT) {
            return "(同一调用已失败/执行过 " + n + " 次,本次已跳过。不要再重复这个调用:"
                    + "把失败原因与下一步建议直接告诉玩家,或改用其它方式完成任务)";
        }
        try {
            String result = tool.execute(args);
            return result == null || result.isEmpty() ? "(工具没有返回内容)" : result;
        } catch (Throwable t) {
            return "工具执行出错: " + t;
        }
    }

    /** 参数规范化:键按字典序排序后的紧凑串,用于识别“完全相同”的重复调用。 */
    private static String canonicalArgs(JsonObject args) {
        StringBuilder sb = new StringBuilder();
        appendCanonical(args, sb);
        return sb.toString();
    }

    /** 递归规范化 JSON 元素:对象键排序、数组保序、原始值直出。 */
    private static void appendCanonical(JsonElement el, StringBuilder sb) {
        if (el == null || el.isJsonNull()) {
            sb.append("null");
        } else if (el.isJsonPrimitive()) {
            sb.append(el.getAsString());
        } else if (el.isJsonArray()) {
            sb.append('[');
            for (JsonElement x : el.getAsJsonArray()) {
                appendCanonical(x, sb);
                sb.append(',');
            }
            sb.append(']');
        } else {
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                sorted.put(e.getKey(), e.getValue());
            }
            sb.append('{');
            for (Map.Entry<String, JsonElement> e : sorted.entrySet()) {
                sb.append(e.getKey()).append('=');
                appendCanonical(e.getValue(), sb);
                sb.append(';');
            }
            sb.append('}');
        }
    }

    private void addRound(List<LlmMessage> round) {
        synchronized (rounds) {
            rounds.addLast(round);
        }
    }

    /**
     * 400 时的自适配裁剪:保留最近一半轮次(左边界绝不越过最近一条用户轮)。
     * 连续 400 时每次再减半,逐步逼近服务商能接受的大小——尽量多保留上下文,
     * 而不是一刀切到几轮。完全是为对抗服务商上下文窗口的物理上限,平时不触发。
     */
    private void trimRoundsForProvider() {
        synchronized (rounds) {
            java.util.List<java.util.List<LlmMessage>> snap = new java.util.ArrayList<>(rounds);
            int n = snap.size();
            int userAnchor = -1;
            for (int i = n - 1; i >= 0; i--) {
                List<LlmMessage> r = snap.get(i);
                if (!r.isEmpty() && r.get(0).role().equals("user")) {
                    userAnchor = i;
                    break;
                }
            }
            int start = Math.max(0, n - Math.max(1, n / 2)); // 保留最近一半
            if (userAnchor >= 0 && userAnchor < start) {
                start = userAnchor; // 绝不越过最近一条用户轮(GLM 1214 要求 user 开头)
            }
            while (rounds.size() > n - start && rounds.size() > 1) {
                rounds.pollFirst();
            }
        }
    }

    /**
     * 组装本次请求消息:system(每次重建)+ 历史全部轮次(玩家要求:本地不设任何上下文上限,
     * 历史全量发送;只有当服务商因超出其上下文窗口返回 400 时,才由 request() 的
     * 自适配裁剪兜底,自动保留最近的轮次让对话继续,而不是报错中断)。
     * 双保险保证 GLM 1214 的硬性要求——messages 以 user 开头。
     */
    private List<LlmMessage> buildRequest() {
        List<LlmMessage> out = new ArrayList<>();
        out.add(LlmMessage.system(buildSystemPrompt()));
        synchronized (rounds) {
            boolean empty = true;
            for (List<LlmMessage> r : rounds) {
                if (empty && !r.isEmpty() && !r.get(0).role().equals("user")) {
                    // 兜底:历史不以 user 开头时补一条,否则 GLM 1214 必拒
                    out.add(LlmMessage.user(currentTask == null || currentTask.isBlank()
                            ? "(继续)" : currentTask));
                }
                empty = false;
                out.addAll(r);
            }
            if (empty) {
                out.add(LlmMessage.user(currentTask == null || currentTask.isBlank()
                        ? "(继续)" : currentTask));
            }
        }
        return out;
    }

    /** 系统提示:身份 + 按取证优先级分层的工具清单(由注册表自动生成,新插件自动进层)+ 硬性规则。 */
    private String buildSystemPrompt() {
        StringBuilder mem = new StringBuilder();
        StringBuilder loc = new StringBuilder();
        StringBuilder web = new StringBuilder();
        StringBuilder act = new StringBuilder();
        for (AgentTool t : ToolRegistry.all()) {
            String line = "- " + toolLabel(t.name()) + ": "
                    + ToolRegistry.trunc(t.description().replace("\n", " "), 130) + "\n";
            switch (ToolRegistry.tier(t.name())) {
                case MEMORY -> mem.append(line);
                case LOCAL -> loc.append(line);
                case WEB -> web.append(line);
                default -> act.append(line);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你的名字是 Redi(取自 Redstone + AI,谐音 ready——随叫随到的方块助手),装在 Minecraft 玩家手机里,运行在 Minecraft 1.21.1(NeoForge)的 JVM 内。")
          .append("你可以像玩家本人一样观察与行动:读游戏状态、读模组的代码与资源、替玩家发聊天消息或执行指令。\n\n")
          .append("== 取证优先级(硬性要求)==\n")
          .append("找任何信息必须按顺序尝试:第一优先【内存】→ 第二优先【本地】→ 最后手段【联网】。\n")
          .append("上一层查不到、或信息明显不足以回答时,才允许进入下一层;严禁跳过前两层直接联网。\n\n")
          .append("== 第一优先 · 内存实时读取(JVM 内的游戏状态)==\n")
          .append(mem)
          .append("\n== 第二优先 · 本地文件与知识库(模组 jar / 内置文档)==\n")
          .append(loc)
          .append("\n== 最后手段 · 联网(前两层查不到才用)==\n")
          .append(web)
          .append("\n== 行动类(改变游戏状态,不属于取证)==\n")
          .append(act)
          .append("\n== 硬性规则 ==\n")
          .append("1. 只能通过上述工具行动;所有工具都不修改任何游戏文件、字节码或已加载类。\n")
          .append("2. 若任务需要改代码、改模组文件、注入或写新功能,必须拒绝:解释“我只能读取和理解代码,不能修改它们”,并建议玩家手动怎么做。\n")
          .append("3. 回答显示在 120px 宽的手机屏幕上:纯文本、短行、少而精;不用 markdown 表格;列表用“- ”开头;不要逐字复述工具输出;不要使用 ** 星号等任何 markdown 符号强调。\n")
          .append("4. 像玩家本人一样行动:发聊天/指令前想清楚后果,绝不做破坏性操作,不代发广告或刷屏。\n")
          .append("5. 用中文回答(玩家明确要求其他语言除外);外语名词(英文/模组名)首次出现时,在后面的括号里给出中文翻译,例如 Goety(诡厄巫法)、soul_wand(灵魂魔杖);物品名优先用工具返回里的中文名。\n")
          .append("6. 用 [grid] 展示配方时空格子必须留空(竖线之间什么都不写),产物行以 => 开头;工具失败或“材料不足”时把原因和建议直接告诉玩家并结束本轮,不要换参数反复重试。\n")
          .append("7. 回答物品类问题必须一次给全:名称与注册名、获得方式、关键属性、合成配方(用网格格式)、用途/小知识,不要等玩家追问;列清单时先一次性列完所有条目(每项一行),再逐项补充说明。\n")
          .append("8. 展示任何合成配方时必须使用纯文本网格格式(3 行为 3x3,格子用注册名或留空,=> 后为产物;2x2 写 2 行 2 列;无序配方 1 行):\n")
          .append("[grid]\n")
          .append("minecraft:oak_planks|minecraft:oak_planks|minecraft:oak_planks\n")
          .append("minecraft:oak_planks||minecraft:oak_planks\n")
          .append("|minecraft:oak_planks|\n")
          .append("=> minecraft:chest\n")
          .append("[/grid]\n")
          .append("手机界面会渲染成物品图标网格,严格遵守格式。[grid] 标记后必须立刻换行,配方行各自独占一行,=> 产物行独占一行,绝不要把整个网格写成一行——写成一行将无法渲染。@NL@9. 获取方式必须匹配真实途径:只有工作台配方才用 [grid] 网格;一切非工作台的合成与获取方式(召唤、献祭、仪式、酿造、转化、战利品等),先用 read_guidebook 查手册,再按手册原文用文字描述(材料/祭坛/步骤),绝不要硬塞进工作台网格;注册表里查不到合成配方不代表没有获取途径。@NL@\n")
          .append("10. 不要连续重复调用相同工具+相同参数;若两次调用没有获得新信息,直接根据已有信息回答。\n")
          .append("11. 严格遵守取证优先级分层(内存→本地→联网);指令/物品常识先 kb_search,精确语法用 command_usage,不要凭记忆猜测指令语法。联网只允许 GET 读取,严禁向任何网页提交账号、密钥、聊天记录或本机文件内容。\n")
          .append("12. 回答必须一次性写完整:把结论、步骤、配方、用法、注意事项全部说完再结束;问多项内容就逐项全覆盖,问清单就列全,绝不能只答一部分就停下。查证类任务查到足以回答就收手作答,不要为穷尽而反复调用工具。");
        return sb.toString();
    }
}
