package com.redi.craft;

import net.minecraft.world.item.ItemStack;

/**
 * 自动合成/冶炼的可视化状态(手机旁边的悬浮网格 HUD)。
 *
 * <p>引擎侧(自动化工具)在客户端主线程上按步骤调用:
 * {@link #show} 开场 → {@link #updateSlot} 逐格放料 → {@link #setResult} 标记产物 →
 * 结束后可调 {@link #close()} 立即隐藏(不调也会在数秒后自动淡出)。
 * 渲染层(HUD 浮层)每帧读取 {@link #isActive()}/{@link #grid()} 等绘制。</p>
 *
 * <p>全部方法线程安全:内部用 volatile 快照交换,渲染线程与引擎线程互不阻塞。
 * 槽位索引 0..8,行优先(0 1 2 / 3 4 5 / 6 7 8);2x2 合成映射到 0/1/3/4。</p>
 */
public final class CraftHud {
    private static final long AUTO_HIDE_MS = 8000;

    public record Snapshot(String label, ItemStack[] grid, ItemStack result, long startedAt, boolean done) {
    }

    private static volatile Snapshot snap = null;

    private CraftHud() {
    }

    /** 开始一次展示。grid 长度可为 0..9(不足的槽按空处理);label 如「自动合成」「自动冶炼」。 */
    public static void show(String label, ItemStack[] grid, ItemStack result) {
        ItemStack[] cells = normalize(grid);
        snap = new Snapshot(label == null ? "自动作业" : label, cells, copy(result), System.currentTimeMillis(), false);
    }

    /** 更新一个槽位的物品(放料动画感)。 */
    public static void updateSlot(int index0to8, ItemStack stack) {
        Snapshot s = snap;
        if (s == null || index0to8 < 0 || index0to8 > 8) return;
        ItemStack[] cells = copyGrid(s.grid());
        cells[index0to8] = copy(stack);
        snap = new Snapshot(s.label(), cells, s.result(), s.startedAt(), s.done());
    }

    /** 设置/更新产物。 */
    public static void setResult(ItemStack stack) {
        Snapshot s = snap;
        if (s == null) return;
        snap = new Snapshot(s.label(), copyGrid(s.grid()), copy(stack), s.startedAt(), true);
    }

    /** 立即隐藏。 */
    public static void close() {
        snap = null;
    }

    /** 渲染层用:当前快照;null 或已超时表示不绘制。 */
    public static Snapshot current() {
        Snapshot s = snap;
        if (s == null) return null;
        if (System.currentTimeMillis() - s.startedAt() > AUTO_HIDE_MS) {
            snap = null;
            return null;
        }
        return s;
    }

    private static ItemStack[] normalize(ItemStack[] grid) {
        ItemStack[] out = new ItemStack[9];
        if (grid != null) {
            for (int i = 0; i < Math.min(9, grid.length); i++) out[i] = copy(grid[i]);
        }
        return out;
    }

    private static ItemStack[] copyGrid(ItemStack[] grid) {
        ItemStack[] out = new ItemStack[9];
        for (int i = 0; i < 9; i++) out[i] = copy(grid != null && i < grid.length ? grid[i] : null);
        return out;
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.copy();
    }
}
