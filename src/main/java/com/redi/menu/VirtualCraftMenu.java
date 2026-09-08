package com.redi.menu;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * v2 虚拟工作台菜单 —— 常驻、留物(参考 Sophisticated Backpacks 内置工作台思路):
 * 每个玩家服务端绑定一个【持久 3x3 合成容器】(UUID→容器表,见 {@link #WORKBENCHES}),
 * 网格里放进去的材料不因关界面/断线而退回;关掉再开看到同一份网格;
 * 产物只出现在结果槽(配方结算仍走原版 CraftingMenu.slotsChanged→slotChangedCraftingGrid),
 * 玩家点产物时经 ResultSlot.onTake 真实扣料。
 *
 * <p>槽位布局与原版 CraftingMenu 完全一致:结果槽 0,3x3 网格 1..9,玩家背包 10..45。</p>
 *
 * <p><b>实现机制(全部经 neoforge-21.1.249 反编译源码 sourcesWithNeoForge_1c3bf90a
 * 与 javap 对 compiledWithNeoForge_735b2cb249c24959c061acf2b26fe22f1b5efbf9_output.jar 核对):</b></p>
 *
 * <p>父类构造器会自建网格并把 10 个槽位焊死在自建的临时容器上
 * (CraftingMenu.java 源码第 28/42/46 行):
 * {@code private final CraftingContainer craftSlots = new TransientCraftingContainer(this, 3, 3);},
 * 构造器里 {@code addSlot(new ResultSlot(player, this.craftSlots, this.resultSlots, 0, 124, 35))}
 * 与 9 个 {@code new Slot(this.craftSlots, ...)}。要让网格内容持久化,必须在构造完成后把
 * 三处引用换绑到持久容器(反射写字段;均为【实例 final 字段】—— JDK 21 对非 record 的
 * 实例 final 字段,setAccessible(true) 后允许 Field.set 写入,仅 static final 被拒):
 * <ol>
 *   <li>{@code CraftingMenu.craftSlots}(javap: private final CraftingContainer)——
 *       slotsChanged/quickMoveStack/配方书/结果槽重算全读它;</li>
 *   <li>{@code ResultSlot.craftSlots}(javap: private final CraftingContainer)——
 *       onTake 扣料(逐格 removeItem 1 个 + 剩余物容器返还)读它;</li>
 *   <li>{@code Slot.container}(javap: public final Container,槽 1..9)——
 *       网格槽位实际存取与客户端同步的容器。</li>
 * </ol>
 * 槽 0 的 Slot.container 是 resultSlots(每菜单的结果展示容器,javap: ResultContainer),
 * 保持原样不动。</p>
 *
 * <p><b>removed(Player) 覆盖为「不退料」:</b>父类 CraftingMenu.removed(Player) =
 * super.removed(光标物品)+ {@code clearContainer(player, this.craftSlots)}(源码 129-131 行,
 * 网格材料退回背包/掉落)。本类不再调 super,只手动复刻 AbstractContainerMenu.removed
 * (源码 546-559 行)的光标物品处理,网格材料原样留在持久容器里。</p>
 *
 * <p><b>持久化:</b>登出时 9 格逐格写 player.getPersistentData(),键
 * {@code redi_workbench_slot0} .. {@code redi_workbench_slot8}
 * (javap: ItemStack.save(HolderLookup.Provider, Tag) / ItemStack.parse(HolderLookup.Provider, Tag)
 * → Optional,persistentData 随 player.dat 存档);登入恢复到持久容器。
 * 菜单重复打开经 OpenVirtualCraft → 构造器 {@link #workbenchOf} 拿到同一实例,网格内容原样。</p>
 */
public class VirtualCraftMenu extends CraftingMenu {

    /** 持久网格的存档键(redi_workbench_slot0 .. redi_workbench_slot8)。 */
    public static final String NBT_ROOT = "redi_workbench";

    /** 3x3 网格 = 9 格。 */
    private static final int GRID_SIZE = 9;

    /** UUID -> 持久网格(仅服务端用;登录时建,登出时序列化并摘除,菜单开关不换实例)。 */
    private static final Map<UUID, PersistentGrid> WORKBENCHES = new ConcurrentHashMap<>();

    // ---- 反射换绑用 Field(静态缓存一次 setAccessible)----
    private static final Field CRAFT_MENU_CRAFT_SLOTS;
    private static final Field RESULT_SLOT_CRAFT_SLOTS;
    private static final Field SLOT_CONTAINER;

    static {
        try {
            // javap -p:private final net.minecraft.world.inventory.CraftingContainer craftSlots
            CRAFT_MENU_CRAFT_SLOTS = CraftingMenu.class.getDeclaredField("craftSlots");
            CRAFT_MENU_CRAFT_SLOTS.setAccessible(true);
            // javap -p:private final net.minecraft.world.inventory.CraftingContainer craftSlots
            RESULT_SLOT_CRAFT_SLOTS = ResultSlot.class.getDeclaredField("craftSlots");
            RESULT_SLOT_CRAFT_SLOTS.setAccessible(true);
            // javap -p:public final net.minecraft.world.Container container
            SLOT_CONTAINER = Slot.class.getDeclaredField("container");
            SLOT_CONTAINER.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public VirtualCraftMenu(int containerId, Inventory playerInv) {
        super(containerId, playerInv, virtualAccess(playerInv));
        boolean clientSide = playerInv.player.level().isClientSide;
        PersistentGrid grid = clientSide ? new PersistentGrid(null) : workbenchOf(playerInv.player);
        rebind(grid);
        if (!clientSide) {
            // 重开菜单时按网格现状重算结果槽(重复打开 = 同一持久容器,产物仍可点收)。
            // slotsChanged → slotChangedCraftingGrid 内部有 !level.isClientSide 保护(CraftingMenu 源码 61-69 行),
            // 客户端构造器即使走了也无副作用;这里服务端才调。结果槽状态由 ModNetworking 打开链路
            // 带到客户端:OpenVirtualWorkbench payload(网格 1..9)+ 挂同步器后 sendAllDataToRemote()
            // 的全量 SetContent(含槽 0,在 payload 之后按序到达、containerId 已匹配)。
            this.slotsChanged(grid);
        }
    }

    /** 虚拟容器没有方块位置,菜单永远有效(契约:恒 true)。 */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /**
     * v2 核心:关界面/断线【不退料】。不调 super.removed(那是 CraftingMenu.removed,
     * 会 clearContainer(player, this.craftSlots) 把网格材料退回背包 —— CraftingMenu 源码 129-131 行),
     * 只复刻 AbstractContainerMenu.removed(源码 546-559 行)的光标物品处理:
     * 服务端把玩家拿在光标上的物品放回背包(已断线则 player.drop 落地),防止凭空消失。
     * 网格材料原样留在 {@link PersistentGrid},下次打开还在。
     */
    @Override
    public void removed(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ItemStack carried = this.getCarried();
            if (!carried.isEmpty()) {
                if (player.isAlive() && !serverPlayer.hasDisconnected()) {
                    // javap:Inventory#placeItemBackInInventory —— 背包满自动 player.drop
                    player.getInventory().placeItemBackInInventory(carried);
                } else {
                    player.drop(carried, false);
                }
                this.setCarried(ItemStack.EMPTY);
            }
        }
    }

    // ===================== 持久容器与换绑 =====================

    /** 取(或建)该玩家的持久网格;重复打开菜单绑同一实例。 */
    private static PersistentGrid workbenchOf(Player player) {
        return WORKBENCHES.computeIfAbsent(player.getUUID(), id -> new PersistentGrid(null));
    }

    /** 把菜单内三处对临时网格的引用换绑到持久容器(见类 javadoc 的 1/2/3)。 */
    private void rebind(PersistentGrid grid) {
        setField(CRAFT_MENU_CRAFT_SLOTS, this, grid);
        setField(RESULT_SLOT_CRAFT_SLOTS, this.getSlot(0), grid); // 槽 0 = ResultSlot(javap: getSlot(int) → Slot)
        for (int i = 1; i <= GRID_SIZE; i++) {                    // 槽 1..9 = 3x3 网格
            setField(SLOT_CONTAINER, this.getSlot(i), grid);
        }
        grid.bind(this);
    }

    private static void setField(Field field, Object owner, Object value) {
        try {
            field.set(owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("VirtualCraftMenu 反射换绑失败: " + field, e);
        }
    }

    // ===================== 登入登出持久化(net 层事件调用) =====================

    /** 登出:持久网格 9 格逐格写 persistentData(随 player.dat 存档),内存表摘除。 */
    public static void onLogout(ServerPlayer player) {
        PersistentGrid grid = WORKBENCHES.remove(player.getUUID());
        if (grid == null) {
            return; // 本局从未打开过工作台:网格为空,无需存档
        }
        CompoundTag tag = player.getPersistentData();
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack stack = grid.getItem(i);
            String key = NBT_ROOT + "_slot" + i;
            if (stack.isEmpty()) {
                tag.remove(key);
            } else {
                // javap:public Tag save(HolderLookup.Provider, Tag)
                tag.put(key, stack.save(player.registryAccess(), new CompoundTag()));
            }
        }
    }

    /** 登入:从 persistentData 恢复网格内容(未绑菜单,不触发配方结算)。 */
    public static void onLogin(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData();
        PersistentGrid grid = workbenchOf(player);
        for (int i = 0; i < GRID_SIZE; i++) {
            String key = NBT_ROOT + "_slot" + i;
            if (tag.contains(key, Tag.TAG_COMPOUND)) { // javap:public static final byte TAG_COMPOUND
                // javap:public static Optional<ItemStack> parse(HolderLookup.Provider, Tag)
                Optional<ItemStack> parsed = ItemStack.parse(player.registryAccess(), tag.getCompound(key));
                if (parsed.isPresent() && !parsed.get().isEmpty()) {
                    grid.setItem(i, parsed.get()); // 未绑菜单,不会触发配方结算
                }
                tag.remove(key);
            }
        }
    }

    // ===================== 嵌套类 =====================

    /**
     * 每玩家一份的持久 3x3 网格容器:SimpleContainer 底座 + CraftingContainer 语义。
     * 逐条镜像 TransientCraftingContainer(neoforge 反编译源码逐行核对):
     * <ul>
     *   <li>setItem/removeItem 后回调 {@code menu.slotsChanged(this)} —— 触发 CraftingMenu
     *       的结果槽重算(slotsChanged 忽略参数、直接读换绑后的 craftSlots);</li>
     *   <li>stillValid 恒 true;</li>
     *   <li>getItems 返回全 9 格按序拷贝(含空格)—— CraftingContainer.asPositionedCraftInput
     *       的 default 实现 → CraftingInput.ofPositioned(getWidth, getHeight, getItems)
     *       按索引消费(ResultSlot.onTake 用 left/top 折算回容器绝对索引),不能滤空格。</li>
     * </ul>
     * boundMenu 为 null 表示未打开(客户端临时实例/登入恢复期),此时只改数据不回调。
     */
    public static final class PersistentGrid extends SimpleContainer implements CraftingContainer {

        private AbstractContainerMenu boundMenu;

        public PersistentGrid(AbstractContainerMenu boundMenu) {
            super(GRID_SIZE); // javap:public SimpleContainer(int)
            this.boundMenu = boundMenu;
        }

        void bind(AbstractContainerMenu menu) {
            this.boundMenu = menu;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            super.setItem(slot, stack);
            if (this.boundMenu != null) {
                this.boundMenu.slotsChanged(this); // 镜像 TransientCraftingContainer.setItem
            }
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack taken = super.removeItem(slot, amount);
            if (!taken.isEmpty() && this.boundMenu != null) {
                this.boundMenu.slotsChanged(this); // 镜像 TransientCraftingContainer.removeItem
            }
            return taken;
        }

        @Override
        public int getWidth() {
            return 3;
        }

        @Override
        public int getHeight() {
            return 3;
        }

        /** 全 9 格按序拷贝(含空格),语义同 TransientCraftingContainer.getItems。
         *  返回类型须为 NonNullList(父类 SimpleContainer.getItems 的窄化覆盖,javap:
         *  public NonNullList&lt;ItemStack&gt; getItems()),NonNullList 是 List 子类,
         *  CraftingContainer 接口的 default asPositionedCraftInput 照常按索引消费。 */
        @Override
        public NonNullList<ItemStack> getItems() {
            NonNullList<ItemStack> all = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
            for (int i = 0; i < this.getContainerSize(); i++) {
                all.set(i, this.getItem(i));
            }
            return all;
        }

        @Override
        public boolean stillValid(Player player) {
            return true; // 镜像 TransientCraftingContainer.stillValid
        }
    }

    /**
     * 「空位置」访问器:不绑定 Level/BlockPos,evaluate 直接执行 lambda。
     * javap 核对(net.minecraft.world.inventory.ContainerLevelAccess):
     * {@code public abstract <T> Optional<T> evaluate(BiFunction<Level, BlockPos, T>)},
     * 默认 void execute(BiConsumer) 经由 evaluate 实现 —— 覆盖 evaluate 即覆盖全部路径。
     * Level 用玩家当前所在(Inventory.player 为 public final 字段,javap 实测),
     * 服务端 slotsChanged 内部本就有 {@code if (!level.isClientSide)} 保护,客户端执行无副作用。
     */
    private static ContainerLevelAccess virtualAccess(Inventory playerInv) {
        return new ContainerLevelAccess() {
            @Override
            public <T> Optional<T> evaluate(BiFunction<Level, BlockPos, T> action) {
                return Optional.of(action.apply(playerInv.player.level(), BlockPos.ZERO));
            }
        };
    }
}
