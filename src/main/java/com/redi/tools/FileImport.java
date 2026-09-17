package com.redi.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @文件导入:输入框文本里的 “@路径” token 在发送时读取文件内容,
 * 附加为任务上下文(聊天气泡仍显示玩家原文)。
 *
 * <p>支持的形式:Windows 绝对(@E:\docs\a.md)、POSIX 绝对(@/home/u/a.txt)、
 * 相对(@config/redi/settings.json);图片会转成像素矩阵;token 到第一个空白为止(路径含空格时
 * 请先确认无空格或用相对路径)。每个文件最多内联 6000 字符,与 read_file 一致。</p>
 */
public final class FileImport {

    /** @路径 token:绝对盘符 / 斜杠开头 / 相对路径。 */
    private static final Pattern AT = Pattern.compile(
            "@([A-Za-z]:[\\\\/][^\\s@]+|[\\\\/][^\\s@]+|[^\\s@:*?\"<>|\\\\/]+[\\\\/][^\\s@]+)");

    private FileImport() {
    }

    /** 提取文本里的全部 @路径 token(原文,去 @)。 */
    public static List<String> extractPaths(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.indexOf('@') < 0) {
            return out;
        }
        Matcher m = AT.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * 构造发给引擎的任务文本:玩家原文 + 每个 @文件的内容块。
     * 文件读不到时附加一行错误提示(任务不中断)。
     */
    public static String enrich(String text) {
        List<String> paths = extractPaths(text);
        if (paths.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (String raw : paths) {
            sb.append("\n\n[@导入文件 ").append(raw).append("]\n");
            sb.append(ReadFileTool.read(Path.of(raw).toString()));
        }
        sb.append("\n[@导入结束] 以上 @ 文件内容已随消息提供,直接基于它回答,无需重复读取。");
        return sb.toString();
    }
}
