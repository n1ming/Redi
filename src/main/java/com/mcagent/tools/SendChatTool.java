package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ClientExec;
import com.mcagent.agent.ToolRegistry;
import net.minecraft.client.Minecraft;

/**
 * send_chat:以玩家身份发一条公屏聊天消息(经 ClientExec 切主线程)。
 * 消息超过 256 字符直接拒绝。
 */
public final class SendChatTool implements AgentTool {

    /** 与原版聊天框一致的单条长度上限。 */
    private static final int MAX_LENGTH = 256;

    @Override
    public String name() {
        return "send_chat";
    }

    @Override
    public String description() {
        return "以玩家本人的身份发送一条公屏聊天消息(上限 256 字符)。像玩家说话一样措辞;绝不用它发广告、刷屏或攻击性内容。参数 message 必填。";
    }

    @Override
    public JsonObject schema() {
        JsonObject message = new JsonObject();
        message.addProperty("type", "string");
        message.addProperty("description", "要发送的聊天内容");

        JsonObject props = new JsonObject();
        props.add("message", message);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("message");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String message = ToolRegistry.argStr(args, "message");
        if (message == null || message.isBlank()) return "缺少参数 message。";
        if (message.length() > MAX_LENGTH) {
            return "消息超过 " + MAX_LENGTH + " 字符(当前 " + message.length() + "),已拒绝。";
        }
        Minecraft mc = Minecraft.getInstance();
        final String msg = message;
        Boolean ok = ClientExec.get(() -> {
            if (mc.player == null || mc.player.connection == null) return false;
            mc.player.connection.sendChat(msg);
            return true;
        }, false);
        return Boolean.TRUE.equals(ok) ? "已以玩家身份发送: " + msg : "发送失败: 当前不在游戏中。";
    }
}
