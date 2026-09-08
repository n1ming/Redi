package com.redi.phone;

import com.redi.menu.VirtualCraftMenu;
import com.redi.net.ModNetworking;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

/**
 * 工作台面板 —— 原版 CraftingScreen(176x166)的纯填充复刻:3x3 合成网格 + 灰色合成箭头 +
 * 产物格 + 玩家背包三行 + 快捷栏。全部 46 个槽位可放可取(完整 PICKUP/QUICK_MOVE 交互,
 * 服务端结算),玩家能自己把背包里的材料摆上九宫格、点产物拿取 —— 不是只有 AI 能用。
 * 无任何提示文字。
 *
 * <p><b>槽位号 ↔ 面板坐标对照</b>(逐条核对 CraftingMenu 构造器的 addSlot 字面值;
 * {@link VirtualCraftMenu} extends CraftingMenu 原样继承):</p>
 * <pre>
 *   槽 0      = ResultSlot(..., 0, 124, 35)               → 产物格 (124,35)
 *   槽 1..9   = Slot(craftSlots, j+i*3, 30+j*18, 17+i*18) → 3x3 网格 (30,17) 步距 18
 *   槽 10..36 = Slot(inv, j+i*9+9, 8+j*18, 84+i*18)       → 背包主区三行 (8,84)
 * </pre>
 *
 * <p><b>点击语义</b>(经 {@link VanillaPanels#clickMenuSlot} →
 * {@code MultiPlayerGameMode.handleInventoryMouseClick(menu.containerId, slot, button, ClickType, player)},
 * 客户端不预测,服务端结算后经同步包回读):左键 PICKUP+0、右键 PICKUP+1、
 * Shift+左/右 QUICK_MOVE+0/1。手持堆({@code menu.getCarried()})由 AgentFlatScreen
 * 渲染在鼠标位置;点面板外空白 = 放回背包({@link VanillaPanels#putBackCarried});
 * 点面板内非槽位空白:消费但不动作(原版语义)。</p>
 *
 * <p>菜单未连接时静默重连(每秒最多一次),格子照常绘制(空)。手机页(AgentPage,
 * 120 宽画布)复用本视图时右侧超宽部分由底盘裁剪,交互照常。</p>
 */
final class WorkbenchView {
    static final int PANEL_W = 176;
    static final int PANEL_H = 142;

    // 槽位 16x16 内容区坐标(与类注释对照表一一对应)
    private static final int RESULT_X = 124;
    private static final int RESULT_Y = 35;
    private static final int GRID_X = 30;
    private static final int GRID_Y = 17;
    /** 合成箭头 24x16:网格右缘 84 与产物槽外框左缘 123 之间水平居中;垂直中心 43 = 槽中心。 */
    private static final int ARROW_X = 92;
    private static final int ARROW_Y = 35;

    private final Runnable backToChat;
    private final Runnable openFurnace;
    private int viewX, viewY;
    private long lastReconnectMs = 0;

    WorkbenchView(Runnable backToChat, Runnable openFurnace) {
        this.backToChat = backToChat; // 手机页(AgentPage)的页内导航回调;平铺模式传 noop
        this.openFurnace = openFurnace;
    }

    void render(PhoneCanvas canvas) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        int x = canvas.x();
        int y = canvas.y();
        viewX = x;
        viewY = y;

        // 需求:去掉面板底板,只留格子和箭头(本行已移除)

        VirtualCraftMenu menu = currentMenu();
        if (menu == null) {
            // 静默重连:每秒最多发一次,不打扰玩家
            long now = System.currentTimeMillis();
            if (now - lastReconnectMs > 1000L) {
                lastReconnectMs = now;
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    ModNetworking.openVirtualCraft();
                }
            }
        }

        // 悬停槽位 = 指针下的菜单槽位号(渲染高亮与点击命中共用 slotAt:画的可点、点的可画)
        int hover = slotAt(canvas.mouseX() - x, canvas.mouseY() - y);

        // 3x3 合成网格(槽 1..9)
        for (int i = 0; i < 9; i++) {
            int cx = GRID_X + (i % 3) * VanillaPanels.STEP;
            int cy = GRID_Y + (i / 3) * VanillaPanels.STEP;
            VanillaPanels.drawSlot(g, x + cx, y + cy, hover == 1 + i);
            if (menu != null) {
                VanillaPanels.drawItem(g, font, menu.getSlot(1 + i).getItem(), x + cx, y + cy);
            }
        }

        // 合成箭头(静态灰)
        VanillaPanels.drawCraftArrow(g, x + ARROW_X, y + ARROW_Y);

        // 产物格(槽 0)
        VanillaPanels.drawSlot(g, x + RESULT_X, y + RESULT_Y, hover == 0);
        if (menu != null) {
            VanillaPanels.drawItem(g, font, menu.getSlot(0).getItem(), x + RESULT_X, y + RESULT_Y);
        }

        // 玩家背包主区三行(槽 10..36)+ 快捷栏(槽 37..45)
        VanillaPanels.drawPlayerInventory(g, font, menu, x, y, true, false, hover); // 需求:不画快捷栏
    }

    /**
     * 面板局部坐标 → 菜单槽位号(0 = 产物,1..9 = 网格,10..45 = 背包/快捷栏);
     * 不在任何槽位上返回 -1。命中区 = 16x16 槽内容区(与原版一致)。
     */
    private static int slotAt(double lx, double ly) {
        if (VanillaPanels.hitCell(lx, ly, RESULT_X, RESULT_Y)) {
            return 0;
        }
        for (int i = 0; i < 9; i++) {
            if (VanillaPanels.hitCell(lx, ly, GRID_X + (i % 3) * VanillaPanels.STEP,
                    GRID_Y + (i / 3) * VanillaPanels.STEP)) {
                return 1 + i;
            }
        }
        return VanillaPanels.playerSlotAt(lx, ly, true, false); // 需求:不画快捷栏
    }

    boolean mouseClicked(double mx, double my, int button) {
        if (!Widgets.hit(mx, my, viewX, viewY, PANEL_W, PANEL_H)) {
            return false; // 面板外不消费(面板外"放回手持物"由 AgentFlatScreen 统一处理)
        }
        VirtualCraftMenu menu = currentMenu();
        if (menu == null) {
            return true; // 面板内吞掉点击防穿透;菜单未连上时暂无可交互
        }
        int slot = slotAt(mx - viewX, my - viewY);
        if (slot < 0) {
            return true; // 原版语义:GUI 内非槽位区域点击不动作,但消费
        }
        VanillaPanels.clickMenuSlot(menu, slot, button);
        return true;
    }

    private static VirtualCraftMenu currentMenu() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.containerMenu instanceof VirtualCraftMenu vm ? vm : null;
    }

    boolean mouseScrolled(double amount) {
        return false;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    boolean charTyped(char codePoint, int modifiers) {
        return false;
    }
}
