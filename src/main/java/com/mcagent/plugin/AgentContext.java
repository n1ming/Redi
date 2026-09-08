package com.mcagent.plugin;

import com.mcagent.agent.AgentTool;
import com.mcagent.agent.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 上下文(cordis 架构的 Context):插件世界里所有操作的唯一入口。
 *
 * <ul>
 *   <li><b>服务</b>:{@code provide/inject} —— 具名能力,供其他插件按需取用;</li>
 *   <li><b>事件</b>:{@code on/emit} —— 订阅即注册一个可回滚监听器;</li>
 *   <li><b>工具</b>:{@code tool(t)} —— 向注册表登记一个工具,返回可回滚句柄;</li>
 *   <li><b>作用域</b>:{@code fork()} —— 派生子上下文;子作用域里的一切注册
 *       (工具/事件/服务/自定义副作用)在 {@link #dispose()} 时按逆序回滚,
 *       这就是插件“卸载即还原”的机制,插件之间互不干扰。</li>
 * </ul>
 *
 * <p>{@link #SHARED} 是全局共享上下文;每个插件拿到的是它自己的 fork
 * (由 {@link PluginManager} 创建),插件里只管注册,清理交给框架。</p>
 */
public final class AgentContext {
    /** 全局共享上下文(服务/事件的全局总线)。 */
    public static final AgentContext SHARED = new AgentContext(null);

    private final AgentContext parent;
    private final Map<String, Object> services = new LinkedHashMap<>();
    private final List<Revertible> disposals = new ArrayList<>();
    private final Map<String, List<Consumer<Object>>> listeners = new LinkedHashMap<>();

    private AgentContext(AgentContext parent) {
        this.parent = parent;
    }

    /** 派生子作用域:子作用域的全部注册随 dispose() 一并回滚。 */
    public AgentContext fork() {
        return new AgentContext(this);
    }

    // ---------------- 服务 ----------------

    /** 提供具名服务(同 id 覆盖旧值,返回回滚句柄恢复旧值)。 */
    public <T> Revertible provide(String id, T service) {
        Object old = services.put(id, service);
        disposals.add(() -> {
            if (old == null) {
                services.remove(id);
            } else {
                services.put(id, old);
            }
        });
        emit("service.provided", id);
        return Revertible.noop();
    }

    /** 按 id 取服务(沿父链向上找);没有返回 null。 */
    @SuppressWarnings("unchecked")
    public <T> T inject(String id) {
        for (AgentContext c = this; c != null; c = c.parent) {
            Object v = c.services.get(id);
            if (v != null) {
                return (T) v;
            }
        }
        return null;
    }

    // ---------------- 事件 ----------------

    /** 订阅事件;返回回滚句柄(= 取消订阅)。 */
    public Revertible on(String event, Consumer<Object> handler) {
        listeners.computeIfAbsent(event, k -> new ArrayList<>(1)).add(handler);
        Revertible revert = () -> {
            List<Consumer<Object>> l = listeners.get(event);
            if (l != null) {
                l.remove(handler);
            }
        };
        disposals.add(revert);
        return revert;
    }

    /** 广播事件:沿本作用域到根的顺序通知所有监听者;监听者异常互不影响。 */
    public void emit(String event, Object payload) {
        for (AgentContext c = this; c != null; c = c.parent) {
            List<Consumer<Object>> l = c.listeners.get(event);
            if (l == null) {
                continue;
            }
            for (Consumer<Object> h : List.copyOf(l)) {
                try {
                    h.accept(payload);
                } catch (Throwable ignored) {
                    // 单个插件监听器异常不拖垮其它插件
                }
            }
        }
    }

    // ---------------- 工具与自定义副作用 ----------------

    /** 向工具注册表登记一个工具(可回滚)。 */
    public Revertible tool(AgentTool t) {
        ToolRegistry.install(t);
        Revertible revert = () -> ToolRegistry.uninstall(t);
        disposals.add(revert);
        return revert;
    }

    /** 登记自定义副作用,插件卸载时回滚。 */
    public void defer(Revertible r) {
        disposals.add(r);
    }

    /** 回滚本作用域全部注册(逆序),恢复到 fork 之前的状态。 */
    public void dispose() {
        for (int i = disposals.size() - 1; i >= 0; i--) {
            try {
                disposals.get(i).dispose();
            } catch (Throwable ignored) {
            }
        }
        disposals.clear();
    }
}
