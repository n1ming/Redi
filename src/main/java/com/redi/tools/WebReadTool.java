package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ToolRegistry;

/**
 * web_read:抓取一个网页并转成纯文本(只读 GET),配合 web_search 用
 * (搜索命中后读 wiki/攻略正文)。禁止非 http/https 协议。
 */
public final class WebReadTool implements AgentTool {

    @Override
    public String name() {
        return "web_read";
    }

    @Override
    public String description() {
        return "读取网页正文(转纯文本)。配合 web_search 使用:搜索结果里挑最相关的链接传入。"
                + "参数 url 必填(完整 http/https 链接);max_chars 可选,默认 6000。"
                + "只读操作;wiki 页面信息密度低时优先读条目页而不是搜索页。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject p = new JsonObject();
        JsonObject u = new JsonObject();
        u.addProperty("type", "string");
        u.addProperty("description", "完整网页链接(http/https)");
        JsonObject m = new JsonObject();
        m.addProperty("type", "integer");
        m.addProperty("description", "返回正文最大字符数,默认 6000,最大 12000");
        p.add("url", u);
        p.add("max_chars", m);
        schema.add("properties", p);
        JsonArray required = new JsonArray();
        required.add("url");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String url = ToolRegistry.argStr(args, "url");
        if (url == null || url.isBlank()) {
            return "参数错误:url 必填。";
        }
        String u = url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            return "参数错误:只支持 http/https 链接。";
        }
        int max = 6000;
        try {
            if (args.has("max_chars") && args.get("max_chars").isJsonPrimitive()) {
                max = Math.max(500, Math.min(12000, args.get("max_chars").getAsInt()));
            }
        } catch (Exception ignored) {
        }
        try {
            String html = WebFetch.get(u, 20);
            String text = WebFetch.stripTags(html);
            if (text.isEmpty()) {
                return "页面抓到了但没有正文(可能是纯 JS 渲染页面),换一个链接试试。";
            }
            String head = "已读取 " + u + " (正文 " + text.length() + " 字符):\n";
            return ToolRegistry.trunc(head + text, max);
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }
}
