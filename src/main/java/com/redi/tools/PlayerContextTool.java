package com.redi.tools;

import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ClientExec;
import com.redi.agent.ToolRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * player_context:一次读齐玩家自身状态、周边环境概览与周围地形总览,
 * 是 agent 接到新任务时最常用的“先看一眼”工具。
 */
public final class PlayerContextTool implements AgentTool {

    @Override
    public String name() {
        return "player_context";
    }

    @Override
    public String description() {
        return "查看玩家当前状态:方块坐标、维度、生物群系、游戏模式、生命/饥饿/经验等级、主手与副手物品(id×数量)、游戏内时辰、天气,附近 8 格内实体数(按敌对/动物/玩家/掉落物/其他粗分),以及周围地形总览(8 个水平方向在 4/8/16 格处的顶面方块与群系)。接到需要了解玩家处境的任务时,先调用它。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        return ClientExec.get(() -> {
            Player p = mc.player;
            if (mc.level == null || p == null) return "客户端未就绪(不在世界里)。";
            try {
                StringBuilder sb = new StringBuilder();
                BlockPos pos = p.blockPosition();
                sb.append("位置: ").append(pos.getX()).append(", ").append(pos.getY())
                        .append(", ").append(pos.getZ()).append('\n');
                sb.append("维度: ").append(mc.level.dimension().location()).append('\n');
                sb.append("生物群系: ").append(mc.level.getBiome(pos).unwrapKey()
                        .map(k -> k.location().toString()).orElse("未知")).append('\n');
                sb.append("游戏模式: ")
                        .append(mc.gameMode == null ? "未知" : mc.gameMode.getPlayerMode().getName())
                        .append('\n');
                sb.append("生命: ").append(String.format("%.1f", p.getHealth()))
                        .append('/').append(String.format("%.1f", p.getMaxHealth()))
                        .append(";饥饿: ").append(p.getFoodData().getFoodLevel()).append("/20")
                        .append(";经验等级: ").append(p.experienceLevel).append('\n');
                sb.append("主手: ").append(label(p.getMainHandItem())).append('\n');
                sb.append("副手: ").append(label(p.getOffhandItem())).append('\n');
                // dayTime:一天 24000 tick,0 tick = 早上 6 点
                long t = mc.level.getDayTime() % 24000L;
                int hh = (int) ((t / 1000L + 6L) % 24L);
                int mm = (int) ((t % 1000L) * 60L / 1000L);
                sb.append("时辰: ").append(String.format("%02d:%02d", hh, mm)).append("(游戏内时间)\n");
                sb.append("天气: ")
                        .append(mc.level.isThundering() ? "雷暴" : mc.level.isRaining() ? "下雨" : "晴")
                        .append('\n');

                // 周围地形总览:8 个水平方向 × 4/8/16 格的顶面方块与群系
                sb.append(terrainOverview(mc.level, pos));

                int monsters = 0, animals = 0, players = 0, drops = 0, others = 0;
                AABB box = p.getBoundingBox().inflate(8.0);
                List<Entity> nearby = mc.level.getEntitiesOfClass(Entity.class, box);
                for (Entity e : nearby) {
                    if (e == p) continue;
                    if (e instanceof Monster) monsters++;
                    else if (e instanceof Animal) animals++;
                    else if (e instanceof Player) players++;
                    else if (e instanceof ItemEntity) drops++;
                    else others++;
                }
                sb.append("附近8格实体: 敌对 ").append(monsters)
                        .append(",动物 ").append(animals)
                        .append(",玩家 ").append(players)
                        .append(",掉落物 ").append(drops)
                        .append(",其他 ").append(others);
                return ToolRegistry.trunc(sb.toString(), 6000);
            } catch (Throwable t) {
                return "读取失败: " + t;
            }
        }, "客户端未就绪(不在世界里)。");
    }

    /**
     * 周围地形总览:8 个水平方向(东南西北 + 四个斜角)在 4/8/16 格处的
     * 顶面方块(注册名)与群系,压缩成每方向一行的文本。
     */
    private static String terrainOverview(Level lvl, BlockPos origin) {
        String[] names = {"北", "南", "东", "西", "东北", "东南", "西南", "西北"};
        int[][] dirs = {
                {0, -1}, {0, 1}, {1, 0}, {-1, 0},
                {1, -1}, {1, 1}, {-1, 1}, {-1, -1}
        };
        int[] dists = {4, 8, 16};
        StringBuilder sb = new StringBuilder("周围地形(各方向 4/8/16 格顶面方块):\n");
        for (int i = 0; i < dirs.length; i++) {
            int dx = dirs[i][0], dz = dirs[i][1];
            sb.append("- ").append(names[i]).append(": ");
            for (int j = 0; j < dists.length; j++) {
                if (j > 0) sb.append(" / ");
                int x = origin.getX() + dx * dists[j];
                int z = origin.getZ() + dz * dists[j];
                sb.append(topBlock(lvl, x, z, origin.getY())).append('@').append(dists[j]);
            }
            // 群系取 8 格处的采样点
            BlockPos sample = new BlockPos(origin.getX() + dx * 8, origin.getY(), origin.getZ() + dz * 8);
            sb.append(" | 群系: ").append(lvl.getBiome(sample).unwrapKey()
                    .map(k -> k.location().toString()).orElse("未知")).append('\n');
        }
        return sb.toString();
    }

    /** 某纵列在玩家附近高度的“顶面方块”注册名:从头顶上方往下找第一个非空气方块。 */
    private static String topBlock(Level lvl, int x, int z, int yRef) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = yRef + 6; y >= yRef - 8; y--) {
            m.set(x, y, z);
            BlockState st = lvl.getBlockState(m);
            if (!st.isAir()) {
                return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
            }
        }
        return "(空)";
    }

    /** 物品概要:注册名×数量(显示名)。 */
    private static String label(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "(空)";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "×" + stack.getCount()
                + "(" + stack.getHoverName().getString() + ")";
    }
}
