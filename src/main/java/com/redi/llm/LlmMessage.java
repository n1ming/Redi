package com.redi.llm;

import java.util.List;

/**
 * 发给/收自 LLM 的一条消息(OpenAI chat 格式的中性表达)。
 * role: system / user / assistant / tool
 */
public record LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null);
    }

    public static LlmMessage assistant(String content, List<ToolCall> toolCalls) {
        return new LlmMessage("assistant", content, toolCalls, null);
    }

    /** 工具结果回填,toolCallId 必须对应被调用的那个 call。 */
    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage("tool", content, null, toolCallId);
    }
}
