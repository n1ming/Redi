package com.redi.phone;

import com.redi.menu.VirtualCraftMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * 原版容器 GUI 风格的共用绘制/命中/点击助手(工作台面板与熔炉面板共用)。
 * 不加载任何贴图,全部用 GuiGraphics.fill / renderOutline 像素填充复刻原版观感。
 *
 * <p>配色(规格指定 + 原版 widgets.png 近似实测):</p>
 * <ul>
 *   <li>面板底 0xFFC6C6C6(规格指定)、外描边 0xFF3F3F3F(原版容器底板深色边缘);</li>
 *   <li>槽位 18x18 外框:上/左 1px 0xFF373737、下/右 1px 0xFFFFFFFF、槽内 16x16 0xFF8B8B8B(规格指定);</li>
 *   <li>悬停高亮 = 原版槽位悬停白罩 0x80FFFFFF;</li>
 *   <li>箭头静态灰 0xFF545454;熔炉进度段覆盖白色(原版语义:进度从左往右填白);
 *       火焰橙 0xFFE2761F + 黄芯 0xFFFFD875。</li>
 * </ul>
 *
 * <p><b>玩家背包区几何(两面板同布局)</b>:主区三行 (8,84) 起、快捷栏一行 (8,142),
 * x 步距 18。槽位号与 CraftingMenu 构造器字面值一一对应(javap -p 核对,
 * {@link VirtualCraftMenu} extends CraftingMenu 原样继承):
 * 槽 10..36 = {@code new Slot(inv, j+i*9+9, 8+j*18, 84+i*18)},
 * 槽 37..45 = {@code new Slot(inv, j, 8+j*18, 142)}。</p>
 */
final class VanillaPanels {

    // ---- 配色 ----
    static final int BG = 0xFFC6C6C6;
    static final int PANEL_EDGE = 0xFF3F3F3F;
    static final int SLOT_DARK = 0xFF373737;
    static final int SLOT_LIGHT = 0xFFFFFFFF;
    static final int SLOT_INNER = 0xFF8B8B8B;
    static final int HOVER = 0x80FFFFFF;
    static final int ARROW_GRAY = 0xFF545454;
    static final int ARROW_FILL = 0xFFFFFFFF;
    static final int FLAME_OFF = 0xFF545454;
    static final int FLAME_ORANGE = 0xFFE2761F;
    static final int FLAME_CORE = 0xFFFFD875;

    // ---- 背包区几何(16x16 内容区坐标)----
    static final int INV_X = 8;
    static final int INV_Y = 84;
    static final int HOTBAR_Y = 142;
    static final int CELL = 16;
    static final int STEP = 18;

    private VanillaPanels() {
    }

    // ---------------------------------------------------------------- 底板/槽位/物品

    /** 原版容器底板:纯色底 + 1px 深描边。 */
    static void drawPanelBackground(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BG);
        g.renderOutline(x, y, w, h, PANEL_EDGE);
    }

    /**
     * 原版槽位(x,y 为 16x16 内容区左上,外框共 18x18):整块白底 → 上/左 17x17 深灰
     * → 槽内 16x16 灰,得到"上/左暗、下/右亮"的原版内凹框;hovered 时槽内叠原版白罩。
     */
    static void drawSlot(GuiGraphics g, int x, int y, boolean hovered) {
        g.fill(x - 1, y - 1, x + 17, y + 17, SLOT_LIGHT);
        g.fill(x - 1, y - 1, x + 16, y + 16, SLOT_DARK);
        g.fill(x, y, x + 16, y + 16, SLOT_INNER);
        if (hovered) {
            g.fill(x, y, x + 16, y + 16, HOVER);
        }
    }

    /** 物品 + 原版角标(数量/耐久);空堆不画。javap:renderItem(ItemStack,int,int) 与
     *  renderItemDecorations(Font,ItemStack,int,int) 均 public。 */
    static void drawItem(GuiGraphics g, Font font, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        g.renderItem(stack, x, y);
        g.renderItemDecorations(font, stack, x, y);
    }

    // ---------------------------------------------------------------- 图标(纯填充)

    /**
     * 工作台合成箭头(24x16,静态灰):轴(前 16 列,中线 ±2)+ 三角头(后 8 列展开到半高 7,
     * 顶满后收成钝边,近似原版箭头形状)。规格:静态灰即可,无进度填充。
     */
    static void drawCraftArrow(GuiGraphics g, int x, int y) {
        int cy = y + 8;
        g.fill(x, cy - 2, x + 16, cy + 2, ARROW_GRAY);
        for (int i = 0; i < 8; i++) {
            int half = Math.min(2 + i, 7);
            g.fill(x + 15 + i, cy - half, x + 16 + i, cy + half, ARROW_GRAY);
        }
    }

    /**
     * 熔炉进度箭头(28x16,列剖面绘制):灰底;进度比例部分从左往右覆盖白色(原版语义)。
     * 剖面:前 20 列为 4px 高轴(半高 2);后 8 列三角头,半高 2→7(顶满后保持,钝边)。
     */
    static void drawFurnaceArrow(GuiGraphics g, int x, int y, float progress) {
        int w = 28;
        int cy = y + 8;
        float frac = Math.max(0.0f, Math.min(1.0f, progress));
        int fillCols = Math.round(w * frac);
        for (int dx = 0; dx < w; dx++) {
            int half = dx < w - 8 ? 2 : Math.min(2 + (dx - (w - 8)), 7);
            g.fill(x + dx, cy - half, x + dx + 1, cy + half, dx < fillCols ? ARROW_FILL : ARROW_GRAY);
        }
    }

    /**
     * 熔炉火焰(14x14,左上 x,y):lit = 橙色火苗 + 顶端随时间跳动的黄芯;灭 = 深灰剪影。
     * 服务端 SmeltView 快照没有"燃料剩余燃烧进度"字段,故不做部分高度填充(不伪造状态)。
     */
    static void drawFlame(GuiGraphics g, int x, int y, boolean lit, long nowMs) {
        for (int rr = 0; rr < 14; rr++) {
            flameRow(g, x, y, rr, lit ? FLAME_ORANGE : FLAME_OFF);
        }
        if (lit) {
            int core = 7 + (int) ((nowMs / 180) % 3); // 黄芯高度 7..9 随时间小幅跳动
            for (int rr = 0; rr < core; rr++) {
                flameRow(g, x, y, rr, FLAME_CORE);
            }
        }
    }

    /** 火焰单行:rr = 自底向上 0..13,半宽 6→1 收尖,水平居中于 x+7。 */
    private static void flameRow(GuiGraphics g, int x, int y, int rr, int color) {
        int half = switch (rr) {
            case 0, 1, 2 -> 6;
            case 3, 4, 5 -> 5;
            case 6, 7 -> 4;
            case 8, 9 -> 3;
            case 10, 11 -> 2;
            default -> 1;
        };
        g.fill(x + 7 - half, y + 13 - rr, x + 7 + half + 1, y + 14 - rr, color);
    }

    // ---------------------------------------------------------------- 背包区命中/绘制

    /** 16x16 槽内容区命中(局部坐标)。 */
    static boolean hitCell(double lx, double ly, int cx, int cy) {
        return lx >= cx && lx < cx + CELL && ly >= cy && ly < cy + CELL;
    }

    /**
     * 面板局部坐标 → 玩家背包菜单槽位号(10..45);不在任何【已启用】的背包槽上返回 -1。
     * invRows/hotbar 分别控制主区三行与快捷栏是否参与命中(空间不足被省略的区域不命中,
     * 与绘制保持同一开关,画不出的格子不响应点击)。
     */
    static int playerSlotAt(double lx, double ly, boolean invRows, boolean hotbar) {
        if (invRows) {
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) {
                    if (hitCell(lx, ly, INV_X + c * STEP, INV_Y + r * STEP)) {
                        return 10 + r * 9 + c;
                    }
                }
            }
        }
        if (hotbar) {
            for (int c = 0; c < 9; c++) {
                if (hitCell(lx, ly, INV_X + c * STEP, HOTBAR_Y)) {
                    return 37 + c;
                }
            }
        }
        return -1;
    }

    /**
     * 画玩家背包区(面板局部坐标):主区三行 (8,84) + 快捷栏 (8,142),槽内容从 menu 实读
     * (menu == null 时只画空槽)。invRows/hotbar 控制是否绘制(空间不足时上层按层省略)。
     */
    static void drawPlayerInventory(GuiGraphics g, Font font, VirtualCraftMenu menu, int x, int y,
            boolean invRows, boolean hotbar, int hoverSlot) {
        if (!invRows && !hotbar) {
            return;
        }
        for (int slot = 10; slot <= 45; slot++) {
            boolean isHotbar = slot >= 37;
            if (isHotbar ? !hotbar : !invRows) {
                continue;
            }
            int cx = isHotbar ? INV_X + (slot - 37) * STEP : INV_X + (slot - 10) % 9 * STEP;
            int cy = isHotbar ? HOTBAR_Y : INV_Y + (slot - 10) / 9 * STEP;
            drawSlot(g, x + cx, y + cy, slot == hoverSlot);
            if (menu != null) {
                drawItem(g, font, menu.getSlot(slot).getItem(), x + cx, y + cy);
            }
        }
    }

    // ---------------------------------------------------------------- 菜单点击

    /**
     * 经 gameMode 对 {@link VirtualCraftMenu} 发真实槽位点击(客户端不预测,服务端结算后
     * 经同步包回读)。按钮语义与原版 AbstractContainerScreen.mouseClicked 一致:
     * 左键 = PICKUP + 0(拿/放/合并/交换)、右键 = PICKUP + 1(拿一半/放一个)、
     * Shift+左/右 = QUICK_MOVE + 0/1(快速移动,产物格即"连点合成收包")。
     * javap 核对:Screen.hasShiftDown() 为 public static;
     * MultiPlayerGameMode.handleInventoryMouseClick(int,int,int,ClickType,Player) 为 public。
     *
     * @return 是否发出了点击(button 非 0/1 或 gameMode 缺失时为 false)
     */
    static boolean clickMenuSlot(VirtualCraftMenu menu, int slot, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null || (button != 0 && button != 1)) {
            return false;
        }
        ClickType type = Screen.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP;
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc.player);
        return true;
    }

    /**
     * 面板外空白点击 + 手持堆非空:把手持物放回背包(需求的简化语义;原版同位置是丢到地上)。
     *
     * <p><b>为什么不能直接 {@code placeItemBackInInventory + setCarried(EMPTY)}</b>:
     * 那是纯客户端改动 —— 服务端 AbstractContainerMenu.carried 仍非空,下一次
     * ServerPlayer.tick → broadcastChanges 发现 carried 与 remoteCarried 一致而不重发,
     * 但任何一次真实点击都会让服务端按"手持非空"结算(与客户端预期错位),且
     * VirtualMenuSynchronizer.sendCarriedChange(槽 -1 包)随时可能把服务端手持堆推回来,
     * 造成"背包一份 + 光标一份"的鬼影。故这里发真实 PICKUP 点击,由服务端结算,零不同步。</p>
     *
     * <p>规则:背包主区+快捷栏(槽 10..45)找第一个【空槽,或同物品且未满堆】的槽发
     * PICKUP button0(服务端 doClick 语义:空槽 = 放入,同物品 = 并入,余量留在手持);
     * 背包全满时退回原版"面板外"语义 -999 PICKUP(整堆丢在玩家脚下 ——
     * AbstractContainerMenu.doClick 字节码核对:sipush -999 分支 + ClickAction.PRIMARY →
     * {@code player.drop(getCarried(), false)} 后 setCarried(EMPTY))。</p>
     *
     * @return true = 已消费该点击(手持堆非空);false = 空手,点击不消费
     */
    static boolean putBackCarried(int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }
        if (!(mc.player.containerMenu instanceof VirtualCraftMenu menu)) {
            return false;
        }
        ItemStack carried = menu.getCarried(); // javap: AbstractContainerMenu.getCarried() public
        if (carried.isEmpty()) {
            return false;
        }
        for (int slot = 10; slot <= 45; slot++) {
            ItemStack in = menu.getSlot(slot).getItem();
            boolean mergeable = !in.isEmpty()
                    && ItemStack.isSameItemSameComponents(in, carried) // javap: public static
                    && in.getCount() < in.getMaxStackSize();
            if (in.isEmpty() || mergeable) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, mc.player);
                return true;
            }
        }
        mc.gameMode.handleInventoryMouseClick(menu.containerId, -999, button == 1 ? 1 : 0, ClickType.PICKUP, mc.player);
        return true;
    }
}
