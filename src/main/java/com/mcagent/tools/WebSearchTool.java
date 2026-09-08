package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ToolRegistry;

/**
 * web_search:必应网页搜索(只读 GET),本地知识库/手册/游戏内存都不足以回答时
 * 用来查 wiki 与攻略。代理:设置页 proxyHost/proxyPort → 直连 → 127.0.0.1:7897。
 */
public final class WebSearchTool implements AgentTool {

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "联网搜索(必应网页搜索,返回标题/链接/摘要)。仅当本地信息不足以回答时使用:"
                + "模组玩法先查 mod_overview 与 read_guidebook,游戏常识先查 kb_search,"
                + "都查不到再用本工具(比如查某模组的 wiki、某物品的冷门机制)。"
                + "参数 query 必填(中英文皆可,查模组内容建议用英文名);limit 可选 1~8,默认 5。"
                + "只读操作,绝不会向网络提交玩家的任何本地数据。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject p = new JsonObject();
        JsonObject q = new JsonObject();
        q.addProperty("type", "string");
        q.addProperty("description", "搜索词(查模组相关建议用模组英文名+英文关键词)");
        JsonObject l = new JsonObject();
        l.addProperty("type", "integer");
        l.addProperty("description", "返回结果数,1~8,默认 5");
        p.add("query", q);
        p.add("limit", l);
        schema.add("properties", p);
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String query = ToolRegistry.argStr(args, "query");
        if (query == null || query.isBlank()) {
            return "参数错误:query 必填。";
        }
        int limit = 5;
        try {
            if (args.has("limit") && args.get("limit").isJsonPrimitive()) {
                limit = Math.max(1, Math.min(8, args.get("limit").getAsInt()));
            }
        } catch (Exception ignored) {
        }
        try {
            var hits = WebFetch.search(query.trim(), limit);
            if (hits.isEmpty()) {
                return "搜索没有返回结果,可换关键词(英文)再试。";
            }
            StringBuilder sb = new StringBuilder("搜索 \"").append(query).append("\" 结果:\n");
            for (var h : hits) {
                sb.append("- ").append(h.title).append('\n');
                sb.append("  ").append(h.url).append('\n');
                if (!h.snippet.isEmpty()) {
                    sb.append("  ").append(ToolRegistry.trunc(h.snippet, 160)).append('\n');
                }
            }
            sb.append("需要正文时用 web_read 传对应链接。");
            return ToolRegistry.trunc(sb.toString(), 4000);
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage()
                    + "(网络不可用或被拦截;可在设置页配置代理后重试)";
        }
    }
}
