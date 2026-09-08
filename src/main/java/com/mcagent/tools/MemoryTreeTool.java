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
import net.minecraft.world.entity.player.Inventory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * browse:游戏内存的树形导航(玩家要求:内存信息以树形结构组织,AI 可沿支路深搜/广搜)。
 *
 * <p>路径即查询:每次调用返回该节点的一层子分支(带数量与说明,树形符号输出)。
 * 分支名以 “/” 结尾表示可继续深入;大列表被分成若干分支而不是一次平铺,
 * 模型按分支关键词逐层下钻(相当于自己执行 DFS/BFS),远比在几千行平铺列表里捞精确。</p>
 *
 * <pre>
 * /                      根:player / world / mods / registry / recipes / lang
 * /player                位置/生命/主副手 + inventory/ armor/ effects/
 * /world                 时间/天气/群系/实体统计 + entities/
 * /mods/&lt;modid&gt;          items/ blocks/ entities/ classes/
 * /mods/&lt;m&gt;/items        按注册名首段分组的分支(cauldron/ totem/ …)
 * /mods/&lt;m&gt;/items/&lt;g&gt;     该组物品清单(≤60 条)
 * /mods/&lt;m&gt;/classes/...  包 → 子包/类 → 类成员(字段/方法)
 * /registry/…            全局注册表,同样按命名空间→前缀分组
 * /recipes/&lt;modid&gt;       配方 id 清单
 * /lang/&lt;modid&gt;          语言键按前两段分组
 * </pre>
 *
 * <p>玩家/背包/实体等状态经 {@link ClientExec} 切主线程读取;注册表/语言文件
 * 只读静态结构。所有输出经 5000 字符截断,分支数/成员数各自设上限。</p>
 */
public final class MemoryTreeTool implements AgentTool {

    /** 每个节点最多输出的分支数/叶子数。 */
    private static final int MAX_BRANCHES = 48;
    private static final int MAX_LEAVES = 60;

    @Override
    public String name() {
        return "browse";
    }

    @Override
    public String description() {
        return "以树形结构浏览游戏内存(玩家要求的信息导航方式):路径即查询,每次返回该节点的一层子分支"
                + "(带数量,分支名以 / 结尾表示可继续深入)。大清单被组织成分支树,沿支路逐层下钻查找,"
                + "比一次性平铺大列表精准得多。可用根:/player(玩家状态/背包/效果)、/world(世界/附近实体)、"
                + "/mods(各模组的 items/blocks/entities/classes 分支)、/registry(全局注册表)、"
                + "/recipes(配方)、/lang(语言键)。"
                + "参数 path 必填(如 /mods/goety/items、/player/inventory、/registry/items/goety/totem)。"
                + "要找“某模组有哪些某类东西”时,先到 items 层看分支名,再进相关分支,而不是直接拉全量清单。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject p = new JsonObject();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "树路径,如 / 或 /player/inventory 或 /mods/goety/items");
        p.add("path", path);
        schema.add("properties", p);
        JsonArray required = new JsonArray();
        required.add("path");
        schema.add("required", required);
        return schema;
    }

    // ------------------------------------------------------------------

    @Override
    public String execute(JsonObject args) {
        String raw = ToolRegistry.argStr(args, "path");
        String path = raw == null || raw.isBlank() ? "" : raw.trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String[] seg = path.isEmpty() ? new String[0] : path.split("/");
        try {
            switch (seg.length == 0 ? "" : seg[0]) {
                case "":
                    return root();
                case "player":
                    return ClientExec.get(() -> player(segments(seg)), "玩家状态读取失败(主线程超时)。");
                case "world":
                    return ClientExec.get(() -> world(segments(seg)), "世界状态读取失败(主线程超时)。");
                case "mods":
                    return mods(segments(seg));
                case "registry":
                    return ClientExec.get(() -> registry(segments(seg)), "注册表读取失败。");
                case "recipes":
                    return ClientExec.get(() -> recipes(segments(seg)), "配方读取失败(需单机存档)。");
                case "lang":
                    return lang(segments(seg));
                default:
                    return "未知根分支 \"" + seg[0] + "\"。可用:/player /world /mods /registry /recipes /lang";
            }
        } catch (Exception e) {
            return "工具执行出错: " + e;
        }
    }

    private static String[] segments(String[] seg) {
        return seg.length == 0 ? new String[0] : java.util.Arrays.copyOfRange(seg, 1, seg.length);
    }

    // ---------------- 通用输出 ----------------

    /** 树形输出构建器:分支(可继续深入)与叶子。 */
    private static final class Tree {
        final StringBuilder sb = new StringBuilder();
        int branches;

        void title(String t) {
            sb.append(t).append('\n');
        }

        void branch(String name, int count, String note) {
            branches++;
            sb.append("├─ ").append(name).append(count >= 0 ? " (" + count + ")" : "");
            if (note != null && !note.isEmpty()) {
                sb.append("  ").append(note);
            }
            sb.append('\n');
        }

        void leaf(String line) {
            sb.append("· ").append(line).append('\n');
        }

        void note(String n) {
            sb.append(n).append('\n');
        }
    }

    private static String finish(Tree t) {
        return ToolRegistry.trunc(t.sb.toString(), 5000);
    }

    /** 统计分组:token → 数量,按数量降序、同数按名排序。 */
    private static List<Map.Entry<String, Integer>> sortedCounts(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>(m.entrySet());
        out.sort((a, b) -> {
            int c = b.getValue() - a.getValue();
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        return out;
    }

    /** id 路径的分组 token:取第一个 '_' 前的段;无 '_' 归入 "other"。 */
    private static String groupToken(String idPath) {
        int i = idPath.indexOf('_');
        return i > 0 ? idPath.substring(0, i) : "other";
    }

    // ---------------- 根 ----------------

    private static String root() {
        Tree t = new Tree();
        t.title("/ (6 个分支)");
        t.branch("player/", -1, "玩家(位置/生命/背包/状态效果)");
        t.branch("world/", -1, "世界(时间/天气/群系/附近实体)");
        int mods = net.neoforged.fml.ModList.get().size();
        t.branch("mods/", mods, "已加载模组");
        t.branch("registry/", -1, "全局注册表(items/blocks/entities/…)");
        t.branch("recipes/", -1, "配方(按模组分,需单机存档)");
        t.branch("lang/", -1, "语言键(按模组分)");
        return finish(t);
    }

    // ---------------- 全局注册表 ----------------

    private static String registry(String[] seg) {
        if (seg.length == 0) {
            Tree t = new Tree();
            t.title("/registry");
            t.branch("items/", BuiltInRegistries.ITEM.keySet().size(), "物品注册表");
            t.branch("blocks/", BuiltInRegistries.BLOCK.keySet().size(), "方块注册表");
            t.branch("entities/", BuiltInRegistries.ENTITY_TYPE.keySet().size(), "实体类型注册表");
            return finish(t);
        }
        net.minecraft.core.Registry<?> reg;
        switch (seg[0]) {
            case "items" -> reg = BuiltInRegistries.ITEM;
            case "blocks" -> reg = BuiltInRegistries.BLOCK;
            case "entities" -> reg = BuiltInRegistries.ENTITY_TYPE;
            default -> {
                return "未知注册表 /registry/" + seg[0] + "(可用 items / blocks / entities)";
            }
        }
        if (seg.length == 1) {
            Map<String, Integer> ns = new TreeMap<>();
            for (ResourceLocation id : reg.keySet()) {
                ns.merge(id.getNamespace(), 1, Integer::sum);
            }
            Tree t = new Tree();
            t.title("/registry/" + seg[0] + " 共 " + reg.keySet().size() + " 个:");
            for (Map.Entry<String, Integer> e : ns.entrySet()) {
                t.branch(e.getKey() + "/", e.getValue(), null);
            }
            return finish(t);
        }
        String[] rest = java.util.Arrays.copyOfRange(seg, 1, seg.length);
        return idBranch(reg, "/registry/" + seg[0], rest[0].toLowerCase(Locale.ROOT), rest);
    }

    // ---------------- 玩家 ----------------

    private static String player(String[] seg) {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null) {
            return "当前没有玩家。";
        }
        Tree t = new Tree();
        if (seg.length == 0) {
            t.title("/player");
            t.leaf("位置: " + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", p.getX(), p.getY(), p.getZ())
                    + " (" + p.level().dimension().location() + ", 群系 " + biomeName(p) + ")");
            t.leaf("生命: " + (int) p.getHealth() + "/" + (int) p.getMaxHealth()
                    + "  饥饿: " + p.getFoodData().getFoodLevel() + "/20  等级: " + p.experienceLevel);
            var main = p.getMainHandItem();
            t.leaf("主手: " + stackLine(main));
            t.leaf("副手: " + stackLine(p.getOffhandItem()));
            t.branch("inventory/", p.getInventory().items.size(), "背包 36 格");
            t.branch("armor/", 4, "盔甲 4 格");
            t.branch("effects/", p.getActiveEffects().size(), "状态效果");
            return finish(t);
        }
        switch (seg[0]) {
            case "inventory" -> {
                Inventory inv = p.getInventory();
                t.title("/player/inventory (" + inv.items.size() + " 格)");
                int shown = 0;
                for (int i = 0; i < inv.items.size() && shown < MAX_LEAVES; i++) {
                    var s = inv.items.get(i);
                    if (s != null && !s.isEmpty()) {
                        t.leaf("[" + (i < 9 ? "快捷" + i : String.valueOf(i)) + "] " + stackLine(s));
                        shown++;
                    }
                }
                if (shown == 0) {
                    t.note("(背包是空的)");
                }
            }
            case "armor" -> {
                t.title("/player/armor");
                var list = p.getInventory().armor;
                for (int i = 0; i < list.size(); i++) {
                    var s = list.get(i);
                    if (s != null && !s.isEmpty()) {
                        t.leaf("[" + i + "] " + stackLine(s));
                    }
                }
            }
            case "effects" -> {
                t.title("/player/effects");
                for (var e : p.getActiveEffects()) {
                    t.leaf(e.getEffect().value().getDescriptionId() + " " + e.getAmplifier()
                            + " 剩余" + e.getDuration() + "t");
                }
            }
            default -> {
                return "未知子分支 /player/" + seg[0] + "(可用 inventory / armor / effects)";
            }
        }
        return finish(t);
    }

    private static String stackLine(net.minecraft.world.item.ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "(空)";
        }
        String name = s.getHoverName().getString();
        return s.getCount() > 1 ? name + " x" + s.getCount() + " (" + s.getItem() + ")"
                : name + " (" + s.getItem() + ")";
    }

    private static String biomeName(net.minecraft.world.entity.player.Player p) {
        var holder = p.level().getBiome(p.blockPosition());
        return holder.unwrapKey().map(k -> k.location().getPath()).orElse("?");
    }

    // ---------------- 世界 ----------------

    private static String world(String[] seg) {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null) {
            return "当前没有玩家(世界状态不可用)。";
        }
        Tree t = new Tree();
        if (seg.length == 0) {
            var level = p.level();
            t.title("/world");
            t.leaf("时间: " + level.getDayTime() + "t  天气: "
                    + (level.isRaining() ? (level.isThundering() ? "雷雨" : "雨") : "晴"));
            t.leaf("群系: " + biomeName(p));
            Map<String, Integer> types = new HashMap<>();
            var bounds = p.getBoundingBox().inflate(16);
            for (var e : level.getEntities(p, bounds, e -> true)) {
                types.merge(e.getType() + "", 1, Integer::sum);
            }
            t.leaf("周围 16 格实体: " + types.values().stream().mapToInt(Integer::intValue).sum() + " 个");
            for (Map.Entry<String, Integer> en : sortedCounts(types)) {
                if (t.branches >= 10) {
                    break;
                }
                t.leaf("  " + en.getKey() + " x" + en.getValue());
            }
            t.branch("entities/", -1, "附近实体明细(按类型分组)");
            return finish(t);
        }
        if ("entities".equals(seg[0])) {
            t.title("/world/entities (16 格内)");
            Map<String, Integer> types = new HashMap<>();
            Map<String, Float> dist = new HashMap<>();
            for (var e : p.level().getEntities(p, p.getBoundingBox().inflate(16), x -> true)) {
                String k = e.getType() + "";
                types.merge(k, 1, Integer::sum);
                dist.merge(k, e.distanceTo(p), Float::min);
            }
            for (Map.Entry<String, Integer> en : sortedCounts(types)) {
                t.branch(en.getKey() + " x" + en.getValue(), -1,
                        "最近 " + String.format(Locale.ROOT, "%.1f", dist.get(en.getKey())) + " 格");
            }
            return finish(t);
        }
        return "未知子分支 /world/" + seg[0] + "(可用 entities)";
    }

    // ---------------- 模组 ----------------

    private static String mods(String[] seg) {
        if (seg.length == 0) {
            Tree t = new Tree();
            t.title("/mods (" + net.neoforged.fml.ModList.get().size() + " 个模组)");
            for (var info : net.neoforged.fml.ModList.get().getMods()) {
                String id = info.getModId();
                if (id.equals("minecraft") || id.equals("neoforge")) {
                    continue;
                }
                int items = countNs(BuiltInRegistries.ITEM, id);
                t.branch(id + "/", items, String.valueOf(info.getDisplayName()));
            }
            return finish(t);
        }
        String modid = seg[1].toLowerCase(Locale.ROOT);
        if (net.neoforged.fml.ModList.get().getModContainerById(modid) == null) {
            return "没有已加载的模组: " + modid;
        }
        if (seg.length == 2) {
            Tree t = new Tree();
            t.title("/mods/" + modid);
            int items = countNs(BuiltInRegistries.ITEM, modid);
            int blocks = countNs(BuiltInRegistries.BLOCK, modid);
            int entities = countNs(BuiltInRegistries.ENTITY_TYPE, modid);
            if (items > 0) {
                t.branch("items/", items, "物品(按前缀分组可下钻)");
            }
            if (blocks > 0) {
                t.branch("blocks/", blocks, "方块");
            }
            if (entities > 0) {
                t.branch("entities/", entities, "实体");
            }
            t.branch("classes/", classCount(modid), "Java 类(按包下钻)");
            return finish(t);
        }
        switch (seg[2]) {
            case "items" -> {
                return idBranch(BuiltInRegistries.ITEM, "/mods/" + modid + "/items", modid, segments(seg));
            }
            case "blocks" -> {
                return idBranch(BuiltInRegistries.BLOCK, "/mods/" + modid + "/blocks", modid, segments(seg));
            }
            case "entities" -> {
                return idBranch(BuiltInRegistries.ENTITY_TYPE, "/mods/" + modid + "/entities", modid, segments(seg));
            }
            case "classes" -> {
                return classes(modid, segments(seg));
            }
            default -> {
                return "未知子分支 /mods/" + modid + "/" + seg[2] + "(可用 items / blocks / entities / classes)";
            }
        }
    }

    /** 注册表按命名空间计数。 */
    private static <T> int countNs(net.minecraft.core.Registry<T> reg, String ns) {
        int n = 0;
        for (ResourceLocation id : reg.keySet()) {
            if (id.getNamespace().equals(ns)) {
                n++;
            }
        }
        return n;
    }

    /**
     * id 类注册表的分组导航:seg 形如 [modid] 或 [modid, group]。
     * 无 group → 按首段分组;有 group → 列出该组 id(≤60)。
     */
    private static <T> String idBranch(net.minecraft.core.Registry<T> reg, String base,
                                       String modid, String[] seg) {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation id : reg.keySet()) {
            if (id.getNamespace().equals(modid)) {
                ids.add(id.getPath());
            }
        }
        if (ids.isEmpty()) {
            return "注册表 " + base + " 没有内容。";
        }
        Tree t = new Tree();
        if (seg.length == 1) {
            Map<String, Integer> groups = new HashMap<>();
            for (String p : ids) {
                groups.merge(groupToken(p), 1, Integer::sum);
            }
            t.title(base + " 共 " + ids.size() + " 个,按前缀分 " + groups.size() + " 组:");
            int shown = 0;
            for (Map.Entry<String, Integer> en : sortedCounts(groups)) {
                if (shown >= MAX_BRANCHES) {
                    t.note("(其余 " + (groups.size() - shown) + " 组略,精确前缀直接输入 /" + en.getKey() + ")");
                    break;
                }
                t.branch(en.getKey() + "/", en.getValue(), null);
                shown++;
            }
            return finish(t);
        }
        String g = seg[1].toLowerCase(Locale.ROOT);
        List<String> hit = new ArrayList<>();
        for (String p : ids) {
            if (p.startsWith(g + "_") || p.equals(g)) {
                hit.add(modid + ":" + p);
            }
        }
        t.title(base + "/" + g + " (" + hit.size() + " 个)");
        for (String id : hit) {
            if (t.branches >= MAX_LEAVES) {
                t.note("(其余略;可再细分前缀,如 " + g + "_soul/)");
                break;
            }
            t.leaf(id);
        }
        return finish(t);
    }

    // ---------------- 类树 ----------------

    /** 模组全部类全名(jar 条目 .class 剥离)。 */
    private static List<String> classNames(String modid) {
        List<String> out = new ArrayList<>();
        for (String n : ToolRegistry.listModEntries(modid)) {
            if (!n.endsWith(".class") || n.endsWith("module-info.class")) {
                continue;
            }
            out.add(n.substring(0, n.length() - 6).replace('/', '.'));
        }
        return out;
    }

    private static int classCount(String modid) {
        return classNames(modid).size();
    }

    /** 类树:seg = 包段…(点号路径);末段是类名时给成员。 */
    private static String classes(String modid, String[] seg) {
        List<String> all = classNames(modid);
        String prefix = String.join(".", seg);
        if (prefix.isEmpty()) {
            Map<String, Integer> top = new TreeMap<>();
            for (String c : all) {
                int i = c.indexOf('.');
                if (i > 0) {
                    top.merge(c.substring(0, i), 1, Integer::sum);
                }
            }
            Tree t = new Tree();
            t.title("/mods/" + modid + "/classes 共 " + all.size() + " 个类:");
            for (Map.Entry<String, Integer> en : top.entrySet()) {
                t.branch(en.getKey() + "/", en.getValue(), null);
            }
            return finish(t);
        }
        // 先当包看
        Map<String, Integer> sub = new TreeMap<>();
        List<String> direct = new ArrayList<>();
        String dotted = prefix + ".";
        for (String c : all) {
            if (!c.startsWith(dotted)) {
                continue;
            }
            String rest = c.substring(dotted.length());
            int dot = rest.indexOf('.');
            if (dot < 0) {
                direct.add(c);
            } else {
                sub.merge(prefix + "." + rest.substring(0, dot), 1, Integer::sum);
            }
        }
        Tree t = new Tree();
        if (direct.size() == 1 && sub.isEmpty()) {
            return classMembers(direct.get(0));
        }
        t.title("/mods/" + modid + "/classes/" + prefix
                + " (子包 " + sub.size() + ", 直接类 " + direct.size() + ")");
        for (Map.Entry<String, Integer> en : sub.entrySet()) {
            if (t.branches >= MAX_BRANCHES) {
                t.note("(其余子包略)");
                break;
            }
            t.branch(en.getKey().substring(prefix.length() + 1) + "/", en.getValue(), null);
        }
        int shown = 0;
        for (String c : direct) {
            if (shown >= MAX_LEAVES) {
                t.note("(其余 " + (direct.size() - shown) + " 个类略)");
                break;
            }
            t.leaf(c.substring(prefix.length() + 1));
            shown++;
        }
        return finish(t);
    }

    /** 类成员(反射,只读):继承链 + 字段/方法清单。 */
    private static String classMembers(String fqcn) {
        Class<?> c;
        try {
            c = Class.forName(fqcn, false, AgentTool.class.getClassLoader());
        } catch (Throwable e) {
            return "类 " + fqcn + " 无法加载: " + e;
        }
        Tree t = new Tree();
        t.title("/" + fqcn + " (" + (c.isInterface() ? "接口" : c.isEnum() ? "枚举" : "类") + ")");
        if (c.getSuperclass() != null) {
            t.leaf("extends " + c.getSuperclass().getName());
        }
        var ifaces = c.getInterfaces();
        if (ifaces.length > 0) {
            StringBuilder sb = new StringBuilder("implements ");
            for (int i = 0; i < ifaces.length; i++) {
                sb.append(ifaces[i].getSimpleName()).append(i < ifaces.length - 1 ? ", " : "");
            }
            t.leaf(sb.toString());
        }
        var fields = c.getDeclaredFields();
        t.branch("fields", fields.length, "字段");
        int shown = 0;
        for (var f : fields) {
            if (shown >= 30) {
                t.note("(其余 " + (fields.length - shown) + " 个字段略)");
                break;
            }
            t.leaf(java.lang.reflect.Modifier.toString(f.getModifiers() & 0xf)
                    + " " + f.getType().getSimpleName() + " " + f.getName());
            shown++;
        }
        var methods = c.getDeclaredMethods();
        t.branch("methods", methods.length, "方法");
        shown = 0;
        for (var m : methods) {
            if (shown >= MAX_LEAVES) {
                t.note("(其余 " + (methods.length - shown) + " 个方法略)");
                break;
            }
            StringBuilder sb = new StringBuilder(m.getName()).append('(');
            var ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                sb.append(ps[i].getSimpleName()).append(i < ps.length - 1 ? ", " : "");
            }
            t.leaf(sb.append(')').toString());
            shown++;
        }
        return finish(t);
    }

    // ---------------- 配方 ----------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String recipes(String[] seg) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return "配方树只在单机存档下可用。";
        }
        var rm = server.getRecipeManager();
        Map<String, List<String>> byMod = new TreeMap<>();
        for (Object typeObj : (Iterable<?>) BuiltInRegistries.RECIPE_TYPE) {
            try {
                for (Object holder : (List) rm.getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) typeObj)) {
                    ResourceLocation id = ((net.minecraft.world.item.crafting.RecipeHolder<?>) holder).id();
                    byMod.computeIfAbsent(id.getNamespace(), k -> new ArrayList<>()).add(id.getPath());
                }
            } catch (Throwable ignored) {
                // 个别自定义类型不可枚举时跳过
            }
        }
        if (seg.length == 0) {
            Tree t = new Tree();
            t.title("/recipes (" + byMod.size() + " 个模组有配方)");
            for (Map.Entry<String, List<String>> e : byMod.entrySet()) {
                t.branch(e.getKey() + "/", e.getValue().size(), null);
            }
            return finish(t);
        }
        List<String> list = byMod.get(seg[0]);
        if (list == null) {
            return "模组 " + seg[0] + " 没有注册配方。";
        }
        Tree t = new Tree();
        t.title("/recipes/" + seg[0] + " (" + list.size() + " 个)");
        for (String p : list) {
            if (t.branches >= MAX_LEAVES) {
                t.note("(其余略;可查具体物品配方用 find_recipes 工具)");
                break;
            }
            t.leaf(seg[0] + ":" + p);
        }
        return finish(t);
    }

    // ---------------- 语言键 ----------------

    private static String lang(String[] seg) {
        if (seg.length == 0) {
            Tree t = new Tree();
            t.title("/lang (输入模组 id 下钻,如 /lang/goety)");
            for (var info : net.neoforged.fml.ModList.get().getMods()) {
                String id = info.getModId();
                if (id.equals("minecraft") || id.equals("neoforge")) {
                    continue;
                }
                t.branch(id + "/", -1, null);
            }
            return finish(t);
        }
        String modid = seg[0].toLowerCase(Locale.ROOT);
        Map<String, String> kv = new TreeMap<>();
        for (String lc : new String[]{"zh_cn", "en_us"}) {
            try {
                byte[] b = ToolRegistry.readModBytes(modid, "assets/" + modid + "/lang/" + lc + ".json");
                if (b == null) {
                    continue;
                }
                var o = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : o.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        kv.putIfAbsent(e.getKey(), e.getValue().getAsString());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (kv.isEmpty()) {
            return "模组 " + modid + " 没有语言文件。";
        }
        if (seg.length == 1) {
            Map<String, Integer> groups = new TreeMap<>();
            for (String k : kv.keySet()) {
                String[] parts = k.split("\\.");
                groups.merge(parts.length > 1 ? parts[0] + "." + parts[1] : parts[0], 1, Integer::sum);
            }
            Tree t = new Tree();
            t.title("/lang/" + modid + " 共 " + kv.size() + " 个键:");
            for (Map.Entry<String, Integer> e : groups.entrySet()) {
                t.branch(e.getKey() + "/", e.getValue(), null);
            }
            return finish(t);
        }
        String prefix = String.join(".", java.util.Arrays.copyOfRange(seg, 1, seg.length));
        Tree t = new Tree();
        t.title("/lang/" + modid + "/" + prefix + " (前缀匹配)");
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            if (t.branches >= MAX_LEAVES) {
                t.note("(其余略)");
                break;
            }
            t.leaf(e.getKey() + " = " + e.getValue());
        }
        return finish(t);
    }
}
