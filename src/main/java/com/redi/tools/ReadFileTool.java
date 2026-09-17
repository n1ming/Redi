package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * read_file:读本机文件(玩家授权的本机文件访问;只读,不写)。
 * 支持绝对路径(E:\dir\a.md / /home/u/a.txt)与相对路径(相对游戏工作目录)。
 * 文本按 UTF-8 读(6000 字符截断);图片(png/jpg/bmp/gif)自动转成像素矩阵供解读;其它二进制给出提示。
 */
public final class ReadFileTool implements AgentTool {

    /** 结果截断上限。 */
    private static final int MAX = 6000;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取本机的一个文件(只读)。参数 path 支持绝对路径(如 E:\\docs\\note.md 或 C:/Users/pc/a.txt)"
                + "与相对路径(相对游戏工作目录)。文本按 UTF-8 返回,最多 6000 字符;"
                + "列出目录内容用 list_files;二进制文件(图片/压缩包等)会提示不支持。"
                + "玩家在输入框用 @路径 可直接把文件导入对话,导入后通常无需再调用本工具。";
    }

    @Override
    public JsonObject schema() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "文件路径,如 E:\\docs\\配方笔记.md 或 config/redi/settings.json");
        JsonObject props = new JsonObject();
        props.add("path", path);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("path");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String raw = ToolRegistry.argStr(args, "path");
        if (raw == null || raw.isBlank()) {
            return "缺少参数 path。";
        }
        return read(raw.trim());
    }

    /** 读取实现(@导入与工具共用)。 */
    static String read(String rawPath) {
        try {
            Path p = Path.of(rawPath);
            if (!p.isAbsolute()) {
                p = Path.of("").toAbsolutePath().resolve(p); // 相对游戏工作目录
            }
            if (Files.isDirectory(p)) {
                return "这是目录,不是文件。用 list_files 列出内容:" + p;
            }
            if (!Files.isRegularFile(p)) {
                return "文件不存在或不可读: " + p + "(检查路径;列目录用 list_files)";
            }
            String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".bmp") || name.endsWith(".gif")) {
                return ImageMatrixTool.matrix(p, 24); // 图片 → 像素矩阵供模型解读
            }
            if (name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".exe") || name.endsWith(".dll")
                    || name.endsWith(".bin") || name.endsWith(".mp3") || name.endsWith(".mp4")) {
                return "二进制文件不支持读取: " + name + "(" + Files.size(p) + " 字节)。只支持文本与图片。";
            }
            String content = Files.readString(p, StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                return "文件是空的: " + p;
            }
            return "文件 " + p + " (" + content.length() + " 字符):\n" + ToolRegistry.trunc(content, MAX);
        } catch (Exception e) {
            return "读取失败: " + e + "(路径含空格时请确认路径正确;列目录用 list_files)";
        }
    }
}
