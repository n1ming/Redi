package com.mcagent.tools;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

import java.util.ArrayDeque;

/**
 * 指令反馈捕获:send_command 发出指令后,服务器回的聊天/系统反馈文本
 * (如 /locate 打印的坐标、/give 的入包提示)会以 ClientChatReceivedEvent
 * 到达客户端。收集器在收集窗口内把这些文本暂存,供工具读取并回传给模型。
 *
 * <p>注册:客户端分支 {@code NeoForge.EVENT_BUS.addListener(CommandFeedback::handleClientChat)}
 * (事件类仅客户端分发,注册代码必须在 dist guard 内)。全部方法引擎线程/主线程均可调。</p>
 */
public final class CommandFeedback {
    private static final int MAX_LINES = 40;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static volatile boolean collecting = false;

    private CommandFeedback() {
    }

    /** 事件回调(客户端游戏总线):仅在收集窗口内记录,始终只读不阻断聊天显示。 */
    public static void handleClientChat(ClientChatReceivedEvent event) {
        if (!collecting) {
            return;
        }
        String s = event.getMessage().getString();
        if (s != null && !s.isBlank()) {
            synchronized (LINES) {
                LINES.addLast(s.trim());
                while (LINES.size() > MAX_LINES) {
                    LINES.removeFirst();
                }
            }
        }
    }

    /** 开始一个收集窗口(清空上一次的内容)。 */
    public static void begin() {
        synchronized (LINES) {
            LINES.clear();
        }
        collecting = true;
    }

    /** 结束收集并返回全部反馈文本(按行合并);窗口外调用返回已缓存内容。 */
    public static String finish() {
        collecting = false;
        return snapshot();
    }

    /** 当前已收集行数。 */
    public static int lineCount() {
        synchronized (LINES) {
            return LINES.size();
        }
    }

    /** 读取当前内容但不结束收集。 */
    public static String snapshot() {
        synchronized (LINES) {
            if (LINES.isEmpty()) {
                return "";
            }
            return String.join("\n", LINES);
        }
    }
}
