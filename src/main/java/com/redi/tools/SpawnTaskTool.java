package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentEngine;
import com.redi.agent.AgentSession;
import com.redi.agent.AgentTool;

/**
 * spawn_task:派一个子 agent(独立会话、干净上下文、全套工具)执行子任务。
 * 父会话的工具调用线程会等待子完成并取回其最终回答(阻塞式,父取消联动停子)。
 * 适合把大任务拆成独立子部分并行推进(每个子任务一次调用)。
 */
public final class SpawnTaskTool implements AgentTool {

    @Override
    public String name() {
        return "spawn_task";
    }

    @Override
    public String description() {
        return "派一个子 agent(独立会话)执行子任务:参数 task 填给子 agent 的完整任务描述"
                + "(要自包含——子 agent 看不到本会话任何历史,需要的背景信息必须写进 task 里)。"
                + "子 agent 拥有除 spawn_task 外的全部工具与干净上下文,完成后本工具返回它的最终回答。"
                + "适用:把大任务拆成独立子部分(如分别查证多个模组、分别整理多份资料),"
                + "逐个调用本工具,最后你汇总各子结果作答。父任务被停止时子 agent 自动停止。"
                + "注意:子任务与当前对话强相关、需要本会话上下文时不要用它,直接自己做。";
    }

    @Override
    public JsonObject schema() {
        JsonObject task = new JsonObject();
        task.addProperty("type", "string");
        task.addProperty("description", "子 agent 的任务描述(自包含:目标+背景+期望的输出格式)");
        JsonObject props = new JsonObject();
        props.add("task", task);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("task");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String task = com.redi.agent.ToolRegistry.argStr(args, "task");
        if (task == null || task.isBlank()) {
            return "缺少参数 task(子 agent 的任务描述)。";
        }
        AgentSession parent = AgentSession.currentRunning(); // 调用方会话(取消联动)
        try {
            String answer = AgentEngine.spawnSubagent(task.trim(), parent);
            return "子 agent 回答:\n" + com.redi.agent.ToolRegistry.trunc(answer, 6000);
        } catch (Throwable t) {
            return "子 agent 执行出错: " + t;
        }
    }
}
