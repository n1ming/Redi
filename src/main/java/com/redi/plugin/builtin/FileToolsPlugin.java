package com.redi.plugin.builtin;

import com.redi.plugin.AgentContext;
import com.redi.plugin.AgentPlugin;
import com.redi.tools.ListFilesTool;
import com.redi.tools.ReadFileTool;

/**
 * 本机文件插件(玩家授权的本机文件访问,只读):
 * read_file 读文本文件、list_files 列目录;
 * 配合输入框 @路径 快捷导入(见 ChatView/FileImport)。
 */
public final class FileToolsPlugin implements AgentPlugin {
    @Override
    public String id() {
        return "file-tools";
    }

    @Override
    public void setup(AgentContext ctx) {
        ctx.tool(new ReadFileTool());
        ctx.tool(new ListFilesTool());
    }
}
