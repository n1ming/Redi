package com.redi.tools;

import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ToolRegistry;
import net.neoforged.fml.ModList;

/**
 * list_mods:列出当前已加载的全部模组(id、显示名、版本),
 * 并标注手机宿主模组 mcphone。纯读取 FML 模组列表,不碰主线程。
 */
public final class ListModsTool implements AgentTool {

    @Override
    public String name() {
        return "list_mods";
    }

    @Override
    public String description() {
        return "列出当前已加载的全部模组(id、显示名、版本),mcphone(手机宿主模组)会被标注。回答“有哪些模组/某模组的 id 是什么/版本多少”时用它,也适合在研究某个模组前先确认 modid。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        try {
            var mods = ModList.get().getMods();
            StringBuilder sb = new StringBuilder("已加载模组 ").append(mods.size()).append(" 个:\n");
            for (var m : mods) {
                String id = m.getModId();
                sb.append("- ").append(id)
                        .append(" | ").append(m.getDisplayName())
                        .append(" | ").append(m.getVersion());
                if ("mcphone".equals(id)) sb.append(" ← 手机模组");
                sb.append('\n');
            }
            return ToolRegistry.trunc(sb.toString(), 6000);
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }
}
