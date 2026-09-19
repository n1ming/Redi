package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentEngine;
import com.redi.agent.AgentTool;
import com.redi.agent.ToolRegistry;

/**
 * agent_task:子 agent 的异步控制台。五种 action:
 * start(非阻塞派发,返回 id)/ status(实时进度:在干什么+最近动作+已有回答)/
 * stop(掐断当前任务,会话保留)/ send(重指:运行中先停再发新指令,上下文保留)/
 * join(阻塞等完成取回答)。
 */
public final class AgentTaskTool implements AgentTool {

    @Override
    public String name() {
        return "agent_task";
    }

    @Override
    public String description() {
        return "子 agent 异步控制台(action 参数选择操作):"
                + "- start:非阻塞派发子 agent(task=自包含任务描述),立即返回 agent id,你可以继续干别的;"
                + "- status:实时查看进度(id)——运行状态、当前动作、最近 8 条思考/工具步骤、已有回答;"
                + "- stop:掐断子 agent 当前任务(id),会话保留可继续重指;"
                + "- send:重新指派(id+message)——运行中会先停止当前任务再按新指引继续,历史上下文保留;"
                + "- join:阻塞等子 agent 完成(id)并返回最终回答。"
                + "推荐工作流:大任务拆解后 start 多个子 agent → 自己做别的 → status 查进度 →"
                + "跑偏就 stop+send 修正 → 最后 join 逐个收结果汇总。"
                + "子 agent 看不到你的会话历史,start 的 task 必须自包含。每个子 agent 的会话会以 agent_aN 为名出现在玩家的历史会话列表里,你和玩家都能切进去实时查看它在干什么。";
    }

    @Override
    public JsonObject schema() {
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        JsonArray acts = new JsonArray();
        acts.add("start");
        acts.add("status");
        acts.add("stop");
        acts.add("send");
        acts.add("join");
        action.add("enum", acts);
        action.addProperty("description", "start=非阻塞派发 / status=查进度 / stop=掐断 / send=重指 / join=等完成");
        JsonObject task = new JsonObject();
        task.addProperty("type", "string");
        task.addProperty("description", "start 时的子 agent 任务描述(自包含)");
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description", "子 agent id(start 返回的,如 a1)");
        JsonObject message = new JsonObject();
        message.addProperty("type", "string");
        message.addProperty("description", "send 时的重新指派指令");
        JsonObject props = new JsonObject();
        props.add("action", action);
        props.add("task", task);
        props.add("id", id);
        props.add("message", message);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("action");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String action = ToolRegistry.argStr(args, "action");
        if (action == null || action.isBlank()) {
            return "缺少参数 action(start/status/stop/send/join)。";
        }
        String id = ToolRegistry.argStr(args, "id");
        switch (action.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "start" -> {
                String task = ToolRegistry.argStr(args, "task");
                if (task == null || task.isBlank()) {
                    return "start 需要 task 参数(子 agent 的任务描述)。";
                }
                String newId = AgentEngine.agentStart(task.trim());
                return "子 agent 已派发: id=" + newId + "(后台运行中;status 查进度,join 等结果。它的会话已以 agent_" + newId + " 出现在历史会话列表,玩家可随时切进去实时查看过程)";
            }
            case "status" -> {
                if (id == null || id.isBlank()) {
                    return "status 需要 id 参数。";
                }
                return AgentEngine.agentStatus(id.trim());
            }
            case "stop" -> {
                if (id == null || id.isBlank()) {
                    return "stop 需要 id 参数。";
                }
                return AgentEngine.agentStop(id.trim());
            }
            case "send" -> {
                String msg = ToolRegistry.argStr(args, "message");
                if (id == null || id.isBlank() || msg == null || msg.isBlank()) {
                    return "send 需要 id 与 message 参数。";
                }
                return AgentEngine.agentSend(id.trim(), msg.trim());
            }
            case "join" -> {
                if (id == null || id.isBlank()) {
                    return "join 需要 id 参数。";
                }
                return "子 agent 回答:\n" + ToolRegistry.trunc(AgentEngine.agentJoin(id.trim()), 6000);
            }
            default -> {
                return "未知 action: " + action + "(可用 start/status/stop/send/join)。";
            }
        }
    }
}
