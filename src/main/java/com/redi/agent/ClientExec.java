package com.redi.agent;

import java.util.function.Supplier;

/**
 * 引擎线程访问 Minecraft 客户端状态的工具:任何对客户端对象
 * (player、level、注册表、ItemStack 等)的读取都必须切回主线程。
 */
public final class ClientExec {
    private static final long JOIN_TIMEOUT_MS = 10_000;

    private ClientExec() {
    }

    public static boolean onClientThread() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.isSameThread();
    }

    /**
     * 在客户端主线程求值。若已在主线程直接执行;
     * 否则投递到主线程并阻塞等待,超时返回 fallback。
     */
    public static <T> T get(Supplier<T> supplier, T fallback) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) return fallback;
        if (mc.isSameThread()) {
            try {
                return supplier.get();
            } catch (Throwable t) {
                return fallback;
            }
        }
        var future = new java.util.concurrent.CompletableFuture<T>();
        mc.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(JOIN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 无返回值版本。 */
    public static void run(Runnable action) {
        get(() -> {
            action.run();
            return true;
        }, false);
    }
}
