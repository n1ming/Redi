package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ClientExec;
import com.mcagent.agent.ToolRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * command_usage:读客户端从服务器同步回来的 Brigadier 指令树,给出指令的精确注册用法
 * (含模组指令),比内置知识库更权威。指令树是只读结构,全程经 ClientExec 在主线程读。
 *
 * <p>API 依据(javap 核对,NeoForge 1.21.1 / brigadier 1.3.10):
 * <ul>
 *   <li>ClientPacketListener.getCommands() →
 *       com.mojang.brigadier.CommandDispatcher&lt;net.minecraft.commands.SharedSuggestionProvider&gt;</li>
 *   <li>ClientPacketListener.getSuggestionsProvider() → net.minecraft.client.multiplayer.ClientSuggestionProvider
 *       (implements net.minecraft.commands.SharedSuggestionProvider,可作 getAllUsage 的 source)</li>
 *   <li>兜底 source:Entity.createCommandSourceStack()(LocalPlayer 继承)→ net.minecraft.commands.CommandSourceStack
 *       (implements SharedSuggestionProvider,javap 核对)</li>
 *   <li>CommandDispatcher.getRoot() → RootCommandNode&lt;S&gt;;
 *       CommandNode.getChildren() → Collection&lt;CommandNode&lt;S&gt;&gt;;CommandNode.getName() → String</li>
 *   <li>CommandDispatcher.getAllUsage(CommandNode&lt;S&gt;, S, boolean) → String[](javap 核对签名)</li>
 * </ul></p>
 */
public final class CommandUsageTool implements AgentTool {

    /** 指令清单的最大输出长度。 */
    private static final int MAX_LIST_CHARS = 6000;

    /** 单个指令用法的最大输出长度。 */
    private static final int MAX_USAGE_CHARS = 3000;

    /** 指令清单每行个数。 */
    private static final int NAMES_PER_LINE = 4;

    /** 清单行内名字之间的分隔(8 个空格)。 */
    private static final String NAME_SEP = "        ";

    @Override
    public String name() {
        return "command_usage";
    }

    @Override
    public String description() {
        return "实时查询当前服务器/单机已注册指令的精确语法(直接读游戏内同步回来的指令树,含模组指令,永远准确),"
                + "比知识库更权威。写或执行任何指令前用它核对语法。参数 command 可选:指令名(不带 /,如 gamemode);"
                + "留空则列出当前所有可用指令名。";
    }

    @Override
    public JsonObject schema() {
        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("description", "指令名,不带开头的 /,如 gamemode;留空则列出所有可用指令名");

        JsonObject props = new JsonObject();
        props.add("command", command);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String raw = ToolRegistry.argStr(args, "command");
        String cmd = raw == null ? "" : raw.strip();
        while (cmd.startsWith("/")) cmd = cmd.substring(1).strip();
        // 只取首词:查的是指令节点本身
        if (!cmd.isEmpty()) cmd = cmd.split("\\s+", 2)[0];
        final String name = cmd;

        Minecraft mc = Minecraft.getInstance();
        // 指令树只读,在主线程读,避免与网络线程的指令同步竞争
        String result = ClientExec.get(() -> {
            if (mc.player == null || mc.player.connection == null) {
                return "当前不在游戏中,无法查询指令。";
            }
            // javap: public com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.SharedSuggestionProvider> getCommands();
            CommandDispatcher<SharedSuggestionProvider> dispatcher = mc.player.connection.getCommands();
            if (name.isEmpty()) {
                return listCommands(dispatcher);
            }
            return usageOf(dispatcher, mc, name);
        }, "当前不在游戏中,无法查询指令。");
        return result;
    }

    /** 列出根节点下全部指令名:按字母序,每行 4 个、8 空格分隔,截 6000。 */
    private static String listCommands(CommandDispatcher<SharedSuggestionProvider> dispatcher) {
        List<String> names = new ArrayList<>();
        for (CommandNode<SharedSuggestionProvider> node : dispatcher.getRoot().getChildren()) {
            names.add(node.getName());
        }
        if (names.isEmpty()) return "当前没有已注册指令(尚未连接到服务器?)。";
        names.sort(String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder("当前服务器/单机已注册指令(共 ")
                .append(names.size())
                .append(" 个,按字母序):\n");
        for (int i = 0; i < names.size(); i += NAMES_PER_LINE) {
            StringBuilder line = new StringBuilder();
            for (int j = i; j < Math.min(i + NAMES_PER_LINE, names.size()); j++) {
                if (j > i) line.append(NAME_SEP);
                line.append(names.get(j));
            }
            sb.append(line).append('\n');
        }
        sb.append("把指令名作为 command 参数再调一次本工具,可查它的精确用法。");
        return ToolRegistry.trunc(sb.toString(), MAX_LIST_CHARS);
    }

    /** 查单个指令的注册用法:先找节点,再 getAllUsage,截 3000。 */
    private static String usageOf(CommandDispatcher<SharedSuggestionProvider> dispatcher,
                                  Minecraft mc, String name) {
        CommandNode<SharedSuggestionProvider> node = findNode(dispatcher, name);
        if (node == null) {
            return "当前服务器没有该指令(可能是模组指令未装,或名称错误):" + name;
        }
        // source:优先用服务器同步回的建议提供者;javap 核对两者均为 SharedSuggestionProvider 实现
        SharedSuggestionProvider source;
        try {
            // javap: public net.minecraft.client.multiplayer.ClientSuggestionProvider getSuggestionsProvider();
            source = mc.player.connection.getSuggestionsProvider();
        } catch (Throwable t) {
            // javap: Entity.createCommandSourceStack() → CommandSourceStack(implements SharedSuggestionProvider)
            source = mc.player.createCommandSourceStack();
        }
        try {
            // javap: public java.lang.String[] getAllUsage(com.mojang.brigadier.tree.CommandNode<S>, S, boolean);
            String[] usages = dispatcher.getAllUsage(node, source, false);
            StringBuilder sb = new StringBuilder("指令 /").append(node.getName()).append(" 的注册用法(每行一种):\n");
            if (usages == null || usages.length == 0) {
                sb.append("(该指令没有可显示的用法,可能需要更高权限或参数子节点不可见。)");
            } else {
                for (String u : usages) sb.append(u).append('\n');
            }
            sb.append("以上为游戏内真实注册语法,含模组指令。");
            return ToolRegistry.trunc(sb.toString(), MAX_USAGE_CHARS);
        } catch (Exception e) {
            return "查询指令用法失败: " + e;
        }
    }

    /** 在根节点的直接子节点里找指令:先精确匹配,再忽略大小写。 */
    private static CommandNode<SharedSuggestionProvider> findNode(
            CommandDispatcher<SharedSuggestionProvider> dispatcher, String name) {
        for (CommandNode<SharedSuggestionProvider> node : dispatcher.getRoot().getChildren()) {
            if (node.getName().equals(name)) return node;
        }
        for (CommandNode<SharedSuggestionProvider> node : dispatcher.getRoot().getChildren()) {
            if (node.getName().equalsIgnoreCase(name)) return node;
        }
        return null;
    }
}
