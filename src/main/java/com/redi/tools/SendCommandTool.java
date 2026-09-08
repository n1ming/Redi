package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ClientExec;
import com.redi.agent.ToolRegistry;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Set;

/**
 * send_command:以玩家身份执行一条指令(去掉开头 '/' 后经 ClientExec 切主线程
 * 走 sendCommand)。账号/服务器管理类指令黑名单硬拒——即使服务端会拦也不发出去。
 */
public final class SendCommandTool implements AgentTool {

    /** 禁止执行的指令(权限与账号管理类),首词匹配即拒绝。 */
    private static final Set<String> BLOCKED =
            Set.of("op", "deop", "stop", "ban", "ban-ip", "pardon", "kick", "whitelist");

    @Override
    public String name() {
        return "send_command";
    }

    @Override
    public String description() {
        return "以玩家本人的身份执行一条指令,参数 command 填指令内容(可带或不带开头的 /,如 gamemode creative、tp @s 100 64 100)。"
                + "指令执行后的聊天反馈文本(如 locate 的坐标、give 的入包提示)会自动收集并回传给你,你可以据此继续行动。"
                + "op/deop/stop/ban/ban-ip/pardon/kick/whitelist 等管理类指令会被硬性拒绝。执行前务必想清楚后果,绝不做破坏性操作。";
    }

    @Override
    public JsonObject schema() {
        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("description", "指令内容,如 gamemode creative");

        JsonObject props = new JsonObject();
        props.add("command", command);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("command");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String raw = ToolRegistry.argStr(args, "command");
        if (raw == null || raw.isBlank()) return "缺少参数 command。";
        String cmd = raw.strip();
        while (cmd.startsWith("/")) cmd = cmd.substring(1).strip();
        if (cmd.isEmpty()) return "指令为空。";
        String head = cmd.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(head)) {
            return "已拒绝:\"" + head + "\" 属于禁用指令(账号/服务器管理类),本工具绝不执行。";
        }
        Minecraft mc = Minecraft.getInstance();
        final String c = cmd;
        CommandFeedback.begin(); // 开始收集该指令的聊天反馈
        Boolean ok = ClientExec.get(() -> {
            if (mc.player == null || mc.player.connection == null) return false;
            mc.player.connection.sendCommand(c);
            return true;
        }, false);
        if (!Boolean.TRUE.equals(ok)) {
            CommandFeedback.finish();
            return "发送失败: 当前不在游戏中。";
        }
        // 轮询收集反馈:反馈通常几百毫秒内到达;有内容后再给 500ms 余量,总上限 3 秒
        long deadline = System.currentTimeMillis() + 3000;
        int seen = 0;
        long lastGrow = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            int n = CommandFeedback.lineCount();
            if (n > seen) {
                seen = n;
                lastGrow = System.currentTimeMillis();
            } else if (seen > 0 && System.currentTimeMillis() - lastGrow > 500) {
                break; // 反馈已稳定
            }
        }
        String feedback = CommandFeedback.finish();
        StringBuilder sb = new StringBuilder("已执行指令: /").append(c);
        if (feedback.isBlank()) {
            sb.append("\n(3 秒内没有收到聊天反馈;若该指令的结果只显示给 OP 或有 gamerule 关闭反馈,则拿不到文本。)");
        } else {
            sb.append("\n指令反馈:\n").append(ToolRegistry.trunc(feedback, 1200));
        }
        return sb.toString();
    }
}
