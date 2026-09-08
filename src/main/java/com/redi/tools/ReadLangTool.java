package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redi.agent.AgentTool;
import com.redi.agent.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * read_lang:读模组包内 assets/&lt;ns&gt;/lang/&lt;file&gt;.json 的键值(优先 en_us、zh_cn),
 * 按前缀过滤,最多 80 条——是“翻译未汉化内容”类任务的基础数据。
 * jar/文件 IO,直接在引擎线程做,不经主线程。
 */
public final class ReadLangTool implements AgentTool {

    /** 单次最多输出的键值条数。 */
    private static final int MAX_ENTRIES = 80;

    /** 超过该字节数(2MB)的语言文件不解析(防超大文件全量读入)。 */
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    /** 单次最多解析的语言文件个数(防语言文件特别多的模组逐个全量读)。 */
    private static final int MAX_FILES = 12;

    @Override
    public String name() {
        return "read_lang";
    }

    @Override
    public String description() {
        return "读取某个模组语言文件(en_us/zh_cn 优先)的键值对,最多 80 条。参数 modid 必填(如 minecraft、mcphone),key_prefix 可选(只输出以它开头的键,如 item.、block.)。做“翻译未汉化内容”“查某物品显示名”类任务时用它。";
    }

    @Override
    public JsonObject schema() {
        JsonObject modid = new JsonObject();
        modid.addProperty("type", "string");
        modid.addProperty("description", "模组 id,如 mcphone");
        JsonObject prefix = new JsonObject();
        prefix.addProperty("type", "string");
        prefix.addProperty("description", "可选;只输出以该前缀开头的键");

        JsonObject props = new JsonObject();
        props.add("modid", modid);
        props.add("key_prefix", prefix);

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
        String prefix = ToolRegistry.argStr(args, "key_prefix");
        if (prefix == null) prefix = "";

        if (ToolRegistry.modFilePath(modid) == null) return "未找到模组: " + modid;
        List<String> langFiles = new ArrayList<>();
        for (String n : ToolRegistry.listModEntries(modid)) {
            if (isLangFile(n)) langFiles.add(n);
        }
        // 优先 en_us,其次 zh_cn,其余排后
        langFiles.sort(Comparator.comparingInt(ReadLangTool::rank));

        StringBuilder sb = new StringBuilder("模组 ").append(modid)
                .append(" 语言条目(前缀 ").append(prefix.isEmpty() ? "(无)" : prefix).append("):\n");
        int count = 0;
        boolean more = false;
        boolean skipped = false;
        int parsedFiles = 0;
        for (String file : langFiles) {
            if (count >= MAX_ENTRIES) {
                more = true;
                break;
            }
            if (parsedFiles >= MAX_FILES) {
                skipped = true;
                break;
            }
            byte[] bytes;
            try {
                bytes = ToolRegistry.readModBytes(modid, file);
            } catch (Exception e) {
                continue;
            }
            if (bytes == null) continue;
            parsedFiles++;
            if (bytes.length > MAX_FILE_BYTES) {
                skipped = true; // 超大语言文件不解析,避免全量读入
                continue;
            }
            JsonObject json;
            try {
                json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                continue; // 解析不了的文件跳过
            }
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, JsonElement> en : json.entrySet()) {
                if (count + lines.size() >= MAX_ENTRIES) break;
                String k = en.getKey();
                if (!prefix.isEmpty() && !k.startsWith(prefix)) continue;
                String v = en.getValue().isJsonPrimitive()
                        ? en.getValue().getAsString() : String.valueOf(en.getValue());
                lines.add("- " + k + " = " + v);
            }
            if (!lines.isEmpty()) {
                sb.append('[').append(file).append("]\n");
                for (String l : lines) sb.append(l).append('\n');
                count += lines.size();
            }
        }
        if (count == 0) {
            return "未在模组 " + modid + " 里找到匹配的语言条目(前缀: "
                    + (prefix.isEmpty() ? "(无)" : prefix) + ")。";
        }
        if (more) sb.append("(最多 ").append(MAX_ENTRIES).append(" 条,可能已截断)\n");
        if (skipped) sb.append("(部分语言文件过大或超出单次解析上限,未读取;可用 read_resource 指定具体文件)\n");
        return ToolRegistry.trunc(sb.toString(), 6000);
    }

    /** 判断条目是不是 assets/<ns>/lang/<名>.json(lang/ 下不允许再嵌套)。 */
    private static boolean isLangFile(String name) {
        if (!name.startsWith("assets/") || !name.endsWith(".json")) return false;
        int langIdx = name.indexOf("/lang/");
        if (langIdx < 0) return false;
        return name.indexOf('/', langIdx + 6) < 0;
    }

    /** 排序权重:en_us 最高。 */
    private static int rank(String name) {
        if (name.endsWith("/en_us.json")) return 0;
        if (name.endsWith("/zh_cn.json")) return 1;
        return 2;
    }
}
