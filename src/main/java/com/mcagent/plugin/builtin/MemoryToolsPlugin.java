package com.mcagent.plugin.builtin;

import com.mcagent.plugin.AgentContext;
import com.mcagent.plugin.AgentPlugin;
import com.mcagent.tools.FindRecipesTool;
import com.mcagent.tools.GetTargetTool;
import com.mcagent.tools.InspectClassTool;
import com.mcagent.tools.InspectItemTool;
import com.mcagent.tools.ListClassesTool;
import com.mcagent.tools.ListItemsTool;
import com.mcagent.tools.ListModsTool;
import com.mcagent.tools.MemoryTreeTool;
import com.mcagent.tools.ModOverviewTool;
import com.mcagent.tools.PlayerContextTool;

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
