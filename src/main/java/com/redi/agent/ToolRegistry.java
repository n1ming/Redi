package com.redi.agent;

import com.google.gson.JsonObject;
import com.redi.tools.CommandUsageTool;
import com.redi.tools.CraftItemTool;
import com.redi.tools.FindRecipesTool;
import com.redi.tools.GetTargetTool;
import com.redi.tools.GuidebookTool;
import com.redi.tools.InspectClassTool;
import com.redi.tools.InspectItemTool;
import com.redi.tools.KbSearchTool;
import com.redi.tools.ListClassesTool;
import com.redi.tools.ListItemsTool;
import com.redi.tools.ListModsTool;
import com.redi.tools.MemoryTreeTool;
import com.redi.tools.ModOverviewTool;
import com.redi.tools.PlayerContextTool;
import com.redi.tools.ReadLangTool;
import com.redi.tools.ReadResourceTool;
import com.redi.tools.SendChatTool;
import com.redi.tools.SendCommandTool;
import com.redi.tools.SmeltItemTool;
import com.redi.tools.WebReadTool;
import com.redi.tools.WebSearchTool;
import net.neoforged.fml.ModList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 全部 agent 工具的注册表:给引擎用({@link #all()}/{@link #byName(String)}),
 * 给 LLM 用({@link #schemas()})。工具本体由插件({@code com.redi.plugin})
 * 在装配期通过 {@link #install(AgentTool)} 登记,卸载时 {@link #uninstall(AgentTool)} 还原。
 * 另带几个工具共用的静态辅助(结果截断、参数读取、模组包条目列举与读取)。
 */
public final class ToolRegistry {

    /** 取证优先级层(玩家要求:优先内存,其次本地,最后联网;行动类不参与分层)。 */
    public enum Tier {MEMORY, LOCAL, WEB, ACTION}

    private static final List<AgentTool> TOOLS = new ArrayList<>();
    private static final Map<String, AgentTool> BY_NAME = new LinkedHashMap<>();
    private static boolean pluginsLoaded = false;

    /** 工具 → 取证优先级层(新增插件工具默认 LOCAL 层)。 */
    private static final Map<String, Tier> TIERS = Map.ofEntries(
            Map.entry("browse", Tier.MEMORY),
            Map.entry("player_context", Tier.MEMORY),
            Map.entry("get_target", Tier.MEMORY),
            Map.entry("inspect_item", Tier.MEMORY),
            Map.entry("find_recipes", Tier.MEMORY),
            Map.entry("list_items", Tier.MEMORY),
            Map.entry("list_mods", Tier.MEMORY),
            Map.entry("mod_overview", Tier.MEMORY),
            Map.entry("list_classes", Tier.MEMORY),
            Map.entry("inspect_class", Tier.MEMORY),
            Map.entry("kb_search", Tier.LOCAL),
            Map.entry("read_guidebook", Tier.LOCAL),
            Map.entry("read_lang", Tier.LOCAL),
            Map.entry("read_resource", Tier.LOCAL),
            Map.entry("command_usage", Tier.LOCAL),
            Map.entry("web_search", Tier.WEB),
            Map.entry("web_read", Tier.WEB),
            Map.entry("craft_item", Tier.ACTION),
            Map.entry("smelt_item", Tier.ACTION),
            Map.entry("send_chat", Tier.ACTION),
            Map.entry("send_command", Tier.ACTION));

    public static Tier tier(String toolName) {
        return TIERS.getOrDefault(toolName == null ? "" : toolName, Tier.LOCAL);
    }

    /** 插件登记一个工具(同名忽略后到者)。 */
    public static synchronized void install(AgentTool t) {
        if (t == null || BY_NAME.containsKey(t.name())) {
            return;
        }
        TOOLS.add(t);
        BY_NAME.put(t.name(), t);
    }

    /** 移除一个工具(插件卸载时由作用域回滚触发)。 */
    public static synchronized void uninstall(AgentTool t) {
        if (t == null) {
            return;
        }
        BY_NAME.remove(t.name(), t);
        TOOLS.remove(t);
    }

    private static void ensurePluginsLoaded() {
        if (!pluginsLoaded) {
            pluginsLoaded = true;
            com.redi.plugin.PluginManager.loadAll();
        }
    }

    /** 全部工具实例(顺序稳定;首次访问触发插件装载)。 */
    public static synchronized List<AgentTool> all() {
        ensurePluginsLoaded();
        return List.copyOf(TOOLS);
    }

    /** OpenAI tools 数组内容:每项 {"type":"function","function":{name,description,parameters}}。 */
    public static List<JsonObject> schemas() {
        List<JsonObject> out = new ArrayList<>();
        for (AgentTool t : all()) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", t.schema());
            JsonObject wrap = new JsonObject();
            wrap.addProperty("type", "function");
            wrap.add("function", fn);
            out.add(wrap);
        }
        return out;
    }

    /** 按名取工具;没有则 null。 */
    public static synchronized AgentTool byName(String name) {
        ensurePluginsLoaded();
        return name == null ? null : BY_NAME.get(name);
    }

    // ---------------------- 工具共用的静态辅助 ----------------------

    /** 结果文本截断到 max 字符,截断时在结尾注明“(已截断)”。 */
    public static String trunc(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "\n(已截断)";
    }

    /** 从工具参数里读一个字符串;不存在或不是字符串返回 null。 */
    public static String argStr(JsonObject args, String key) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) return null;
        return args.get(key).getAsString();
    }

    /** 模组包的根路径(jar 文件,或 dev 环境下的目录);模组未加载返回 null。 */
    public static Path modFilePath(String modid) {
        try {
            var info = ModList.get().getModFileById(modid);
            if (info == null) return null;
            return info.getFile().getFilePath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 列出模组包内全部条目的内部路径(jar 条目名 / 目录相对路径,统一用 '/')。
     * 纯文件 IO,可在引擎线程直接调用;失败返回空列表。
     */
    public static List<String> listModEntries(String modid) {
        Path root = modFilePath(modid);
        if (root == null) return List.of();
        List<String> out = new ArrayList<>();
        if (root.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (ZipFile zf = new ZipFile(root.toFile())) {
                var it = zf.entries();
                while (it.hasMoreElements()) out.add(it.nextElement().getName());
            } catch (Exception e) {
                return List.of();
            }
        } else {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .forEach(f -> out.add(root.relativize(f).toString().replace('\\', '/')));
            } catch (Exception e) {
                return List.of();
            }
        }
        return out;
    }

    /**
     * 读模组包内一个资源的字节;不存在返回 null。
     * 纯文件 IO,可在引擎线程直接调用。
     */
    public static byte[] readModBytes(String modid, String internalPath) throws Exception {
        Path root = modFilePath(modid);
        if (root == null) return null;
        String p = internalPath.startsWith("/") ? internalPath.substring(1) : internalPath;
        if (root.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (ZipFile zf = new ZipFile(root.toFile())) {
                ZipEntry e = zf.getEntry(p);
                if (e == null) return null;
                try (var in = zf.getInputStream(e)) {
                    return in.readAllBytes();
                }
            }
        }
        Path f = root.resolve(p);
        if (!Files.isRegularFile(f)) return null;
        return Files.readAllBytes(f);
    }
}
