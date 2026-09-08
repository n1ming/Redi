package com.redi.plugin.builtin;

import com.redi.plugin.AgentContext;
import com.redi.plugin.AgentPlugin;
import com.redi.tools.CraftItemTool;
import com.redi.tools.SendChatTool;
import com.redi.tools.SendCommandTool;
import com.redi.tools.SmeltItemTool;

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
