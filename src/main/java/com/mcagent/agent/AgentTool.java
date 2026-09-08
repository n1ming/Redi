package com.mcagent.agent;

import com.google.gson.JsonObject;

/**
 * 一个通用 agent 工具 = LLM function calling 里的一项。
 * 工具只能“读取游戏状态/读取模组资源与代码/以玩家身份发消息或指令”,
 * 任何具备修改游戏文件、字节码、已加载类能力的操作都不允许成为工具。
 */
public interface AgentTool {
    /** snake_case 工具名,同时是 function name(限 a-zA-Z0-9_-)。 */
    String name();

    /** 中文描述:写给 LLM 看,要说清楚什么时候该用、参数怎么选。 */
    String description();

    /** OpenAI function 的 parameters JSON Schema:{"type":"object","properties":{...},"required":[...]} */
    JsonObject schema();

    /**
     * 执行工具。在引擎后台线程被调用;内部凡是要读 Minecraft 客户端状态,
     * 必须经 {@link ClientExec} 切回主线程。
     *
     * @return 给 LLM 看的结果文本(中文,紧凑,大结果要自行截断到 ~6000 字符)
     */
    String execute(JsonObject args) throws Exception;
}
