package com.redi.phone;

import com.redi.menu.VirtualCraftMenu;
import com.redi.net.ModNetworking;
import com.redi.net.SmeltClientState;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 熔炉面板 —— 原版 FurnaceScreen(176x166)的纯填充复刻:原料槽上 (56,17)、燃料槽下 (56,53)、
 * 中间火焰位 (57,35)、进度箭头 (79,35)、产物槽 (116,35),下方玩家背包区与工作台面板同布局
 * (同一玩家背包容器,槽 10..45 经 VirtualCraftMenu 真实点击,两面板互通)。无任何提示文字。
 *
 * <p><b>交互(全部以服务端会话快照 {@link SmeltClientState#current()} 实读为准,不伪造状态):</b></p>
 * <ul>
 *   <li>点产物格 → {@link ModNetworking#collectSmelt()}:服务端把堆积产物收进背包
 *       (产物空时消费点击但不动作);</li>
 *   <li>点原料格 / 燃料格 → {@link ModNetworking#cancelSmelt()}:取下 = 取消冶炼并返还材料
 *       (无活跃会话时消费点击但不动作);</li>
 *   <li>背包/快捷栏 → {@link VanillaPanels#clickMenuSlot}(左/右 PICKUP,Shift QUICK_MOVE)。</li>
 * </ul>
 *
 * <p><b>垂直空间自适应</b>:面板高度不足以画全 166 时按层省略(越界部分由外层 scissor 裁剪,
 * 命中测试与绘制同一开关 —— 画不出的格子不响应点击):
 * ≥161 画背包三行+快捷栏;≥139 只画背包三行;更矮只保留上半熔炉区
 * (原料/燃料/火焰/箭头/产物全部在 y≤70 内)。工作台面板永远完整优先。</p>
 */
final class FurnaceView {
    static final int PANEL_W = 176;
    static final int PANEL_H = 142;

    // 槽位/图标 16x16 内容区坐标(原版 FurnaceScreen 布局)
    private static final int IN_X = 56;
    private static final int IN_Y = 17;
    private static final int FUEL_X = 56;
    private static final int FUEL_Y = 53;
    private static final int FLAME_X = 57;
    private static final int FLAME_Y = 35;
    private static final int ARROW_X = 79;
    private static final int ARROW_Y = 35;
    private static final int OUT_X = 116;
    private static final int OUT_Y = 35;

    /** slotAt 的熔炉特有"槽位"代号(背包容器槽 10..45 直接用菜单槽位号)。 */
    private static final int SLOT_OUTPUT = 100;
    private static final int SLOT_INPUT = 101;
    private static final int SLOT_FUEL = 102;

    /** 背包三行 / 快捷栏可绘制的最小面板高(84+3*18+1=139;142+18+1=161)。 */
    private static final int MIN_H_INV_ROWS = 139;
    private static final int MIN_H_HOTBAR = 161;

    private final Runnable backToChat;
    private final Runnable openWorkbench;
    private int viewX, viewY;
    private int viewH = PANEL_H;

    FurnaceView(Runnable backToChat, Runnable openWorkbench) {
        this.backToChat = backToChat; // 手机页(AgentPage)的页内导航回调;平铺模式传 noop
        this.openWorkbench = openWorkbench;
    }

    void render(PhoneCanvas canvas) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        int x = canvas.x();
        int y = canvas.y();
        viewX = x;
        viewY = y;
        viewH = Math.min(canvas.height(), PANEL_H);
        boolean invRows = viewH >= MIN_H_INV_ROWS;
        boolean hotbar = false; // 需求:不画快捷栏

        // 需求:去掉面板底板,只留格子和箭头(本行已移除)

        SmeltClientState.SmeltView s = SmeltClientState.current();

        // 悬停命中(与点击共用 slotAt:画的可点、点的可画)
        int hover = slotAt(canvas.mouseX() - x, canvas.mouseY() - y, invRows, hotbar);

        // 原料槽(上)
        VanillaPanels.drawSlot(g, x + IN_X, y + IN_Y, hover == SLOT_INPUT);
        if (s != null && s.inputCount() > 0) {
            drawItemById(g, font, s.inputId(), s.inputCount(), x + IN_X, y + IN_Y);
        }

        // 火焰:燃烧中 = 橙苗 + 顶端跳动的黄芯;熄灭 = 深灰剪影
        // (快照无燃料剩余燃烧进度字段,不做部分高度填充 —— 不伪造状态)
        VanillaPanels.drawFlame(g, x + FLAME_X, y + FLAME_Y, s != null && s.active(),
                System.currentTimeMillis());

        // 进度箭头:progressTicks/totalTicks 实读填充(灰底、进度段白色)
        float progress = s != null && s.totalTicks() > 0
                ? s.progressTicks() / (float) s.totalTicks()
                : 0.0f;
        VanillaPanels.drawFurnaceArrow(g, x + ARROW_X, y + ARROW_Y, progress);

        // 产物槽
        VanillaPanels.drawSlot(g, x + OUT_X, y + OUT_Y, hover == SLOT_OUTPUT);
        if (s != null && s.outputCount() > 0) {
            drawItemById(g, font, s.outputId(), s.outputCount(), x + OUT_X, y + OUT_Y);
        }

        // 燃料槽(下)
        VanillaPanels.drawSlot(g, x + FUEL_X, y + FUEL_Y, hover == SLOT_FUEL);
        if (s != null && s.fuelCount() > 0) {
            drawItemById(g, font, s.fuelId(), s.fuelCount(), x + FUEL_X, y + FUEL_Y);
        }

        // 玩家背包区(空间不足按层省略;内容从 VirtualCraftMenu 槽 10..45 实读)
        VanillaPanels.drawPlayerInventory(g, font, currentMenu(), x, y, invRows, hotbar, hover);
    }

    /**
     * 面板局部坐标 → 交互目标:SLOT_OUTPUT/SLOT_INPUT/SLOT_FUEL(熔炉特有)、
     * 10..45(玩家背包容器槽位号)、-1(无)。invRows/hotbar 与绘制同开关。
     */
    private static int slotAt(double lx, double ly, boolean invRows, boolean hotbar) {
        if (VanillaPanels.hitCell(lx, ly, IN_X, IN_Y)) {
            return SLOT_INPUT;
        }
        if (VanillaPanels.hitCell(lx, ly, FUEL_X, FUEL_Y)) {
            return SLOT_FUEL;
        }
        if (VanillaPanels.hitCell(lx, ly, OUT_X, OUT_Y)) {
            return SLOT_OUTPUT;
        }
        return VanillaPanels.playerSlotAt(lx, ly, invRows, hotbar);
    }

    boolean mouseClicked(double mx, double my, int button) {
        if (!Widgets.hit(mx, my, viewX, viewY, PANEL_W, viewH)) {
            return false; // 面板外不消费(面板外"放回手持物"由 AgentFlatScreen 统一处理)
        }
        boolean invRows = viewH >= MIN_H_INV_ROWS;
        boolean hotbar = false; // 需求:不画快捷栏
        int slot = slotAt(mx - viewX, my - viewY, invRows, hotbar);

        // 产物格:收取堆积产物入背包(空产物消费但不动作)
        if (slot == SLOT_OUTPUT) {
            SmeltClientState.SmeltView s = SmeltClientState.current();
            if (s != null && s.outputCount() > 0) {
                ModNetworking.collectSmelt();
            }
            return true;
        }
        // 原料/燃料格:取下 = 取消冶炼并返还材料(无会话消费但不动作)
        if (slot == SLOT_INPUT || slot == SLOT_FUEL) {
            SmeltClientState.SmeltView s = SmeltClientState.current();
            if (s != null && s.active()) {
                ModNetworking.cancelSmelt();
            }
            return true;
        }
        // 背包/快捷栏:走 VirtualCraftMenu 真实菜单点击(同一玩家背包容器,与工作台面板互通)
        if (slot >= 10) {
            VirtualCraftMenu menu = currentMenu();
            if (menu != null) {
                VanillaPanels.clickMenuSlot(menu, slot, button);
            }
            return true;
        }
        return true; // 面板内非槽位空白:消费不动作(原版语义)
    }

    /** 冶炼快照的注册名 → 物品图标(无效注册名静默不画;数量角标经 renderItemDecorations)。 */
    private static void drawItemById(GuiGraphics g, Font font, String id, int count, int sx, int sy) {
        if (count <= 0 || id == null || id.isBlank()) {
            return;
        }
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id.trim());
        if (rl == null) {
            return;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item != null && rl.equals(BuiltInRegistries.ITEM.getKey(item))) {
            VanillaPanels.drawItem(g, font, new ItemStack(item, count), sx, sy);
        }
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
