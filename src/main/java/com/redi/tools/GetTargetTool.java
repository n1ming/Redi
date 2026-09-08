package com.redi.tools;

import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ClientExec;
import com.redi.agent.ToolRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * get_target:看玩家准星指向的东西(方块或实体)与距离;
 * 可选 range 额外沿视线做“面前扫描”,并补充脚下/身后/左右 2 格的方块,
 * 用于回答“我面前是什么/准星指着什么/周围是什么”一类问题。
 */
public final class GetTargetTool implements AgentTool {

    /** 面前扫描的默认距离(格)。 */
    private static final int DEFAULT_RANGE = 6;
    /** 面前扫描的最大距离(格)。 */
    private static final int MAX_RANGE = 32;

    @Override
    public String name() {
        return "get_target";
    }

    @Override
    public String description() {
        return "获取玩家准星指向的东西(方块或实体)及其距离;可选参数 range(默认 6,最大 32)会额外沿视线每格扫描,列出面前的方块序列,并补充脚下/身后/左右 2 格的方块。回答“我面前/准星指着/周围是什么”类问题时用它。";
    }

    @Override
    public JsonObject schema() {
        JsonObject range = new JsonObject();
        range.addProperty("type", "integer");
        range.addProperty("description", "可选;面前扫描距离(格),默认 6,最大 32");

        JsonObject props = new JsonObject();
        props.add("range", range);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        int range = DEFAULT_RANGE;
        if (args != null && args.has("range") && args.get("range").isJsonPrimitive()) {
            try {
                range = args.get("range").getAsInt();
            } catch (Exception ignored) {
            }
        }
        range = Math.max(1, Math.min(MAX_RANGE, range));
        final int r = range;
        Minecraft mc = Minecraft.getInstance();
        return ClientExec.get(() -> {
            Player p = mc.player;
            Level lvl = mc.level;
            if (lvl == null || p == null) return "客户端未就绪(不在世界里)。";
            try {
                StringBuilder sb = new StringBuilder();
                HitResult hit = mc.hitResult;
                boolean pointed = false;
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    // 准星指着方块:注册名 + 坐标 + 距离
                    BlockPos pos = ((BlockHitResult) hit).getBlockPos();
                    BlockState st = lvl.getBlockState(pos);
                    double dist = p.getEyePosition().distanceTo(hit.getLocation());
                    sb.append("准星指向方块: ")
                            .append(BuiltInRegistries.BLOCK.getKey(st.getBlock()))
                            .append(" @ ").append(pos.getX()).append(',').append(pos.getY())
                            .append(',').append(pos.getZ())
                            .append("(距离 ").append(String.format("%.1f", dist)).append(" 格)\n");
                    pointed = true;
                } else if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
                    // 准星指着实体:类型 + 名字 + 距离 + 生命(若是生物)
                    Entity e = ((EntityHitResult) hit).getEntity();
                    double dist = p.getEyePosition().distanceTo(e.position());
                    sb.append("准星指向实体: ")
                            .append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))
                            .append("(名字 ").append(e.getName().getString()).append(')');
                    if (e instanceof LivingEntity le) {
                        sb.append(" 生命 ").append(String.format("%.1f", le.getHealth()))
                                .append('/').append(String.format("%.1f", le.getMaxHealth()));
                    }
                    sb.append("(距离 ").append(String.format("%.1f", dist)).append(" 格)\n");
                    pointed = true;
                }
                if (!pointed) {
                    // MISS(或没有准星结果):用面前扫描兜底
                    sb.append("准星没有指向任何东西(范围 ").append(r).append(" 格)\n");
                }
                sb.append(scanAhead(lvl, p, r));
                sb.append(scanAround(lvl, p));
                return ToolRegistry.trunc(sb.toString(), 6000);
            } catch (Throwable t) {
                return "读取失败: " + t;
            }
        }, "客户端未就绪(不在世界里)。");
    }

    /** 面前扫描:沿视线每格采样,列出前 8 种不同方块(注册名+距离)。 */
    private static String scanAhead(Level lvl, Player p, int range) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = p.getViewVector(1.0F);
        LinkedHashMap<String, Integer> found = new LinkedHashMap<>();
        for (int i = 1; i <= range && found.size() < 8; i++) {
            BlockPos bp = BlockPos.containing(eye.x + dir.x * i, eye.y + dir.y * i, eye.z + dir.z * i);
            BlockState st = lvl.getBlockState(bp);
            if (st.isAir()) continue;
            String rid = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
            if (found.containsKey(rid)) continue;
            found.put(rid, i);
        }
        StringBuilder sb = new StringBuilder("面前扫描(视线方向,1~").append(range).append(" 格):\n");
        if (found.isEmpty()) {
            sb.append("- 一路空旷,没有方块\n");
        } else {
            for (Map.Entry<String, Integer> e : found.entrySet()) {
                sb.append("- ").append(e.getKey()).append('(').append(e.getValue()).append("格)\n");
            }
        }
        return sb.toString();
    }

    /** 四周补充:脚下/身后/左右各 2 格的方块。 */
    private static String scanAround(Level lvl, Player p) {
        Direction facing = p.getDirection();
        BlockPos base = p.blockPosition();
        StringBuilder sb = new StringBuilder("四周(2 格):\n");
        sb.append("- 脚下: ").append(blockAt(lvl, base.below(2))).append('\n');
        sb.append("- 身后: ").append(blockAt(lvl, base.relative(facing.getOpposite(), 2))).append('\n');
        sb.append("- 左手: ").append(blockAt(lvl, base.relative(facing.getCounterClockWise(), 2))).append('\n');
        sb.append("- 右手: ").append(blockAt(lvl, base.relative(facing.getClockWise(), 2))).append('\n');
        return sb.toString();
    }

    /** 单个位置的方块注册名;空气返回“(空)”。 */
    private static String blockAt(Level lvl, BlockPos pos) {
        BlockState st = lvl.getBlockState(pos);
        if (st.isAir()) return "(空)";
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    }
}
