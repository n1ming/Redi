package com.redi.craft;

import com.redi.menu.VirtualCraftMenu;
import com.redi.net.SmeltClientState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * HUD 浮层,双形态(v2:虚拟工作台/熔炉的客户端可见):
 * <ul>
 *   <li><b>左侧持久镜像面板</b>(v2 主形态):仅当 AI 助手 App 打开
 *       ({@link #appVisible},AgentPage.render 每帧置 true、onClose 置 false)时,
 *       画在屏幕中央左侧(与右侧手机对称、留 22px 空隙,不遮挡手机)。内容 =
 *       「虚拟工作台」label + 3x3 网格实时镜像(服务端持久容器
 *       {@link VirtualCraftMenu} 的槽位 1..9,渲染线程即主线程可直接读;没连接画空槽)
 *       + 「▶」+ 产物槽(槽 0)+ 底部一行小字「产物在 App 内点取」。
 *       若 {@link SmeltClientState} 有活跃冶炼会话,面板下半部分换成熔炉行
 *       (原料 / 燃料 / 进度条 / 产物小图标,数据全部来自 SmeltView 快照)。</li>
 *   <li><b>右侧临时作业面板</b>(v1 保留):App 未打开时,若 CraftHud 快照存在
 *       (agent 自动合成/冶炼的摆料动画),按原布局画在手机右侧,300ms 淡入淡出、
 *       done 时绿描边闪烁——SmeltItemTool / 容器自动化的「HUD 展示」由此继续生效。</li>
 * </ul>
 *
 * <p>注册机制(javap 核对,NeoForge 21.1.249 / MC 1.21.1,保持不变):
 * {@code RegisterGuiLayersEvent} 是 IModBusEvent(MOD 总线,见 RediMod 构造器);
 * 注册方法 {@code registerAboveAll(ResourceLocation, LayeredDraw$Layer)};
 * 层接口 {@code render(GuiGraphics, DeltaTracker)} —— 本类直接实现该接口,
 * 单例 {@link #INSTANCE} 传入,注册侧零改动。</p>
 *
 * <p>左侧镜像面板布局(gui 像素,x0/y0 为面板左上):
 * <pre>
 *   x0 = guiWidth()/2 - 92 - 90(手机机壳右缘 ≈ 中央+68、左缘 ≈ 中央-68,面板右缘 = 中央-90,留 22px 空隙)
 *   y0 = (guiHeight() - 面板高) / 2(垂直居中;面板高 76,有熔炉行时 84)
 *   label     「虚拟工作台」 y0+1,水平居中,白
 *   网格      3x3,格 16px、间隔 2px(步距 18),起点 (x0+5, y0+11);槽底 0x66000000 + 1px 描边
 *   箭头/产物 「▶」(x0+59, y0+33)、产物 16px @ (x0+71, y0+29)(与网格垂直居中)
 *   小字      「产物在 App 内点取」 y0+65,水平居中,灰
 *   熔炉行    (替换小字)原料 (x0+5, y0+64)、燃料 (x0+23, y0+64)、
 *            百分比 + 进度条 x0+43..x0+67 @ y0+76..80(accent 填充)、产物 (x0+71, y0+64)
 * </pre></p>
 */
public final class HudOverlay implements LayeredDraw.Layer {

    public static final HudOverlay INSTANCE = new HudOverlay();

    private HudOverlay() {
    }

    /**
     * AI 助手 App 打开标志:AgentPage.render 每帧置 true,onClose 置 false。
     * volatile 只为防御性(读写都在渲染线程);true = 画左侧镜像面板。
     */
    public static volatile boolean appVisible = false;

    /** 与 CraftHud.AUTO_HIDE_MS(私有常量 8000)保持一致,用于计算淡出窗口;CraftHud 若改动需同步。 */
    private static final long AUTO_HIDE_MS = 8000;
    /** 淡入/淡出时长 ms(仅右侧临时面板用)。 */
    private static final long FADE_MS = 300;

    // ---- 左侧镜像面板布局常量(见类注释布局图) ----
    private static final int PANEL_W = 3 * 18 + 8 + 30; // 92
    private static final int PANEL_H = 76;              // label 10 + 网格 52 + 小字带 14
    private static final int PANEL_H_SMELT = PANEL_H + 8; // 84(熔炉行图标 16px 比文本行高)
    private static final int GRID_X = 5;                // 网格起点相对面板左上
    private static final int GRID_Y = 11;
    private static final int CELL = 16;                 // 格子内物品 16px
    private static final int PITCH = 18;                // 16px 格 + 2px 间隔
    private static final int ARROW_X = 59;              // 「▶」相对面板
    private static final int ARROW_Y = 33;              // 与网格垂直居中(网格 v 中心 37,文本高 8)
    private static final int RESULT_X = 71;             // 产物图标相对面板
    private static final int RESULT_Y = 29;
    private static final int HINT_Y = 65;               // 底部小字 / 熔炉行起点
    private static final int PANEL_X_OFFSET = 90;       // 屏幕中央向左偏移(与手机右侧对称)
    /** 熔炉行进度条宽度(进度 = progressTicks/totalTicks,按比例填充)。 */
    private static final int PROGRESS_BAR_W = 24;

    // ---- 右侧临时面板布局常量(v1 保留,CraftHud 快照) ----
    private static final int LEGACY_GRID_X = 5;
    private static final int LEGACY_GRID_Y = 14;
    private static final int LEGACY_RESULT_X = 71;
    private static final int LEGACY_RESULT_Y = 32;

    // ---- 配色(两个面板共用) ----
    private static final int PANEL_FILL_RGB = 0x101420; // 半透明深底
    private static final int PANEL_FILL_A = 0xAA;
    private static final int OUTLINE_IDLE_RGB = 0x3A4A66;
    private static final int OUTLINE_DONE_RGB = 0x2ECC71;
    private static final int OUTLINE_FLASH_RGB = 0x66FFA0; // 闪的亮绿
    private static final int SLOT_BG = 0x66000000;     // 槽位深底
    private static final int SLOT_EDGE = 0x33FFFFFF;   // 槽位 1px 描边
    private static final int ACCENT = 0xFF2ECC71;      // 箭头 / 进度条
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFF8A97A8;  // 底部小字灰

    /** v1 右侧临时面板:done 状态边沿检测(闪 600ms 用)。 */
    private boolean lastDone = false;
    private long doneFlashUntil = 0L;

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        if (appVisible) {
            renderMirrorPanel(g);
            return;
        }
        renderLegacySnapshot(g);
    }

    // ================================================================ 左侧镜像面板

    /**
     * 左侧持久镜像面板(仅 App 打开时被调用)。
     * 槽位数据直接读客户端已同步的 {@link VirtualCraftMenu}(渲染线程 = 主线程);
     * 没连接(菜单没开 / 玩家不在世界)就画空槽 + 空产物槽,面板不消失。
     */
    private void renderMirrorPanel(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        VirtualCraftMenu menu = p != null && p.containerMenu instanceof VirtualCraftMenu vm ? vm : null;

        SmeltClientState.SmeltView smelt = SmeltClientState.current();
        boolean smelting = smelt != null && smelt.active();
        int panelH = smelting ? PANEL_H_SMELT : PANEL_H;

        int x0 = g.guiWidth() / 2 - PANEL_X_OFFSET - PANEL_W;
        int y0 = (g.guiHeight() - panelH) / 2;

        // 面板底 + 描边
        g.fill(x0, y0, x0 + PANEL_W, y0 + panelH, PANEL_FILL_A << 24 | PANEL_FILL_RGB);
        g.renderOutline(x0, y0, PANEL_W, panelH, OUTLINE_IDLE_RGB | 0xFF000000);

        var font = mc.font;

        // 顶部 label,水平居中
        String label = "虚拟工作台";
        g.drawString(font, label, x0 + (PANEL_W - font.width(label)) / 2, y0 + 1, LABEL_COLOR);

        // 3x3 网格:槽位深底 + 描边 + 真实物品(菜单槽 1..9;没连接画空槽)
        for (int i = 0; i < 9; i++) {
            int cx = x0 + GRID_X + (i % 3) * PITCH;
            int cy = y0 + GRID_Y + (i / 3) * PITCH;
            g.fill(cx, cy, cx + CELL, cy + CELL, SLOT_BG);
            g.renderOutline(cx, cy, CELL, CELL, SLOT_EDGE);
            ItemStack st = menu != null && 1 + i < menu.slots.size() ? menu.getSlot(1 + i).getItem() : null;
            if (st != null && !st.isEmpty()) {
                g.renderItem(st, cx, cy);
            }
        }

        // 箭头 + 产物槽(菜单槽 0)
        g.drawString(font, "▶", x0 + ARROW_X, y0 + ARROW_Y, ACCENT);
        g.fill(x0 + RESULT_X, y0 + RESULT_Y, x0 + RESULT_X + CELL, y0 + RESULT_Y + CELL, SLOT_BG);
        g.renderOutline(x0 + RESULT_X, y0 + RESULT_Y, CELL, CELL, SLOT_EDGE);
        ItemStack result = menu != null && !menu.slots.isEmpty() ? menu.getSlot(0).getItem() : null;
        if (result != null && !result.isEmpty()) {
            g.renderItem(result, x0 + RESULT_X, y0 + RESULT_Y);
        }

        // 下半部分:活跃冶炼 → 熔炉行;否则 → 提示小字
        if (smelting) {
            renderSmeltRow(g, font, x0, y0, smelt);
        } else {
            String hint = "产物在 App 内点取";
            g.drawString(font, hint, x0 + (PANEL_W - font.width(hint)) / 2, y0 + HINT_Y, HINT_COLOR);
        }
    }

    /** 熔炉行:原料 / 燃料 / 进度条(accent 填充)/ 产物,全部来自 SmeltView 快照。 */
    private void renderSmeltRow(GuiGraphics g, net.minecraft.client.gui.Font font, int x0, int y0,
            SmeltClientState.SmeltView smelt) {
        drawSmeltItem(g, smelt.inputId(), smelt.inputCount(), x0 + GRID_X, y0 + HINT_Y);
        drawSmeltItem(g, smelt.fuelId(), smelt.fuelCount(), x0 + GRID_X + PITCH, y0 + HINT_Y);

        // 进度条:底 + accent 填充(progressTicks/totalTicks,钳制 0..1);百分比小字在条上方
        int barX = x0 + 43;
        int barY = y0 + HINT_Y + 12;
        g.fill(barX, barY, barX + PROGRESS_BAR_W, barY + 4, SLOT_BG);
        g.renderOutline(barX, barY, PROGRESS_BAR_W, 4, SLOT_EDGE);
        float frac = smelt.totalTicks() > 0
                ? Math.max(0.0f, Math.min(1.0f, smelt.progressTicks() / (float) smelt.totalTicks()))
                : 0.0f;
        int fillW = Math.round(PROGRESS_BAR_W * frac);
        if (fillW > 0) {
            g.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + 3, ACCENT);
        }
        String pct = (int) (frac * 100) + "%";
        g.drawString(font, pct, barX + (PROGRESS_BAR_W - font.width(pct)) / 2, y0 + HINT_Y + 2, HINT_COLOR);

        drawSmeltItem(g, smelt.outputId(), smelt.outputCount(), x0 + RESULT_X, y0 + HINT_Y);
    }

    /** 熔炉行的一个小槽:16px 深底 + 描边;count>0 且 id 可解析时画物品(计数角标随 renderItem)。 */
    private static void drawSmeltItem(GuiGraphics g, String id, int count, int x, int y) {
        g.fill(x, y, x + CELL, y + CELL, SLOT_BG);
        g.renderOutline(x, y, CELL, CELL, SLOT_EDGE);
        if (count <= 0) {
            return;
        }
        Item item = itemById(id);
        if (item != null) {
            g.renderItem(new ItemStack(item, count), x, y);
        }
    }

    /** 注册名 → 物品;空串 / 解析失败 / 注册表缺失返回 null(与 ChatView.itemOrNull 同套路)。 */
    private static Item itemById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        if (rl == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item != null && rl.equals(BuiltInRegistries.ITEM.getKey(item)) ? item : null;
    }

    // ================================================================ 右侧临时面板(v1 保留)

    /**
     * v1 右侧临时作业面板:App 未打开且 CraftHud 快照存在时绘制
     * (agent 自动合成/冶炼的摆料动画,位置与 v1 完全一致)。
     */
    private void renderLegacySnapshot(GuiGraphics g) {
        // 快照不可变;每帧只读一次 volatile 引用
        CraftHud.Snapshot snap = CraftHud.current();
        if (snap == null) {
            lastDone = false;
            return;
        }

        long now = System.currentTimeMillis();
        long age = now - snap.startedAt();
        long remain = AUTO_HIDE_MS - age;
        float fade = Math.min(1.0f, Math.min(age, remain) / (float) FADE_MS);
        if (fade <= 0.0f) return;

        // done 边沿:变绿一闪 600ms
        if (snap.done() && !lastDone) doneFlashUntil = now + 600;
        lastDone = snap.done();

        int x0 = g.guiWidth() / 2 + PANEL_X_OFFSET;
        int y0 = (g.guiHeight() - PANEL_H) / 2;

        // 面板底 + 描边(alpha 随淡入淡出缩放)
        g.fill(x0, y0, x0 + PANEL_W, y0 + PANEL_H, withAlpha(PANEL_FILL_A, fade) << 24 | PANEL_FILL_RGB);
        g.renderOutline(x0, y0, PANEL_W, PANEL_H, outlineColor(now, fade));

        // 3x3 网格:槽位深底 + 真实物品图标(null 画空槽即只有深底)
        int slotBg = withAlpha(0x66, fade) << 24;
        ItemStack[] grid = snap.grid();
        for (int i = 0; i < 9; i++) {
            int cx = x0 + LEGACY_GRID_X + (i % 3) * PITCH;
            int cy = y0 + LEGACY_GRID_Y + (i / 3) * PITCH;
            g.fill(cx, cy, cx + CELL, cy + CELL, slotBg);
            ItemStack st = grid != null && i < grid.length ? grid[i] : null;
            if (st != null && !st.isEmpty()) {
                g.renderItem(st, cx, cy);
            }
        }

        // 箭头 + 产物
        var font = Minecraft.getInstance().font;
        g.drawString(font, "▶", x0 + ARROW_X, y0 + LEGACY_RESULT_Y + 4, ACCENT);
        if (snap.result() != null && !snap.result().isEmpty()) {
            g.renderItem(snap.result(), x0 + LEGACY_RESULT_X, y0 + LEGACY_RESULT_Y);
        }

        // 顶部 label,水平居中
        String label = snap.label() == null ? "自动作业" : snap.label();
        g.drawString(font, label, x0 + (PANEL_W - font.width(label)) / 2, y0 + 1, LABEL_COLOR);
    }

    /** done 时绿描边;头 600ms 在绿/亮绿间交替(「一闪」),之后稳定绿色。 */
    private int outlineColor(long now, float fade) {
        int rgb;
        if (!lastDone) {
            rgb = OUTLINE_IDLE_RGB;
        } else if (now < doneFlashUntil) {
            rgb = (now / 120) % 2 == 0 ? OUTLINE_FLASH_RGB : OUTLINE_DONE_RGB;
        } else {
            rgb = OUTLINE_DONE_RGB;
        }
        return withAlpha(0xFF, fade) << 24 | rgb;
    }

    /** 把基色 alpha 通道乘以淡入淡出系数。 */
    private static int withAlpha(int baseAlpha, float fade) {
        int a = (int) (baseAlpha * Math.max(0.0f, Math.min(1.0f, fade)));
        return Math.min(0xFF, Math.max(0, a));
    }
}
