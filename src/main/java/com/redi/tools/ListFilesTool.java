package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * list_files:列本机目录内容(只读),配合 read_file 浏览文件系统。
 * 目录带 / 后缀,文件带大小;最多 80 项。
 */
public final class ListFilesTool implements AgentTool {

    private static final int MAX = 80;

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "列出本机一个目录的内容(只读)。参数 path 支持绝对与相对路径;"
                + "目录项以 / 结尾,文件项带大小;最多 80 项,超出的提示截断。"
                + "配合 read_file 逐层浏览;玩家 @导入文件时可用它确认路径。";
    }

    @Override
    public JsonObject schema() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "目录路径,如 E:\\docs 或 ./config");
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
        try {
            Path p = Path.of(raw.trim());
            if (!p.isAbsolute()) {
                p = Path.of("").toAbsolutePath().resolve(p);
            }
            if (Files.isRegularFile(p)) {
                return "这是文件不是目录: " + p + "(直接用 read_file 读取)";
            }
            if (!Files.isDirectory(p)) {
                return "目录不存在: " + p;
            }
            List<String> dirs = new ArrayList<>();
            List<String> files = new ArrayList<>();
            try (var stream = Files.list(p)) {
                for (Path c : (Iterable<Path>) stream::iterator) {
                    String n = c.getFileName().toString();
                    if (Files.isDirectory(c)) {
                        dirs.add(n + "/");
                    } else {
                        try {
                            long kb = Files.size(c);
                            files.add(n + "  (" + (kb < 1024 ? kb + "B" : kb / 1024 + "KB") + ")");
                        } catch (Exception e) {
                            files.add(n);
                        }
                    }
                    if (dirs.size() + files.size() >= MAX) {
                        break;
                    }
                }
            }
            java.util.Collections.sort(dirs);
            java.util.Collections.sort(files);
            StringBuilder sb = new StringBuilder("目录 " + p + " (子目录 " + dirs.size() + ", 文件 " + files.size() + "):\n");
            for (String d : dirs) sb.append("- ").append(d).append('\n');
            for (String f : files) sb.append("- ").append(f).append('\n');
            if (dirs.size() + files.size() >= MAX) {
                sb.append("(超过 ").append(MAX).append(" 项已截断)\n");
            }
            return ToolRegistry.trunc(sb.toString(), 5000);
        } catch (Exception e) {
            return "列目录失败: " + e;
        }
    }
}
