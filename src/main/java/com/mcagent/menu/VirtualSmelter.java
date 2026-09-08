package com.mcagent.menu;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mcagent.net.ModNetworking;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;

/**
 * v2 虚拟熔炉 —— 每个玩家一个服务端冶炼会话(参考 Sophisticated Backpacks
 * 「界面即机器」思路:没有真实熔炉方块,材料从玩家背包真实扣除)。
 *
 * <p>v2 变更:产物【不再自动入包】。烧好的产物堆进会话「产物槽」(outputItem/produced
 * 累加),等玩家在手机工作台视图点收取(C→S {@code CollectSmelt} →
 * {@link #collect})后一次性流入背包(满则落地);input 烧完 done=true 后会话
 * 【保留在表内】直到产物被收取(collect)或取消(cancel)。</p>
 *
 * <p>设计(全部签名经 javap 对 compiledWithNeoForge_735b2cb249c24959c061acf2b26fe22f1b5efbf9_output.jar
 * + neoforge-21.1.249.jar 核对):</p>
 * <ul>
 *   <li>会话表:{@code UUID -> Session},static ConcurrentHashMap;handler 与
 *       ServerTickEvent.Post 均在服务端主线程执行(PayloadRegistrar 默认
 *       HandlerThread.MAIN,NeoForge 源码 PayloadRegistrar.java 第 29 行),库存操作天然主线程。</li>
 *   <li>配方:RecipeManager.getRecipeFor(RecipeType.SMELTING, SingleRecipeInput, Level)
 *       查产物;查不到配方绝不启动会话。</li>
 *   <li>燃料:coal -> charcoal -> 背包内任意 AbstractFurnaceBlockEntity.isFuel(stack)
 *       (javap: public static boolean isFuel(ItemStack))。时序换算沿用原版数值:
 *       每件 200 tick(SMELT_DURATION)、每份燃料 1600 tick(FUEL_BURN)= 8 件/份,
 *       开炉至少备 max(2, ceil(n/8)) 份,不够直接失败(不动背包)。</li>
 *   <li>tick:ServerTickEvent.Post(MOD 总线在 ModNetworking.register 挂 NeoForge.EVENT_BUS)
 *       推进进度;每 10 tick 向属主发 SmeltStateSync(含 outputCount=产物槽堆积数);
 *       完成一件只累加产物槽,不入包。</li>
 *   <li>收取:{@link #collect} 把产物槽全部入包(placeItemBackInInventory,满则
 *       player.drop 落地,javap/源码 Inventory 297 行);活跃会话收取后继续烧,
 *       done 会话收完即结束出表。cancel 返还原料+燃料,已有产物一并返还。</li>
 *   <li>持久化:登出把会话写 player.getPersistentData()(CompoundTag,javap 确认
 *       Entity.getPersistentData() 存在并随 player.dat 存档;产物槽数量
 *       {@code mcagent_smelt_produced} 与 done 标记 {@code mcagent_smelt_done} 均在范围内),
 *       登入恢复继续 tick 或等待收取。</li>
 * </ul>
 */
public final class VirtualSmelter {

    /** 每件冶炼耗时(tick)= 原版 200。 */
    public static final int SMELT_DURATION_TICKS = 200;
    /** 每份燃料燃烧时长(tick)= 原版煤 1600 => 8 件/份(1600/200)。 */
    public static final int FUEL_BURN_TICKS = 1600;
    /** 每份燃料可冶炼件数 = 1600/200 = 8。 */
    public static final int ITEMS_PER_FUEL = FUEL_BURN_TICKS / SMELT_DURATION_TICKS;
    /** 状态同步间隔(tick)。 */
    private static final int SYNC_INTERVAL_TICKS = 10;

    /** player.getPersistentData() 里的存档键前缀。 */
    private static final String NBT_ROOT = "mcagent_smelt";

    /** 每玩家一个会话;仅服务端主线程读写(ConcurrentHashMap 仅防御性)。 */
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** 服务端冶炼会话。全部字段仅主线程访问。 */
    private static final class Session {
        Item inputItem;
        int inputCount;
        Item fuelItem;
        int fuelCount; // 未点燃的整份燃料
        int burnTicks; // 当前燃烧中的这份燃料剩余 tick(0=没有燃烧中的燃料)
        Item outputItem;
        int perOutput;   // 每件原料的产物个数(通常 1)
        int produced;    // 产物槽堆积件数(待收取;collect 时一次性入包,v2 不自动入包)
        int progressTicks;
        boolean done;    // 烧完(done=true 后会话仍留在表内等待收取)
        String message = "";
    }

    private VirtualSmelter() {
    }

    // ===================== 入口(net 层调用) =====================

    /**
     * C→S 请求冶炼:先全部校验,校验通过才真实扣料并启动/并入会话。
     * count <= 0 表示「背包里有多少炼多少」;count 会被背包实际存量截断。
     */
    public static void handleRequest(ServerPlayer player, String itemId, int count) {
        // ---- 1. 物品与配方校验(未动背包)----
        Item input = parseItem(itemId);
        if (input == null) {
            syncError(player, "无法识别物品: " + itemId);
            return;
        }
        String inputId = idOf(input);
        SmeltingRecipe recipe = findSmelting(player, input);
        if (recipe == null) {
            syncError(player, inputId + " 没有可用的熔炼配方,无法冶炼。");
            return;
        }
        ItemStack result = recipe.getResultItem(player.registryAccess());
        if (result == null || result.isEmpty()) {
            syncError(player, inputId + " 的熔炼配方产物为空,拒绝冶炼。");
            return;
        }

        // ---- 2. 材料校验 ----
        int available = countItem(player, input);
        if (available <= 0) {
            syncError(player, "背包里没有 " + inputId + ",无法冶炼。");
            return;
        }
        int want = count <= 0 ? available : Math.min(count, available);
        int add = Math.min(want, available);
        if (add <= 0) {
            syncError(player, "请求数量无效。");
            return;
        }

        // ---- 3. 上一轮产物未收取则拒绝(防旧会话连产物一起被覆盖丢弃)----
        Session s = SESSIONS.get(player.getUUID());
        if (s != null && s.done && s.produced > 0) {
            s.message = "产物槽还有未收取的 " + idOf(s.outputItem) + " x" + s.produced
                    + ",请先点击收取再开新会话。";
            sync(player, s);
            return;
        }

        // ---- 4. 燃料校验(启动 vs 并入)----
        Item fuelItem;
        int addFuelUnits;
        if (s == null || s.done) {
            FuelPick pick = pickFuel(player, input);
            if (pick == null) {
                syncError(player, "背包里没有燃料(推荐煤炭 minecraft:coal),无法开炼。");
                return;
            }
            fuelItem = pick.item;
            int needUnits = Math.max(2, (add + ITEMS_PER_FUEL - 1) / ITEMS_PER_FUEL);
            if (pick.available < needUnits) {
                syncError(player, "燃料不足:炼 " + add + " 个 " + inputId + " 需要燃料 "
                        + fuelItemOrName(fuelItem) + " 至少 " + needUnits + " 个,背包只有 " + pick.available + " 个。");
                return;
            }
            addFuelUnits = needUnits;
        } else {
            if (s.inputItem != input) {
                syncError(player, "已有会话正在冶炼 " + idOf(s.inputItem) + ",请先 cancel_smelt 或等烧完。");
                return;
            }
            // 缺口燃料:按「会话内每件都要有 200 tick 燃烧配额」补足
            int haveCapacity = s.fuelCount * FUEL_BURN_TICKS + s.burnTicks;
            int needCapacity = (s.inputCount + add) * SMELT_DURATION_TICKS;
            int deficit = Math.max(0, needCapacity - haveCapacity);
            addFuelUnits = (deficit + FUEL_BURN_TICKS - 1) / FUEL_BURN_TICKS;
            if (addFuelUnits > 0) {
                int haveFuel = countItem(player, s.fuelItem);
                if (haveFuel >= addFuelUnits) {
                    fuelItem = s.fuelItem;
                } else if (s.fuelCount == 0 && s.burnTicks == 0) {
                    FuelPick pick = pickFuel(player, input); // 旧燃料耗尽,允许换燃料种类
                    if (pick == null || pick.available < addFuelUnits) {
                        syncError(player, "燃料不足:还需要燃料 " + addFuelUnits + " 个(可烧 " + add + " 件),请补充煤炭等燃料。");
                        return;
                    }
                    fuelItem = pick.item;
                } else {
                    syncError(player, "燃料不足:还需要 " + fuelItemOrName(s.fuelItem) + " "
                            + addFuelUnits + " 个(身上还有该会话燃料,种类需一致)。");
                    return;
                }
            } else {
                fuelItem = s.fuelItem; // 燃料足够,只补料
            }
        }

        // ---- 5. 校验全部通过:真实扣料(先扣后建会话,任何失败都在扣料前返回)----
        int removedInput = removeItems(player, input, add);
        if (removedInput < add) { // 理论不可达(刚清点过),防御:把扣多的退回
            if (removedInput > 0) {
                giveBack(player, input, removedInput);
            }
            syncError(player, "背包材料扣取失败,会话未启动。");
            return;
        }
        if (addFuelUnits > 0 && removeItems(player, fuelItem, addFuelUnits) < addFuelUnits) {
            giveBack(player, input, add); // 燃料扣失败:退回原料
            syncError(player, "燃料扣取失败,会话未启动。");
            return;
        }

        // ---- 6. 启动/并入会话 ----
        boolean fresh = (s == null || s.done); // done 会话已无堆积产物(第 3 步挡过),可安全覆盖
        if (fresh) {
            s = new Session();
            s.inputItem = input;
            s.inputCount = 0;
            s.fuelItem = fuelItem;
            s.fuelCount = 0;
            s.burnTicks = 0;
            s.outputItem = result.getItem();
            s.perOutput = Math.max(1, result.getCount());
            s.produced = 0;
            s.progressTicks = 0;
            s.done = false;
            SESSIONS.put(player.getUUID(), s);
        }
        s.inputCount += add;
        s.fuelCount += addFuelUnits;
        s.message = fresh
                ? "开始冶炼 " + inputId + " x" + s.inputCount + "(燃料 " + fuelItemOrName(s.fuelItem) + " x" + s.fuelCount
                        + ");产物将堆在产物槽,烧好后点收取。"
                : "已并入 " + inputId + " x" + add + ",当前共 " + s.inputCount + " 个";
        sync(player, s);
    }

    /** C→S 取消:返还原料+剩余燃料+产物槽已有产物,清会话,发 done 同步。 */
    public static void cancel(ServerPlayer player) {
        Session s = SESSIONS.remove(player.getUUID());
        if (s == null) {
            syncError(player, "当前没有进行中的冶炼会话。");
            return;
        }
        giveBack(player, s.inputItem, s.inputCount);
        int fuelBack = s.fuelCount + (s.burnTicks > 0 ? 1 : 0); // 燃烧中的那份按 1 个退
        if (fuelBack > 0) {
            giveBack(player, s.fuelItem, fuelBack);
        }
        int pileBack = s.produced; // v2:产物槽已有产物一并返还(满则落地)
        if (pileBack > 0) {
            giveBack(player, s.outputItem, pileBack);
        }
        sendSync(player, new ViewSpec("", 0, "", 0, idOf(s.outputItem), 0,
                0, SMELT_DURATION_TICKS, false, true,
                "已取消冶炼:" + idOf(s.inputItem) + " x" + s.inputCount + " 与剩余燃料已退回背包"
                        + (pileBack > 0 ? ",产物槽 " + idOf(s.outputItem) + " x" + pileBack + " 一并退回。": "。")));
    }

    /**
     * C→S 收取(v2 新增):把产物槽堆积的产物全部入包(满则落地)。
     * 活跃会话收完继续烧;done 会话收完即结束出表。
     */
    public static void collect(ServerPlayer player) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null || s.produced <= 0) {
            syncError(player, "产物槽当前没有可收取的产物。");
            return;
        }
        int collected = s.produced;
        giveBack(player, s.outputItem, collected); // Inventory#placeItemBackInInventory:背包满自动 player.drop 落地
        s.produced = 0;
        if (s.done) {
            // 全收完,会话结束出表;发终态视图(outputId 保留供客户端展示,done=true)
            SESSIONS.remove(player.getUUID(), s);
            sendSync(player, new ViewSpec("", 0, "", 0, idOf(s.outputItem), 0,
                    0, SMELT_DURATION_TICKS, false, true,
                    "已收取 " + idOf(s.outputItem) + " x" + collected + ",全部入包(背包满则落地);会话结束。"));
        } else {
            s.message = "已收取产物 " + idOf(s.outputItem) + " x" + collected + ",冶炼继续。";
            sync(player, s);
        }
    }

    // ===================== tick / 登入登出(游戏总线) =====================

    /** ServerTickEvent.Post:推进所有会话;每 10 tick 向属主同步(done 会话静止,跳过)。 */
    public static void serverTick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        boolean syncTick = server.getTickCount() % SYNC_INTERVAL_TICKS == 0;
        for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            Session s = e.getValue();
            if (player == null) {
                continue; // 理论不发生(登出已摘除),防御:进度冻结
            }
            if (s.done) {
                continue; // v2:done 会话保留在表内等待收取,不 tick
            }
            if (s.inputCount <= 0) {
                finish(player, s);
                continue;
            }
            if (s.burnTicks <= 0) {
                if (s.fuelCount > 0) {
                    s.fuelCount--;
                    s.burnTicks = FUEL_BURN_TICKS;
                } else {
                    s.progressTicks = 0;
                    String pause = "燃料耗尽,冶炼暂停;补充燃料后再次请求可续烧,产物槽 " + s.produced
                            + " 个产物待收取,cancel 可取回原料。";
                    if (!pause.equals(s.message)) {
                        s.message = pause;
                        sync(player, s); // 进入暂停时立即同步一次
                    }
                    continue;
                }
            }
            s.burnTicks--;
            s.progressTicks++;
            if (s.progressTicks >= SMELT_DURATION_TICKS) {
                s.progressTicks = 0;
                s.inputCount--;
                int out = s.perOutput;
                s.produced += out; // v2:只堆产物槽,不自动入包,等玩家 collect
                if (s.inputCount <= 0) {
                    finish(player, s);
                    continue;
                }
            }
            if (syncTick) {
                sync(player, s);
            }
        }
    }

    /** 登出:会话写入 persistentData(随 player.dat 存档),从内存摘除。产物槽数量与 done 标记均在存档范围。 */
    public static void onLogout(ServerPlayer player) {
        Session s = SESSIONS.remove(player.getUUID());
        if (s == null) {
            return;
        }
        CompoundTag tag = player.getPersistentData();
        tag.putString(NBT_ROOT + "_input", idOf(s.inputItem));
        tag.putInt(NBT_ROOT + "_input_cnt", s.inputCount);
        tag.putString(NBT_ROOT + "_fuel", idOf(s.fuelItem));
        tag.putInt(NBT_ROOT + "_fuel_cnt", s.fuelCount);
        tag.putInt(NBT_ROOT + "_burn", s.burnTicks);
        tag.putString(NBT_ROOT + "_output", idOf(s.outputItem));
        tag.putInt(NBT_ROOT + "_per_out", s.perOutput);
        tag.putInt(NBT_ROOT + "_produced", s.produced);
        tag.putInt(NBT_ROOT + "_progress", s.progressTicks);
        tag.putBoolean(NBT_ROOT + "_done", s.done);
        tag.putString(NBT_ROOT + "_msg", s.message);
    }

    /** 登入:从 persistentData 恢复会话,继续 tick(done+无堆积的完结会话不恢复)。 */
    public static void onLogin(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData();
        if (!tag.contains(NBT_ROOT + "_input")) {
            return;
        }
        boolean done = tag.getBoolean(NBT_ROOT + "_done");
        int inputCount = tag.getInt(NBT_ROOT + "_input_cnt");
        int produced = tag.getInt(NBT_ROOT + "_produced");
        Item input = parseItem(tag.getString(NBT_ROOT + "_input"));
        Item fuel = parseItem(tag.getString(NBT_ROOT + "_fuel"));
        Item output = parseItem(tag.getString(NBT_ROOT + "_output"));
        boolean itemsValid = input != null && fuel != null && output != null;
        if (!itemsValid) {
            // 物品丢失(如卸了附属):不能凭空结算,直接丢弃存档并通知
            clearNbt(tag);
            syncError(player, "冶炼会话数据无效(物品已不存在),已丢弃。");
            return;
        }
        if (done && produced <= 0) {
            // 已完结且产物槽无堆积:无需恢复(collect 也无东西可收)
            clearNbt(tag);
            return;
        }
        if (!done && inputCount <= 0) {
            // 活跃会话没料 = 数据无效
            clearNbt(tag);
            syncError(player, "冶炼会话数据无效,已丢弃。");
            return;
        }
        Session s = new Session();
        s.inputItem = input;
        s.inputCount = inputCount;
        s.fuelItem = fuel;
        s.fuelCount = tag.getInt(NBT_ROOT + "_fuel_cnt");
        s.burnTicks = tag.getInt(NBT_ROOT + "_burn");
        s.outputItem = output;
        s.perOutput = Math.max(1, tag.getInt(NBT_ROOT + "_per_out"));
        s.produced = produced;
        s.progressTicks = tag.getInt(NBT_ROOT + "_progress");
        s.done = done; // done=true(烧完待收取):serverTick 直接跳过,不会重复退燃料
        s.message = tag.getString(NBT_ROOT + "_msg");
        clearNbt(tag);
        SESSIONS.put(player.getUUID(), s);
        sync(player, s);
    }

    // ===================== 内部 =====================

    /**
     * 自然烧完:退回没用掉的燃料,发最终 done 同步。
     * v2:产物堆在产物槽【不入包】,会话保留在表内直到 collect(收完出表)或 cancel;
     * serverTick 对 done 会话直接跳过,燃料只退这一次。
     */
    private static void finish(ServerPlayer player, Session s) {
        s.done = true;
        int fuelBack = s.fuelCount + (s.burnTicks > 0 ? 1 : 0);
        if (fuelBack > 0) {
            giveBack(player, s.fuelItem, fuelBack);
            s.fuelCount = 0;
            s.burnTicks = 0;
        }
        s.message = "冶炼完成:共产出 " + idOf(s.outputItem) + " x" + s.produced
                + ",已堆放在产物槽,请在手机上点击收取。";
        sync(player, s);
    }

    /** 会话快照 -> S→C 同步(active = 未 done 且在表内;outputCount = 产物槽堆积件数)。 */
    private static void sync(ServerPlayer player, Session s) {
        int fuelShown = s.fuelCount + (s.burnTicks > 0 ? 1 : 0);
        sendSync(player, new ViewSpec(idOf(s.inputItem), s.inputCount,
                idOf(s.fuelItem), fuelShown, idOf(s.outputItem), s.produced,
                s.progressTicks, SMELT_DURATION_TICKS, !s.done, s.done, s.message));
    }

    /** 失败/提示同步:不改会话本身,只把 message 带给客户端;无会话则发空视图。 */
    private static void syncError(ServerPlayer player, String message) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null || s.done) {
            sendSync(player, new ViewSpec("", 0, "", 0, "", 0,
                    0, SMELT_DURATION_TICKS, false, false, message));
        } else {
            s.message = message;
            sync(player, s);
        }
    }

    /** 组包并经 net 层发给属主(包体字段与 SmeltClientState.SmeltView 一一对应)。 */
    private static void sendSync(ServerPlayer player, ViewSpec v) {
        ModNetworking.sendSmeltStateSync(player, new ModNetworking.SmeltStateSync(
                v.inputId(), v.inputCount(), v.fuelId(), v.fuelCount(),
                v.outputId(), v.outputCount(), v.progressTicks(), v.totalTicks(),
                v.active(), v.done(), v.message()));
    }

    /** 轻量参数对象,避免 11 参长签名到处重复。 */
    private record ViewSpec(String inputId, int inputCount, String fuelId, int fuelCount,
            String outputId, int outputCount, int progressTicks, int totalTicks,
            boolean active, boolean done, String message) {
    }

    private record FuelPick(Item item, int available) {
    }

    /** 查 SMELTING 配方(SingleRecipeInput 单材料输入);查不到返回 null。 */
    private static SmeltingRecipe findSmelting(ServerPlayer player, Item input) {
        Optional<RecipeHolder<SmeltingRecipe>> hit = player.level().getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(input)), player.level());
        return hit.map(RecipeHolder::value).orElse(null);
    }

    /** 燃料选择:coal -> charcoal -> 背包里任意 isFuel 物品(跳过待炼物本身)。 */
    private static FuelPick pickFuel(ServerPlayer player, Item input) {
        for (Item preferred : new Item[] {Items.COAL, Items.CHARCOAL}) {
            int n = countItem(player, preferred);
            if (n > 0) {
                return new FuelPick(preferred, n);
            }
        }
        Inventory inv = player.getInventory();
        for (List<ItemStack> list : List.of(inv.items, inv.armor, inv.offhand)) {
            for (ItemStack st : list) {
                if (!st.isEmpty() && st.getItem() != input && net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(st)) {
                    return new FuelPick(st.getItem(), countItem(player, st.getItem()));
                }
            }
        }
        return null;
    }

    private static String fuelItemOrName(Item fuel) {
        return idOf(fuel);
    }

    /** 清点某物品总数(主背包+盔甲+副手)。 */
    private static int countItem(ServerPlayer player, Item item) {
        int n = 0;
        Inventory inv = player.getInventory();
        for (List<ItemStack> list : List.of(inv.items, inv.armor, inv.offhand)) {
            for (ItemStack st : list) {
                if (!st.isEmpty() && st.getItem() == item) {
                    n += st.getCount();
                }
            }
        }
        return n;
    }

    /** 真实扣除某物品 amount 个,返回实际扣除数;扣过则 setChanged 触发同步。 */
    private static int removeItems(ServerPlayer player, Item item, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int removed = 0;
        Inventory inv = player.getInventory();
        for (List<ItemStack> list : List.of(inv.items, inv.armor, inv.offhand)) {
            for (int i = 0; i < list.size() && removed < amount; i++) {
                ItemStack st = list.get(i);
                if (st.isEmpty() || st.getItem() != item) {
                    continue;
                }
                int take = Math.min(st.getCount(), amount - removed);
                st.shrink(take);
                removed += take;
            }
        }
        if (removed > 0) {
            inv.setChanged();
        }
        return removed;
    }

    /** 整堆入包(满则 placeItemBackInInventory 内部 player.drop 落地)。collect/cancel/退燃料共用。 */
    private static void giveBack(ServerPlayer player, Item item, int count) {
        Inventory inv = player.getInventory();
        while (count > 0) {
            int n = Math.min(count, item.getDefaultMaxStackSize());
            inv.placeItemBackInInventory(new ItemStack(item, n));
            count -= n;
        }
    }

    /** 解析物品 id("minecraft:iron_ore" 或 "iron_ore");未注册返回 null。 */
    private static Item parseItem(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String s = id.trim().toLowerCase(Locale.ROOT);
        ResourceLocation rl = ResourceLocation.tryParse(s.indexOf(':') >= 0 ? s : "minecraft:" + s);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(rl); // DefaultedRegistry:未命中回退 air,已用 containsKey 挡掉
        if (item == Items.AIR && !"air".equals(rl.getPath())) {
            return null;
        }
        return item;
    }

    private static String idOf(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "unknown" : key.toString();
    }

    private static void clearNbt(CompoundTag tag) {
        tag.remove(NBT_ROOT + "_input");
        tag.remove(NBT_ROOT + "_input_cnt");
        tag.remove(NBT_ROOT + "_fuel");
        tag.remove(NBT_ROOT + "_fuel_cnt");
        tag.remove(NBT_ROOT + "_burn");
        tag.remove(NBT_ROOT + "_output");
        tag.remove(NBT_ROOT + "_per_out");
        tag.remove(NBT_ROOT + "_produced");
        tag.remove(NBT_ROOT + "_progress");
        tag.remove(NBT_ROOT + "_done");
        tag.remove(NBT_ROOT + "_msg");
    }
}
