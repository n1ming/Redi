package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ClientExec;
import com.redi.agent.ToolRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * find_recipes:在配方管理器里查某件物品的配方——
 * direction=produce 查“哪些配方产出它”,direction=use 查“它被用在哪些配方里”。
 * 覆盖合成/熔炼/锻造/切石四类常用配方。
 */
public final class FindRecipesTool implements AgentTool {

    @Override
    public String name() {
        return "find_recipes";
    }

    @Override
    public String description() {
        return "查配方。参数 item 填物品注册名(如 minecraft:iron_sword,可省略 minecraft: 前缀);direction 填 produce(默认,查产出它的配方)或 use(查用到它的配方)。输出配方类型、id、材料表(注册名×数量)与产物,最多 20 条。";
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
                for (RecipeHolder<? extends Recipe<?>> h : collect(lvl)) {
                    Recipe<?> r = h.value();
                    boolean hit;
                    if (produce) {
                        ItemStack result = r.getResultItem(lvl.registryAccess());
                        hit = !result.isEmpty()
                                && BuiltInRegistries.ITEM.getKey(result.getItem()).toString().equals(itemId);
                    } else {
                        hit = false;
                        for (Ingredient ing : r.getIngredients()) {
                            for (ItemStack opt : ing.getItems()) {
                                if (BuiltInRegistries.ITEM.getKey(opt.getItem()).toString().equals(itemId)) {
                                    hit = true;
                                    break;
                                }
                            }
                            if (hit) break;
                        }
                    }
                    if (!hit) continue;
                    lines.add(formatRecipe(lvl, r, h.id().toString()));
                    if (lines.size() >= 20) break;
                }
                if (lines.isEmpty()) {
                    return "没有找到" + (produce ? "产出 " : "使用 ") + itemId
                            + " 的配方(已查合成/熔炼/锻造/切石四类)。";
                }
                StringBuilder sb = new StringBuilder((produce ? "产出 " : "使用 ") + itemId + " 的配方:\n");
                for (String l : lines) sb.append(l).append('\n');
                if (lines.size() >= 20) sb.append("(最多显示 20 条,可能已截断)\n");
                return ToolRegistry.trunc(sb.toString(), 6000);
            } catch (Throwable t) {
                return "读取失败: " + t;
            }
        }, "客户端未就绪(不在世界里)。");
    }

    /** 收集四类常用配方的全部条目(须在主线程调用)。 */
    static List<RecipeHolder<? extends Recipe<?>>> collect(Level lvl) {
        List<RecipeHolder<? extends Recipe<?>>> out = new ArrayList<>();
        out.addAll(lvl.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING));
        out.addAll(lvl.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING));
        out.addAll(lvl.getRecipeManager().getAllRecipesFor(RecipeType.SMITHING));
        out.addAll(lvl.getRecipeManager().getAllRecipesFor(RecipeType.STONECUTTING));
        return out;
    }

    /** 返回产出目标物品的配方 id(须在主线程调用;供 inspect_item 复用)。 */
    static List<String> produceIds(Level lvl, String itemId, int limit) {
        List<String> out = new ArrayList<>();
        for (RecipeHolder<? extends Recipe<?>> h : collect(lvl)) {
            ItemStack result = h.value().getResultItem(lvl.registryAccess());
            if (!result.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(result.getItem()).toString().equals(itemId)) {
                out.add(h.id().toString());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /** 单条配方格式化:- [类型] id | 材料: a×2 + b×1 | 产物: c×1 */
    private String formatRecipe(Level lvl, Recipe<?> r, String rid) {
        StringBuilder b = new StringBuilder("- [")
                .append(BuiltInRegistries.RECIPE_TYPE.getKey(r.getType()).getPath())
                .append("] ").append(rid);
        LinkedHashMap<String, Integer> mats = new LinkedHashMap<>();
        for (Ingredient ing : r.getIngredients()) {
            ItemStack[] opts = ing.getItems();
            if (opts == null || opts.length == 0) {
                mats.merge("任意物品", 1, Integer::sum); // 空材料表 = 任意物品
                continue;
            }
            String first = BuiltInRegistries.ITEM.getKey(opts[0].getItem()).toString();
            if (opts.length > 1) first += "(或等价物)";
            mats.merge(first, 1, Integer::sum);
        }
        if (!mats.isEmpty()) {
            b.append(" | 材料: ");
            boolean firstMat = true;
            for (Map.Entry<String, Integer> e : mats.entrySet()) {
                if (!firstMat) b.append(" + ");
                b.append(e.getKey()).append('×').append(e.getValue());
                firstMat = false;
            }
        }
        ItemStack result = r.getResultItem(lvl.registryAccess());
        if (!result.isEmpty()) {
            b.append(" | 产物: ").append(BuiltInRegistries.ITEM.getKey(result.getItem()))
                    .append('×').append(result.getCount());
        }
        return b.toString();
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
