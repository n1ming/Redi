package com.mcagent.llm;

/**
 * 模型请求的一次工具调用。argumentsJson 是 JSON 对象文本(可能为空串,视作 "{}")。
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
