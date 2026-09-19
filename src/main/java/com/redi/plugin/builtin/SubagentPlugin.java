package com.redi.plugin.builtin;

import com.redi.agent.AgentSession;
import com.redi.plugin.AgentContext;
import com.redi.plugin.AgentPlugin;
import com.redi.tools.AgentTaskTool;
import com.redi.tools.SpawnTaskTool;

/**
 * 子 agent 插件:spawn_task 工具——模型派一个独立子会话(干净上下文、
 * 全套工具)执行子任务并取回其最终回答。父会话被停止时联动停子。
 */
public final class SubagentPlugin implements AgentPlugin {
    @Override
    public String id() {
        return "subagent";
    }

    @Override
    public void setup(AgentContext ctx) {
        ctx.tool(new SpawnTaskTool()); // 同步便捷:派发即等结果
        ctx.tool(new AgentTaskTool()); // 异步控制台:start/status/stop/send/join
    }
}
