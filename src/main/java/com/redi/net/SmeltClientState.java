package com.redi.net;

/**
 * 客户端冶炼状态快照。S→C 的 SmeltStateSync 落在这里,
 * 客户端工具/HUD 层只读 {@link #current()},与服务端会话零耦合。
 *
 * <p>本类只依赖 JDK,不 import 任何 client-only 类,
 * 专用服务端加载同样安全(handler 只在客户端被调用)。</p>
 */
public final class SmeltClientState {

    /**
     * 冶炼会话视图(服务端 SmeltStateSync 的逐字段快照)。
     *
     * @param inputId      原料物品注册名(如 "minecraft:iron_ore");空串 = 无原料
     * @param inputCount   会话内剩余原料个数
     * @param fuelId       燃料物品注册名;空串 = 无燃料
     * @param fuelCount    可用燃料份数(含正在燃烧的那份)
     * @param outputId     产物物品注册名
     * @param outputCount  v2:产物槽堆积件数(堆在工作台等玩家收取,collect 后入包;不自动入包)
     * @param progressTicks 当前进度 tick(0..totalTicks)
     * @param totalTicks   每件冶炼总 tick(恒 200,与原版一致)
     * @param active       会话进行中
     * @param done         会话已结束且产物待收取(v2:done=true 且 outputCount>0 时会话仍保留,收取后结束)
     * @param message      服务端附言:进度说明/失败原因;"空串" 表示无附加信息
     */
    public record SmeltView(String inputId, int inputCount, String fuelId, int fuelCount,
            String outputId, int outputCount, int progressTicks, int totalTicks,
            boolean active, boolean done, String message) {
    }

    private static volatile SmeltView current = null;

    private SmeltClientState() {
    }

    /** 客户端读取最近一次同步的冶炼状态;null = 无会话。任意线程可调(volatile 读)。 */
    public static SmeltView current() {
        return current;
    }

    /** 仅 ModNetworking 的 SmeltStateSync 处理器调用(默认 MAIN 线程)。 */
    public static void set(SmeltView view) {
        current = view;
    }
}
