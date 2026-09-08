package com.mcagent.plugin;

/**
 * 插件(cordis 的 component):实现 {@link #setup(AgentContext)} 在给定上下文里
 * 注册工具/服务/事件监听即可;注册产生的副作用由上下文记录,插件卸载时由框架统一回滚。
 * 插件之间不直接互相引用,需要协作时通过服务(provide/inject)或事件(on/emit)。
 */
public interface AgentPlugin {
    /** 插件 id(唯一,用于日志与热卸载定位)。 */
    String id();

    /** 装配:在给定作用域里完成全部注册。抛异常只影响本插件,不会拖垮其它插件。 */
    void setup(AgentContext ctx);
}
