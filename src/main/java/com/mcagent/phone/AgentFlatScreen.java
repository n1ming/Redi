package com.mcagent.phone;

import com.mcagent.menu.VirtualCraftMenu;
import com.mcagent.net.ModNetworking;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * 「AI 助手」平铺模式(替代手机全屏):聊天面板居中,左上原版工作台面板、其正下方原版熔炉面板。
 * 不遮世界、不进手机全屏。mcphone 侧 {@link AgentApp#openPage()} 返回 null,
 * launchApp 回退调 {@link AgentApp#onPress()},由它 {@code setScreen} 本屏(主线程)。
 *
 * <p><b>布局(gui 像素)</b></p>
 * <pre>
 *   工作台面板  x=8,   y=8,          176x166(原版 CraftingScreen 复刻,VanillaPanels 底板)
 *   熔炉面板    x=8,   y=8+166+8=182, 176x166(原版 FurnaceScreen 复刻);
 *              垂直放不下时保持 y=182 不动(绝不与工作台重叠 —— 优先保证工作台完整),
 *              高度钳为 height-182 只露上半,scissor 裁剪;熔炉视图再按剩余高度分层省略
 *              背包区/快捷栏区(见 FurnaceView)
 *   聊天面板    x=(width-124)/2(水平居中,需求原话:App 页面在屏幕中间),宽 124,
 *              高 clamp(height-16, 96, 172),垂直居中;ChatView / SettingsView 原样复用
 *              (深色主题面板),窄窗与左面板重叠时聊天面板画在最上、命中也最优先
 * </pre>
 *
 * <p><b>输入路由(详见各 override 注释)</b>
 * <ul>
 *   <li>鼠标点击:命中顺序 = 绘制顶层优先:聊天 → 工作台 → 熔炉;面板外空白:
 *       手持堆非空 = 放回背包({@link VanillaPanels#putBackCarried},原版面板外点击
 *       "丢下手持物"的简化语义,经真实菜单点击实现零不同步),空手不消费;</li>
 *   <li>滚轮:聊天面板内翻聊天历史(设置页翻模型列表);左侧两面板内消费但不动作(防误切);
 *       三块面板之外转发原版热键栏滚动,方向对齐原版
 *       {@code Inventory.swapPaint(k)}(javap:{@code selected -= signum(k)},
 *       即滚轮向上 = 槽位左移):{@code selected = clamp(selected - signum(scrollY), 0, 8)};</li>
 *   <li>键盘:ESC → 原版关屏;聊天/设置视图 keyPressed 优先;聊天输入框聚焦 = 打字模式,
 *       其余按键全吞(数字键不透传!EditBox 聚焦时 keyPressed 对数字键也返回 false,
 *       所以必须查焦点而不能只看返回值,见 {@link ChatView#isInputFocused()});
 *       输入框未聚焦时数字 1..9 切快捷栏({@code Inventory.selected = keyCode - 49});
 *       移动键(WASD/跳跃/潜行/疾跑)按 {@code KeyMapping.matches(keyCode, scanCode)}
 *       命中后 {@code setDown(true)}(keyReleased 置 false),恒返回 false 不消费
 *       —— 玩家可以边开着面板边走。</li>
 * </ul></p>
 *
 * <p><b>手持堆</b>:两原版面板的全部槽位点击经
 * {@code mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, ClickType, mc.player)}
 * 发给服务端(客户端不预测,服务端结算后经同步包回读);服务端回推的手持堆
 * ({@code menu.getCarried()},javap:public)在本屏最后渲染于鼠标位置(renderItem 居中于指针)。</p>
 *
 * <p><b>生命周期</b>:{@link #init()} 请求服务端打开虚拟工作台
 * ({@link ModNetworking#openVirtualCraft()},重复调用无害、绑定同一持久容器);
 * 故意<b>不</b>点亮 {@code HudOverlay.appVisible}——平铺模式左侧真面板已存在,
 * 该标志只控制「手机全屏时的左侧镜像面板」,保持 false。{@link #removed()} 释放移动键并
 * 断开虚拟工作台(服务端持久容器保留材料,不清空)。</p>
 *
 * <p>签名核对(javap,compiledWithNeoForge_735b2cb249c24959c061acf2b26fe22f1b5efbf9):
 * {@code Screen.renderBackground(GuiGraphics,int,int,float)、hasShiftDown()、
 * isPauseScreen()、removed()、keyPressed(int,int,int)、keyReleased(int,int,int)}、
 * {@code GuiEventListener.mouseScrolled(double,double,double,double)}(1.21.1 四参)、
 * {@code KeyMapping.setDown(boolean)、matches(int keyCode,int scanCode)}、
 * {@code AbstractContainerMenu.getCarried()/setCarried(ItemStack)}、
 * {@code MultiPlayerGameMode.handleInventoryMouseClick(int,int,int,ClickType,Player)}、
 * {@code Inventory.selected(public int)、Mth.clamp(int,int,int)}。</p>
 */
public final class AgentFlatScreen extends Screen {

    // ---- 布局常量(gui 像素) ----
    /** 聊天面板底:深藏青半透明(不遮世界;聊天/设置面板沿用既有深色主题)。 */
    private static final int PANEL_FILL = 0xC0101420;
    /** 聊天面板 1px 描边(HudOverlay.OUTLINE_IDLE_RGB 同值)。 */
    private static final int PANEL_EDGE = 0xFF3A4A66;
    /** 原版面板尺寸(CraftingScreen / FurnaceScreen 同款)。 */
    private static final int PANEL_W = WorkbenchView.PANEL_W; // 176
    private static final int PANEL_H = WorkbenchView.PANEL_H; // 166
    /** 工作台面板:左上 (8,8)。 */
    private static final int WB_X = 8;
    private static final int WB_Y = 8;
    /** 熔炉面板与工作台的间距(熔炉 y = 8+142+8 = 158)。 */
    private static final int FU_GAP = 8;
    /** 手机机身尺寸(复刻 mcphone 观感:136x216,屏幕 120x176 内嵌)。 */
    private static final int BODY_W = 136;
    private static final int BODY_H = 216;
    private static final int SCREEN_W = 120;
    private static final int SCREEN_H = 176;
    private static final int MARGIN = 8;

    // ---- PhoneCanvas 体系视图复用(与 AgentPage 同一套实例逻辑) ----
    private final ChatView chat = new ChatView(this::showSettings, this::showHistory);
    private final SettingsView settings = new SettingsView(this::showChat);
    private final HistoryView history = new HistoryView(this::showChat);
    private final WorkbenchView workbench = new WorkbenchView(this::noop, this::noop);
    private final FurnaceView furnace = new FurnaceView(this::noop, this::noop);
    /** 平铺屏内的"设置"状态:聊天面板区域切换渲染 SettingsView,齿轮进、"< 返回"出。 */
    private boolean inSettings = false;
    /** "历史"状态:聊天面板区域切换渲染 HistoryView(标题行「历史」进)。 */
    private boolean inHistory = false;
    /** 左列(工作台+熔炉)收起开关:收起后只留一个展开按钮。 */
    private boolean leftCollapsed = false;

    /** 原版移动键(每帧透传 setDown 用),init() 时从 options 收集。 */
    private KeyMapping[] movementKeys;

    /** 本次游戏启动是否已自动恢复过上次的会话(静态:每次开屏都是新 Screen 实例,标志须跨实例)。 */
    private static boolean autoRestoredLaunch = false;

    // 渲染期刷新的面板矩形(绘制与命中测试共用);工作台矩形恒 (8,8,176,142) 用常量
    private int fuY, fuH;
    private int bodyX, bodyY;
    private int chatX, chatY;

    public AgentFlatScreen() {
        super(Component.literal("Redi"));
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * init(minecraft, w, h) 后必调(窗口 resize 也会重跑;openVirtualCraft 重复调用无害)。
     * mcphone 的 AgentPage.onOpen 同款动作:请求服务端打开虚拟工作台。
     */
    @Override
    protected void init() {
        // javap: Options.keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift/keySprint 均 public final KeyMapping
        movementKeys = new KeyMapping[] {
                minecraft.options.keyUp,
                minecraft.options.keyDown,
                minecraft.options.keyLeft,
                minecraft.options.keyRight,
                minecraft.options.keyJump,
                minecraft.options.keyShift,
                minecraft.options.keySprint,
        };
        if (minecraft.player != null) {
            com.mcagent.McAgentMod.LOGGER.info("[mcagent] 平铺 init → openVirtualCraft");
            ModNetworking.openVirtualCraft(); // 主线程(屏幕回调)发包;绑定同一持久容器
        }
        // 重进游戏:本次启动首次打开助手且当前没有任何聊天记录时,
        // 自动载入最近一次保存的会话(= 玩家上次退出时的会话,含模型上下文)
        if (!autoRestoredLaunch) {
            autoRestoredLaunch = true;
            if (com.mcagent.agent.ChatModel.get().snapshot().isEmpty()) {
                com.mcagent.agent.ChatStore.loadLatest();
            }
        }
        // 注意:故意不设 HudOverlay.appVisible(见类注释"生命周期")
    }

    /** 屏幕被替换/关闭(ESC、setScreen):松开透传键防"关面板后仍走路",并断开虚拟工作台。 */
    @Override
    public void removed() {
        super.removed();
        if (movementKeys != null) {
            for (KeyMapping km : movementKeys) {
                km.setDown(false);
            }
        }
        Minecraft mc = Minecraft.getInstance();
        com.mcagent.McAgentMod.LOGGER.info("[mcagent] 平铺 removed(关闭/切屏)");
        com.mcagent.agent.ChatStore.saveCurrent(); // 关屏自动存档(空会话不落盘)
        // 服务端持久容器:断开连接不清空网格材料(与 AgentPage.onClose 同语义)
        if (mc.player != null && mc.player.containerMenu instanceof VirtualCraftMenu) {
            mc.player.closeContainer(); // javap: LocalPlayer.closeContainer() 发 C2S 关包
        }
    }

    /** 平铺模式:打开游戏菜单不会暂停(多人本就不暂停,单机也不该停)。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 平铺核心:不画全屏遮罩/模糊——世界、方块、物品栏照常透出。
     * 聊天面板底色由 {@link #render(GuiGraphics, int, int, float)} 按面板矩形自画;
     * 两个原版面板的底板由视图自绘(VanillaPanels,0xFFC6C6C6)。
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 空:什么都不画(签名 javap:renderBackground(GuiGraphics,int,int,float))
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        layout();

        // 绘制顺序 = 叠放次序:左侧工作台、熔炉,中间手机机身(带聊天 App),手持堆最上
        // 左列可收起:收起后只显示「展开工具」小按钮
        if (leftCollapsed) {
            guiGraphics.fill(WB_X, WB_Y, WB_X + 66, WB_Y + 14, 0xAA101420);
            guiGraphics.renderOutline(WB_X, WB_Y, 66, 14, PANEL_EDGE);
            guiGraphics.drawString(font, "▸ 展开工具", WB_X + 4, WB_Y + 3, 0xFFADADAD, false);
        } else {
            // 左上工作台(原版面板,自带 0xFFC6C6C6 底板)
            renderVanillaPanel(guiGraphics, WB_X, WB_Y, PANEL_W, PANEL_H, mouseX, mouseY, partialTick,
                    workbench::render);
            // 工作台正下方熔炉(垂直放不下时只露上半:fuH = height-158,scissor 裁剪)
            if (fuH > 0) {
                renderVanillaPanel(guiGraphics, WB_X, fuY, PANEL_W, fuH, mouseX, mouseY, partialTick,
                        furnace::render);
            }
            // 收起按钮(工作台面板右上角,盖在面板上)
            guiGraphics.fill(WB_X + PANEL_W - 34, WB_Y - 1, WB_X + PANEL_W - 2, WB_Y + 11, 0xAA101420);
            guiGraphics.renderOutline(WB_X + PANEL_W - 34, WB_Y - 1, 32, 12, PANEL_EDGE);
            guiGraphics.drawString(font, "◂ 收起", WB_X + PANEL_W - 32, WB_Y + 1, 0xFFADADAD, false);
        }
        // 手机机身:直接复用 mcphone 的 PhoneChassis(带玩家已装皮肤/状态栏/导航条),
        // 与手机全屏模式观感一致。几何同 PhoneScreen.render(反编译 330-331 行):
        // phoneLeft=(width-136)/2+8、phoneTop=(height-216)/2+8,屏幕 120x176,内容从 +10 起。
        com.november.mcphone.core.client.PhoneChassis.drawFrame(guiGraphics, bodyX, bodyY);
        com.november.mcphone.core.client.PhoneChassis.drawScreenBackground(guiGraphics, bodyX, bodyY);
        com.november.mcphone.core.client.PhoneChassis.drawStatusBar(guiGraphics, font, bodyX, bodyY);
        renderVanillaPanel(guiGraphics, chatX, chatY, SCREEN_W, SCREEN_H, mouseX, mouseY, partialTick,
                inHistory ? history::render
                        : inSettings ? settings::render : chat::render);
        com.november.mcphone.core.client.PhoneChassis.drawNavBar(guiGraphics, font, bodyX, bodyY, mouseX, mouseY);

        // 手持堆:最后画,保证在所有面板之上;渲染在鼠标位置(原版 cursor item 语义)
        renderCarried(guiGraphics, mouseX, mouseY);
    }

    /** 一块面板:scissor 到面板矩形内渲染对应视图(canvas 按面板矩形与实时鼠标构造)。 */
    private void renderVanillaPanel(GuiGraphics g, int px, int py, int pw, int ph,
            int mouseX, int mouseY, float partialTick, Consumer<PhoneCanvas> view) {
        g.enableScissor(px, py, px + pw, py + ph);
        view.accept(new PhoneCanvas(g, font, px, py, pw, ph, mouseX, mouseY, partialTick, FlatStyle.INSTANCE));
        g.disableScissor();
    }

    /** 手持堆渲染在指针位置(左上对齐指针-8,居中于指针;javap:AbstractContainerMenu.getCarried() public)。 */
    private void renderCarried(GuiGraphics g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.containerMenu instanceof VirtualCraftMenu menu)) {
            return;
        }
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        g.renderItem(carried, mouseX - 8, mouseY - 8);
        g.renderItemDecorations(font, carried, mouseX - 8, mouseY - 8);
    }

    /** 每帧重算面板矩形(窗口 resize 由 Screen.init 重跑兜底,这里按当前 width/height 居中)。 */
    private void layout() {
        // 熔炉在工作台正下方,与工作台同宽同高、左对齐;放不下时钳到剩余空间只露上半(scissor)
        fuY = WB_Y + PANEL_H + FU_GAP;
        fuH = Math.max(0, Math.min(PANEL_H, height - fuY));

        // 手机机身几何同 PhoneScreen.render:锚点 = 居中后再 +8(机身画在锚点外扩 8px 处)
        bodyX = (width - BODY_W) / 2 + 8;
        bodyY = Math.max(2, (height - BODY_H) / 2 + 8);
        chatX = bodyX;
        chatY = bodyY + 10; // 顶部 10px 是状态栏(时间),内容从 +10 起
    }

    // ---------------------------------------------------------------- 状态切换

    private void showSettings() {
        inSettings = true;
        inHistory = false;
    }

    private void showHistory() {
        inSettings = false;
        inHistory = true;
        history.reload();
    }

    private void showChat() {
        inSettings = false;
        inHistory = false;
        chat.focusInput(); // 把键盘焦点交回聊天输入框
    }

    private void noop() {
        // 平铺模式下工作台/熔炉两面板常驻同屏,视图顶行的「< 返回」「熔炉 ▸」「工作台 ▸」
        // 手机页内切换入口在这里无事可做(保持可点但无动作,避免引入新的状态机)。
    }

    // ---------------------------------------------------------------- 面板命中

    private boolean inWorkbench(double mx, double my) {
        return Widgets.hit(mx, my, WB_X, WB_Y, PANEL_W, PANEL_H);
    }

    private boolean inFurnace(double mx, double my) {
        return fuH > 0 && Widgets.hit(mx, my, WB_X, fuY, PANEL_W, fuH);
    }

    private boolean inChat(double mx, double my) {
        return Widgets.hit(mx, my, chatX, chatY, SCREEN_W, SCREEN_H);
    }

    // ---------------------------------------------------------------- 鼠标路由

    /**
     * 命中顺序 = 绘制顶层优先:聊天(最上)→ 工作台 → 熔炉(窄窗重叠时聊天面板胜出);
     * 面板外空白:手持堆非空 = 放回背包(VanillaPanels.putBackCarried,经真实菜单点击,
     * 服务端结算零不同步),空手不消费。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 手机导航栏(◁ 返回 / ○ 主屏 / □ 多任务):◁ 与 ○ = 收起平铺回桌面,□ 无动作。
        // 注意 hitTestNavBar 在导航区外返回 NavButton.NONE(非 null!),必须显式排除,
        // 否则全屏所有点击都会被这里吞掉。
        var nav = com.november.mcphone.core.client.PhoneChassis.hitTestNavBar(mouseX, mouseY, chatX, chatY);
        if (nav != null && nav != com.november.mcphone.core.client.PhoneChassis.NavButton.NONE) {
            com.mcagent.McAgentMod.LOGGER.info("[mcagent] 导航栏点击: {}", nav);
            if (nav == com.november.mcphone.core.client.PhoneChassis.NavButton.BACK
                    || nav == com.november.mcphone.core.client.PhoneChassis.NavButton.HOME) {
                onClose(); // javap: Screen.onClose → setScreen(null),removed 里存档并断开工作台
            }
            return true;
        }
        // 左列收起开关
        if (Widgets.hit(mouseX, mouseY, WB_X + PANEL_W - 34, WB_Y - 1, 32, 12)) {
            leftCollapsed = !leftCollapsed;
            com.mcagent.McAgentMod.LOGGER.info("[mcagent] 左列收起切换 → {}", leftCollapsed ? "收起" : "展开");
            return true;
        }
        // 收起状态下的「▸ 展开工具」按钮
        if (leftCollapsed && Widgets.hit(mouseX, mouseY, WB_X, WB_Y, 66, 14)) {
            leftCollapsed = false;
            com.mcagent.McAgentMod.LOGGER.info("[mcagent] 左列展开");
            return true;
        }
        if (inChat(mouseX, mouseY)) {
            if (inHistory) {
                return history.mouseClicked(mouseX, mouseY, button);
            }
            return inSettings
                    ? settings.mouseClicked(mouseX, mouseY, button)
                    : chat.mouseClicked(mouseX, mouseY, button);
        }
        if (inWorkbench(mouseX, mouseY)) {
            return workbench.mouseClicked(mouseX, mouseY, button);
        }
        if (inFurnace(mouseX, mouseY)) {
            return furnace.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 || button == 1) {
            return VanillaPanels.putBackCarried(button);
        }
        return false;
    }

    /**
     * 滚轮:聊天面板内翻历史/模型列表;左侧两面板内消费但不动作(防悬停误切热键栏);
     * 三块面板之外转发原版热键栏滚动。方向对齐原版 swapPaint(javap:{@code selected -= signum(k)},
     * 滚轮向上 = 选中槽左移),再用 Mth.clamp 收在 0..8。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inChat(mouseX, mouseY)) {
            if (inHistory) {
                return history.mouseScrolled(scrollY);
            }
            return inSettings
                    ? settings.mouseScrolled(scrollY)
                    : chat.mouseScrolled(mouseX, mouseY, scrollY);
        }
        if (inWorkbench(mouseX, mouseY)) {
            workbench.mouseScrolled(scrollY); // 无滚动内容,恒 false;吞掉滚动避免误切热键
            return true;
        }
        if (inFurnace(mouseX, mouseY)) {
            furnace.mouseScrolled(scrollY);
            return true;
        }
        if (minecraft.player != null && scrollY != 0.0) {
            var inv = minecraft.player.getInventory(); // javap: Inventory.selected 为 public int
            inv.selected = Mth.clamp(inv.selected - (int) Math.signum(scrollY), 0, 8);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 键盘路由

    /**
     * 顺序:ESC → 视图按键(聊天输入框控制键 / Enter 发送 / 设置表单)→ 打字模式吞键 →
     * 数字 1..9 切快捷栏 → 移动键透传 → 原版兜底。透传条件是"输入框未聚焦":
     * EditBox 聚焦时 keyPressed 对数字键返回 false,只看返回值会误透传,故先查
     * {@link ChatView#isInputFocused()}(包内新增的最小查询方法)。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 交给原版:shouldCloseOnEsc → onClose → setScreen(null) → removed() 断开工作台
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // 1) 聊天 / 设置视图按键优先(ChatView 与 SettingsView 无公共基类,不能写成一个三元式)
        boolean consumed = inSettings
                ? settings.keyPressed(keyCode, scanCode, modifiers)
                : chat.keyPressed(keyCode, scanCode, modifiers);
        if (consumed) {
            return true;
        }
        if (inSettings) {
            // 设置表单期间不透传热键与移动(防边填 API Key 边走动)
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // 2) 打字模式:聊天输入框持有焦点 → 其余按键全吞(数字/移动都不得误触)
        if (chat.isInputFocused()) {
            return true;
        }

        // 3) 数字 1..9(GLFW_KEY_1=49..KEY_9=57)→ 快捷栏槽位 0..8
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9 && minecraft.player != null) {
            minecraft.player.getInventory().selected = keyCode - GLFW.GLFW_KEY_1;
            return true;
        }

        // 4) 移动键透传:setDown(true) 后渲染线程之外的玩家 tick 会照常走路/跳/潜行/疾跑;
        //    返回 false 不消费,保持事件继续走原版链路
        if (movementKeys != null) {
            for (KeyMapping km : movementKeys) {
                if (km.matches(keyCode, scanCode)) { // javap: matches(int keyCode, int scanCode)
                    km.setDown(true);
                    return false;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 移动键松开:置 false(幂等,未按下也无害);恒返回 false 不消费。 */
    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (movementKeys != null) {
            for (KeyMapping km : movementKeys) {
                if (km.matches(keyCode, scanCode)) {
                    km.setDown(false);
                }
            }
        }
        return false;
    }

    /** 打字字符只进聊天/设置的输入框(输入框未聚焦时它们自己会返回 false)。 */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return inSettings
                ? settings.charTyped(codePoint, modifiers)
                : chat.charTyped(codePoint, modifiers);
    }
}
