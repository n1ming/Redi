package com.mcagent.plugin.builtin;

import com.mcagent.plugin.AgentContext;
import com.mcagent.plugin.AgentPlugin;
import com.mcagent.tools.WebReadTool;
import com.mcagent.tools.WebSearchTool;

/**
 * 联网插件(取证最后手段):web_search + web_read。
 * 前两层(内存/本地)查不到才允许使用 —— 由系统提示词的分层规则约束。
 */
public final class WebToolsPlugin implements AgentPlugin {
    @Override
    public String id() {
        return "web-tools";
    }

    @Override
    public void setup(AgentContext ctx) {
        ctx.tool(new WebSearchTool());
        ctx.tool(new WebReadTool());
    }
}
