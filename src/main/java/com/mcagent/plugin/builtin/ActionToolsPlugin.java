package com.mcagent.plugin.builtin;

import com.mcagent.plugin.AgentContext;
import com.mcagent.plugin.AgentPlugin;
import com.mcagent.tools.CraftItemTool;
import com.mcagent.tools.SendChatTool;
import com.mcagent.tools.SendCommandTool;
import com.mcagent.tools.SmeltItemTool;

/**
 * 行动插件:以玩家身份改变游戏状态的工具(合成/冶炼/发消息/执行指令)。
 * 行动类不参与取证优先级分层,但受系统提示词硬性规则约束(禁止破坏性操作)。
 */
public final class ActionToolsPlugin implements AgentPlugin {
    @Override
    public String id() {
        return "action-tools";
    }

    @Override
    public void setup(AgentContext ctx) {
        ctx.tool(new CraftItemTool());
        ctx.tool(new SmeltItemTool());
        ctx.tool(new SendChatTool());
        ctx.tool(new SendCommandTool());
    }
}
