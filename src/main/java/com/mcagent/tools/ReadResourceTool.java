package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * read_resource:读模组包内一个文本资源(assets/... 或 data/... 内部路径),
 * 截 6000 字符;二进制资源只报类型与大小;超过 5MB 的文件拒绝整体读入。
 * 纯 jar/文件 IO,在引擎线程直接做。
 */
public final class ReadResourceTool implements AgentTool {

    /** 超过该字节数(5MB)的文件不做全量读入,提示模型换更具体的路径。 */
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    @Override
    public String name() {
        return "read_resource";
    }

    @Override
    public String description() {
        return "读取模组包内一个资源的文本内容(截 6000 字符),如 assets/mcphone/lang/en_us.json、data/*/recipes/*.json。参数 modid 与 path(模组包内部路径,不要以 / 开头)必填。二进制资源只返回大小。读语言文件用 read_lang 更方便;先 list_mods 确认 modid。";
    }

    @Override
    public JsonObject schema() {
        JsonObject modid = new JsonObject();
        modid.addProperty("type", "string");
        modid.addProperty("description", "模组 id,如 mcphone");
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "模组包内部路径,如 assets/mcphone/lang/en_us.json");

        JsonObject props = new JsonObject();
        props.add("modid", modid);
        props.add("path", path);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("modid");
        required.add("path");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String modid = ToolRegistry.argStr(args, "modid");
        String path = ToolRegistry.argStr(args, "path");
        if (modid == null || modid.isBlank()) return "缺少参数 modid。";
        if (path == null || path.isBlank()) return "缺少参数 path。";
        if (ToolRegistry.modFilePath(modid.trim()) == null) return "未找到模组: " + modid;
        try {
            byte[] bytes = ToolRegistry.readModBytes(modid.trim(), path.trim());
            if (bytes == null) return "模组 " + modid + " 内没有资源: " + path;
            // 加固:超大文件(>5MB)不整体转文本,避免大模组资源(贴图/音频/大 json)把内存与上下文撑爆
            if (bytes.length > MAX_BYTES) {
                return "文件过大(" + String.format(Locale.ROOT, "%.1f", bytes.length / 1048576.0)
                        + " MB),已拒绝整体读取。请指定更具体的路径,或用 read_lang 读语言文件。";
            }
            if (looksBinary(bytes)) {
                return "非文本资源: " + path + ",大小 " + bytes.length + " 字节。";
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            return ToolRegistry.trunc("资源 " + path + "(" + text.length() + " 字符):\n" + text, 6000);
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    /** 粗判字节内容是不是二进制:出现 0x00 或控制字符占比过高即视为二进制。 */
    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 1024);
        int ctrl = 0;
        for (int i = 0; i < n; i++) {
            int v = bytes[i] & 0xFF;
            if (v == 0) return true;
            if (v < 0x09 || (v > 0x0D && v < 0x20)) ctrl++;
        }
        return n > 0 && ctrl * 10 > n;
    }
}
