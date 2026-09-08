package com.redi.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 聊天界面的显示模型(UI 渲染线程读,引擎线程写;全部线程安全)。
 * 只管“显示”,不带 LLM 结构化历史——发给模型的对话由 AgentEngine 自己维护。
 */
public final class ChatModel {
    public enum Role {USER, ASSISTANT, ERROR, NOTE}

    public record Msg(Role role, String text, long ts, java.util.List<String> details) {
        /** 兼容旧构造(无思考过程详情)。 */
        public Msg(Role role, String text, long ts) {
            this(role, text, ts, null);
        }
    }

    private static final ChatModel INSTANCE = new ChatModel();
    private static final int MAX_MSGS = 300;

    private final ArrayDeque<Msg> msgs = new ArrayDeque<>();
    private volatile boolean busy = false;
    /** 引擎执行中的活动描述(如“调用 inspect_item…”),busy 带展示;空闲时为空串。 */
    private volatile String activity = "";

    // ---- 实时思考块:任务进行中就出现在聊天里,随工具调用逐条增长 ----
    private long liveThinkTs = -1;
    private final java.util.ArrayList<String> liveThinkSteps = new java.util.ArrayList<>();

    private static String thinkTitle(int n) {
        return "思考过程 · " + n + " 步(点击展开)";
    }

    /** 任务第一个工具调用前调用:插入一个实时思考块(已存在则复用)。 */
    public synchronized void beginThink() {
        if (liveThinkTs >= 0) {
            return;
        }
        liveThinkSteps.clear();
        long ts = System.currentTimeMillis();
        msgs.addLast(new Msg(Role.NOTE, thinkTitle(0), ts, java.util.List.of()));
        while (msgs.size() > MAX_MSGS) {
            msgs.removeFirst();
        }
        liveThinkTs = ts;
        seq++;
    }

    /** 追加一个思考步骤(实时刷新折叠块)。 */
    public synchronized void addThinkStep(String step) {
        if (liveThinkTs < 0) {
            beginThink();
        }
        if (step == null || step.isBlank()) {
            return;
        }
        liveThinkSteps.add(step.trim());
        long ts = liveThinkTs;
        java.util.ArrayList<Msg> tmp = new java.util.ArrayList<>(msgs);
        for (int i = 0; i < tmp.size(); i++) {
            if (tmp.get(i).ts() == ts) {
                tmp.set(i, new Msg(Role.NOTE, thinkTitle(liveThinkSteps.size()), ts,
                        java.util.List.copyOf(liveThinkSteps)));
                break;
            }
        }
        msgs.clear();
        msgs.addAll(tmp);
        seq++;
    }

    /** 任务结束(或取消):结束实时块,标题保留步数供展开回看。 */
    public synchronized void endThink() {
        if (liveThinkTs >= 0) {
            long ts = liveThinkTs;
            java.util.ArrayList<Msg> tmp = new java.util.ArrayList<>(msgs);
            for (int i = 0; i < tmp.size(); i++) {
                if (tmp.get(i).ts() == ts) {
                    tmp.set(i, new Msg(Role.NOTE, thinkTitle(liveThinkSteps.size()), ts,
                            java.util.List.copyOf(liveThinkSteps)));
                    break;
                }
            }
            msgs.clear();
            msgs.addAll(tmp);
        }
        liveThinkTs = -1;
        liveThinkSteps.clear();
        seq++;
    }

    /** 该消息是否是当前进行中的实时思考块。 */
    public synchronized boolean isLiveThink(long ts) {
        return liveThinkTs >= 0 && liveThinkTs == ts;
    }

    /** 当前实时思考块的步骤快照(没有进行中的块时为空列表);固定思考面板用。 */
    public synchronized java.util.List<String> liveSteps() {
        return liveThinkTs >= 0 ? java.util.List.copyOf(liveThinkSteps) : java.util.List.<String>of();
    }
    private long seq = 0; // 每次渲染循环递增,UI 可用来决定是否重排

    private ChatModel() {
    }

    public static ChatModel get() {
        return INSTANCE;
    }

    public synchronized List<Msg> snapshot() {
        return List.copyOf(msgs);
    }

    public synchronized void append(Role role, String text) {
        append(role, text, null);
    }

    /** 带思考过程详情的消息:details 非空时 UI 渲染为可折叠块。 */
    public synchronized void append(Role role, String text, java.util.List<String> details) {
        if (text == null || text.isBlank()) return;
        msgs.addLast(new Msg(role, text, System.currentTimeMillis(),
                details == null || details.isEmpty() ? null : java.util.List.copyOf(details)));
        while (msgs.size() > MAX_MSGS) {
            msgs.removeFirst();
        }
        seq++;
    }

    public synchronized void clear() {
        msgs.clear();
        seq++;
    }

    /**
     * 批量恢复:整表替换当前显示记录(历史会话回灌用),逐条原样 addLast(保留原 ts)。
     * 超过 {@link #MAX_MSGS} 时从头丢弃最旧的,与 {@link #append} 的封顶策略一致。
     */
    public synchronized void replaceAll(List<Msg> msgs) {
        this.msgs.clear();
        if (msgs != null) {
            for (Msg m : msgs) {
                if (m != null) {
                    this.msgs.addLast(m);
                }
            }
        }
        while (this.msgs.size() > MAX_MSGS) {
            this.msgs.removeFirst();
        }
        seq++;
    }

    public boolean busy() {
        return busy;
    }

    public void setBusy(boolean b) {
        this.busy = b;
        this.seq++;
        if (!b) this.activity = "";
    }

    /** 引擎写:当前活动描述(工具调用中);空闲时为空串。UI 读:busy 带展示。 */
    public String activity() {
        return activity;
    }

    public void setActivity(String text) {
        this.activity = text == null ? "" : text;
        this.seq++;
    }

    /** 供 UI 判断“内容或忙碌状态是否变化,要不要重绘滚动”。 */
    public long revision() {
        return seq;
    }

    public synchronized List<Msg> tail(int n) {
        List<Msg> all = new ArrayList<>(msgs);
        if (n >= all.size()) return all;
        return Collections.unmodifiableList(all.subList(all.size() - n, all.size()));
    }
}
