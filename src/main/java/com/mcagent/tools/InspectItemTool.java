package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ClientExec;
import com.mcagent.agent.ToolRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * inspect_item:深入查看一件物品——注册名、名称、堆叠/耐久、
 * 数据组件要点(附魔、食物、工具属性、属性加成、药水等)、悬浮提示、能否合成。
 */
public final class InspectItemTool implements AgentTool {

    @Override
    public String name() {
        return "inspect_item";
    }

    @Override
    public String description() {
        return "查看一件物品的详细信息:注册名、显示名、翻译键、可否堆叠、耐久,遍历数据组件(附魔、食物营养、工具属性、属性加成、药水内容、自定义名/lore、防火等,常见组件给中文释义),悬浮提示文本(附翻译键),以及获得方式与合成(最多 3 条产出配方的材料清单与配方类型)。参数 item 填物品注册名(如 minecraft:iron_sword)或 mainhand / offhand / hotbar:0~8;detail=true 时列出全部未解析组件。";
    }

    @Override
    public JsonObject schema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description", "物品注册名(minecraft:iron_sword)、mainhand、offhand 或 hotbar:0~8");
        JsonObject detail = new JsonObject();
        detail.addProperty("type", "boolean");
        detail.addProperty("description", "可选;true 时额外列出全部组件 id(默认只列可解读的)");

        JsonObject props = new JsonObject();
        props.add("item", item);
        props.add("detail", detail);

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
        String target = ToolRegistry.argStr(args, "item");
        if (target == null || target.isBlank()) return "缺少参数 item。";
        boolean detail = args.has("detail") && args.get("detail").isJsonPrimitive()
                && args.get("detail").getAsBoolean();
        Minecraft mc = Minecraft.getInstance();
        return ClientExec.get(() -> {
            Player p = mc.player;
            if (mc.level == null || p == null) return "客户端未就绪(不在世界里)。";
            try {
                ItemStack stack = resolve(p, target.trim());
                if (stack == null) {
                    return "无法解析物品: " + target + "(可用: 物品注册名 / mainhand / offhand / hotbar:0~8)";
                }
                if (stack.isEmpty()) return "该槽位是空的。";
                return describe(stack, mc.level, detail);
            } catch (Throwable t) {
                return "读取失败: " + t;
            }
        }, "客户端未就绪(不在世界里)。");
    }

    /** 把参数解析成 ItemStack;解析不了返回 null。 */
    private ItemStack resolve(Player p, String target) {
        return switch (target) {
            case "mainhand" -> p.getMainHandItem();
            case "offhand" -> p.getOffhandItem();
            default -> {
                if (target.startsWith("hotbar:")) {
                    try {
                        int i = Integer.parseInt(target.substring(7).trim());
                        yield (i >= 0 && i <= 8) ? p.getInventory().getItem(i) : null;
                    } catch (NumberFormatException e) {
                        yield null;
                    }
                }
                String s = target.contains(":") ? target : "minecraft:" + target;
                ResourceLocation id = ResourceLocation.tryParse(s);
                if (id == null) yield null;
                // 注册表 get 查不到时返回默认值(AIR),用 key 反查判断是否真的存在
                Item item = BuiltInRegistries.ITEM.get(id);
                yield BuiltInRegistries.ITEM.getKey(item).equals(id) ? new ItemStack(item) : null;
            }
        };
    }

    /** 组织物品描述文本。 */
    private String describe(ItemStack stack, Level lvl, boolean detail) {
        StringBuilder sb = new StringBuilder();
        Item item = stack.getItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        sb.append("物品: ").append(id).append('\n');
        sb.append("名称: ").append(stack.getHoverName().getString()).append('\n');
        sb.append("翻译键: ").append(item.getDescriptionId()).append('\n');
        sb.append("堆叠: ").append(stack.isStackable() ? "可堆叠,最多 " + stack.getMaxStackSize() : "不可堆叠")
                .append(";耐久: ").append(stack.isDamageableItem() ? stack.getMaxDamage() : "无").append('\n');
        Integer dmg = stack.get(DataComponents.DAMAGE);
        if (dmg != null && dmg != 0) sb.append("已损耗耐久: ").append(dmg).append('\n');

        // 遍历物品身上实际存在的组件,常见组件给中文释义
        sb.append("组件:\n");
        for (TypedDataComponent<?> tdc : stack.getComponents()) {
            DataComponentType<?> type = tdc.type();
            String tid = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString();
            String note = componentNote(stack, tid);
            if (note != null) sb.append("- ").append(note).append('\n');
            else sb.append("- ").append(tid).append(detail ? "(未解析)" : "").append('\n');
        }

        // 悬浮提示(附上 lang key,便于后续 read_lang / 翻译类任务)
        try {
            List<Component> tip = new ArrayList<>();
            stack.getItem().appendHoverText(stack, Item.TooltipContext.of(lvl), tip, TooltipFlag.Default.NORMAL);
            if (!tip.isEmpty()) {
                sb.append("悬浮提示:\n");
                int n = 0;
                for (Component line : tip) {
                    if (n++ >= 20) {
                        sb.append("…(其余略)\n");
                        break;
                    }
                    sb.append("- ").append(line.getString());
                    if (line.getContents() instanceof TranslatableContents tc) {
                        sb.append(" [").append(tc.getKey()).append(']');
                    }
                    sb.append('\n');
                }
            }
        } catch (Throwable ignored) {
            // 个别模组物品的 tooltip 可能抛异常,不影响其余输出
        }

        // 获得方式与合成:最多 3 条产出配方的材料清单+配方类型(本工具内遍历四类配方)
        try {
            sb.append("获得方式与合成:\n");
            List<String> recipes = produceSummaries(lvl, id.toString(), 3);
            if (recipes.isEmpty()) {
                sb.append("- 未找到产出它的配方(可能靠探索、交易或其他方式获得)\n");
            } else {
                for (String line : recipes) sb.append(line).append('\n');
            }
            sb.append("(配方网格见图示)\n");
        } catch (Throwable ignored) {
        }
        return ToolRegistry.trunc(sb.toString(), 6000);
    }

    /**
     * 产出目标物品的配方简表(材料清单+配方类型;最多 limit 条)。须在主线程调用。
     * 每行:- 类型 | 材料: 注册名×数量, ... | 可随身 2x2 合成 / 需 3x3(工作台)
     * (FindRecipesTool 只提供配方 id 列表,故在本工具内实现简版材料汇总。)
     */
    private static List<String> produceSummaries(Level lvl, String itemId, int limit) {
        List<String> out = new ArrayList<>();
        List<RecipeHolder<? extends Recipe<?>>> all = new ArrayList<>();
        all.addAll(lvl.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING));
        all.addAll(lvl.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING));
        all.addAll(lvl.getRecipeManager().getAllRecipesFor(RecipeType.SMITHING));
        all.addAll(lvl.getRecipeManager().getAllRecipesFor(RecipeType.STONECUTTING));
        for (RecipeHolder<? extends Recipe<?>> h : all) {
            Recipe<?> r = h.value();
            ItemStack result = r.getResultItem(lvl.registryAccess());
            if (result == null || result.isEmpty()
                    || !BuiltInRegistries.ITEM.getKey(result.getItem()).toString().equals(itemId)) {
                continue;
            }
            // 材料清单:注册名×数量(多选一记“或等价物”,空材料表记“任意物品”)
            LinkedHashMap<String, Integer> mats = new LinkedHashMap<>();
            for (Ingredient ing : r.getIngredients()) {
                ItemStack[] opts = ing.getItems();
                if (opts == null || opts.length == 0) {
                    mats.merge("任意物品", 1, Integer::sum);
                    continue;
                }
                String name = BuiltInRegistries.ITEM.getKey(opts[0].getItem()).toString();
                if (opts.length > 1) name += "(或等价物)";
                mats.merge(name, 1, Integer::sum);
            }
            String type = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType()).getPath();
            StringBuilder line = new StringBuilder("- ").append(type).append(" | 材料: ");
            if (mats.isEmpty()) {
                line.append("(无)");
            } else {
                boolean first = true;
                for (Map.Entry<String, Integer> e : mats.entrySet()) {
                    if (!first) line.append(", ");
                    line.append(e.getKey()).append('×').append(e.getValue());
                    first = false;
                }
            }
            // 2x2 / 3x3 判断:合成类配方且材料格数≤4 即可随身 2x2 完成
            if (r.getType() == RecipeType.CRAFTING) {
                line.append(r.getIngredients().size() <= 4 ? " | 可随身 2x2 合成" : " | 需 3x3(工作台)");
            }
            out.add(line.toString());
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** 按组件注册名给出“组件 id(中文释义): 要点”;不认识的组件返回 null。 */
    private String componentNote(ItemStack stack, String tid) {
        switch (tid) {
            case "minecraft:custom_name": {
                Component c = stack.get(DataComponents.CUSTOM_NAME);
                return "custom_name(自定义名): " + c.getString();
            }
            case "minecraft:item_name": {
                Component c = stack.get(DataComponents.ITEM_NAME);
                return "item_name(物品名): " + c.getString();
            }
            case "minecraft:lore": {
                ItemLore lore = stack.get(DataComponents.LORE);
                StringBuilder b = new StringBuilder("lore(描述行):");
                for (Component line : lore.lines()) b.append("\n- ").append(line.getString());
                return b.toString();
            }
            case "minecraft:max_stack_size":
                return "max_stack_size(最大堆叠): " + stack.get(DataComponents.MAX_STACK_SIZE);
            case "minecraft:max_damage":
                return "max_damage(最大耐久): " + stack.get(DataComponents.MAX_DAMAGE);
            case "minecraft:damage":
                return "damage(已损耗耐久): " + stack.get(DataComponents.DAMAGE);
            case "minecraft:unbreakable":
                return "unbreakable(不可破坏)";
            case "minecraft:fire_resistant":
                return "fire_resistant(防火:岩浆与火不会烧毁)";
            case "minecraft:enchantment_glint_override": {
                Boolean g = stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
                return "enchantment_glint_override(附魔光泽): " + g;
            }
            case "minecraft:rarity": {
                Rarity r = stack.get(DataComponents.RARITY);
                return "rarity(稀有度): " + (r == null ? "?" : r.name());
            }
            case "minecraft:repair_cost":
                return "repair_cost(铁砧修复费用): " + stack.get(DataComponents.REPAIR_COST);
            case "minecraft:food": {
                FoodProperties f = stack.get(DataComponents.FOOD);
                if (f == null) return "food(食物)";
                return "food(食物): 恢复饥饿 " + f.nutrition() + ",饱和度系数 " + f.saturation();
            }
            case "minecraft:tool": {
                Tool tool = stack.get(DataComponents.TOOL);
                if (tool == null) return "tool(工具)";
                return "tool(工具): 默认挖掘速度 " + tool.defaultMiningSpeed()
                        + ",规则 " + tool.rules().size() + " 条,每块损耗 " + tool.damagePerBlock();
            }
            case "minecraft:attribute_modifiers": {
                ItemAttributeModifiers am = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
                if (am == null) return "attribute_modifiers(属性加成)";
                StringBuilder b = new StringBuilder("attribute_modifiers(属性加成):");
                for (ItemAttributeModifiers.Entry e : am.modifiers()) {
                    String attr = e.attribute().unwrapKey().map(k -> k.location().toString()).orElse("?");
                    b.append("\n- ").append(attr).append(' ').append(e.modifier().amount())
                            .append('(').append(e.modifier().operation())
                            .append(",槽位 ").append(e.slot()).append(')');
                }
                return b.toString();
            }
            case "minecraft:enchantments":
                return enchantments(stack.get(DataComponents.ENCHANTMENTS), "enchantments(已附魔)");
            case "minecraft:stored_enchantments":
                return enchantments(stack.get(DataComponents.STORED_ENCHANTMENTS), "stored_enchantments(附魔书存储)");
            case "minecraft:potion_contents": {
                PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
                if (pc == null) return "potion_contents(药水)";
                StringBuilder b = new StringBuilder("potion_contents(药水): ");
                b.append(pc.potion().flatMap(Holder::unwrapKey)
                        .map(k -> k.location().toString()).orElse("普通水瓶"));
                for (MobEffectInstance eff : pc.customEffects()) {
                    b.append("\n- ").append(eff.getEffect().unwrapKey()
                                    .map(k -> k.location().toString()).orElse("?"))
                            .append(" 等级").append(eff.getAmplifier() + 1)
                            .append(" 持续").append(eff.getDuration() / 20).append("秒");
                }
                return b.toString();
            }
            case "minecraft:custom_data": {
                CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
                if (cd == null) return "custom_data(自定义数据)";
                return "custom_data(自定义数据,键: " + String.join(", ", cd.copyTag().getAllKeys()) + ")";
            }
            default:
                return null;
        }
    }

    /** 把附魔表格式化成多行文本。 */
    private String enchantments(ItemEnchantments en, String label) {
        if (en == null) return label;
        StringBuilder b = new StringBuilder(label + ":");
        int n = 0;
        for (Map.Entry<Holder<Enchantment>, Integer> e : en.entrySet()) {
            String name = e.getKey().unwrapKey().map(k -> k.location().toString()).orElse("?");
            b.append("\n- ").append(name).append(" 等级 ").append(e.getValue());
            n++;
        }
        return n == 0 ? label : b.toString();
    }
}
