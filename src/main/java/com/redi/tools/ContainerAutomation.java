package com.redi.tools;

import com.redi.agent.ClientExec;
import com.redi.craft.CraftHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 容器自动化共用助手:craft_item(冶炼走 SmeltClientState 轮询,不经本执行器)共用。
 *
 * <p><b>执行器设计(一句话)</b>:引擎线程驱动一个步骤循环,每个步骤经
 * {@link ClientExec} 投递到客户端主线程执行,步骤之间在引擎线程上
 * sleep 2~3 tick(100~150ms)给服务器留同步时间,并带全局超时与
 * 单步等待重试上限;任何失败/超时都会在主线程回滚(关容器 + 清 CraftHud)。</p>
 *
 * <p>所有容器/世界操作只在主线程做;步骤之间不依赖客户端“手持物品栏”
 * 的实时回包(客户端 predicted 状态可能滞后),转移数量全部按点击规则
 * 算术推算,真实状态在下一步(服务器同步后)再读 {@code menu.getSlot(i).getItem()} 核对,
 * 绝不伪造结果。</p>
 *
 * <p>合成已改为走服务端虚拟工作台(com.redi.menu.VirtualCraftMenu,继承
 * CraftingMenu,槽位一致),原“找真实工作台/熔炉并右键打开”的世界侧逻辑
 * 已随改造删除。保留的槽位依据(javap 核对):
 * <ul>
 *   <li>{@code CraftingMenu}(= VirtualCraftMenu 基类):RESULT_SLOT=0,格子槽 1..9
 *       (构造器 Slot(craftSlots, j + i*3) → 菜单槽 1+i*3+j,行优先),
 *       背包主区 10..36,快捷栏 37..45。</li>
 *   <li>玩家背包来源槽:统一用 {@code Slot.container == player.getInventory()} 判定,
 *       不写死区段,天然兼容原版与改版菜单。</li>
 * </ul></p>
 */
public final class ContainerAutomation {

    /** 一 tick = 50ms。 */
    public static final int TICK_MS = 50;
    /** 整个流程的全局超时(打开虚拟工作台 ~3 秒 + 最多 8 轮“放料/取产物/等结算”,给足余量)。 */
    public static final long TOTAL_TIMEOUT_MS = 20_000;
    /** 单步 WAIT 状态的最大重试次数(30 次 × 2 tick ≈ 3 秒,够服务器开容器)。 */
    public static final int MAX_STEP_ATTEMPTS = 30;

    private ContainerAutomation() {
    }

    // ------------------------- 步骤执行器 -------------------------

    /** 步骤执行结果。 */
    public enum Status { OK, WAIT, FAIL, STOP }

    /**
     * 一步的返回:OK=进入下一步;WAIT=未就绪,稍后重试;FAIL=中止(回滚);
     * STOP=正常提前结束(如材料用完),携带中文说明。
     */
    public record StepResult(Status status, String message) {
        public static StepResult ok() {
            return new StepResult(Status.OK, null);
        }

        public static StepResult waitMore(String why) {
            return new StepResult(Status.WAIT, why);
        }

        public static StepResult fail(String why) {
            return new StepResult(Status.FAIL, why);
        }

        public static StepResult stop(String note) {
            return new StepResult(Status.STOP, note);
        }
    }

    /** 一个自动化步骤:在主线程被调用;attempt 是本步已重试次数。 */
    public interface Step {
        StepResult run(int attempt);

        /** 本步完成后额外等待的 tick 数(默认 2,给服务器同步)。 */
        default int delayTicks() {
            return 2;
        }
    }

    /** 执行报告:completed=是否按计划走完(含 STOP);failReason=失败中文原因;note=STOP 说明。 */
    public record Report(boolean completed, String failReason, String note) {
        public boolean failed() {
            return !completed && failReason != null;
        }
    }

    /**
     * 在引擎线程上驱动步骤循环:每步投递主线程执行,步间 sleep,超时/失败回滚。
     * onAbort 在任何非成功退出时被调用(引擎线程上下文,内部应自行切主线程)。
     */
    public static Report run(List<Step> steps, Runnable onAbort) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null) {
            runAbort(onAbort);
            return new Report(false, "客户端未就绪(不在世界里)。", null);
        }
        long deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS;
        int[] attempts = new int[steps.size()];
        for (int i = 0; i < steps.size(); ) {
            if (System.currentTimeMillis() >= deadline) {
                runAbort(onAbort);
                return new Report(false, "流程超时中止(总时限约 20 秒),容器已关闭、界面已还原。", null);
            }
            Step s = steps.get(i);
            final int attempt = attempts[i]++;
            StepResult r = ClientExec.get(() -> {
                try {
                    return s.run(attempt);
                } catch (Throwable t) {
                    return StepResult.fail("步骤执行异常: " + t);
                }
            }, StepResult.fail("主线程调度超时"));
            if (r == null) r = StepResult.fail("内部错误(空步骤结果)");
            switch (r.status()) {
                case OK -> {
                    i++;
                    sleepTicks(s.delayTicks());
                }
                case WAIT -> {
                    if (attempt + 1 >= MAX_STEP_ATTEMPTS) {
                        runAbort(onAbort);
                        return new Report(false, "等待超时: " + (r.message() == null ? "条件未满足" : r.message()), null);
                    }
                    sleepTicks(2);
                }
                case FAIL -> {
                    runAbort(onAbort);
                    return new Report(false, r.message() == null ? "操作失败" : r.message(), null);
                }
                case STOP -> {
                    return new Report(true, null, r.message());
                }
            }
        }
        return new Report(true, null, null);
    }

    private static void runAbort(Runnable onAbort) {
        if (onAbort != null) {
            try {
                onAbort.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sleepTicks(int ticks) {
        try {
            Thread.sleep((long) TICK_MS * Math.max(1, ticks));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 失败回滚(须主线程):关掉打开的容器并清 CraftHud。 */
    public static void abortCleanup(Minecraft mc) {
        try {
            LocalPlayer p = mc.player;
            if (p != null && p.containerMenu != null && p.containerMenu != p.inventoryMenu) {
                p.closeContainer();
            }
        } catch (Throwable ignored) {
        }
        CraftHud.close();
    }

    // ------------------------- 基础容器操作(全部须主线程) -------------------------

    /**
     * 一次真实容器点击。对应
     * MultiPlayerGameMode.handleInventoryMouseClick(int containerId, int slotId, int mouseButton,
     * ClickType type, Player player)(javap 核对)。
     */
    public static void click(Minecraft mc, AbstractContainerMenu menu, int slot, int mouseButton, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, mouseButton, type, mc.player);
    }

    /** ItemStack 是否命中任一候选物品。 */
    public static boolean matches(ItemStack stack, List<Item> options) {
        if (stack == null || stack.isEmpty() || options == null || options.isEmpty()) return false;
        for (Item it : options) {
            if (stack.is(it)) return true;
        }
        return false;
    }

    /** Ingredient 的具体候选物品列表(getItems() → Item 列表)。 */
    public static List<Item> optionItems(Ingredient ing) {
        List<Item> out = new ArrayList<>();
        if (ing == null) return out;
        for (ItemStack s : ing.getItems()) {
            if (!s.isEmpty() && !out.contains(s.getItem())) out.add(s.getItem());
        }
        return out;
    }

    /** 候选物品的紧凑中文标签:minecraft:planks(或等价物)。 */
    public static String labelOf(List<Item> options) {
        if (options == null || options.isEmpty()) return "(任意)";
        StringBuilder b = new StringBuilder(idOf(options.get(0)));
        if (options.size() > 1) b.append("(或等价物)");
        return b.toString();
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String idOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "(空)" : idOf(stack.getItem());
    }

    /**
     * 把 need 个 options 中任一物品从玩家背包转移进菜单槽 targetSlot,返回实际转移数量。
     * 点击规则(原版 AbstractContainerMenu.clicked,PICKUP):
     * 左键(button=0)空手=拿整组/手持=整组放下合并;右键(button=1)空手=拿一半(向上取整)、
     * 手持=放下 1 个。
     *
     * <p><b>状态一致性</b>:本方法对“步骤开始时客户端可见的背包快照”做算术推算并
     * 一次性发出全部点击——步骤内的连续点击之间服务器不会回包,客户端槽位内容是
     * 滞后的,因此绝不边点边读;真实结果由下一步(2 tick 后)重新读槽核对。</p>
     */
    public static int transfer(Minecraft mc, AbstractContainerMenu menu, int targetSlot, List<Item> options, int need) {
        if (mc.player == null || need <= 0) return 0;
        var inv = mc.player.getInventory();
        Slot dstSlot = menu.getSlot(targetSlot);
        ItemStack dstItem = dstSlot.getItem();
        if (!dstItem.isEmpty() && !matches(dstItem, options)) return 0; // 目标格被其它物品占用
        int maxStack = dstItem.isEmpty() ? dstSlot.getMaxStackSize() : Math.min(dstItem.getMaxStackSize(), dstSlot.getMaxStackSize());
        int room = maxStack - dstItem.getCount();
        if (room <= 0) return 0;

        // 快照玩家背包来源槽(菜单槽号 + 数量算术余量)
        List<Integer> srcSlots = new ArrayList<>();
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s.container != inv) continue;
            ItemStack it = s.getItem();
            if (!it.isEmpty() && matches(it, options)) {
                srcSlots.add(i);
                remaining.add(it.getCount());
            }
        }

        int moved = 0;
        boolean progress = true;
        while (moved < need && room > 0 && progress) {
            progress = false;
            for (int j = 0; j < srcSlots.size() && moved < need && room > 0; j++) {
                int count = remaining.get(j);
                if (count <= 0) continue;
                int src = srcSlots.get(j);
                int want = Math.min(need - moved, room);
                if (count <= want) {
                    // 整组转移:左键拿整组 → 左键整组合并放下
                    click(mc, menu, src, 0, ClickType.PICKUP);
                    click(mc, menu, targetSlot, 0, ClickType.PICKUP);
                    moved += count;
                    room -= count;
                    remaining.set(j, 0);
                    progress = true;
                } else {
                    // 部分转移:右键拿一半(向上取整)→ 右键逐个放 → 左键把剩余放回原格
                    click(mc, menu, src, 1, ClickType.PICKUP);
                    int carried = (count + 1) / 2;
                    int place = Math.min(want, carried);
                    for (int k = 0; k < place; k++) {
                        click(mc, menu, targetSlot, 1, ClickType.PICKUP); // 右键放 1 个
                    }
                    if (carried - place > 0) {
                        click(mc, menu, src, 0, ClickType.PICKUP); // 剩余放回(与原格同类合并,必装得下)
                    }
                    moved += place;
                    room -= place;
                    remaining.set(j, count - place); // 原格余量 = floor(半) + 放回数
                    progress = true;
                }
            }
        }
        return moved;
    }

    // ------------------------- 参数与统计 -------------------------

    /** 解析物品注册名(缺 minecraft: 前缀自动补);注册表里不存在返回 null。 */
    public static Item parseItem(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        ResourceLocation id = ResourceLocation.tryParse(s);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        return BuiltInRegistries.ITEM.getKey(item).equals(id) ? item : null;
    }

    /** 统计玩家背包(主背包 + 副手 + 盔甲)里命中 options 的总数量。须主线程。 */
    public static int countInInventory(LocalPlayer player, List<Item> options) {
        var inv = player.getInventory();
        int total = 0;
        total += countList(inv.items, options);
        total += countList(inv.offhand, options);
        total += countList(inv.armor, options);
        return total;
    }

    private static int countList(List<ItemStack> list, List<Item> options) {
        int total = 0;
        for (ItemStack s : list) {
            if (!s.isEmpty() && matches(s, options)) total += s.getCount();
        }
        return total;
    }

    /** 玩家已离开世界时的标准失败。 */
    public static StepResult gone() {
        return StepResult.fail("已离开世界,流程中止。");
    }

    /** 燃料判定:原版 AbstractFurnaceBlockEntity.isFuel(ItemStack)(javap 核对,public static)。 */
    public static boolean isFuel(ItemStack stack) {
        try {
            return AbstractFurnaceBlockEntity.isFuel(stack);
        } catch (Throwable t) {
            return false;
        }
    }
}
