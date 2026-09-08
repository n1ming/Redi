package com.mcagent;

import com.mcagent.craft.HudOverlay;
import com.mcagent.config.AgentConfig;
import com.mcagent.net.ModNetworking;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MC Agent —— 装在 MCphone 手机里的通用 AI 助手 App。
 *
 * <p>本模组没有服务端逻辑:App 通过 Java SPI 注册进 mcphone
 * (见 META-INF/services/com.november.mcphone.api.client.app.IPhoneApp)。
 * Agent 是一个带工具调用能力的通用 LLM 会话,所有“动作”都通过通用工具
 * 在游戏运行时内以玩家身份完成;它只读取代码与数据,永不修改。</p>
 */
@Mod(McAgentMod.MODID)
public final class McAgentMod {
    public static final String MODID = "mcagent";
    public static final Logger LOGGER = LoggerFactory.getLogger("mcagent");

    public McAgentMod() {
        // MOD 事件总线只取一次(javap 依据见 registerCraftHud 注释);
        // 先落局部变量、再分别 addListener(方法引用),不再把「泛型方法引用链 +
        // 泛型 lambda」挂在同一条链式表达式尾部 —— 该写法在本机 javac 21.0.2 的
        // 部分 classpath 下会触发 TransTypes 编译器崩溃(bug 模式),拆开即绕开,语义不变。
        IEventBus modBus = ModList.get().getModContainerById(MODID).orElseThrow(
                () -> new IllegalStateException("mcagent ModContainer 未就绪")).getEventBus();

        if (FMLEnvironment.dist.isClient()) {
            AgentConfig.get().load();
            // LLM 全链路插桩:每次调用摘要进游戏日志(原始字节在 config/mcagent/trace/)
            com.mcagent.llm.LlmClient.logHook = LOGGER::info;
            // 插件装载(cordis 风格:工具/服务/事件全部由插件装配,卸载即回滚)
            com.mcagent.plugin.PluginManager.loadAll();
            LOGGER.info("[mcagent] 插件装载完成: {}", com.mcagent.plugin.PluginManager.loadedIds());

            // === 合成 HUD 浮层注册(仅客户端)===
            // javap 核对依据(NeoForge 21.1.249 universal jar + MC compiledWithNeoForge jar):
            //  1) net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
            //     extends net.neoforged.bus.api.Event implements net.neoforged.fml.event.IModBusEvent
            //     → IModBusEvent 事件必须注册在「MOD 事件总线」,不是 NeoForge.EVENT_BUS 游戏总线。
            //  2) 本工程 FML(net.neoforged.fancymodloader:loader 4.0.44)javap 确认不存在
            //     FMLJavaModLoadingContext;mod 构造器签名又不便改动,故沿用 mcphone 同款
            //     ModList 取容器方式拿 MOD 事件总线(javap 实测):
            //     net.neoforged.fml.ModList.get() → ModList
            //     ModList.getModContainerById(String) → Optional<? extends ModContainer>
            //     net.neoforged.fml.ModContainer.getEventBus() → net.neoforged.bus.api.IEventBus
            //     IEventBus.addListener(java.util.function.Consumer<T extends Event>)
            //  3) 注册方法签名(javap 实测):
            //     RegisterGuiLayersEvent.registerAboveAll(net.minecraft.resources.ResourceLocation,
            //         net.minecraft.client.gui.LayeredDraw$Layer)
            //  4) 层接口签名(javap 实测):
            //     net.minecraft.client.gui.LayeredDraw$Layer
            //         .render(net.minecraft.client.gui.GuiGraphics, net.minecraft.client.DeltaTracker)
            //     HudOverlay.INSTANCE 直接实现该接口。registerAboveAll=画在所有原生层之上。
            modBus.addListener(McAgentMod::registerCraftHud); // 方法引用:与 ModNetworking::onRegisterPayloads 同款,可稳定编译

            // 客户端游戏总线:捕获指令反馈聊天文本(send_command 用)
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    com.mcagent.tools.CommandFeedback::handleClientChat);

            // 退出存档兜底:断开连接(退服/退到标题)时立即保存会话,
            // 防止玩家不关手机界面直接退游戏导致最后一段问答丢失
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e)
                            -> com.mcagent.agent.ChatStore.saveCurrent());

            // 插件显化:ESC 暂停菜单里加「Agent 插件」按钮,点开列出已装载插件与各自工具
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.ScreenEvent.Init.Post e) -> {
                        if (e.getScreen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
                            e.addListener(net.minecraft.client.gui.components.Button.builder(
                                            net.minecraft.network.chat.Component.literal("Agent 插件"),
                                            b -> e.getScreen().getMinecraft().setScreen(
                                                    new com.mcagent.client.AgentPluginsScreen(e.getScreen())))
                                    .bounds(8, 8, 96, 20)
                                    .build());
                        }
                    });

            LOGGER.info("[mcagent] Redi App 已就绪(在手机主屏打开「Redi」)");
        }

        // === 服务端基建注册(两侧执行;上方 HUD 注册代码原样保留) ===
        // 1) 菜单 DeferredRegister(Registries.MENU)挂 MOD 总线;
        // 2) RegisterPayloadHandlersEvent(MOD 总线,IModBusEvent)注册 4 个 C→S
        //    (OpenVirtualCraft/SmeltRequest/SmeltCancel/CollectSmelt)+ 2 个 S→C
        //    (SmeltStateSync/OpenVirtualWorkbench)payload;
        // 3) 冶炼会话 tick(ServerTickEvent.Post)与登入登出持久化挂 NeoForge.EVENT_BUS 游戏总线。
        // 全部收口在 ModNetworking.register(IEventBus)(契约入口),实现细节见该类 javap 注释。
        ModNetworking.register(modBus);
        LOGGER.info("[mcagent] 服务端基建已注册(虚拟工作台菜单 / 虚拟熔炉会话 / 冶炼同步)");
    }

    /**
     * RegisterGuiLayersEvent 处理器(原内联 lambda 拆出的显式方法,语义逐字不变):
     * 注册合成 HUD 浮层到「所有原生层之上」。
     */
    private static void registerCraftHud(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(MODID, "craft_hud"),
                HudOverlay.INSTANCE);
    }
}
