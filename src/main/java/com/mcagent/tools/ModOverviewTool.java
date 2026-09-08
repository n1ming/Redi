package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ClientExec;
import com.mcagent.agent.ToolRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * mod_overview:一站式模组速览。一次调用拼出某模组全景——基本信息与描述、
 * 注册物品/方块数量与样例、语言文件清单、Patchouli 手册检测、配置文件、类规模——
 * 让模型被问“某模组怎么玩/有什么内容”时不必反复调 list_classes/read_lang/read_resource。
 *
 * <p>实现分两类数据:jar 包条目(listModEntries/readModBytes,纯 IO,引擎线程直接做)
 * 与注册表扫描(BuiltInRegistries.ITEM/BLOCK,必须经 ClientExec 切回客户端主线程)。</p>
 *
 * <p>javap 证据(compiledWithNeoForge jar + fancymodloader loader-4.0.44.jar):
 * <ul>
 *   <li>{@code net.neoforged.fml.ModList#getMods()} → {@code List<IModInfo>};
 *       {@code getModFileById(String)} → IModFileInfo(ToolRegistry.modFilePath 已封装);</li>
 *   <li>{@code net.neoforged.neoforgespi.language.IModInfo}:
 *       {@code getModId()}/{@code getDisplayName()}/{@code getDescription()}/{@code getVersion()};</li>
 *   <li>{@code net.minecraft.core.registries.BuiltInRegistries}:
 *       {@code public static final DefaultedRegistry<Item> ITEM} 与 {@code DefaultedRegistry<Block> BLOCK},
 *       keySet() 可迭代 ResourceLocation(与 ListItemsTool 用法一致);</li>
 *   <li>{@code net.minecraft.client.Minecraft#gameDirectory}(public final java.io.File)。</li>
 * </ul></p>
 */
public final class ModOverviewTool implements AgentTool {

    /** 物品样例最多列几个(按字母序)。 */
    private static final int ITEM_SAMPLE = 30;

    /** 描述最多输出多少字符(很多模组的玩法说明就写在 mods.toml 描述里)。 */
    private static final int DESC_MAX = 800;

    /** 语言文件超过这个字节数就不再解析条目数(防超大文件全量读入)。 */
    private static final int LANG_PARSE_LIMIT = 2 * 1024 * 1024;

    /** 最多解析多少个语言文件的条目数(防几十个语言文件逐个全量读)。 */
    private static final int LANG_PARSE_MAX_FILES = 12;

    /** 输出整体截断长度。 */
    private static final int OVERVIEW_MAX = 6000;

    /** 一次注册表扫描的结果(主线程里算好,只把小结果带回引擎线程)。 */
    private record RegScan(int itemCount, int blockCount, List<String> items) {
    }

    @Override
    public String name() {
        return "mod_overview";
    }

    @Override
    public String description() {
        return "一站式模组速览:一次调用返回某模组的全景——显示名/版本/描述(mods.toml)、注册物品与方块的数量及样例、"
                + "语言文件清单、Patchouli 手册(有手册的模组玩法文档就在里面)、配置文件、类规模。参数 modid 必填。"
                + "被问“某模组怎么玩/有什么内容/有什么物品”时,先调它拿全景,再按需用 read_lang/read_resource/list_items 深入,"
                + "不要用 list_classes/read_resource 反复拼凑。";
    }

    @Override
    public JsonObject schema() {
        JsonObject modid = new JsonObject();
        modid.addProperty("type", "string");
        modid.addProperty("description", "模组 id,如 goety、mcphone");

        JsonObject props = new JsonObject();
        props.add("modid", modid);

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
        String raw = ToolRegistry.argStr(args, "modid");
        if (raw == null || raw.isBlank()) return "缺少参数 modid。";
        final String ns = raw.trim();
        // 未加载的 modid:提示先 list_mods
        if (ToolRegistry.modFilePath(ns) == null) {
            return "未找到模组 " + ns + "。可先用 list_mods 查看已加载模组的准确 id。";
        }
        try {
            // 一次遍历拿全部条目名(纯 IO,引擎线程);后面各节都在这份列表上过滤
            List<String> entries = ToolRegistry.listModEntries(ns);

            StringBuilder sb = new StringBuilder();
            appendBasicInfo(sb, ns);
            appendRegistryScan(sb, ns);
            appendLangFiles(sb, ns, entries);
            appendPatchouli(sb, ns, entries);
            appendConfigs(sb, ns, entries);
            appendClassScale(sb, ns, entries);
            return ToolRegistry.trunc(sb.toString(), OVERVIEW_MAX);
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    // ------------------------------------------------------------------

    /** 1. 基本信息:显示名、版本、描述(mods.toml 里的描述,截 800 字符)。 */
    private static void appendBasicInfo(StringBuilder sb, String ns) {
        IModInfo info = null;
        // javap: ModList#getMods() → List<IModInfo>;ListModsTool 已在引擎线程同样使用
        for (IModInfo mi : ModList.get().getMods()) {
            if (mi.getModId().equals(ns)) {
                info = mi;
                break;
            }
        }
        if (info == null) {
            sb.append("【模组速览】").append(ns).append("(无 modInfo)\n");
            return;
        }
        sb.append("【模组速览】").append(info.getDisplayName())
                .append("  v").append(info.getVersion()).append('\n');
        String desc = info.getDescription();
        if (desc == null || desc.isBlank()) {
            desc = "(无描述)";
        } else {
            // mods.toml 描述常是多行文本块,压成一行更省屏
            desc = desc.replaceAll("\\s*\\r?\\n\\s*", " ").trim();
        }
        sb.append("描述: ").append(ToolRegistry.trunc(desc, DESC_MAX)).append('\n');
    }

    /** 2. 注册表扫描:该命名空间的物品数量+前 30 个 id、方块数量。必须在主线程读。 */
    private static void appendRegistryScan(StringBuilder sb, String ns) {
        // javap: BuiltInRegistries.ITEM/BLOCK 为 DefaultedRegistry,keySet() 迭代 ResourceLocation
        // (getNamespace() 判命名空间,与 ListItemsTool 相同);注册表读取必须经 ClientExec 主线程
        RegScan scan = ClientExec.get(() -> {
            List<String> items = new ArrayList<>();
            int blocks = 0;
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                if (id.getNamespace().equals(ns)) items.add(id.toString());
            }
            for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
                if (id.getNamespace().equals(ns)) blocks++;
            }
            Collections.sort(items);
            return new RegScan(items.size(), blocks, items);
        }, null);
        if (scan == null) {
            sb.append("物品/方块: 注册表不可读(客户端未就绪)。\n");
            return;
        }
        sb.append("物品 ").append(scan.itemCount()).append(" 个");
        if (!scan.items().isEmpty()) {
            int shown = Math.min(ITEM_SAMPLE, scan.items().size());
            sb.append("(前 ").append(shown).append(",按字母序):\n");
            for (int i = 0; i < shown; i++) {
                String id = scan.items().get(i);
                String loc = inGameLocalizedName(id);
                String disp = loc.isEmpty() ? id : id + "(" + loc + ")";
                sb.append("- ").append(disp).append('\n');
            }
            if (scan.items().size() > shown) sb.append("等 ").append(scan.items().size()).append(" 个\n");
        } else {
            sb.append('\n');
        }
        sb.append("方块 ").append(scan.blockCount()).append(" 个\n");
    }

    /** 3. 语言文件:assets/&lt;ns&gt;/lang/ 下的文件列表(附条目数)。 */
    private static void appendLangFiles(StringBuilder sb, String ns, List<String> entries) {
        String dir = "assets/" + ns + "/lang/";
        List<String> langFiles = new ArrayList<>();
        for (String n : entries) {
            if (!n.startsWith(dir) || !n.endsWith(".json")) continue;
            if (n.substring(dir.length()).contains("/")) continue; // lang/ 下不再嵌套(与 read_lang 一致)
            langFiles.add(n);
        }
        if (langFiles.isEmpty()) {
            sb.append("语言文件: 无\n");
            return;
        }
        // en_us、zh_cn 优先展示
        langFiles.sort(Comparator.comparingInt(ModOverviewTool::langRank));
        List<String> parts = new ArrayList<>();
        int parsed = 0;
        for (String f : langFiles) {
            String file = f.substring(f.lastIndexOf('/') + 1);
            if (parsed >= LANG_PARSE_MAX_FILES) {
                parts.add(file + "(未统计)");
                continue;
            }
            parsed++;
            parts.add(file + "(" + countLangEntries(ns, f) + ")");
        }
        sb.append("语言文件 ").append(langFiles.size()).append(" 个: ")
                .append(String.join("、", parts)).append('\n');
    }

    /** 统计一个语言文件的条目数;过大或解析失败给占位文案,绝不抛出。 */
    private static String countLangEntries(String ns, String path) {
        try {
            byte[] bytes = ToolRegistry.readModBytes(ns, path);
            if (bytes == null) return "?";
            if (bytes.length > LANG_PARSE_LIMIT) return "过大未统计";
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            return json.entrySet().size() + " 条";
        } catch (Exception e) {
            return "解析失败";
        }
    }

    /** 排序权重:en_us 最高,其次 zh_cn。 */
    private static int langRank(String name) {
        if (name.endsWith("/en_us.json")) return 0;
        if (name.endsWith("/zh_cn.json")) return 1;
        return 2;
    }

    /**
     * 4. Patchouli 手册检测。1.21 布局:书籍定义 book.json 在 data/&lt;ns&gt;/patchouli_books/&lt;书&gt;/,
     * 正文条目在 assets/&lt;ns&gt;/patchouli_books/&lt;书&gt;/&lt;语言&gt;/entries/*.json。
     * 两边都扫,按书名合并。
     */
    private static void appendPatchouli(StringBuilder sb, String ns, List<String> entries) {
        // 书名 -> [json 数, 是否有 zh_cn]
        TreeMap<String, int[]> books = new TreeMap<>();
        collectBooks(entries, "data/" + ns + "/patchouli_books/", books);
        collectBooks(entries, "assets/" + ns + "/patchouli_books/", books);
        if (books.isEmpty()) {
            sb.append("Patchouli 手册: 无\n");
            return;
        }
        sb.append("Patchouli 手册 ").append(books.size()).append(" 本:\n");
        for (var en : books.entrySet()) {
            int[] v = en.getValue();
            sb.append("- ").append(en.getKey()).append("(文档 json ").append(v[0])
                    .append(v[1] > 0 ? ",含 zh_cn" : "").append(")\n");
        }
        sb.append("↑ 手册就是玩法文档:用 read_resource 读 assets/").append(ns)
                .append("/patchouli_books/<书名>/<语言>/entries/*.json,content 字段即正文。\n");
    }

    private static void collectBooks(List<String> entries, String prefix, TreeMap<String, int[]> books) {
        for (String n : entries) {
            if (!n.startsWith(prefix) || !n.endsWith(".json")) continue;
            String rest = n.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash <= 0) continue; // <书>/ 下的才算书内文件
            String book = rest.substring(0, slash);
            int[] v = books.computeIfAbsent(book, k -> new int[2]);
            v[0]++;
            if (rest.contains("/zh_cn/")) v[1] = 1;
        }
    }

    /** 5. 配置文件:jar 内 config|defaultconfigs/ 以及游戏实例 config/ 目录下以模组 id 开头的文件。 */
    private static void appendConfigs(StringBuilder sb, String ns, List<String> entries) {
        String low = ns.toLowerCase(Locale.ROOT);
        List<String> jarCfg = new ArrayList<>();
        for (String n : entries) {
            if (!n.startsWith("config/") && !n.startsWith("defaultconfigs/")) continue;
            String name = n.substring(n.lastIndexOf('/') + 1);
            if (!name.isBlank() && name.toLowerCase(Locale.ROOT).startsWith(low)) jarCfg.add(n);
        }
        // javap: Minecraft#gameDirectory 为 public final java.io.File;读它必须切主线程
        List<String> liveCfg = ClientExec.get(() -> {
            List<String> out = new ArrayList<>();
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return out;
            Path cfg = mc.gameDirectory.toPath().resolve("config");
            if (!Files.isDirectory(cfg)) return out;
            try (var stream = Files.list(cfg)) {
                stream.map(p -> p.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(low))
                        .sorted()
                        .limit(10)
                        .forEach(out::add);
            } catch (Exception ignored) {
                // 目录读不了就当作没有
            }
            return out;
        }, List.of());

        if (jarCfg.isEmpty() && liveCfg.isEmpty()) {
            sb.append("配置文件: 未发现\n");
            return;
        }
        sb.append("配置文件:");
        if (!jarCfg.isEmpty()) {
            sb.append(" jar 内 ").append(String.join("、", jarCfg));
        }
        if (!liveCfg.isEmpty()) {
            if (!jarCfg.isEmpty()) sb.append(";");
            sb.append(" 游戏 config/ ").append(String.join("、", liveCfg));
        }
        sb.append('\n');
    }

    /** 6. 类规模:jar 内 class 总数与该模组 id 相关的数量(粗略:路径含模组 id,不区分大小写)。 */
    private static void appendClassScale(StringBuilder sb, String ns, List<String> entries) {
        String low = ns.toLowerCase(Locale.ROOT);
        int total = 0;
        int matched = 0;
        for (String n : entries) {
            if (!n.endsWith(".class") || n.endsWith("module-info.class")) continue;
            total++;
            if (n.toLowerCase(Locale.ROOT).contains(low)) matched++;
        }
        sb.append("class 规模: 共 ").append(total).append(" 个");
        if (total > 0 && matched == total) {
            sb.append("(均与 ").append(ns).append(" 相关)");
        } else if (matched > 0) {
            sb.append(",与 ").append(ns).append(" 相关约 ").append(matched).append(" 个");
        }
        sb.append('\n');
    }

    /** 当前语言下的物品显示名(I18n 直读内存语言表,全模组语言已合并);无翻译返回空串。须主线程。 */
    private static String inGameLocalizedName(String id) {
        try {
            var rl = ResourceLocation.tryParse(id);
            if (rl == null) return "";
            String key = "item." + rl.getNamespace() + "." + rl.getPath();
            String t = net.minecraft.client.resources.language.I18n.get(key);
            if (t != null && !t.equals(key)) return t;
            String bkey = "block." + rl.getNamespace() + "." + rl.getPath();
            String bt = net.minecraft.client.resources.language.I18n.get(bkey);
            return (bt != null && !bt.equals(bkey)) ? bt : "";
        } catch (Throwable t) {
            return "";
        }
    }
}