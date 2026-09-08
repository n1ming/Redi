package com.redi.plugin;

import com.redi.RediMod;
import com.redi.plugin.builtin.ActionToolsPlugin;
import com.redi.plugin.builtin.LocalDocsPlugin;
import com.redi.plugin.builtin.MemoryToolsPlugin;
import com.redi.plugin.builtin.SessionPlugin;
import com.redi.plugin.builtin.WebToolsPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件装载器:按序加载内置插件,每个插件一个独立作用域(fork),
 * 单个插件装配失败只记日志、不影响其余插件(cordis 的隔离性)。
 * 保留各插件作用域,后续可按 id 热卸载(dispose 即还原)。
 */
public final class PluginManager {
    private static final Map<String, AgentContext> SCOPES = new LinkedHashMap<>();
    /** 插件 id → 该插件登记的工具名(供插件列表面板展示)。 */
    private static final Map<String, List<String>> PLUGIN_TOOLS = new LinkedHashMap<>();
    private static boolean loaded = false;

    private PluginManager() {
    }

    /** 内置插件清单(顺序即装配顺序)。新增插件在这里加一行即可。 */
    private static List<AgentPlugin> builtins() {
        List<AgentPlugin> l = new ArrayList<>();
        l.add(new MemoryToolsPlugin());   // 第一优先:内存读取
        l.add(new LocalDocsPlugin());     // 第二优先:本地文档/知识库
        l.add(new WebToolsPlugin());      // 最后手段:联网
        l.add(new ActionToolsPlugin());   // 行动类(合成/冶炼/发消息/指令)
        l.add(new SessionPlugin());       // 会话持久化(事件驱动)
        return l;
    }

    /** 装载全部内置插件(幂等;首次由 ToolRegistry 惰性触发,也可显式调用)。 */
    public static synchronized void loadAll() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (AgentPlugin p : builtins()) {
            load(p);
        }
    }

    /** 装载单个插件到独立作用域;失败时回滚其已产生的副作用再记日志。 */
    public static synchronized void load(AgentPlugin plugin) {
        if (SCOPES.containsKey(plugin.id())) {
            return;
        }
        java.util.Set<String> before = new java.util.HashSet<>(ToolRegistryNames.snapshot());
        AgentContext scope = AgentContext.SHARED.fork();
        try {
            plugin.setup(scope);
            java.util.List<String> added = new ArrayList<>();
            for (String n : ToolRegistryNames.snapshot()) {
                if (!before.contains(n)) {
                    added.add(n);
                }
            }
            SCOPES.put(plugin.id(), scope);
            PLUGIN_TOOLS.put(plugin.id(), added);
            RediMod.LOGGER.info("[redi] 插件已加载: {} (工具: {})", plugin.id(), added);
        } catch (Throwable t) {
            scope.dispose(); // 回滚半个装配件的副作用
            RediMod.LOGGER.warn("[redi] 插件加载失败: {} ({})", plugin.id(), t.toString());
        }
    }

    /** 某插件登记的工具名列表(没有则空列表)。 */
    public static synchronized List<String> toolsOf(String pluginId) {
        return PLUGIN_TOOLS.getOrDefault(pluginId, List.of());
    }

    /** 按 id 卸载插件:dispose 其作用域,一切注册自动还原。 */
    public static synchronized void unload(String pluginId) {
        AgentContext scope = SCOPES.remove(pluginId);
        if (scope != null) {
            scope.dispose();
            PLUGIN_TOOLS.remove(pluginId);
            RediMod.LOGGER.info("[redi] 插件已卸载: {}", pluginId);
        }
    }

    /** 已加载的插件 id 列表。 */
    public static synchronized List<String> loadedIds() {
        return new ArrayList<>(SCOPES.keySet());
    }

    /** ToolRegistry 工具名快照的内部访问点(避免跨包暴露可变注册表)。 */
    private static final class ToolRegistryNames {
        static List<String> snapshot() {
            List<String> out = new ArrayList<>();
            for (com.redi.agent.AgentTool t : com.redi.agent.ToolRegistry.all()) {
                out.add(t.name());
            }
            return out;
        }
    }
}
