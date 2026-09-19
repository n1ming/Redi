package com.redi.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redi.llm.LlmMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 引擎门面(facade):多会话管理 + 跨会话静态算法。
 *
 * <ul>
 *   <li><b>会话</b>:每个会话是独立的 {@link AgentSession}(自己的上下文/线程/显示模型),
 *       切换历史只切 {@link ChatModel#setActive 显示指针},运行中的会话在后台继续;</li>
 *   <li><b>子 agent</b>:{@link #spawnSubagent} 创建临时会话(不落盘)执行子任务,
 *       由 spawn_task 工具触发,父会话取消时联动取消子会话;</li>
 *   <li><b>静态算法</b>:系统提示词、上下文整形(compaction)、400 裁剪、
 *       工具中文标注等所有会话共用。</li>
 * </ul>
 */
public final class AgentEngine {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("redi");

    /** 文件名 → 主会话(历史会话按需装载,运行中切换不销毁)。 */
    private static final java.util.Map<String, AgentSession> BY_FILE = new java.util.concurrent.ConcurrentHashMap<>();
    /** 当前交互(显示)的主会话。 */
    private static volatile AgentSession current = AgentSession.mainSession();
    /** 子 agent 注册表:id → 子会话(进度查询/掐断/重指/取结果)。
     *  完成的子保留(父可 join 结果或继续对话),仅在上限内淘汰最旧的非运行中条目。 */
    private static final java.util.Map<String, AgentSession> AGENTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong AGENT_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    private static final int AGENTS_MAX = 16;

    static {
        ChatModel.setActive(current.chat());
    }

    private AgentEngine() {
    }

    /** 兼容旧接口(ChatView 等持有者):返回门面自身。 */
    public static AgentEngine get() {
        return new AgentEngine();
    }

    // ---------------------------------------------------------------- 会话管理

    /** 当前交互的主会话。 */
    public static AgentSession currentSession() {
        return current;
    }

    /** 提交到当前会话(ChatView 发送入口)。 */
    public void submit(String userText) {
        current.submit(userText, userText);
    }

    public void submit(String displayText, String taskText) {
        current.submit(displayText, taskText);
    }

    /** 停止当前会话任务,并联动停止全部子 agent。 */
    public void cancel() {
        current.cancel();
        for (AgentSession s : AGENTS.values()) {
            s.cancel(); // 全部子 agent 联动停止
        }
    }

    /** 当前会话开新对话。 */
    public void resetConversation() {
        current.newConversation();
    }

    /** 当前会话上下文快照(ChatStore 旧接口)。 */
    public List<List<LlmMessage>> snapshotRounds() {
        return current.snapshotRounds();
    }

    /** 当前会话回灌上下文。 */
    public void restoreRounds(List<List<LlmMessage>> restored, boolean quiet) {
        current.restoreRounds(restored);
        if (!quiet) {
            current.chat().append(ChatModel.Role.NOTE, "已载入历史会话,模型上下文已恢复。");
        }
    }

    /**
     * 切换(或装载)一个历史会话为当前会话:复用已注册会话(运行中的原样继续,
     * 只切显示),否则从文件装载(显示记录 + 空闲时回灌上下文)。
     */
    public static AgentSession switchSession(String fileName, List<ChatModel.Msg> msgs,
                                             List<List<LlmMessage>> rounds) {
        AgentSession s = BY_FILE.get(fileName);
        if (s == null) {
            s = AgentSession.mainSession();
            s.fileName = fileName;
            s.chat().replaceAll(msgs == null ? List.of() : msgs);
            s.restoreRounds(rounds);
            BY_FILE.put(fileName, s);
        }
        current = s;
        ChatModel.setActive(s.chat());
        return s;
    }

    /** 「新对话」:切到空白新会话(旧会话若在运行,后台继续)。 */
    public static void newChat() {
        freshSession();
    }

    /** 新建空白主会话为当前(「新对话」后开新档用)。 */
    public static AgentSession freshSession() {
        AgentSession s = AgentSession.mainSession();
        current = s;
        ChatModel.setActive(s.chat());
        return s;
    }

    /** 任务结束回调(会话线程 finally):存档 + 全局事件。 */
    static void onTaskFinished(AgentSession session, String task) {
        try {
            if (session.fileName == null) {
                session.fileName = ChatStore.newSessionFile();
            }
            ChatStore.saveSession(session);
        } catch (Exception e) {
            LOG.warn("[redi] 会话存档失败: {}", e.toString());
        }
        com.redi.plugin.AgentContext.SHARED.emit("task.finished", task);
    }

    // ---------------------------------------------------------------- 子 agent

    /** 派子 agent(非阻塞):立即返回 agent id,子在独立线程执行。 */
    public static String agentStart(String task) {
        AgentSession sub = AgentSession.transientSession();
        String id = "a" + AGENT_SEQ.incrementAndGet();
        AGENTS.put(id, sub);
        // 上限淘汰:移除最旧的非运行中条目
        if (AGENTS.size() > AGENTS_MAX) {
            for (String k : AGENTS.keySet()) {
                if (!AGENTS.get(k).busy()) {
                    AGENTS.remove(k);
                    if (AGENTS.size() <= AGENTS_MAX) break;
                }
            }
        }
        sub.submit(task, task);
        return id;
    }

    /** 子 agent 实时进度文本:状态/当前活动/最近思考与工具步骤/已有回答。 */
    public static String agentStatus(String id) {
        AgentSession sub = AGENTS.get(id);
        if (sub == null) {
            return "没有 id 为 " + id + " 的子 agent(用 action=start 创建)。";
        }
        StringBuilder sb = new StringBuilder("子 agent " + id + ": ");
        if (sub.busy()) {
            sb.append("运行中");
            String act = sub.chat().activity();
            if (act != null && !act.isBlank()) {
                sb.append("(当前: ").append(act.trim()).append(")");
            }
        } else {
            sb.append(sub.lastAnswer().isEmpty() ? "空闲(无产出)" : "已完成");
        }
        sb.append('\n');
        // 最近步骤(干了什么):liveSteps 含流式缓冲
        java.util.List<String> steps = sub.chat().liveSteps();
        int from = Math.max(0, steps.size() - 8);
        if (from < steps.size()) {
            sb.append("最近动作:\n");
            for (int k = from; k < steps.size(); k++) {
                String st = steps.get(k);
                if (st.length() > 100) st = st.substring(0, 100) + "…";
                sb.append("- ").append(st).append('\n');
            }
        }
        String ans = sub.lastAnswer();
        if (!ans.isEmpty()) {
            sb.append("已有回答(尾 300 字): …").append(ans.substring(Math.max(0, ans.length() - 300)));
        }
        return sb.toString();
    }

    /** 掐断子 agent 当前任务(会话保留,可再 send 重指)。 */
    public static String agentStop(String id) {
        AgentSession sub = AGENTS.get(id);
        if (sub == null) {
            return "没有 id 为 " + id + " 的子 agent。";
        }
        if (!sub.busy()) {
            return "子 agent " + id + " 本就空闲(无运行中任务)。";
        }
        sub.cancel();
        // 等它退出运行态(通常 1-3 秒,在途请求被打断后循环检测 cancelled 即退)
        long deadline = System.currentTimeMillis() + 10_000;
        while (sub.busy() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return sub.busy() ? "已发停止信号,子 agent 正在收尾(稍后 status 确认)。"
                : "子 agent " + id + " 已停止(会话保留,可用 send 重新指派)。";
    }

    /** 给子 agent 发新指令:运行中先掐断再重指;空闲直接继续(上下文保留)。 */
    public static String agentSend(String id, String message) {
        AgentSession sub = AGENTS.get(id);
        if (sub == null) {
            return "没有 id 为 " + id + " 的子 agent。";
        }
        if (sub.busy()) {
            agentStop(id); // 掐断当前,按新指引重来
        }
        sub.submit(message, message);
        return "已向子 agent " + id + " 发出新指令(其历史上下文保留,它会结合此前进展继续)。";
    }

    /** 阻塞等待子 agent 完成当前任务,返回最终回答。 */
    public static String agentJoin(String id) {
        AgentSession sub = AGENTS.get(id);
        if (sub == null) {
            return "没有 id 为 " + id + " 的子 agent。";
        }
        long deadline = System.currentTimeMillis() + 15 * 60_000;
        while (sub.busy() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (sub.busy()) {
            return "子 agent " + id + " 超时(15 分钟)仍在运行;可用 status 看进度或 stop 停止。";
        }
        String answer = sub.lastAnswer();
        return answer.isEmpty() ? "子 agent " + id + " 没有最终回答(可能被停止)。" : answer;
    }

    /**
     * 同步派子 agent(便捷:启动即等完成)——spawn_task 工具用。
     */
    public static String spawnSubagent(String task, AgentSession parent) {
        String id = agentStart(task);
        try {
            long deadline = System.currentTimeMillis() + 15 * 60_000;
            AgentSession sub = AGENTS.get(id);
            while (sub.busy() && System.currentTimeMillis() < deadline) {
                if (parent != null && parent.isCancelled()) {
                    sub.cancel(); // 父会话被停止:联动停子
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    sub.cancel();
                    break;
                }
            }
            if (sub.busy()) {
                sub.cancel();
                return "子 agent 超时(15 分钟)已停止。";
            }
            String answer = sub.lastAnswer();
            if (answer.isBlank()) {
                return "子 agent 没有产生最终回答(可能被停止或模型空响应)。";
            }
            return answer;
        } finally {
            AGENTS.remove(id); // 同步用法一次性,不留注册表
        }
    }

    // ---------------------------------------------------------------- 上下文整形(compaction)

    /** 软预算:请求体超过此字符数才启用剪枝(平时零干预,历史全量)。 */
    private static final int SHAPE_SOFT_CHARS = 120_000;
    /** 硬预算:剪枝后仍超,从最老开始丢整轮(绝不越过最近一条用户轮)。 */
    private static final int SHAPE_HARD_CHARS = 300_000;
    /** 最近 N 轮永远完整(过程细节保留)。 */
    private static final int SHAPE_KEEP_RECENT = 6;

    /**
     * 上下文整形(不改 rounds,返回发送副本):
     * ①超出软预算:较早轮次的工具结果替换为存根(用户提问/最终回答/最近 6 轮完整);
     * ②仍超硬预算:从最老丢整轮(不越过最近用户轮)。
     */
    static List<LlmMessage> shapeForRequest(List<List<LlmMessage>> roundsIn, String currentTask) {
        List<List<LlmMessage>> rounds = new ArrayList<>();
        for (List<LlmMessage> r : roundsIn) {
            rounds.add(new ArrayList<>(r));
        }
        long total = 0;
        for (List<LlmMessage> r : rounds) {
            for (LlmMessage m : r) {
                total += m.content() == null ? 0 : m.content().length();
            }
        }
        if (total <= SHAPE_SOFT_CHARS) {
            return assemble(rounds, currentTask); // 平时零干预
        }
        int userAnchor = -1;
        for (int i = rounds.size() - 1; i >= 0; i--) {
            if (!rounds.get(i).isEmpty() && rounds.get(i).get(0).role().equals("user")) {
                userAnchor = i;
                break;
            }
        }
        int keepFrom = Math.max(0, rounds.size() - SHAPE_KEEP_RECENT);
        long pruned = 0;
        int prunedRounds = 0;
        for (int i = 0; i < keepFrom && i < rounds.size(); i++) {
            List<LlmMessage> r = rounds.get(i);
            if (r.isEmpty()) {
                continue;
            }
            String toolNames = null;
            for (LlmMessage m : r) {
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (com.redi.llm.ToolCall tc : m.toolCalls()) {
                        if (names.length() > 0) {
                            names.append('/');
                        }
                        names.append(tc.name());
                    }
                    toolNames = names.toString();
                }
                if ("tool".equals(m.role()) && m.content() != null && m.content().length() > 200) {
                    String stub = "[已归档" + (toolNames != null ? ":" + toolNames : "")
                            + "](原 " + m.content().length() + " 字符,过程数据已消费。"
                            + "开头摘要: " + m.content().substring(0, Math.min(60, m.content().length())) + "…)";
                    pruned += m.content().length() - stub.length();
                    r.set(r.indexOf(m), LlmMessage.tool(m.toolCallId(), stub));
                    prunedRounds++;
                }
            }
        }
        total = 0;
        for (List<LlmMessage> r : rounds) {
            for (LlmMessage m : r) {
                total += m.content() == null ? 0 : m.content().length();
            }
        }
        int dropTo = 0;
        while (total > SHAPE_HARD_CHARS && dropTo < rounds.size() - 1
                && (userAnchor < 0 || dropTo < userAnchor)) {
            for (LlmMessage m : rounds.get(dropTo)) {
                total -= m.content() == null ? 0 : m.content().length();
            }
            dropTo++;
        }
        if (dropTo > 0) {
            rounds.subList(0, dropTo).clear();
        }
        if (prunedRounds > 0 || dropTo > 0) {
            LOG.info("[redi] 上下文整形: 归档 {} 轮工具细节(省 {} 字符), 移除最老 {} 轮",
                    prunedRounds, pruned, dropTo);
        }
        return assemble(rounds, currentTask);
    }

    /** 400 自适配裁剪:保留最近一半轮次(左边界不越过最近用户轮)。 */
    static void trimRoundsForProvider(ArrayDeque<List<LlmMessage>> rounds) {
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
            int start = Math.max(0, n - Math.max(1, n / 2));
            if (userAnchor >= 0 && userAnchor < start) {
                start = userAnchor;
            }
            while (rounds.size() > n - start && rounds.size() > 1) {
                rounds.pollFirst();
            }
        }
    }

    /** system + 轮次序列(保证 user 开头)。 */
    static List<LlmMessage> assemble(List<List<LlmMessage>> rounds, String currentTask) {
        List<LlmMessage> out = new ArrayList<>();
        out.add(LlmMessage.system(buildSystemPrompt()));
        boolean empty = true;
        for (List<LlmMessage> r : rounds) {
            if (empty && !r.isEmpty() && !r.get(0).role().equals("user")) {
                out.add(LlmMessage.user(currentTask == null || currentTask.isBlank() ? "(继续)" : currentTask));
            }
            empty = false;
            out.addAll(r);
        }
        if (empty) {
            out.add(LlmMessage.user(currentTask == null || currentTask.isBlank() ? "(继续)" : currentTask));
        }
        return out;
    }

    // ---- 以下为从旧引擎原样保留的共用静态成员(由拼接脚本注入) ----

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
        m.put("spawn_task", "派子agent");
        m.put("agent_task", "子agent控制台");
        m.put("read_guidebook", "读模组手册");
        m.put("read_file", "读本机文件");
        m.put("list_files", "列目录");
        m.put("image_matrix", "图片转像素矩阵");
        m.put("web_search", "联网搜索");
        m.put("web_read", "读网页");
        return m;
    }

    /** 工具显示名:name(中文标注);未登记的工具返回原名。 */
    static String toolLabel(String name) {
        String zh = TOOL_ZH.get(name);
        return zh == null ? name : name + "(" + zh + ")";
    }

    static boolean isTransient(Throwable t) {
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

    static String canonicalArgs(JsonObject args) {
        StringBuilder sb = new StringBuilder();
        appendCanonical(args, sb);
        return sb.toString();
    }

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

    /** 系统提示:身份 + 分层工具清单 + 硬性规则(所有会话共用)。 */
    static String buildSystemPrompt() {
        StringBuilder kb = new StringBuilder();
        StringBuilder mem = new StringBuilder();
        StringBuilder loc = new StringBuilder();
        StringBuilder web = new StringBuilder();
        StringBuilder act = new StringBuilder();
        for (AgentTool t : ToolRegistry.all()) {
            String line = "- " + toolLabel(t.name()) + ": "
                    + ToolRegistry.trunc(t.description().replace("\n", " "), 130) + "\n";
            if (t.name().equals("kb_search")) {
                kb.append(line);
                continue;
            }
            switch (ToolRegistry.tier(t.name())) {
                case MEMORY -> mem.append(line);
                case LOCAL -> loc.append(line);
                case WEB -> web.append(line);
                default -> act.append(line);
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean cheatsAllowed = currentSession().isCheatsAllowed();
        sb.append(cheatsAllowed
                ? "[环境状态: 允许作弊——玩家拥有指令权限,send_command 可正常使用。]\n"
                : "[环境状态: 未开放作弊——无指令权限!禁止调用 send_command,"
                        + "只能口头回答并指导玩家手动操作;craft_item/smelt_item/send_chat 等非指令工具仍可用。]\n");
        sb.append("你的名字是 Redi(取自 Redstone + AI,谐音 ready——随叫随到的方块助手),装在 Minecraft 玩家手机里,运行在 Minecraft 1.21.1(NeoForge)的 JVM 内。")
          .append("你可以像玩家本人一样观察与行动:读游戏状态、读模组的代码与资源、替玩家发聊天消息或执行指令。\n\n")
          .append("== 取证优先级(硬性要求)==\n")
          .append("找任何信息必须按顺序尝试:第一优先【本地知识库】→ 第二优先【内存】→ 第三优先【本地模组文件】→ 最后手段【联网】。\n")
          .append("知识库已内置指令/物品常识与 NeoForge 官方 API 文档,凡知识类问题先 kb_search;"
                  + "上一层查不到或信息不足才进入下一层;严禁跳层直接联网。\n\n")
          .append("== 第一优先 · 本地知识库(内置文档,查得最快)==\n")
          .append(kb)
          .append("\n== 第二优先 · 内存实时读取(JVM 内的游戏状态)==\n")
          .append(mem)
          .append("\n== 第三优先 · 本地模组文件(jar / 手册)==\n")
          .append(loc)
          .append("\n== 最后手段 · 联网(以上各层都查不到才用)==\n")
          .append(web)
          .append("\n== 行动类(改变游戏状态,不属于取证)==\n")
          .append(act)
          .append("\n== 硬性规则 ==\n")
          .append("1. 唯一硬性红线:绝不修改游戏源码、模组文件、字节码或已加载类——代码永远只读。\n")
          .append("2. 除此之外的一切游戏内操作(合成/冶炼/交易/战斗/探索/建造/执行指令/发消息等)都可直接执行,"
                  + "像玩家本人一样把任务做完,不要过度请示或自我设限;单人世界里玩家就是 OP,"
                  + "管理类指令也照常执行。只有当任务必须改代码才能完成时,才说明做不了并给出手动替代方案。\n")
          .append("3. 回答显示在 120px 宽的手机屏幕上:纯文本、短行、少而精;不用 markdown 表格;列表用“- ”开头;不要逐字复述工具输出;不要使用 ** 星号等任何 markdown 符号强调。\n")
          .append("4. 像玩家本人一样行动:按任务需要放手使用指令与聊天,不代发广告或刷屏。\n")
          .append("5. 用中文回答(玩家明确要求其他语言除外);外语名词(英文/模组名)首次出现时,在后面的括号里给出中文翻译,例如 Goety(诡厄巫法)、soul_wand(灵魂魔杖);物品名优先用工具返回里的中文名。\n")
          .append("6. 用 [grid] 展示配方时空格子必须留空(竖线之间什么都不写),产物行以 => 开头;工具失败或“材料不足”时把原因和建议直接告诉玩家并结束本轮,不要换参数反复重试。\n")
          .append("7. 回答物品类问题必须一次给全:名称与注册名、获得方式、关键属性、合成配方(用网格格式)、用途/小知识,不要等玩家追问;列清单时先一次性列完所有条目(每项一行),再逐项补充说明。\n")
          .append("8. 图形化输出规范:凡涉及物品/材料/产物/流程的展示,必须用图形标记(手机会渲染成真实物品图标),禁止只用文字罗列物品名。按内容自由选择最贴切的图形,不局限于工作台网格:\n")
          .append("- [grid] 工作台配方(3x3 网格,3 行各独占一行,=> 产物行独占一行;2x2 写 2 行,无序写 1 行)。示例:\n")
          .append("  [grid]\n")
          .append("  minecraft:oak_planks|minecraft:oak_planks|minecraft:oak_planks\n")
          .append("  minecraft:oak_planks||minecraft:oak_planks\n")
          .append("  |minecraft:oak_planks|\n")
          .append("  => minecraft:chest\n")
          .append("  [/grid]\n")
          .append("- [items] 物品横排图标(材料清单/掉落/套装成员):[items]minecraft:iron_ingot*3|minecraft:chest|minecraft:stick*2[/items],数量用 *n,竖线分隔,可单行也可换行。\n")
          .append("- [flow] 流程/转化链(图标+箭头):[flow]minecraft:raw_iron >> minecraft:iron_ingot >> minecraft:iron_sword[/flow],用 >> 分隔。\n")
          .append("- [ring] 环形布局(祭坛仪式/环绕结构,中心+环绕):[ring]goety:dark_altar | goety:pedestal*8 | goety:cursed_cage[/ring],首项居中,其余环绕一圈;常配合文字说明仪式步骤一起用。\n")
          .append("9. 像素画/马赛克建造任务:先用 image_matrix(mode=build,size 按墙的大小选 16~48)拿到施工单,然后逐行用 /fill 执行(如 /fill x1 y z1 x2 y z2 minecraft:white_concrete 需按游程分段),颜色严格按施工单映射,不要自行换色;\n")
          .append("图形单独成段,与文字说明穿插;文字负责讲清步骤与条件,图形负责展示物品。[grid] 配方行各自独占一行,不要把网格写成一行。\n9. 获取方式必须匹配真实途径:工作台配方用 [grid];材料/流程/仪式按第 8 条选图形;一切非工作台获取(召唤、献祭、仪式、酿造、转化、战利品等)先用 read_guidebook 查手册,按手册原文转述(材料用 [items]、祭坛布局用 [ring]、步骤用 [flow] 配文字),绝不要硬塞进工作台网格;注册表里查不到合成配方不代表没有获取途径。\n\n")
          .append("10. 不要连续重复调用相同工具+相同参数;若两次调用没有获得新信息,直接根据已有信息回答。\n")
          .append("11. 严格遵守取证优先级分层(知识库→内存→本地文件→联网);指令/物品常识先 kb_search,精确语法用 command_usage,不要凭记忆猜测指令语法。联网只允许 GET 读取,严禁向任何网页提交账号、密钥、聊天记录或本机文件内容。\n")
          .append("12. 回答必须一次性写完整:把结论、步骤、配方、用法、注意事项全部说完再结束;问多项内容就逐项全覆盖,问清单就列全,绝不能只答一部分就停下。查证类任务查到足以回答就收手作答,不要为穷尽而反复调用工具。");
        return sb.toString();
    }
}
