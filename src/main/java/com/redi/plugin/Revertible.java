package com.redi.plugin;

/**
 * 可回滚副作用(cordis 论文的 effect 概念):插件在 Context 上做的每件对外的事
 * (注册工具、订阅事件、提供服务等)都留下一个逆向操作;插件卸载时按逆序全部执行,
 * 保证“卸载即还原”,不同插件的副作用互不干扰。
 */
@FunctionalInterface
public interface Revertible {
    /** 执行回滚(幂等:实现方应容忍重复调用)。 */
    void dispose();

    /** 空回滚。 */
    static Revertible noop() {
        return () -> {
        };
    }
}
