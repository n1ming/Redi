package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * list_classes:列出模组包内的 class 名(按包前缀过滤,最多 100 个)。
 * 纯 jar/文件 IO,在引擎线程直接做。
 */
public final class ListClassesTool implements AgentTool {

    /** 单次最多输出的类名数量。 */
    private static final int MAX_CLASSES = 100;

    @Override
    public String name() {
        return "list_classes";
    }

    @Override
    public String description() {
        return "列出某个模组包内的 class 名,最多 100 个。参数 modid 必填,package_prefix 可选(Java 包前缀,如 com.november.mcphone.api)。想研究某个模组的代码结构时,先用它找到感兴趣的类,再用 inspect_class 反射查看。";
    }

    @Override
    public JsonObject schema() {
        JsonObject modid = new JsonObject();
        modid.addProperty("type", "string");
        modid.addProperty("description", "模组 id,如 mcphone");
        JsonObject prefix = new JsonObject();
        prefix.addProperty("type", "string");
        prefix.addProperty("description", "可选;Java 包前缀,如 com.november.mcphone.api");

        JsonObject props = new JsonObject();
        props.add("modid", modid);
        props.add("package_prefix", prefix);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("modid");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String modid = ToolRegistry.argStr(args, "modid");
        if (modid == null || modid.isBlank()) return "缺少参数 modid。";
        modid = modid.trim();
        String prefix = ToolRegistry.argStr(args, "package_prefix");

        if (ToolRegistry.modFilePath(modid) == null) return "未找到模组: " + modid;

        // jar 条目名转全限定类名
        List<String> classes = new ArrayList<>();
        for (String n : ToolRegistry.listModEntries(modid)) {
            if (!n.endsWith(".class") || n.endsWith("module-info.class")) continue;
            classes.add(n.substring(0, n.length() - 6).replace('/', '.'));
        }

        String prefixTrimmed = prefix == null ? "" : prefix.trim().replace('/', '.');
        if (!prefixTrimmed.isEmpty()) {
            final String dottedPrefix = prefixTrimmed.endsWith(".") ? prefixTrimmed : prefixTrimmed + ".";
            classes.removeIf(fq -> !fq.startsWith(dottedPrefix) && !fq.equals(prefixTrimmed));
        }
        if (classes.isEmpty()) {
            return "模组 " + modid + " 里没有匹配"
                    + (prefixTrimmed.isEmpty() ? "" : " 前缀 " + prefixTrimmed) + " 的 class。";
        }
        Collections.sort(classes);
        int total = classes.size();
        int shown = Math.min(MAX_CLASSES, total);
        StringBuilder sb = new StringBuilder("模组 ").append(modid)
                .append(" 匹配 ").append(total).append(" 个 class")
                .append(total > shown ? "(显示前 " + shown + ")" : "").append(":\n");
        for (int i = 0; i < shown; i++) sb.append("- ").append(classes.get(i)).append('\n');
        if (total > shown) sb.append("(已截断,可用更细的 package_prefix 缩小范围)\n");
        return ToolRegistry.trunc(sb.toString(), 6000);
    }
}
