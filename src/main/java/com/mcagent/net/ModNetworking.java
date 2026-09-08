package com.mcagent.net;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.mcagent.McAgentMod;
import com.mcagent.menu.VirtualCraftMenu;
import com.mcagent.menu.VirtualSmelter;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

/**
 * 服务端基建的注册与网络契约入口(客户端工具层只依赖本类静态方法
 * openVirtualCraft/requestSmelt/cancelSmelt/collectSmelt 与 {@link SmeltClientState})。
 *
 * <p>全部签名经 javap 对 MC 1.21.1
 * (compiledWithNeoForge_735b2cb249c24959c061acf2b26fe22f1b5efbf9_output.jar)
 * 与 NeoForge 21.1.249 universal jar 核对,证据见各成员注释。</p>
 *
 * <p><b>虚拟工作台打开链路(v2.1,绕过 Screen 注册缺口):</b>
 * {@code mcagent:virtual_craft} 菜单类型没有注册 Screen,而 1.21.1 客户端
 * {@code ClientPacketListener.handleOpenScreen(ClientboundOpenScreenPacket)} 只调
 * {@code MenuScreens.create(...)}(反编译源码 1245-1247 行),创建不出 Screen 时
 * {@code player.containerMenu} 永远不会被赋值;因此服务端<b>不再走
 * {@code player.openMenu(...)}</b>(它必发 OpenScreen 包),改为直接给
 * {@code ServerPlayer.containerMenu} 赋值 + 发自定义 payload
 * {@link OpenVirtualWorkbench},客户端 handler 自己构造 {@link VirtualCraftMenu}
 * 挂上去(见两处 handler 的 javap 证据注释)。</p>
 */
public final class ModNetworking {

    /** 网格槽位布局与原版 CraftingMenu 一致:结果槽 0,3x3 网格 1..9。 */
    private static final int GRID_SLOTS = 9;

    /**
     * 自维护 containerId 计数(javap -p 实测:{@code ServerPlayer.containerCounter} 是
     * <b>private int</b>,{@code nextContainerCounter()}/{@code initMenu(...)} 也都是
     * private,无法复用)。vanilla 取值域为 {@code containerCounter % 100 + 1 ∈ [1,100]}
     * (反编译 ServerPlayer 1110-1112 行),本计数从 101 起单调递增,与 vanilla 永不相撞;
     * 每个玩家同一时刻只有一个 containerMenu,跨玩家共用该全局计数亦无歧义。
     */
    private static final AtomicInteger VIRTUAL_MENU_IDS = new AtomicInteger(100);

    // ===================== 菜单注册(DeferredRegister,MOD 总线) =====================

    /**
     * javap:DeferredRegister.create(net.minecraft.core.registries.Registries.MENU 为
     * ResourceKey&lt;Registry&lt;MenuType&lt;?&gt;&gt;&gt;, java.lang.String) → DeferredRegister;
     * register(IEventBus) 挂 MOD 总线(在 {@link #register} 里调用)。
     */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, McAgentMod.MODID);

    /**
     * javap:IMenuTypeExtension.create(net.neoforged.neoforge.network.IContainerFactory&lt;T&gt;) → MenuType&lt;T&gt;;
     * IContainerFactory 的抽象方法 create(int, Inventory, RegistryFriendlyByteBuf) 让
     * 客户端反序列化与服务端构造走同一个工厂(这里 buf 恒空,忽略即可)。
     */
    public static final DeferredHolder<MenuType<?>, MenuType<VirtualCraftMenu>> VIRTUAL_CRAFT_MENU =
            MENUS.register("virtual_craft",
                    () -> IMenuTypeExtension.create((id, inv, buf) -> new VirtualCraftMenu(id, inv)));

    // ===================== Payload 定义(嵌套 record) =====================

    /** C→S:请求打开虚拟工作台菜单(空体)。 */
    public record OpenVirtualCraft() implements CustomPacketPayload {
        public static final Type<OpenVirtualCraft> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(McAgentMod.MODID, "open_virtual_craft"));
        /** StreamCodec.unit(V) —— javap 实测存在;空体包无需编解码字段。 */
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenVirtualCraft> STREAM_CODEC =
                StreamCodec.unit(new OpenVirtualCraft());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S→C:虚拟工作台已打开 —— 携带 containerId 与持久网格 9 槽(槽 1..9)快照。
     * 客户端 handler 收到后构造同 id 的 {@link VirtualCraftMenu} 挂到
     * {@code player.containerMenu},绕过 {@code handleOpenScreen} 只认 Screen 注册的缺口;
     * 网格随包先到,界面立刻有内容,后续增量同步走原版 SetSlot/SetContent(containerId 匹配)。
     */
    public record OpenVirtualWorkbench(int containerId, List<ItemStack> grid) implements CustomPacketPayload {
        public static final Type<OpenVirtualWorkbench> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(McAgentMod.MODID, "open_virtual_workbench"));
        /**
         * javap 实测:网格列表直接复用原版容器同步的同一款编解码
         * {@code ItemStack.OPTIONAL_LIST_STREAM_CODEC}(
         * javap: public static final StreamCodec&lt;RegistryFriendlyByteBuf, java.util.List&lt;ItemStack&gt;&gt;;
         * ClientboundContainerSetContentPacket 的 items 字段读写用的就是它 ——
         * 空格编码为空 ItemStack,9 格有空位不炸);containerId 用 ByteBufCodecs.VAR_INT(javap 实测)。
         * composite 2 参在 6 参上限内。
         */
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenVirtualWorkbench> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OpenVirtualWorkbench::containerId,
                        ItemStack.OPTIONAL_LIST_STREAM_CODEC, OpenVirtualWorkbench::grid,
                        OpenVirtualWorkbench::new);

        /**
         * 服务端打包:菜单槽 1..9(3x3 网格)逐格 copy 成快照。
         * (javap:AbstractContainerMenu.getSlot(int) → Slot、Slot.getItem() → ItemStack;
         * copy 是因为包体编码可能发生在 netty 线程,不能引用会随点击变化的活 ItemStack。)
         */
        public static OpenVirtualWorkbench of(int containerId, AbstractContainerMenu menu) {
            List<ItemStack> grid = new ArrayList<>(GRID_SLOTS);
            for (int i = 1; i <= GRID_SLOTS && i < menu.slots.size(); i++) {
                grid.add(menu.getSlot(i).getItem().copy());
            }
            return new OpenVirtualWorkbench(containerId, List.copyOf(grid));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S:请求冶炼。count <= 0 表示「有多少炼多少」,服务端按背包存量截断。 */
    public record SmeltRequest(String itemId, int count) implements CustomPacketPayload {
        public static final Type<SmeltRequest> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(McAgentMod.MODID, "smelt_request"));
        /** composite 2 参重载 + ByteBufCodecs.STRING_UTF8 / VAR_INT(javap 实测)。 */
        public static final StreamCodec<RegistryFriendlyByteBuf, SmeltRequest> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, SmeltRequest::itemId,
                        ByteBufCodecs.VAR_INT, SmeltRequest::count,
                        SmeltRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S:取消当前冶炼会话(原料燃料返还背包)。 */
    public record SmeltCancel() implements CustomPacketPayload {
        public static final Type<SmeltCancel> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(McAgentMod.MODID, "smelt_cancel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SmeltCancel> STREAM_CODEC =
                StreamCodec.unit(new SmeltCancel());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S:v2 收取虚拟熔炉产物槽堆积的产物(服务端全部入包,满则落地;收完则结束会话)。 */
    public record CollectSmelt() implements CustomPacketPayload {
        public static final Type<CollectSmelt> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(McAgentMod.MODID, "collect_smelt"));
        /** StreamCodec.unit(V) —— javap 实测存在;空体包无需编解码字段(同 SmeltCancel)。 */
        public static final StreamCodec<RegistryFriendlyByteBuf, CollectSmelt> STREAM_CODEC =
                StreamCodec.unit(new CollectSmelt());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S→C:冶炼状态同步,字段与 {@link SmeltClientState.SmeltView} 一一对应。
     * 11 字段超出 composite 的 6 参上限,改用
     * CustomPacketPayload.codec(StreamMemberEncoder, StreamDecoder)(javap 实测存在)
     * 手写编解码;RegistryFriendlyByteBuf 继承 FriendlyByteBuf,writeUtf/readUtf/
     * writeVarInt/readVarInt/writeBoolean/readBoolean 均 javap 实测存在。
     */
    public record SmeltStateSync(String inputId, int inputCount, String fuelId, int fuelCount,
            String outputId, int outputCount, int progressTicks, int totalTicks,
            boolean active, boolean done, String message) implements CustomPacketPayload {
        public static final Type<SmeltStateSync> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(McAgentMod.MODID, "smelt_state_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SmeltStateSync> STREAM_CODEC =
                CustomPacketPayload.codec(ModNetworking::encodeSync, ModNetworking::decodeSync);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void encodeSync(SmeltStateSync p, RegistryFriendlyByteBuf buf) {
        buf.writeUtf(p.inputId());
        buf.writeVarInt(p.inputCount());
        buf.writeUtf(p.fuelId());
        buf.writeVarInt(p.fuelCount());
        buf.writeUtf(p.outputId());
        buf.writeVarInt(p.outputCount());
        buf.writeVarInt(p.progressTicks());
        buf.writeVarInt(p.totalTicks());
        buf.writeBoolean(p.active());
        buf.writeBoolean(p.done());
        buf.writeUtf(p.message());
    }

    private static SmeltStateSync decodeSync(RegistryFriendlyByteBuf buf) {
        return new SmeltStateSync(buf.readUtf(), buf.readVarInt(),
                buf.readUtf(), buf.readVarInt(),
                buf.readUtf(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(),
                buf.readUtf());
    }

    // ===================== 契约:客户端工具层调用 =====================

    /**
     * C→S:请求打开虚拟工作台菜单。服务端收到后直接给 ServerPlayer.containerMenu 赋值并
     * 回发 {@link OpenVirtualWorkbench}(不再走 player.openMenu —— 它发的
     * ClientboundOpenScreenPacket 会因 {@code mcagent:virtual_craft} 未注册 Screen 而在
     * 客户端 handleOpenScreen/MenuScreens.create 处丢失 containerMenu 赋值,见类 javadoc)。
     */
    public static void openVirtualCraft() {
        PacketDistributor.sendToServer(new OpenVirtualCraft());
    }

    /** C→S:请求冶炼:服务端校验背包材料并真实扣除,启动/并入虚拟熔炉会话。 */
    public static void requestSmelt(String itemId, int count) {
        PacketDistributor.sendToServer(new SmeltRequest(itemId, count));
    }

    /** C→S:取消当前冶炼会话(原料燃料返还背包)。 */
    public static void cancelSmelt() {
        PacketDistributor.sendToServer(new SmeltCancel());
    }

    /**
     * C→S:v2 收取虚拟熔炉产物槽堆积的产物(客户端可调用)。
     * 服务端把产物全部入包(满则落地);活跃会话收取后继续烧,done 会话收完即结束。
     */
    public static void collectSmelt() {
        PacketDistributor.sendToServer(new CollectSmelt());
    }

    // ===================== 注册(McAgentMod 构造器两侧调用) =====================

    private static boolean registered = false;

    public static void register(IEventBus modBus) {
        if (registered) {
            return; // 防重复装载(内嵌服与客户端共用同一 mod 实例,理论只调一次,防御性)
        }
        registered = true;

        MENUS.register(modBus); // 菜单注册须 MOD 总线,且两侧都要(注册表两侧对齐)

        // 网络:注册 RegisterPayloadHandlersEvent —— javap:
        //   RegisterPayloadHandlersEvent extends Event implements IModBusEvent → MOD 总线事件
        //   event.registrar(String) → PayloadRegistrar(version "1")
        modBus.addListener(ModNetworking::onRegisterPayloads);

        // 冶炼会话 tick + 玩家登入登出持久化(熔炉会话 + 持久工作台网格):游戏总线
        // (NeoForge.EVENT_BUS,javap: NeoForge.EVENT_BUS 为 public static IEventBus;ServerTickEvent$Post /
        // PlayerEvent$PlayerLoggedOutEvent / PlayerEvent$PlayerLoggedInEvent 均 javap 实测)
        NeoForge.EVENT_BUS.addListener(ModNetworking::onServerTick);
        NeoForge.EVENT_BUS.addListener(ModNetworking::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ModNetworking::onPlayerLoggedOut);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1"); // 协议版本 1
        // playToServer(Type, StreamCodec<? super RegistryFriendlyByteBuf, T>, IPayloadHandler) javap 实测。
        // PayloadRegistrar 默认 HandlerThread.MAIN(NeoForge 源码第 29 行):
        // 下列处理器均已被包裹为主线程执行,库存操作天然主线程安全。
        registrar.playToServer(OpenVirtualCraft.TYPE, OpenVirtualCraft.STREAM_CODEC, ModNetworking::handleOpenVirtualCraft);
        registrar.playToServer(SmeltRequest.TYPE, SmeltRequest.STREAM_CODEC, ModNetworking::handleSmeltRequest);
        registrar.playToServer(SmeltCancel.TYPE, SmeltCancel.STREAM_CODEC, ModNetworking::handleSmeltCancel);
        registrar.playToServer(CollectSmelt.TYPE, CollectSmelt.STREAM_CODEC, ModNetworking::handleSmeltCollect);
        registrar.playToClient(SmeltStateSync.TYPE, SmeltStateSync.STREAM_CODEC, ModNetworking::handleSmeltSync);
        // S→C:虚拟工作台打开(专用服上注册 playToClient 只是声明协议;handler 仅客户端会执行)。
        // 注意写成 lambda 而非方法引用:javac 21.0.2 在【缺类】的精简验证 classpath 下
        // (如缺 net.neoforged.api.distmarker.Dist 时,FMLEnvironment.dist 为 error-type),
        // 对「泛型字段 TYPE/STREAM_CODEC + 静态方法引用」的 playToClient 调用会触发
        // TransTypes.visitApply 的 ClassCastException(编译器 bug 模式);lambda 形态在
        // 精简与完整两种 classpath 下均可稳定编译,语义与行为完全一致。
        registrar.playToClient(OpenVirtualWorkbench.TYPE, OpenVirtualWorkbench.STREAM_CODEC,
                (payload, ctx) -> handleOpenVirtualWorkbench(payload, ctx));
    }

    // ===================== S 端 / C 端处理器 =====================

    /** S→C 发送(仅供服务端会话 VirtualSmelter 调用)。javap:sendToPlayer(ServerPlayer, CustomPacketPayload, ...)。 */
    public static void sendSmeltStateSync(ServerPlayer player, SmeltStateSync payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * 服务端:打开虚拟工作台(v2.1 —— 不走 openMenu,直接赋值 containerMenu + 自定义 payload)。
     *
     * <p>镜像 ServerPlayer.openMenu(反编译源码 1119-1167 行)除 OpenScreen 包外的全部步骤:</p>
     * <ol>
     *   <li>已有打开容器先 {@code player.doCloseContainer()}(v2.2 修复):静默关掉服务端
     *       旧菜单 —— javap 核对 ServerPlayer.doCloseContainer 为 public,只执行
     *       containerMenu.removed(player) 收尾并复位 inventoryMenu,<b>不发</b>任何包。
     *       v2.1 用 closeContainer() 会发 ClientboundContainerClosePacket(int),客户端
     *       ClientPacketListener.handleContainerClose 处理后会把刚 setScreen 的平铺界面
     *       一并关掉(诊断日志实证:连续数轮 opened→removed 循环后才成功打开);</li>
     *   <li>自维护 id({@link #VIRTUAL_MENU_IDS};javap -p:ServerPlayer.containerCounter/
     *       nextContainerCounter()/initMenu() 均 private,不可复用);</li>
     *   <li>构造 VirtualCraftMenu 后直接 {@code player.containerMenu = menu}
     *       (javap:Player.containerMenu 为 <b>public</b> 字段)。此后 ServerPlayer.tick
     *       (反编译源码 949 行,无条件 {@code this.containerMenu.broadcastChanges()})、
     *       点击处理(ServerGamePacketListenerImpl.handleContainerClick 校验
     *       {@code player.containerMenu.containerId == 包.containerId},源码 1659 行)
     *       全走本菜单,与 openMenu 后无差别;</li>
     *   <li>先发 {@link OpenVirtualWorkbench}(客户端切 containerMenu),再挂
     *       {@link VirtualMenuSynchronizer}:{@code setSynchronizer} 内部立即
     *       {@code sendAllDataToRemote()}(javap + 反编译 AbstractContainerMenu 129-147 行),
     *       全量 SetContent 在 payload 之后经同一条连接按序到达,客户端 containerId 已匹配,
     *       {@code handleContainerContent} 正常落地(结果槽 0/背包槽 10..45/光标一并初始化)。
     *       同一 TCP 流内服务端 send 的写序 = 客户端处理序,故顺序天然成立。</li>
     * </ol>
     *
     * <p>不调 player.initMenu:javap -p 核对为 <b>private</b>(其内部只做
     * addSlotListener(私有的 advancement 触发匿名监听器,非同步必需)+
     * setSynchronizer(私有匿名 ContainerSynchronizer,由本方法的
     * {@link VirtualMenuSynchronizer} 等价替代 —— 逐方法镜像 ServerPlayer 源码 228-260 行))。
     * 若在发 payload 前调 initMenu,其 SetContent 会因客户端 containerMenu 尚未切换而被
     * ClientPacketListener 的 containerId 相等校验丢弃(徒增噪音)—— 本实现靠
     * 「payload 先行」把全量包变成有效包,一个包都不浪费。</p>
     */
    private static void handleOpenVirtualCraft(OpenVirtualCraft payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            com.mcagent.McAgentMod.LOGGER.info("[mcagent] S:收到打开虚拟工作台请求,当前 containerMenu={}",
                    player.containerMenu == null ? "null" : player.containerMenu.getClass().getSimpleName());
            if (player.containerMenu != player.inventoryMenu) {
                // 静默关闭服务端旧菜单(javap:ServerPlayer.doCloseContainer 为 public):
                // 只做 containerMenu.removed(player) 收尾并复位 inventoryMenu,【不发】
                // ClientboundContainerClosePacket —— closeContainer() 发的那个包会在客户端
                // ClientPacketListener.handleContainerClose 里把刚 setScreen 的平铺界面关掉,
                // 造成"opened→removed"循环(诊断日志实证)。旧菜单的光标物品仍由
                // VirtualCraftMenu.removed 正常收尾(放回背包),材料网格保持持久不清空。
                player.doCloseContainer();
            }
            int containerId = VIRTUAL_MENU_IDS.incrementAndGet();
            VirtualCraftMenu menu = new VirtualCraftMenu(containerId, player.getInventory());
            player.containerMenu = menu;
            PacketDistributor.sendToPlayer(player, OpenVirtualWorkbench.of(containerId, menu));
            menu.setSynchronizer(new VirtualMenuSynchronizer(player));
            com.mcagent.McAgentMod.LOGGER.info("[mcagent] S:虚拟工作台已打开 containerId={}", containerId);
        }
    }

    /**
     * 客户端:收到 OpenVirtualWorkbench → 构造同 containerId 的 {@link VirtualCraftMenu}
     * 挂到 {@code player.containerMenu},并把 9 格网格写入槽 1..9。
     *
     * <p>槽位写入路径:Slot.set(ItemStack)(javap:public void set)→ container.setItem →
     * {@link VirtualCraftMenu.PersistentGrid#setItem} → menu.slotsChanged → CraftingMenu
     * slotChangedCraftingGrid 的 {@code if (!level.isClientSide)} 保护(CraftingMenu 反编译
     * 源码 61-69 行)→ 客户端纯无副作用。之后服务端每 tick 的
     * broadcastChanges 经 {@link VirtualMenuSynchronizer} 增量同步,增量包的 containerId
     * 与本菜单一致,ClientPacketListener.handleContainerSetSlot/handleContainerContent
     * (反编译源码 1251-1292 行)的 {@code containerId == player.containerMenu.containerId}
     * 校验通过,自然落地 —— WorkbenchView/CraftItemTool/HudOverlay 只依赖
     * {@code containerMenu instanceof VirtualCraftMenu} + 槽位读取,零改动即恢复工作。</p>
     *
     * <p>专用服安全:方法体内引用的 LocalPlayer 仅客户端 play 阶段收到本包时才会被 JVM
     * 加载并校验(专用服上本 handler 永不执行,HotSpot 校验器对未加载类型的约束惰性求值);
     * 与 NeoForge 常见的「公共类里写客户端分支」模式一致。</p>
     */
    private static void handleOpenVirtualWorkbench(OpenVirtualWorkbench payload, IPayloadContext ctx) {
        if (ctx.player() instanceof LocalPlayer player) {
            com.mcagent.McAgentMod.LOGGER.info("[mcagent] C:收到工作台同步 containerId={},格数={}",
                    payload.containerId(), payload.grid().size());
            // 与服务端同构的构造(clientSide 路径建临时 PersistentGrid,见 VirtualCraftMenu 构造器);
            // javap:Player.getInventory() → Inventory(VirtualCraftMenu 构造器第二参为 Inventory)
            VirtualCraftMenu menu = new VirtualCraftMenu(payload.containerId(), player.getInventory());
            player.containerMenu = menu; // javap:Player.containerMenu 为 public 字段
            List<ItemStack> grid = payload.grid();
            for (int i = 0; i < grid.size() && i < GRID_SLOTS; i++) {
                menu.getSlot(i + 1).set(grid.get(i)); // 槽 1..9(槽 0 是结果槽,由服务端同步)
            }
        }
    }

    private static void handleSmeltRequest(SmeltRequest payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            VirtualSmelter.handleRequest(player, payload.itemId(), payload.count());
        }
    }

    private static void handleSmeltCancel(SmeltCancel payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            VirtualSmelter.cancel(player);
        }
    }

    /** 服务端:v2 收取产物槽堆积的产物(入包,满则落地)。 */
    private static void handleSmeltCollect(CollectSmelt payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            VirtualSmelter.collect(player);
        }
    }

    /** 客户端:只落一个 volatile 快照,渲染/工具层随时读 SmeltClientState.current()。 */
    private static void handleSmeltSync(SmeltStateSync payload, IPayloadContext ctx) {
        SmeltClientState.set(new SmeltClientState.SmeltView(
                payload.inputId(), payload.inputCount(),
                payload.fuelId(), payload.fuelCount(),
                payload.outputId(), payload.outputCount(),
                payload.progressTicks(), payload.totalTicks(),
                payload.active(), payload.done(), payload.message()));
    }

    // ===================== 游戏总线事件 → 会话 =====================

    private static void onServerTick(ServerTickEvent.Post event) {
        VirtualSmelter.serverTick(event.getServer());
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            VirtualSmelter.onLogin(player);
            VirtualCraftMenu.onLogin(player); // v2:恢复持久工作台网格(mcagent_workbench_slot*)
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            VirtualSmelter.onLogout(player);
            VirtualCraftMenu.onLogout(player); // v2:持久化工作台网格(mcagent_workbench_slot*)
        }
    }

    // ===================== 服务端菜单同步器(镜像 ServerPlayer 私有匿名实现) =====================

    /**
     * 给 {@link VirtualCraftMenu} 接通原版增量同步通道。背景:javap -p 实测
     * ServerPlayer.initMenu/private containerSynchronizer 均 private 不可复用
     * (ServerGamePacketListenerImpl 也不实现 ContainerSynchronizer,javap implements 列表无它),
     * 而没有 synchronizer 时 AbstractContainerMenu 的
     * synchronizeSlotToRemote/synchronizeCarriedToRemote/synchronizeDataSlotToRemote 全部
     * {@code if (this.synchronizer != null)} 静默跳过(反编译 228/240/251 行)→ 客户端永远
     * 收不到增量包。本类逐方法镜像 ServerPlayer 源码 228-260 行的匿名
     * containerSynchronizer(javap 四个抽象方法签名与实现一致):
     * <ul>
     *   <li>sendInitialData → ClientboundContainerSetContentPacket(int,int,NonNullList&lt;ItemStack&gt;,ItemStack)
     *       (javap 实测构造器)+ dataSlots 逐个 ClientboundContainerSetDataPacket(int,int,int)(javap 实测);</li>
     *   <li>sendSlotChange → ClientboundContainerSetSlotPacket(int,int,int,ItemStack)(javap 实测构造器);</li>
     *   <li>sendCarriedChange → 同上,containerId=-1 / slot=-1(镜像 vanilla:客户端
     *       handleContainerSetSlot 的 {@code containerId == -1} 分支 → containerMenu.setCarried);
     *       javap:ClientboundContainerSetSlotPacket.CARRIED_ITEM = -1;</li>
     *   <li>sendDataChange → ClientboundContainerSetDataPacket(VirtualCraftMenu 基于
     *       CraftingMenu,无任何 addDataSlot,实际不会触发;照 vanilla 语义转发)。</li>
     * </ul>
     * stateId 一律用 menu.incrementStateId()(javap:public int,内部 {@code state+1 & 32767}),
     * 与 vanilla 匿名实现逐字节一致。
     */
    private static final class VirtualMenuSynchronizer implements ContainerSynchronizer {
        private final ServerPlayer player;

        VirtualMenuSynchronizer(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public void sendInitialData(AbstractContainerMenu menu, NonNullList<ItemStack> items,
                ItemStack carried, int[] stateData) {
            // javap:ServerCommonPacketListenerImpl.send(Packet<?>) 为 public
            this.player.connection.send(new ClientboundContainerSetContentPacket(
                    menu.containerId, menu.incrementStateId(), items, carried));
            for (int i = 0; i < stateData.length; i++) {
                this.player.connection.send(new ClientboundContainerSetDataPacket(menu.containerId, i, stateData[i]));
            }
        }

        @Override
        public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) {
            this.player.connection.send(new ClientboundContainerSetSlotPacket(
                    menu.containerId, menu.incrementStateId(), slot, stack));
        }

        @Override
        public void sendCarriedChange(AbstractContainerMenu menu, ItemStack stack) {
            this.player.connection.send(new ClientboundContainerSetSlotPacket(
                    -1, menu.incrementStateId(), -1, stack));
        }

        @Override
        public void sendDataChange(AbstractContainerMenu menu, int id, int value) {
            this.player.connection.send(new ClientboundContainerSetDataPacket(menu.containerId, id, value));
        }
    }

    private ModNetworking() {
    }
}
