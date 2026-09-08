package com.mcagent.plugin.builtin;

import com.mcagent.plugin.AgentContext;
import com.mcagent.plugin.AgentPlugin;
import com.mcagent.tools.CommandUsageTool;
import com.mcagent.tools.GuidebookTool;
import com.mcagent.tools.KbSearchTool;
import com.mcagent.tools.ReadLangTool;
import com.mcagent.tools.ReadResourceTool;

/**
 * 本地文档插件(取证第二优先):模组 jar 内文件、内置知识库、指令语法实时查询。
 */
public final class LocalDocsPlugin implements AgentPlugin {
    @Override
    public String id() {
        return "local-docs";
    }

    @Override
    public void setup(AgentContext ctx) {
        ctx.tool(new KbSearchTool());
        ctx.tool(new GuidebookTool());
        ctx.tool(new ReadLangTool());
        ctx.tool(new ReadResourceTool());
        ctx.tool(new CommandUsageTool());
    }
}
