package com.mcagent.plugin.builtin;

import com.mcagent.agent.ChatStore;
import com.mcagent.plugin.AgentContext;
import com.mcagent.plugin.AgentPlugin;

/**
 * 会话持久化插件(事件驱动示例):监听任务结束事件,自动保存当前会话。
 * 引擎不再直接依赖存档逻辑 —— 卸载本插件即失去自动存档,重新装载即恢复,
 * 副作用完全由上下文管理(cordis 的可回滚 effect)。
 */
public final class SessionPlugin implements AgentPlugin {
    @Override
    public String id() {
        return "session-persist";
    }

    @Override
    public void setup(AgentContext ctx) {
        ctx.on("task.finished", payload -> ChatStore.saveCurrent());
    }
}
