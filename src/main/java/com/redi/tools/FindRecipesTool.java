package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ClientExec;
import com.redi.agent.ToolRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * find_recipes:JEI(Just Enough Items)式配方查询。
 *
 * <p>借鉴 JEI 的组织方式:每个配方类型一个分类,并标注它的工作站(催化方块)——
 * 玩家一看就知道“去哪儿做”。覆盖全部注册的配方类型(原版 7 类 +
 * 模组自定义类型),有序合成直接输出手机界面可渲染的 [grid] 精确网格
 * (按 width×height 重建 3x3 摆位),烧炼类输出 材料→产物,
 * 模组专属类型(如祭坛仪式)标注所属模组并建议查 read_guidebook。</p>
 */
public final class FindRecipesTool implements AgentTool {

    /** JEI 式分类表:配方类型注册名 → {中文类型名, 工作站方块注册名}。 */
    private static final Map<String, String[]> CATEGORIES = buildCategories();

    private static Map<String, String[]> buildCategories() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("minecraft:crafting", new String[]{"有序/无序合成", "minecraft:crafting_table"});
        m.put("minecraft:smelting", new String[]{"烧炼", "minecraft:furnace"});
        m.put("minecraft:smoking", new String[]{"烟熏", "minecraft:smoker"});
        m.put("minecraft:blasting", new String[]{"高炉熔炼", "minecraft:blast_furnace"});
        m.put("minecraft:campfire_cooking", new String[]{"篝火烹饪", "minecraft:campfire"});
        m.put("minecraft:stonecutting", new String[]{"切石", "minecraft:stonecutter"});
        m.put("minecraft:smithing", new String[]{"锻造", "minecraft:smithing_table"});
        return m;
    }

    @Override
    public String name() {
        return "find_recipes";
    }

    @Override
    public String description() {
        return "查配方(JEI 式,覆盖全部配方类型:合成/烧炼/烟熏/高炉/篝火/切石/锻造/模组自定义)。"
                + "参数 item 填物品注册名;direction 填 produce(默认,产出它的配方)或 use(作为材料的配方)。"
                + "每条配方标注:类型中文名、所需工作站方块、精确材料;有序合成直接给出 [grid] 网格"
                + "(手机可渲染,回答时原样照搬网格,不要自己重新摆放);"
                + "模组专属类型会标注所属模组,其获取步骤应用 read_guidebook 查手册补充。";
    }

    @Override
    public JsonObject schema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description", "物品注册名,如 minecraft:iron_sword 或 iron_sword");
        JsonObject direction = new JsonObject();
        direction.addProperty("type", "string");
        JsonArray dirs = new JsonArray();
        dirs.add("produce");
        dirs.add("use");
        direction.add("enum", dirs);
        direction.addProperty("description", "produce=产出该物品的配方(默认);use=用到该物品作为材料的配方");

        JsonObject props = new JsonObject();
        props.add("item", item);
        props.add("direction", direction);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("item");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String itemArg = ToolRegistry.argStr(args, "item");
        if (itemArg == null || itemArg.isBlank()) return "缺少参数 item。";
        String direction = ToolRegistry.argStr(args, "direction");
        final boolean produce = direction == null || direction.isBlank() || direction.startsWith("p");
        Minecraft mc = Minecraft.getInstance();
        return ClientExec.get(() -> {
            Level lvl = mc.level;
            if (lvl == null) return "客户端未就绪(不在世界里)。";
            try {
                String itemId = normalizeItemId(itemArg);
                if (itemId == null) {
                    return "无法解析物品 id: " + itemArg + "(示例: minecraft:iron_sword 或 iron_sword)";
                }
                List<String> lines = new ArrayList<>();
                int total = 0;
                for (RecipeHolder<? extends Recipe<?>> h : collectAll(lvl)) {
                    Recipe<?> r = h.value();
                    boolean hit;
                    if (produce) {
                        ItemStack result = r.getResultItem(lvl.registryAccess());
                        hit = !result.isEmpty()
                                && BuiltInRegistries.ITEM.getKey(result.getItem()).toString().equals(itemId);
                    } else {
                        hit = usesItem(r, itemId);
                    }
                    if (!hit) continue;
                    total++;
                    if (lines.size() < 20) {
                        lines.add(formatRecipe(lvl, r, h.id().toString()));
                    }
                }
                if (total == 0) {
                    return "没有找到" + (produce ? "产出 " : "使用 ") + itemId
                            + " 的注册表配方。它可能是非工作台获取(战利品/交易/仪式/献祭),"
                            + "用 read_guidebook 查模组手册,或 mod_overview 看模组概览。";
                }
                StringBuilder sb = new StringBuilder((produce ? "产出 " : "使用 ") + itemId
                        + " 的配方(共 " + total + " 条):\n\n");
                for (String l : lines) sb.append(l).append('\n');
                if (total > lines.size()) {
                    sb.append("(其余 ").append(total - lines.size()).append(" 条略)\n");
                }
                sb.append("提示:带 [grid] 的可直接照搬进回答;烧炼类在对应工作站操作;模组类型查 read_guidebook。\n");
                return ToolRegistry.trunc(sb.toString(), 6000);
            } catch (Throwable t) {
                return "读取失败: " + t;
            }
        }, "客户端未就绪(不在世界里)。");
    }

    /** 该配方的任一材料是否包含目标物品。 */
    private static boolean usesItem(Recipe<?> r, String itemId) {
        for (Ingredient ing : safeIngredients(r)) {
            for (ItemStack opt : ing.getItems()) {
                if (BuiltInRegistries.ITEM.getKey(opt.getItem()).toString().equals(itemId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 全配方类型枚举(JEI 同款思路:不止 4 类,包含全部注册的 RecipeType)。须在主线程。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static List<RecipeHolder<? extends Recipe<?>>> collectAll(Level lvl) {
        List<RecipeHolder<? extends Recipe<?>>> out = new ArrayList<>();
        for (Object typeObj : (Iterable) BuiltInRegistries.RECIPE_TYPE) {
            try {
                out.addAll(lvl.getRecipeManager().getAllRecipesFor((RecipeType) typeObj));
            } catch (Throwable ignored) {
                // 个别模组类型枚举失败时跳过
            }
        }
        return out;
    }

    /** 产出该物品的配方 id 列表(inspect_item 复用)。 */
    static List<String> produceIds(Level lvl, String itemId, int limit) {
        List<String> out = new ArrayList<>();
        for (RecipeHolder<? extends Recipe<?>> h : collectAll(lvl)) {
            ItemStack result = h.value().getResultItem(lvl.registryAccess());
            if (!result.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(result.getItem()).toString().equals(itemId)) {
                out.add(h.id().toString());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 格式化

    /** 单条配方(JEI 式):分类中文名 + 工作站 + 材料/网格 + 产物。 */
    private String formatRecipe(Level lvl, Recipe<?> r, String rid) {
        String typeKey = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType()).toString();
        String[] cat = CATEGORIES.get(typeKey);
        StringBuilder b = new StringBuilder();
        if (cat != null) {
            b.append("[").append(cat[0]).append(" · 工作站: ").append(cat[1]).append("] ")
                    .append(rid).append('\n');
        } else {
            String mod = typeKey.contains(":") ? typeKey.split(":")[0] : typeKey;
            b.append("[").append(typeKey).append(" · 模组 ").append(mod)
                    .append(" 专属类型,获取步骤查 read_guidebook] ").append(rid).append('\n');
        }

        // 有序合成 → 精确网格(模型照搬,不再猜摆位)
        if (r instanceof ShapedRecipe shaped) {
            b.append(shapedGrid(shaped));
            appendResultAsGridRow(lvl, b, r);
            return b.toString();
        }
        if (r instanceof ShapelessRecipe) {
            List<String> mats = ingredientLines(r);
            if (!mats.isEmpty()) {
                b.append("[grid]").append('\n');
                b.append(String.join("|", mats)).append('\n');
                appendResultAsGridRow(lvl, b, r);
            }
            return b.toString();
        }
        // 其它类型:材料 + 产物 行式输出
        List<String> mats = ingredientLines(r);
        if (!mats.isEmpty()) {
            b.append("材料: ").append(String.join(" + ", mats)).append('\n');
        }
        appendResult(lvl, b, r);
        return b.toString();
    }

    /** 有序合成 → [grid](按 width×height 精确重建 3x3 摆位,行主序)。 */
    private static String shapedGrid(ShapedRecipe shaped) {
        int w = Math.max(1, Math.min(3, shaped.getWidth()));
        int h = Math.max(1, Math.min(3, shaped.getHeight()));
        NonNullList<Ingredient> ings = shaped.getIngredients(); // 行主序 w*h 个
        StringBuilder g = new StringBuilder("[grid]").append('\n');
        for (int row = 0; row < h; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < w; col++) {
                if (col > 0) line.append('|');
                int idx = row * w + col;
                line.append(idx < ings.size() ? cellOf(ings.get(idx)) : "");
            }
            g.append(line).append('\n');
        }
        return g.toString();
    }

    /** 产物追加为网格 => 行(供 grid 收尾);无产物时只结束网格。 */
    private static void appendResultAsGridRow(Level lvl, StringBuilder b, Recipe<?> r) {
        ItemStack result = r.getResultItem(lvl.registryAccess());
        if (!result.isEmpty()) {
            b.append("=> ").append(BuiltInRegistries.ITEM.getKey(result.getItem()));
            if (result.getCount() > 1) b.append('×').append(result.getCount());
            b.append('\n');
        }
        b.append("[/grid]");
    }

    /** 产物行式追加。 */
    private static void appendResult(Level lvl, StringBuilder b, Recipe<?> r) {
        ItemStack result = r.getResultItem(lvl.registryAccess());
        if (!result.isEmpty()) {
            b.append("产物: ").append(BuiltInRegistries.ITEM.getKey(result.getItem()))
                    .append('×').append(result.getCount()).append('\n');
        }
    }

    /** 材料 → 网格单元(首个选项注册名;多选项标“或”)。 */
    private static String cellOf(Ingredient ing) {
        ItemStack[] opts = ing.getItems();
        if (opts == null || opts.length == 0) return ""; // 空槽
        String first = BuiltInRegistries.ITEM.getKey(opts[0].getItem()).toString();
        return opts.length > 1 ? first + "(或等价)" : first;
    }

    /** 材料清单(网格外类型用):注册名列表。 */
    private static List<String> ingredientLines(Recipe<?> r) {
        List<String> out = new ArrayList<>();
        for (Ingredient ing : safeIngredients(r)) {
            ItemStack[] opts = ing.getItems();
            if (opts == null || opts.length == 0) {
                out.add("任意物品");
                continue;
            }
            String first = BuiltInRegistries.ITEM.getKey(opts[0].getItem()).toString();
            out.add(opts.length > 1 ? first + "(或等价)" : first);
        }
        return out;
    }

    /** 安全取材料(个别模组配方 getIngredients 可能抛异常)。 */
    private static List<Ingredient> safeIngredients(Recipe<?> r) {
        try {
            return r.getIngredients();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 归一化物品参数为注册名;注册表里不存在返回 null。 */
    private static String normalizeItemId(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        ResourceLocation id = ResourceLocation.tryParse(s);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        return BuiltInRegistries.ITEM.getKey(item).equals(id) ? id.toString() : null;
    }
}
