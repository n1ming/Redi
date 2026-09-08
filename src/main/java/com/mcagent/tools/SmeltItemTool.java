package com.mcagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.agent.AgentEngine;
import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ClientExec;
import com.mcagent.craft.CraftHud;
import com.mcagent.net.ModNetworking;
import com.mcagent.net.SmeltClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.List;

/**
 * smelt_item:自动冶炼 —— 不再找真实熔炉:先本地预检背包材料与燃料,
 * 然后向服务端发冶炼请求({@link ModNetworking#requestSmelt},服务端校验后真实扣料启动),
 * 轮询 {@link SmeltClientState} 等待确认;冶炼由服务端异步进行,
 * 产物逐件放回玩家背包(满则落地)。过程与状态通过 CraftHud 与返回文本告知玩家。
 *
 * <p>取消:本工具不暴露 cancel 类工具;若玩家在等待确认期间手动停止任务
 * (引擎 cancelled 标志,反射读取),会发送 {@link ModNetworking#cancelSmelt()}
 * 并收起 CraftHud,材料与燃料由服务端返还。</p>
 */
public final class SmeltItemTool implements AgentTool {

    /** 等待服务端确认的总时长。 */
    private static final long SMELT_WAIT_MS = 5_000;
    /** 轮询 SmeltClientState 的间隔。 */
    private static final long POLL_MS = 100;

    /** AgentEngine 的私有 cancelled 字段(玩家点「■」停止时置位);反射读取,读不到视为未取消。 */
    private static volatile Field engineCancelledField;

    @Override
    public String name() {
        return "smelt_item";
    }

    @Override
    public String description() {
        return "自动冶炼:参数 item 填要冶炼的物品注册名(如 minecraft:iron_ore),count 可选(1~64,默认尽量多)。"
                + "无需真实熔炉:工具消耗背包里的材料与燃料(优先煤炭/木炭),通过服务端虚拟熔炉直接开炼,"
                + "产物炼成后会自动放回背包。工具会汇报在炼什么×多少、燃料用量与预计耗时。"
                + "没有材料或没有燃料时直接返回原因,不发请求。"
                + "使用时机:玩家要求“帮我炼/烧 X”时。";
    }

    @Override
    public JsonObject schema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description", "要冶炼的物品注册名,如 minecraft:iron_ore");
        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "可选;冶炼数量(1~64),默认尽量多(最多 64,受背包存量限制)");

        JsonObject props = new JsonObject();
        props.add("item", item);
        props.add("count", count);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("item");
        schema.add("required", required);
        return schema;
    }

    // ------------------------- 预检数据(主线程解析) -------------------------

    /** 本地预检结果:error 非 null 表示不发请求;requested=期望量,effCount=按背包存量修正后的请求量。 */
    private record PreCheck(String itemId, int requested, int effCount, boolean trimmed, String error) {
        static PreCheck fail(String why) {
            return new PreCheck(null, 0, 0, false, why);
        }
    }

    @Override
    public String execute(JsonObject args) {
        String itemArg = args == null ? null : com.mcagent.agent.ToolRegistry.argStr(args, "item");
        if (itemArg == null || itemArg.isBlank()) return "缺少参数 item。";
        Integer count = null;
        if (args.has("count") && args.get("count").isJsonPrimitive()) {
            try {
                count = Math.max(1, Math.min(64, args.get("count").getAsInt()));
            } catch (Throwable ignored) {
            }
        }
        final int fCount = count == null ? -1 : count;

        Minecraft mc = Minecraft.getInstance();
        // 1) 主线程本地预检(缺料/无燃料直接返回中文原因,不发请求)
        PreCheck pre = ClientExec.get(() -> preCheck(mc, itemArg, fCount), PreCheck.fail("主线程调度超时"));
        if (pre == null) return "内部错误(预检失败)。";
        if (pre.error() != null) return pre.error();

        // 已有冶炼会话在进行?如实告知,不重复请求
        SmeltClientState.SmeltView before = SmeltClientState.current();
        if (before != null && before.active()) {
            return "已有一场冶炼在进行中(" + before.inputId() + "×" + before.inputCount()
                    + ",剩余约 " + Math.max(0, before.totalTicks() - before.progressTicks()) / 20
                    + " 秒);请等它完成,产物会自动入包。";
        }

        // 2) 发起冶炼(主线程发包;服务端校验后真实扣料启动)
        final String itemId = pre.itemId();
        final int effCount = pre.effCount();
        Boolean sent = ClientExec.get(() -> {
            ModNetworking.requestSmelt(itemId, effCount);
            return true;
        }, false);
        if (!sent) return "内部错误:无法在主线程发送冶炼请求。";

        // 3) 引擎线程轮询服务端状态(最多 ~5 秒);玩家手动停止任务则取消冶炼
        long deadline = System.currentTimeMillis() + SMELT_WAIT_MS;
        SmeltClientState.SmeltView view = null;
        boolean cancelled = false;
        while (true) {
            if (engineCancelled()) {
                cancelled = true;
                break;
            }
            SmeltClientState.SmeltView v = SmeltClientState.current();
            if (v != null && !v.equals(before)) {
                if (v.done()) {
                    // done 同步只会是旧会话的收尾(新会话每件要烧 200 tick≈10 秒,
                    // 不可能在 ~5 秒窗口内完成),不作为本次请求的确认,继续等
                } else {
                    view = v; // active=true 的启动确认,或 active=false 的明确拒绝
                    break;
                }
            }
            if (System.currentTimeMillis() >= deadline) break;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (cancelled) {
            ClientExec.run(ModNetworking::cancelSmelt); // C→S:取消并返还
            ClientExec.run(CraftHud::close);
            return "已手动停止任务:冶炼已取消,材料与燃料由服务端返还背包。";
        }
        if (view == null) {
            ClientExec.run(CraftHud::close);
            return "冶炼请求已发出,但约 " + (SMELT_WAIT_MS / 1000)
                    + " 秒内未收到服务端确认;请稍后重试,或确认服务端已安装虚拟冶炼功能。";
        }
        if (view.active()) {
            if (view.inputId() == null || !view.inputId().equals(pre.itemId())) {
                // 同步过来的是别的会话(如服务端已有另一物品在炼),本次请求被拒且未扣料
                String why = view.message() == null || view.message().isEmpty()
                        ? "服务端已有其它物品的冶炼会话。"
                        : view.message();
                return "冶炼未能开始:" + why;
            }
            final SmeltClientState.SmeltView fv = view;
            ClientExec.run(() -> showHud(fv)); // HUD:槽 0 原料 / 槽 1 燃料 / 产物
            return successSummary(pre, view);
        }
        // active=false 且未结束:服务端明确拒绝,原因在 message(如配方不存在/燃料不足)
        String why = view.message() == null || view.message().isEmpty()
                ? "服务端未说明原因(常见:服务端侧判定材料或燃料不足)。"
                : view.message();
        ClientExec.run(CraftHud::close);
        return "冶炼未能开始:" + why + "(本次未开始消耗材料。)";
    }

    // ------------------------- 主线程预检 -------------------------

    private PreCheck preCheck(Minecraft mc, String itemArg, int count) {
        LocalPlayer p = mc.player;
        if (mc.level == null || p == null || mc.gameMode == null) {
            return PreCheck.fail("客户端未就绪(不在世界里)。");
        }
        Item input = ContainerAutomation.parseItem(itemArg);
        if (input == null) {
            return PreCheck.fail("无法解析物品 id: " + itemArg + "(示例: minecraft:iron_ore 或 iron_ore)");
        }
        String inputId = ContainerAutomation.idOf(input);

        // 材料预检:背包里必须有待炼物
        int avail = ContainerAutomation.countInInventory(p, List.of(input));
        if (avail <= 0) {
            return PreCheck.fail("背包里没有 " + inputId + ",无法开始冶炼。");
        }
        int eff = count < 0 ? Math.min(64, avail) : Math.min(count, avail);
        boolean trimmed = (count >= 0 && count > avail) || (count < 0 && avail > 64);

        // 燃料预检:背包里需要有可燃物(跳过待炼物本身),否则服务端也开不了炉
        boolean hasFuel = false;
        var inv = p.getInventory();
        for (ItemStack s : inv.items) {
            if (s.isEmpty() || s.getItem() == input) continue;
            if (ContainerAutomation.isFuel(s)) {
                hasFuel = true;
                break;
            }
        }
        if (!hasFuel) {
            return PreCheck.fail("背包里没有燃料(推荐煤炭 minecraft:coal 或木炭),无法开炼。");
        }
        return new PreCheck(inputId, count < 0 ? avail : count, eff, trimmed, null);
    }

    // ------------------------- HUD 与总结 -------------------------

    /** 成功启动后的 HUD:槽 0=原料、槽 1=燃料、结果=产物(数值全部来自服务端同步的 SmeltView)。 */
    private static void showHud(SmeltClientState.SmeltView v) {
        CraftHud.show("自动冶炼", new ItemStack[9], null);
        ItemStack input = stackOf(v.inputId(), v.inputCount());
        if (input != null) CraftHud.updateSlot(0, input);
        ItemStack fuel = stackOf(v.fuelId(), v.fuelCount());
        if (fuel != null) CraftHud.updateSlot(1, fuel);
        ItemStack out = stackOf(v.outputId(), v.outputCount() > 0 ? v.outputCount() : 1);
        if (out != null) CraftHud.setResult(out);
    }

    /** 按注册名构造展示用 ItemStack(单个堆上限 64);解析失败返回 null。 */
    private static ItemStack stackOf(String id, int count) {
        Item it = ContainerAutomation.parseItem(id);
        if (it == null) return null;
        return new ItemStack(it, Math.max(1, Math.min(64, count)));
    }

    /** 成功启动的中文总结:在炼什么×多少、燃料多少、预计耗时、产物、自动回包。 */
    private static String successSummary(PreCheck pre, SmeltClientState.SmeltView v) {
        StringBuilder sb = new StringBuilder();
        sb.append("已开始自动冶炼:").append(v.inputId()).append('×').append(v.inputCount())
                .append(",燃料 ").append(v.fuelId()).append('×').append(v.fuelCount())
                .append(",预计约 ").append(Math.max(0, v.totalTicks()) / 20).append(" 秒完成,产物 ")
                .append(v.outputId());
        if (v.outputCount() > 0) sb.append('×').append(v.outputCount());
        sb.append(";产物炼成后会自动放回背包。");
        if (v.inputCount() < pre.effCount()) {
            sb.append("(服务端实际开始 ").append(v.inputCount())
                    .append(" 个,少于请求的 ").append(pre.effCount()).append(" 个。)");
        } else if (pre.trimmed() && pre.effCount() < pre.requested()) {
            sb.append("(背包里 ").append(v.inputId()).append(" 共 ").append(pre.requested())
                    .append(" 个,本次炼 ").append(pre.effCount()).append(" 个。)");
        }
        return com.mcagent.agent.ToolRegistry.trunc(sb.toString(), 6000);
    }

    // ------------------------- 引擎取消检测 -------------------------

    /**
     * 玩家是否已手动停止当前任务。AgentEngine 未暴露 cancelled 的读取接口且不在本工具
     * 可改范围内,故反射读取其私有 volatile 字段;任何异常(字段改名/不可访问)按未取消处理。
     */
    private static boolean engineCancelled() {
        try {
            Field f = engineCancelledField;
            if (f == null) {
                f = AgentEngine.class.getDeclaredField("cancelled");
                f.setAccessible(true);
                engineCancelledField = f;
            }
            return f.getBoolean(AgentEngine.get());
        } catch (Throwable t) {
            return false;
        }
    }
}
