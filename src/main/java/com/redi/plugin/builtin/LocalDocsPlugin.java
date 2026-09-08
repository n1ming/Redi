package com.redi.plugin.builtin;

import com.redi.plugin.AgentContext;
import com.redi.plugin.AgentPlugin;
import com.redi.tools.CommandUsageTool;
import com.redi.tools.GuidebookTool;
import com.redi.tools.KbSearchTool;
import com.redi.tools.ReadLangTool;
import com.redi.tools.ReadResourceTool;

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
