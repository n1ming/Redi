package com.redi.plugin.builtin;

import com.redi.plugin.AgentContext;
import com.redi.plugin.AgentPlugin;
import com.redi.tools.FindRecipesTool;
import com.redi.tools.GetTargetTool;
import com.redi.tools.InspectClassTool;
import com.redi.tools.InspectItemTool;
import com.redi.tools.ListClassesTool;
import com.redi.tools.ListItemsTool;
import com.redi.tools.ListModsTool;
import com.redi.tools.MemoryTreeTool;
import com.redi.tools.ModOverviewTool;
import com.redi.tools.PlayerContextTool;

/**
 * 内存读取插件(取证第一优先):读 JVM 内实时游戏状态的全部工具。
 */
public final class MemoryToolsPlugin implements AgentPlugin {
    @Override
    public String id() {
        return "memory-tools";
    }

    @Override
    public void setup(AgentContext ctx) {
        ctx.tool(new PlayerContextTool());
        ctx.tool(new GetTargetTool());
        ctx.tool(new InspectItemTool());
        ctx.tool(new FindRecipesTool());
        ctx.tool(new ListItemsTool());
        ctx.tool(new ListModsTool());
        ctx.tool(new ModOverviewTool());
        ctx.tool(new ListClassesTool());
        ctx.tool(new InspectClassTool());
        ctx.tool(new MemoryTreeTool());
    }
}
