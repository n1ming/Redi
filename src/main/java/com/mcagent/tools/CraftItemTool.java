package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ClientExec;
import com.mcagent.craft.CraftHud;
import com.mcagent.menu.VirtualCraftMenu;
import com.mcagent.net.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * craft_item:自动合成 —— 通过服务端内置的虚拟工作台完成摆料(v2:只摆料,不收取)。
 * 不再要求玩家附近放有真实工作台:工具请求服务端打开 {@link VirtualCraftMenu}
 * (槽位与原版 CraftingMenu 一致:结果 0、网格 1..9、背包 10..45),
 * 按配方逐格放料,【确认结果槽出现产物后即停止】:不点击收取、不 closeContainer ——
 * 服务端是持久容器,关界面不清空;产物由玩家在手机 AI 助手 App 的「⚒ 工作台」页
 * 点击产物槽收取(那次 QUICK_MOVE 上服务端才真实扣料合成并把产物放进背包)。
 * 要合成多个,等玩家收取后再次调用本工具即可。
 * 过程中的网格放料实时显示在手机左侧的虚拟工作台镜像面板(HudOverlay);
 * CraftHud 的摆料/产物快照展示保留(左侧面板被 App 打开标志接管时作为 App 未打开时的兜底)。
 */
public final class CraftItemTool implements AgentTool {

    @Override
    public String name() {
        return "craft_item";
    }

    @Override
    public String description() {
        return "自动合成物品(摆料):参数 item 填要合成的产物注册名(如 minecraft:chest)。"
                + "工具会自动匹配已知配方、检查背包里的材料,把材料摆上服务端内置虚拟工作台:"
                + "不需要放置真实工作台,2x2 与 3x3 配方都支持。结果槽出现产物后工具即停止,"
                + "不会自动收取——产物由玩家在手机 AI 助手 App 的「⚒ 工作台」页点击产物槽收取,"
                + "收取时服务端才真实扣料结算并入包。材料不足、无法打开虚拟工作台或配方未命中时会直接返回原因。"
                + "摆料过程会实时显示在手机左侧的虚拟工作台面板上。"
                + "使用时机:玩家明确要求“帮我合成/做 X 个 Y”时;要合成多个,请提示玩家先收取产物,"
                + "然后再次调用本工具(每次调用摆放一轮材料)。";
    }

    @Override
    public JsonObject schema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description", "要合成的产物注册名,如 minecraft:chest");
        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "已停用(仅为兼容保留):每次调用只摆放一轮材料(结果槽一份产物);"
                + "要合成多个,请让玩家收取产物后再次调用本工具。");

        JsonObject props = new JsonObject();
        props.add("item", item);
        props.add("count", count);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("item");
        schema.add("required", required);
        return schema;
    }

    // ------------------------- 计划数据(主线程解析后带回引擎线程) -------------------------

    /** 一个要摆料的格子:menuSlot=目标菜单槽位;hudIndex=CraftHud 0..8;options=该格可接受的物品。 */
    private record CellPlan(int menuSlot, int hudIndex, List<Item> options, String label) {
    }

    /** 一种材料的总需求:perRound=每轮(每次合成)需要个数。 */
    private record NeedPlan(String label, List<Item> options, int perRound) {
    }

    /** 合成计划(虚拟工作台固定 3x3,不再区分随身/工作台)。 */
    private record CraftPlan(List<CellPlan> cells, ItemStack resultSample, String recipeId,
                             List<NeedPlan> needs, int times) {
    }

    /** 解析结果:plan 与 error 二选一。 */
    private record Resolve(CraftPlan plan, String error) {
    }

    @Override
    public String execute(JsonObject args) {
        String itemArg = args == null ? null : com.mcagent.agent.ToolRegistry.argStr(args, "item");
        if (itemArg == null || itemArg.isBlank()) return "缺少参数 item。";
        // count 参数已停用(v2 只摆一轮料、产物留给玩家在 App 内收取):额外参数直接忽略。

        Minecraft mc = Minecraft.getInstance();
        // 1) 主线程解析配方与材料,产出计划(拿不到就如实报错);times 固定按 1 轮清点材料
        Resolve res = ClientExec.get(() -> resolvePlan(mc, itemArg, 1), new Resolve(null, "主线程调度超时"));
        if (res == null) return "内部错误(计划解析失败)。";
        if (res.error() != null) return res.error();
        CraftPlan plan = res.plan();

        // 2) 组装跨 tick 步骤并在引擎线程驱动(所有容器操作经 ClientExec 在主线程执行)
        //    v2:摆料 + 确认结果槽出现产物即停 —— 不收取、不关界面;
        //    产物由玩家在手机 App 的「⚒ 工作台」页点产物槽收取(那时服务端才真实扣料结算)。
        final Minecraft fmc = mc;
        final AbstractContainerMenu[] menuRef = new AbstractContainerMenu[1];
        final StringBuilder notes = new StringBuilder();
        final ItemStack resultSample = plan.resultSample();
        final Item resultItem = resultSample.getItem();
        List<ContainerAutomation.Step> steps = new ArrayList<>();

        // ---- 第 1 步:请求服务端打开虚拟工作台(C→S) ----
        steps.add(new ContainerAutomation.Step() {
            @Override
            public ContainerAutomation.StepResult run(int attempt) {
                LocalPlayer p = fmc.player;
                if (p == null) return ContainerAutomation.gone();
                if (p.containerMenu instanceof VirtualCraftMenu vm) {
                    menuRef[0] = vm; // 已经开着,直接用
                    return ContainerAutomation.StepResult.ok();
                }
                if (p.containerMenu != p.inventoryMenu) {
                    return ContainerAutomation.StepResult.fail("玩家已打开其它界面,请先关闭再试。");
                }
                ModNetworking.openVirtualCraft(); // 主线程发包;服务端打开虚拟工作台并同步回客户端
                return ContainerAutomation.StepResult.ok();
            }
        });
        // ---- 第 2 步:轮询等待 VirtualCraftMenu 同步到客户端(每 2 tick 一次,30 次上限 ≈ 3 秒) ----
        steps.add(new ContainerAutomation.Step() {
            @Override
            public ContainerAutomation.StepResult run(int attempt) {
                LocalPlayer p = fmc.player;
                if (p == null) return ContainerAutomation.gone();
                if (p.containerMenu instanceof VirtualCraftMenu vm) {
                    menuRef[0] = vm;
                    CraftHud.show("自动合成", new ItemStack[9], null);
                    return ContainerAutomation.StepResult.ok();
                }
                return ContainerAutomation.StepResult.waitMore("等待虚拟工作台打开…");
            }
        });

        // ---- 单轮摆料:补齐每个格子(v2 不做多轮;产物收取后可再次调用) ----
        for (CellPlan cell : plan.cells()) {
            final CellPlan fc = cell;
            steps.add(new ContainerAutomation.Step() {
                @Override
                public ContainerAutomation.StepResult run(int attempt) {
                    LocalPlayer p = fmc.player;
                    if (p == null) return ContainerAutomation.gone();
                    AbstractContainerMenu menu = menuRef[0];
                    if (menuClosed(p, menu)) {
                        return ContainerAutomation.StepResult.fail("虚拟工作台已被关闭或切换(可能被手动关闭)。");
                    }
                    Slot slot = menu.getSlot(fc.menuSlot());
                    ItemStack cur = slot.getItem();
                    if (!cur.isEmpty()) {
                        if (ContainerAutomation.matches(cur, fc.options())) {
                            CraftHud.updateSlot(fc.hudIndex(), cur.copy());
                            return ContainerAutomation.StepResult.ok(); // 格子里还有,直接复用
                        }
                        return ContainerAutomation.StepResult.fail(
                                "合成格被其它物品(" + ContainerAutomation.idOf(cur) + ")占用,请先清空。");
                    }
                    int moved = ContainerAutomation.transfer(fmc, menu, fc.menuSlot(), fc.options(), 1);
                    if (moved <= 0) {
                        return ContainerAutomation.StepResult.fail("放料失败:背包里没有 " + fc.label() + " 了。");
                    }
                    // HUD 更新:展示放入的一个
                    ItemStack show = new ItemStack(fc.options().get(0), 1);
                    CraftHud.updateSlot(fc.hudIndex(), show);
                    return ContainerAutomation.StepResult.ok();
                }
            });
        }
        // ---- 确认结果槽出现产物:出现即停止(不 QUICK_MOVE 收取、不 closeContainer) ----
        steps.add(new ContainerAutomation.Step() {
            @Override
            public ContainerAutomation.StepResult run(int attempt) {
                LocalPlayer p = fmc.player;
                if (p == null) return ContainerAutomation.gone();
                AbstractContainerMenu menu = menuRef[0];
                if (menuClosed(p, menu)) {
                    return ContainerAutomation.StepResult.fail("虚拟工作台已被关闭或切换(可能被手动关闭)。");
                }
                // 结果槽:VirtualCraftMenu 继承 CraftingMenu,RESULT_SLOT=0
                ItemStack out = menu.getSlot(0).getItem();
                if (out.isEmpty()) {
                    if (attempt == 0) {
                        return ContainerAutomation.StepResult.waitMore("等待服务器结算产物…");
                    }
                    return ContainerAutomation.StepResult.fail(
                            "结果槽为空:材料摆放没有命中配方(检查等价材料是否可用)。");
                }
                if (!ContainerAutomation.matches(out, List.of(resultItem))) {
                    notes.append("(注意:实际产出 ").append(ContainerAutomation.idOf(out)).append(")");
                }
                CraftHud.setResult(out.copy()); // 保留 HUD 展示:产物就绪(HudOverlay 左面板接管时为兜底)
                return ContainerAutomation.StepResult.ok();
            }
        });

        ContainerAutomation.Report report = ContainerAutomation.run(steps,
                () -> ClientExec.run(() -> ContainerAutomation.abortCleanup(fmc)));

        // ---- 中文总结:成功路径保持容器打开、产物留在结果槽,由玩家在 App 内收取 ----
        if (report.failed()) {
            return "合成失败:" + report.failReason() + "(界面已关闭,未消耗的材料已退回背包。)";
        }
        StringBuilder sb = new StringBuilder("材料已摆上虚拟工作台,产物已就绪;打开 App 的 ⚒ 工作台页,点产物槽收取。");
        if (!notes.isEmpty()) sb.append(notes);
        sb.append("(配方:").append(plan.recipeId()).append(",收取时服务端才真实扣料结算。)");
        return com.mcagent.agent.ToolRegistry.trunc(sb.toString(), 6000);
    }

    /** 菜单是否已被关闭/切换(手机中途被关、服务端关闭会话等)。 */
    private static boolean menuClosed(LocalPlayer p, AbstractContainerMenu menu) {
        return menu == null || p.containerMenu != menu;
    }

    // ------------------------- 计划解析(须主线程) -------------------------

    private Resolve resolvePlan(Minecraft mc, String itemArg, int times) {
        Level lvl = mc.level;
        LocalPlayer p = mc.player;
        if (lvl == null || p == null || mc.gameMode == null) {
            return new Resolve(null, "客户端未就绪(不在世界里)。");
        }
        Item target = ContainerAutomation.parseItem(itemArg);
        if (target == null) {
            return new Resolve(null, "无法解析物品 id: " + itemArg + "(示例: minecraft:chest 或 chest)");
        }
        String targetId = ContainerAutomation.idOf(target);

        // 在 CRAFTING 配方里找产出该产物的候选(SMITHING/STONECUTTING 按需求忽略;
        // 虚拟工作台固定 3x3,不再区分 2x2/3x3)
        List<RecipeHolder<CraftingRecipe>> cands = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> h : lvl.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe r = h.value();
            ItemStack out = r.getResultItem(lvl.registryAccess());
            if (out.isEmpty() || !ContainerAutomation.matches(out, List.of(target))) continue;
            List<Ingredient> ings = r.getIngredients();
            if (r instanceof ShapedRecipe sr) {
                int w = sr.getWidth(), hh = sr.getHeight(); // ShapedRecipe.getWidth/getHeight(javap 核对)
                if (w < 1 || w > 3 || hh < 1 || hh > 3 || ings.size() != w * hh) continue;
            } else if (r instanceof ShapelessRecipe) {
                if (ings.isEmpty() || ings.size() > 9) continue;
            } else {
                continue; // 其它特殊合成类型不摆格子
            }
            boolean placeable = true;
            for (Ingredient ing : ings) {
                List<Item> opts = ContainerAutomation.optionItems(ing);
                if (!ing.isEmpty() && opts.isEmpty()) {
                    placeable = false; // 非空格却无具体候选(如标签为空),无法确定放什么
                    break;
                }
            }
            if (!placeable) continue;
            cands.add(h);
        }
        if (cands.isEmpty()) {
            return new Resolve(null, "没有找到产出 " + targetId + " 的常规合成配方(锻造/切石不在本工具支持范围)。");
        }
        RecipeHolder<CraftingRecipe> holder = cands.get(0);
        CraftingRecipe recipe = holder.value();

        // 摆放格子(3x3:网格菜单槽 1..9,行优先)
        List<Ingredient> ings = recipe.getIngredients();
        List<CellPlan> cells = new ArrayList<>();
        Map<String, NeedAcc> acc = new LinkedHashMap<>();
        if (recipe instanceof ShapedRecipe sr) {
            int w = sr.getWidth();
            for (int i = 0; i < ings.size(); i++) {
                Ingredient ing = ings.get(i);
                if (ing.isEmpty()) continue; // 空格不摆料
                List<Item> opts = ContainerAutomation.optionItems(ing);
                if (opts.isEmpty()) continue;
                int row = i / w, col = i % w;
                cells.add(cellFor(row, col, opts));
                addNeed(acc, opts);
            }
        } else {
            for (Ingredient ing : ings) { // 无形配方:从左上角按行铺
                List<Item> opts = ContainerAutomation.optionItems(ing);
                if (opts.isEmpty()) continue;
                int k = cells.size();
                cells.add(cellFor(k / 3, k % 3, opts));
                addNeed(acc, opts);
            }
        }
        if (cells.isEmpty()) {
            return new Resolve(null, "配方 " + holder.id() + " 没有可摆放的材料格。");
        }
        List<NeedPlan> needs = new ArrayList<>();
        for (NeedAcc a : acc.values()) {
            needs.add(new NeedPlan(a.label, a.options, a.count));
        }

        // 材料清点:每种材料 需要 = 每轮个数 × 次数
        List<String> shortfalls = new ArrayList<>();
        for (NeedPlan n : needs) {
            int avail = ContainerAutomation.countInInventory(p, n.options());
            int needTotal = n.perRound() * times;
            if (avail < needTotal) {
                shortfalls.add("缺 " + n.label() + "×" + needTotal + ",还差 " + (needTotal - avail) + " 个");
            }
        }
        if (!shortfalls.isEmpty()) {
            return new Resolve(null, "材料不足:" + String.join(";", shortfalls) + "。");
        }

        ItemStack sample = recipe.getResultItem(lvl.registryAccess()).copy();
        return new Resolve(new CraftPlan(cells, sample,
                holder.id().toString(), needs, times), null);
    }

    /** 计算一个格子:虚拟工作台(CraftingMenu 槽位)网格槽 1 + 行*3 + 列;HUD 同为 3x3 行优先。 */
    private static CellPlan cellFor(int row, int col, List<Item> opts) {
        int menuSlot = 1 + row * 3 + col; // 构造器 Slot(craftSlots, j + i*3) → 菜单槽 1+i*3+j
        int hud = row * 3 + col;
        return new CellPlan(menuSlot, hud, opts, ContainerAutomation.labelOf(opts));
    }

    /** 材料需求累加器。 */
    private static final class NeedAcc {
        final String label;
        final List<Item> options;
        int count;

        NeedAcc(String label, List<Item> options) {
            this.label = label;
            this.options = options;
            this.count = 1;
        }
    }

    /** 同一种材料(候选集相同)合并计数。 */
    private static void addNeed(Map<String, NeedAcc> acc, List<Item> opts) {
        StringBuilder key = new StringBuilder();
        for (Item it : opts) key.append(ContainerAutomation.idOf(it)).append('|');
        NeedAcc a = acc.get(key.toString());
        if (a == null) {
            acc.put(key.toString(), new NeedAcc(ContainerAutomation.labelOf(opts), opts));
        } else {
            a.count++;
        }
    }
}
