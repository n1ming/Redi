package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ToolRegistry;
import com.mcagent.kb.KnowledgeBase;

/**
 * kb_search:检索模组内置中文知识库(《指令速查》《选择器与坐标》《物品与组件》),
 * 纯本地文本检索,不走网络、不读游戏状态,可直接在引擎线程执行。
 */
public final class KbSearchTool implements AgentTool {

    @Override
    public String name() {
        return "kb_search";
    }

    @Override
    public String description() {
        return "检索本地内置中文知识库(《指令速查》《选择器与坐标》《物品与组件》)。"
                + "回答指令用法、选择器写法、物品组件语法、附魔/食物/坐标等游戏常识问题前,先用它查一次(通常一次调用即可),"
                + "不要凭记忆猜测语法。参数 query 必填(可多个空格分隔的检索词,支持中文);"
                + "file 可选,指定只在某个文件里查(commands / selectors / items_basics,可带 .md,支持前缀);"
                + "limit 可选,返回小节数 1~8,默认 4。";
    }

    @Override
    public JsonObject schema() {
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "检索词,可多个空格分隔,如 “execute”、“选择器 距离”、“物品 组件”");

        JsonObject file = new JsonObject();
        file.addProperty("type", "string");
        file.addProperty("description", "可选:限定文件 commands(指令速查)/ selectors(选择器与坐标)/ items_basics(物品与组件),可带 .md,支持前缀匹配");

        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("description", "可选:返回小节数,1~8,默认 4");

        JsonObject props = new JsonObject();
        props.add("query", query);
        props.add("file", file);
        props.add("limit", limit);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String query = ToolRegistry.argStr(args, "query");
        String file = ToolRegistry.argStr(args, "file");
        int limit = 0;
        try {
            if (args != null && args.has("limit") && args.get("limit").isJsonPrimitive()) {
                limit = args.get("limit").getAsInt();
            }
        } catch (Exception e) {
            limit = 0; // 非法 limit 走默认值
        }
        return KnowledgeBase.get().search(query, file, limit);
    }
}
