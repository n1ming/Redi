package com.redi.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redi.llm.LlmClient;
import com.redi.llm.LlmMessage;
import com.redi.llm.ToolCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个独立的 agent 会话:自己的对话上下文(rounds)、单线程执行器、
 * 聊天显示模型与运行状态。多会话并行互不干扰——切换显示(切历史)只改
 * {@link ChatModel#setActive},运行中的会话在后台继续。
 *
 * <p>主会话由 {@link AgentEngine} 注册管理(绑定 ChatStore 文件);
 * 子 agent 是不落盘的临时会话({@link #transientSession()})。</p>
 */
public final class AgentSession {

    /** 本线程正在运行的会话(spawn_task 等工具取调用方会话用)。 */
    static final ThreadLocal<AgentSession> RUNNING = new ThreadLocal<>();

    /** 当前线程正在运行的会话(无则 null;spawn_task 取父会话用)。 */
    public static AgentSession currentRunning() {
        return RUNNING.get();
    }

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** 会话单线程:LLM 请求与工具执行都在这里跑。 */
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "redi-session-" + SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    /** 本会话的显示/记录模型(主会话 = 屏幕显示;子会话 = 私有收集)。 */
    final ChatModel chat = new ChatModel();
    /** 发给模型的对话历史(不含 system),按轮组织。 */
    private final ArrayDeque<List<LlmMessage>> rounds = new ArrayDeque<>();
    /** 取消标志。 */
    private volatile boolean cancelled = false;

    boolean isCancelled() {
        return cancelled;
    }

    boolean isCheatsAllowed() {
        return cheatsAllowed;
    }
    /** 在途 LLM 客户端(cancel 时即时打断)。 */
    private volatile LlmClient activeClient;
    /** 重复调用计数。 */
    private final java.util.HashMap<String, Integer> sigCounts = new java.util.HashMap<>();
    /** 当前任务原文。 */
    private String currentTask = "";
    /** 环境作弊权限(任务开始探测)。 */
    private volatile boolean cheatsAllowed = false;
    /** 对应 ChatStore 文件(主会话与子 agent 会话都有;null = 未分配)。 */
    String fileName;
    /** 会话标题覆盖(子 agent 用 "agent_aN";null = 按首条消息自动取名)。 */
    String title;
    /** 是否主会话(影响存档与完成事件)。 */
    final boolean main;

    private AgentSession(boolean main) {
        this.main = main;
    }

    /** 新建主会话(可存档)。 */
    static AgentSession mainSession() {
        return new AgentSession(true);
    }

    /** 新建子 agent 会话(临时,不存档,不进历史 UI)。 */
    static AgentSession transientSession() {
        return new AgentSession(false);
    }

    ChatModel chat() {
        return chat;
    }

    boolean busy() {
        return chat.busy();
    }

    void cancel() {
        cancelled = true;
        LlmClient c = activeClient;
        if (c != null) {
            c.abortInFlight();
        }
    }

    /** 开启新对话(清模型上下文,保留显示记录)。 */
    void newConversation() {
        synchronized (rounds) {
            rounds.clear();
        }
        chat.append(ChatModel.Role.NOTE, "已开启新对话(模型记忆已清空,聊天记录保留)。");
    }

    /** 提交任务(display 显示文本,taskText 任务文本,可含 @导入附件)。 */
    void submit(String displayText, String taskText) {
        if (chat.busy()) {
            chat.append(ChatModel.Role.NOTE, "本会话上一个任务还在进行中,请等它结束或点「■」停止。");
            return;
        }
        if (taskText == null || taskText.isBlank()) {
            return;
        }
        final String text = taskText.trim();
        chat.append(ChatModel.Role.USER,
                displayText == null || displayText.isBlank() ? text : displayText.trim());
        if (!com.redi.config.AgentConfig.get().isConfigured()) {
            chat.append(ChatModel.Role.ERROR,
                    "尚未配置模型接口。请点右上角齿轮进设置,填好 base_url、API Key 和模型名。");
            return;
        }
        cancelled = false;
        chat.setBusy(true); // 提交即占用(含 executor 排队期),spawn 等待循环才不会竞态跳过
        executor.execute(() -> runTask(text));
    }

    // ---------------------------------------------------------------- 主循环

    /** 引擎主流程:工具调用循环(在本会话线程执行)。 */
    private void runTask(String userText) {
        com.redi.config.AgentConfig cfg = com.redi.config.AgentConfig.get();
        RUNNING.set(this);
        try {
            if (!cfg.isConfigured()) {
                chat.append(ChatModel.Role.ERROR, "尚未配置模型接口,无法执行。");
                return;
            }
            sigCounts.clear();
            currentTask = userText;
            chat.setTaskStart(System.currentTimeMillis());
            // 每次执行第一件事:判断环境是否允许作弊(单人开局开作弊 / 多人 OP)
            try {
                Class<?> mc = Class.forName("net.minecraft.client.Minecraft");
                Object inst = mc.getMethod("getInstance").invoke(null);
                Object player = inst.getClass().getField("player").get(inst);
                cheatsAllowed = player != null && (Boolean) player.getClass()
                        .getMethod("hasPermissions", int.class).invoke(player, 2);
            } catch (Throwable notInGame) {
                cheatsAllowed = false; // 无头/不可用环境:按无权限处理
            }
            synchronized (rounds) {
                rounds.addLast(List.of(LlmMessage.user(userText)));
            }
            LlmClient client = new LlmClient(cfg);
            activeClient = client;
            int hardCap = cfg.maxToolIterations > 0 ? cfg.maxToolIterations : Integer.MAX_VALUE;
            List<String> think = new ArrayList<>();
            for (int iter = 0; ; iter++) {
                if (cancelled) {
                    chat.append(ChatModel.Role.NOTE, "已停止。");
                    return;
                }
                if (iter >= hardCap) {
                    chat.append(ChatModel.Role.NOTE, "任务步数过多,已暂停;可以继续追问或开新对话。");
                    return;
                }
                chat.setActivity("思考中…");
                LlmClient.Response resp = request(client);
                if (resp == null) return; // 请求失败已在 request() 里报错
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
                    // 空响应:length 截断(思考配额挤压)先扩容,再同请求重试
                    if (resp.truncatedByLength() && client.bumpMaxTokens()) {
                        chat.setActivity("模型思考较长,已扩大输出配额重试…");
                        resp = request(client);
                        if (resp == null) return;
                        content = resp.content() == null ? "" : resp.content().trim();
                    }
                    if (!resp.hasToolCalls() && content.isEmpty()) {
                        resp = request(client);
                        if (resp == null) return;
                        content = resp.content() == null ? "" : resp.content().trim();
                    }
                    if (!resp.hasToolCalls() && content.isEmpty()) {
                        chat.append(ChatModel.Role.NOTE,
                                "模型没有返回内容,已自动重试仍为空。请换个说法再试,或点「■」停止。");
                        return;
                    }
                }
                if (!resp.hasToolCalls()) {
                    // 最终回答;被 length 掐断则自动"(继续)"接写
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
                    client.bumpMaxTokens();
                    chat.setActivity("回答被截断,自动续写…");
                    addRound(List.of(LlmMessage.user(
                            "(继续:刚才的回答因长度被截断,从中断处接着输出,不要重复已说过的内容;完成即停)")));
                    continue;
                }
                // 一轮 = assistant(tool_calls) + 每个 call 的 tool 结果
                List<LlmMessage> round = new ArrayList<>();
                round.add(LlmMessage.assistant(content, resp.toolCalls()));
                for (ToolCall call : resp.toolCalls()) {
                    if (cancelled) {
                        round.add(LlmMessage.tool(call.id(), "(玩家已停止本任务)"));
                        continue;
                    }
                    if (think.isEmpty()) {
                        chat.beginThink();
                    }
                    chat.setActivity("调用 " + AgentEngine.toolLabel(call.name()) + "…");
                    String out = runTool(call);
                    chat.addThinkStep(AgentEngine.toolLabel(call.name())
                            + (out.startsWith("工具执行出错") ? " ✗" : " ✓"));
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
            chat.clearTaskStart();
            RUNNING.remove();
            if (main) {
                AgentEngine.onTaskFinished(this, userText);
            }
        }
    }

    /** 最后一条 ASSISTANT 文本(子 agent 结果取用)。 */
    String lastAnswer() {
        for (ChatModel.Msg m : chat.snapshotReversed()) {
            if (m.role() == ChatModel.Role.ASSISTANT) {
                return m.text() == null ? "" : m.text();
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- 请求

    /** 流式调用 + 思考缓冲定稿。 */
    private LlmClient.Response streamChat(LlmClient client) throws Exception {
        try {
            return client.chat(buildRequest(), ToolRegistry.schemas(), new LlmClient.StreamListener() {
                @Override
                public void onReasoning(String delta) {
                    chat.streamThink(delta);
                }
            });
        } finally {
            chat.flushStream();
        }
    }

    /**
     * 发一次请求;失败时向玩家报错并返回 null。主路径 = 流式。失败分流:
     * ①max_tokens 被服务商拒绝 → 还原默认配额立即重试;
     * ②瞬时故障 → 自动重试一次;
     * ③其余 400 → 外层自适应链(裁剪/回显切换);
     * ④其它 → 中文错误。
     */
    private LlmClient.Response request(LlmClient client) {
        try {
            try {
                return streamChat(client);
            } catch (Exception first) {
                if (first instanceof com.redi.llm.LlmClient.BadRequestException
                        && String.valueOf(first.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("max_tokens")
                        && client.resetMaxTokens()) {
                    return streamChat(client);
                }
                if (!cancelled && AgentEngine.isTransient(first)) {
                    chat.setActivity("请求超时(上下文较大处理较慢),自动重试…");
                    Thread.sleep(800);
                    if (!cancelled) {
                        return streamChat(client);
                    }
                }
                throw first;
            }
        } catch (com.redi.llm.LlmClient.BadRequestException e) {
            // 400 自适应重试(全程备份,失败即恢复)
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
                return null;
            }
            if (AgentEngine.isTransient(e)) {
                chat.append(ChatModel.Role.ERROR, "请求超时或网络中断(当前对话上下文很大,服务商处理较慢)。"
                        + "可再发一次重试;若反复出现,点「新对话」缩短上下文后会明显变快。");
                return null;
            }
            chat.append(ChatModel.Role.ERROR, "模型请求失败: "
                    + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            return null;
        }
    }

    /** 执行单个工具调用,永不抛出。 */
    private String runTool(ToolCall call) {
        AgentTool tool = ToolRegistry.byName(call.name());
        if (tool == null) {
            return "工具执行出错: 未知工具 " + call.name();
        }
        if ("send_command".equals(tool.name()) && !cheatsAllowed) {
            return "当前环境未开放作弊权限(单人未开作弊或服务器无 OP),"
                    + "无法执行任何指令。请改为口头回答:告诉玩家怎么手动完成,"
                    + "不要再次尝试 send_command。";
        }
        chat.setActivity("调用 " + AgentEngine.toolLabel(tool.name()) + "…");
        JsonObject args;
        try {
            String raw = call.argumentsJson();
            args = JsonParser.parseString(raw == null || raw.isBlank() ? "{}" : raw).getAsJsonObject();
        } catch (Exception e) {
            return "工具执行出错: 参数不是合法 JSON(" + e.getMessage() + ")";
        }
        String sig = call.name() + "|" + AgentEngine.canonicalArgs(args);
        int n = sigCounts.merge(sig, 1, Integer::sum);
        if (n >= 3) {
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

    // ---------------------------------------------------------------- 上下文

    private void addRound(List<LlmMessage> round) {
        synchronized (rounds) {
            rounds.addLast(round);
        }
    }

    private void trimRoundsForProvider() {
        AgentEngine.trimRoundsForProvider(rounds);
    }

    private List<LlmMessage> buildRequest() {
        List<List<LlmMessage>> snap;
        synchronized (rounds) {
            snap = new ArrayList<>(rounds);
        }
        return AgentEngine.shapeForRequest(snap, currentTask);
    }

    /** 模型上下文快照(会话存档用)。 */
    java.util.List<java.util.List<LlmMessage>> snapshotRounds() {
        synchronized (rounds) {
            java.util.List<java.util.List<LlmMessage>> out = new ArrayList<>(rounds.size());
            for (List<LlmMessage> r : rounds) {
                out.add(new ArrayList<>(r));
            }
            return out;
        }
    }

    /** 回灌历史上下文(仅会话空闲时;忙时静默跳过)。 */
    void restoreRounds(java.util.List<java.util.List<LlmMessage>> restored) {
        if (restored == null || restored.isEmpty() || chat.busy()) {
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
    }
}
