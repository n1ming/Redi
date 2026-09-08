package com.redi.tools;

import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ClientExec;
import com.redi.agent.ToolRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * list_items:按查询子串列注册表里的物品 id(可选限定模组),
 * 帮模型确认“某模组有哪些物品/某物品的确切注册名”。
 */
public final class ListItemsTool implements AgentTool {

    @Override
    public String name() {
        return "list_items";
    }

    @Override
    public String description() {
        return "在物品注册表里按子串搜物品 id(匹配注册名,不区分大小写),默认列 30 个。参数 query(子串,如 sword、iron_)、modid(可选,限定命名空间,如 mcphone)、limit(可选,1~100,默认 30)。用于找“该模组有哪些物品”或确认某个物品的确切 id,再用 inspect_item 看详情。";
    }

    @Override
    public JsonObject schema() {
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "注册名子串,如 sword;与 modid 至少给一个");
        JsonObject modid = new JsonObject();
        modid.addProperty("type", "string");
        modid.addProperty("description", "可选;限定命名空间,如 mcphone");
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("description", "可选;最多列几个,默认 30");

        JsonObject props = new JsonObject();
        props.add("query", query);
        props.add("modid", modid);
        props.add("limit", limit);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String query = ToolRegistry.argStr(args, "query");
        String modid = ToolRegistry.argStr(args, "modid");
        int limit = 30;
        if (args.has("limit") && args.get("limit").isJsonPrimitive()
                && args.get("limit").getAsJsonPrimitive().isNumber()) {
            limit = args.get("limit").getAsInt();
        }
        limit = Math.max(1, Math.min(100, limit));
        final int cap = limit;

        final String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final String ns = modid == null ? "" : modid.trim();
        if (q.isEmpty() && ns.isEmpty()) return "请至少提供 query 或 modid 之一。";

        Minecraft mc = Minecraft.getInstance();
        return ClientExec.get(() -> {
            try {
                List<String> hits = new ArrayList<>();
                for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                    if (!ns.isEmpty() && !id.getNamespace().equals(ns)) continue;
                    if (!q.isEmpty()) {
                        String full = id.toString().toLowerCase(Locale.ROOT);
                        String path = id.getPath().toLowerCase(Locale.ROOT);
                        if (!full.contains(q) && !path.contains(q)) continue;
                    }
                    hits.add(id.toString());
                }
                hits.sort(Comparator.naturalOrder());
                if (hits.isEmpty()) {
                    return "没有匹配的物品(query=" + (q.isEmpty() ? "(无)" : q)
                            + ",modid=" + (ns.isEmpty() ? "(无)" : ns) + ").";
                }
                int shown = Math.min(cap, hits.size());
                StringBuilder sb = new StringBuilder("共匹配 ").append(hits.size()).append(" 个物品")
                        .append(hits.size() > shown ? "(显示前 " + shown + ")" : "").append(":\n");
                for (int i = 0; i < shown; i++) {
                    String id = hits.get(i);
                    String loc = localizedName(id);
                    String disp = loc.isEmpty() ? id : id + "(" + loc + ")";
                    sb.append("- ").append(disp).append('\n');
                }
                if (hits.size() > shown) sb.append("(提高 limit 可看更多)\n");
                return ToolRegistry.trunc(sb.toString(), 6000);
            } catch (Throwable t) {
                return "读取失败: " + t;
            }
        }, "无法读取注册表(客户端未就绪)。");
    }

    /** 当前语言的物品显示名(I18n 直读内存语言表);无翻译返回空串。须主线程。 */
    private static String localizedName(String id) {
        try {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (rl == null) return "";
            String key = "item." + rl.getNamespace() + "." + rl.getPath();
            String t = net.minecraft.client.resources.language.I18n.get(key);
            if (t != null && !t.equals(key)) return t;
            String bkey = "block." + rl.getNamespace() + "." + rl.getPath();
            String bt = net.minecraft.client.resources.language.I18n.get(bkey);
            return (bt != null && !bt.equals(bkey)) ? bt : "";
        } catch (Throwable t) {
            return "";
        }
    }
}