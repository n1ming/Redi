package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ClientExec;
import com.redi.agent.ToolRegistry;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * send_command:以玩家身份执行一条指令(去掉开头 '/' 后经 ClientExec 切主线程
 * 走 sendCommand)。无指令黑名单:权限由服务器判定(单人玩家即 OP)。
 */
public final class SendCommandTool implements AgentTool {

    @Override
    public String name() {
        return "send_command";
    }

    @Override
    public String description() {
        return "以玩家本人的身份执行一条指令,参数 command 填指令内容(可带或不带开头的 /,如 gamemode creative、tp @s 100 64 100)。"
                + "指令执行后的聊天反馈文本(如 locate 的坐标、give 的入包提示)会自动收集并回传给你,你可以据此继续行动。"
                + "除修改源码/游戏文件外没有任何指令限制,单人世界里玩家就是 OP;权限不足时服务器会自动拒绝,据反馈换法即可。";
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
