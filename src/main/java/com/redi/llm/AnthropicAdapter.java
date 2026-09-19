package com.redi.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages API 适配层:OpenAI 形态 ↔ Anthropic 形态的完整转换。
 *
 * <p>请求:{@code /v1/messages},鉴权 {@code x-api-key} + {@code anthropic-version: 2023-06-01};
 * system 提为顶层字段;assistant 的 tool_calls → content blocks({@code tool_use});
 * tool 结果合并进紧随的 user 消息({@code tool_result} blocks);
 * tools 用 {@code {name, description, input_schema}}。max_tokens 必填,固定 8192
 * (Anthropic 协议要求,无法省略)。</p>
 *
 * <p>响应:text/thinking blocks → content/reasoning;{@code tool_use} → ToolCall;
 * stop_reason 映射:end_turn→stop、tool_use→tool_calls、max_tokens→length。
 * 流式:{@code content_block_start/delta} 事件,text/thinking/input_json 增量。</p>
 */
final class AnthropicAdapter {
    /** Anthropic 协议 max_tokens 必填(无法省略)。思考型模型思考也计入此配额:
     *  挤压时由 LlmClient.bumpMaxTokens() 翻倍扩容(8192→…→131072 探测上限),
     *  服务商不支持时返回 400,引擎自动回退并按"长度截断→续写"处理。 */
    static final int MAX_TOKENS = 8192;
    static final int MAX_TOKENS_CAP = 131072;

    private AnthropicAdapter() {
    }

    /** 端点:base 去尾斜杠;以 /v1 结尾 → +/messages;以 /messages 结尾原样;其余 +/v1/messages。 */
    static String endpoint(String baseUrl) {
        String u = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (u.endsWith("/messages")) {
            return u;
        }
        return u.endsWith("/v1") ? u + "/messages" : u + "/v1/messages";
    }

    /** OpenAI 形态消息列表 → Anthropic 请求体(不含 stream 字段)。 */
    static JsonObject buildPayload(String model, List<LlmMessage> messages, List<JsonObject> tools, int maxTokens) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("max_tokens", Math.max(1024, maxTokens));
        StringBuilder system = new StringBuilder();
        JsonArray msgs = new JsonArray();
        JsonArray pendingToolResults = new JsonArray(); // 连续 tool 结果合并进一条 user
        for (LlmMessage m : messages) {
            switch (m.role()) {
                case "system" -> {
                    if (m.content() != null && !m.content().isEmpty()) {
                        if (system.length() > 0) system.append('\n');
                        system.append(m.content());
                    }
                }
                case "user" -> {
                    flushToolResults(msgs, pendingToolResults);
                    JsonObject u = new JsonObject();
                    u.addProperty("role", "user");
                    u.addProperty("content", m.content() == null ? "" : m.content());
                    msgs.add(u);
                }
                case "assistant" -> {
                    flushToolResults(msgs, pendingToolResults);
                    JsonArray blocks = new JsonArray();
                    if (m.content() != null && !m.content().isEmpty()) {
                        JsonObject t = new JsonObject();
                        t.addProperty("type", "text");
                        t.addProperty("text", m.content());
                        blocks.add(t);
                    }
                    if (m.toolCalls() != null) {
                        for (ToolCall tc : m.toolCalls()) {
                            JsonObject tu = new JsonObject();
                            tu.addProperty("type", "tool_use");
                            tu.addProperty("id", tc.id());
                            tu.addProperty("name", tc.name());
                            try {
                                tu.add("input", JsonParser.parseString(
                                        tc.argumentsJson() == null || tc.argumentsJson().isBlank()
                                                ? "{}" : tc.argumentsJson()));
                            } catch (Exception e) {
                                tu.add("input", new JsonObject());
                            }
                            blocks.add(tu);
                        }
                    }
                    if (blocks.size() == 0) { // 空 assistant 兜底文本块
                        JsonObject t = new JsonObject();
                        t.addProperty("type", "text");
                        t.addProperty("text", " ");
                        blocks.add(t);
                    }
                    JsonObject a = new JsonObject();
                    a.addProperty("role", "assistant");
                    a.add("content", blocks);
                    msgs.add(a);
                }
                case "tool" -> {
                    JsonObject tr = new JsonObject();
                    tr.addProperty("type", "tool_result");
                    tr.addProperty("tool_use_id", m.toolCallId() == null ? "" : m.toolCallId());
                    tr.addProperty("content", m.content() == null ? "" : m.content());
                    pendingToolResults.add(tr);
                }
                default -> {
                }
            }
        }
        flushToolResults(msgs, pendingToolResults);
        if (system.length() > 0) {
            payload.addProperty("system", system.toString());
        }
        payload.add("messages", msgs);
        if (tools != null && !tools.isEmpty()) {
            JsonArray ts = new JsonArray();
            for (JsonObject openaiTool : tools) {
                JsonObject fn = openaiTool.has("function") && openaiTool.get("function").isJsonObject()
                        ? openaiTool.getAsJsonObject("function") : openaiTool;
                JsonObject t = new JsonObject();
                t.addProperty("name", fn.has("name") ? fn.get("name").getAsString() : "");
                t.addProperty("description", fn.has("description") ? fn.get("description").getAsString() : "");
                t.add("input_schema", fn.has("parameters") && fn.get("parameters").isJsonObject()
                        ? fn.getAsJsonObject("parameters") : new JsonObject());
                ts.add(t);
            }
            payload.add("tools", ts);
        }
        return payload;
    }

    /** 把攒的 tool_result blocks 作为一条 user 消息 flush(Anthropic 要求 tool 结果必须在 user 轮)。 */
    private static void flushToolResults(JsonArray msgs, JsonArray pending) {
        if (pending.size() == 0) {
            return;
        }
        JsonObject u = new JsonObject();
        u.addProperty("role", "user");
        u.add("content", pending.deepCopy());
        msgs.add(u);
        for (int i = pending.size() - 1; i >= 0; i--) { // 清空复用(caller 持同一引用)
            pending.remove(i);
        }
    }

    /** Anthropic 非流式响应 → OpenAI 形态 Response。 */
    static LlmClient.Response parseResponse(JsonObject body) throws Exception {
        if (body.has("type") && "error".equals(body.get("type").getAsString())) {
            JsonObject err = body.has("error") && body.get("error").isJsonObject() ? body.getAsJsonObject("error") : body;
            String msg = err.has("message") && err.get("message").isJsonPrimitive()
                    ? err.get("message").getAsString() : err.toString();
            throw new java.io.IOException("模型接口返回错误: " + msg);
        }
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        if (body.has("content") && body.get("content").isJsonArray()) {
            for (JsonElement el : body.getAsJsonArray("content")) {
                if (!el.isJsonObject()) continue;
                JsonObject b = el.getAsJsonObject();
                String type = b.has("type") && b.get("type").isJsonPrimitive() ? b.get("type").getAsString() : "";
                switch (type) {
                    case "text" -> {
                        if (b.has("text") && b.get("text").isJsonPrimitive()) text.append(b.get("text").getAsString());
                    }
                    case "thinking" -> {
                        if (b.has("thinking") && b.get("thinking").isJsonPrimitive()) {
                            thinking.append(b.get("thinking").getAsString());
                        }
                    }
                    case "tool_use" -> {
                        String id = b.has("id") ? b.get("id").getAsString() : "call_" + calls.size();
                        String name = b.has("name") ? b.get("name").getAsString() : "";
                        String input = b.has("input") && b.get("input").isJsonObject()
                                ? b.get("input").toString() : "{}";
                        if (!name.isBlank()) calls.add(new ToolCall(id, name, input));
                    }
                    default -> {
                    }
                }
            }
        }
        String stop = body.has("stop_reason") && body.get("stop_reason").isJsonPrimitive()
                ? mapStopReason(body.get("stop_reason").getAsString()) : "";
        return new LlmClient.Response(text.toString(), calls, thinking.toString(), stop);
    }

    /** stop_reason → OpenAI finish_reason 语义。 */
    static String mapStopReason(String anthropicStop) {
        return switch (anthropicStop == null ? "" : anthropicStop) {
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            case "stop_sequence", "pause_turn" -> "stop";
            default -> "stop"; // end_turn 及未知
        };
    }

    // ---------------------------------------------------------------- 流式

    /** 流式解析状态(一个响应一个实例)。 */
    static final class StreamState {
        final StringBuilder reasoning = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final java.util.Map<Integer, ToolCallAcc> acc = new java.util.TreeMap<>();
        String finish = "";
        boolean done;
    }

    /** 流式 tool_use 累积器。 */
    static final class ToolCallAcc {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    /**
     * 处理一行 SSE data;返回 true=继续,false=流结束(message_stop)。
     * 增量经 listener 回调,状态累积在 state。
     */
    static boolean onStreamLine(String data, StreamState state, LlmClient.StreamListener listener) {
        JsonObject j;
        try {
            j = JsonParser.parseString(data).getAsJsonObject();
        } catch (Exception e) {
            return true; // 非 JSON 行忽略
        }
        String type = j.has("type") && j.get("type").isJsonPrimitive() ? j.get("type").getAsString() : "";
        switch (type) {
            case "error" -> {
                JsonObject err = j.has("error") && j.get("error").isJsonObject() ? j.getAsJsonObject("error") : j;
                state.finish = "error:" + (err.has("message") ? err.get("message").getAsString() : err.toString());
                state.done = true;
                return false;
            }
            case "content_block_start" -> {
                int idx = j.has("index") ? j.get("index").getAsInt() : state.acc.size();
                if (j.has("content_block") && j.get("content_block").isJsonObject()) {
                    JsonObject b = j.getAsJsonObject("content_block");
                    if (b.has("type") && "tool_use".equals(b.get("type").getAsString())) {
                        ToolCallAcc a = new ToolCallAcc();
                        a.id = b.has("id") ? b.get("id").getAsString() : "call_" + idx;
                        a.name = b.has("name") ? b.get("name").getAsString() : "";
                        state.acc.put(idx, a);
                    }
                }
            }
            case "content_block_delta" -> {
                if (!j.has("delta") || !j.get("delta").isJsonObject()) break;
                JsonObject d = j.getAsJsonObject("delta");
                int idx = j.has("index") ? j.get("index").getAsInt() : -1;
                String dt = d.has("type") && d.get("type").isJsonPrimitive() ? d.get("type").getAsString() : "";
                switch (dt) {
                    case "text_delta" -> {
                        String t = d.has("text") ? d.get("text").getAsString() : "";
                        if (!t.isEmpty()) {
                            state.content.append(t);
                            if (listener != null) listener.onContent(t);
                        }
                    }
                    case "thinking_delta" -> {
                        String t = d.has("thinking") ? d.get("thinking").getAsString() : "";
                        if (!t.isEmpty()) {
                            state.reasoning.append(t);
                            if (listener != null) listener.onReasoning(t);
                        }
                    }
                    case "input_json_delta" -> {
                        ToolCallAcc a = state.acc.get(idx);
                        if (a != null && d.has("partial_json")) {
                            a.args.append(d.get("partial_json").getAsString());
                        }
                    }
                    default -> {
                    }
                }
            }
            case "message_delta" -> {
                if (j.has("delta") && j.get("delta").isJsonObject()
                        && j.getAsJsonObject("delta").has("stop_reason")) {
                    state.finish = mapStopReason(j.getAsJsonObject("delta").get("stop_reason").getAsString());
                }
            }
            case "message_stop" -> {
                state.done = true;
                return false;
            }
            default -> {
            }
        }
        return true;
    }

    /** StreamState → Response。 */
    static LlmClient.Response toResponse(StreamState state) throws Exception {
        if (state.finish.startsWith("error:")) {
            throw new java.io.IOException("模型接口返回错误: " + state.finish.substring(6));
        }
        List<ToolCall> calls = new ArrayList<>();
        for (ToolCallAcc a : state.acc.values()) {
            calls.add(new ToolCall(a.id, a.name, a.args.length() == 0 ? "{}" : a.args.toString()));
        }
        return new LlmClient.Response(state.content.toString(), calls, state.reasoning.toString(), state.finish);
    }
}
