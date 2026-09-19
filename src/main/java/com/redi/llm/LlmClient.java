package com.redi.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.redi.config.AgentConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

/**
 * OpenAI 兼容 chat/completions 客户端(仅 JDK HttpClient + Gson,无第三方依赖)。
 * 支持任意自定义 base_url 与可选出站代理(配置里 proxyHost/proxyPort)。
 *
 * <p>请求体按 OpenAI 规范组装:model/temperature/max_tokens/messages/tools;
 * tools 数组里每项就是 {@code ToolRegistry.schemas()} 给出的
 * {"type":"function","function":{name,description,parameters}} 完整定义,原样放入;
 * 响应里的 tool_calls(id/name/arguments)被解析成 {@link ToolCall},
 * arguments 为空串时按 "{}" 处理。</p>
 */
public final class LlmClient {

    /** 服务端返回 400(请求内容不被当前服务商接受,常见于超长/超深对话触发的限制),引擎可裁剪历史后重试。 */
    public static final class BadRequestException extends IOException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /**
     * @param reasoning 模型的原始思考文本(GLM/DeepSeek 等返回的 reasoning_content 字段),
     *                  可能为空串;仅用于展示,不回传给服务商
     * @param finishReason 服务商标记的结束原因("stop"/"tool_calls"/"length"…),
     *                     "length" = 被 max_tokens 掐断,引擎要自动续写
     */
    /** 流式 tool_calls 碎片累积器(按 index 聚合 id/name/arguments)。 */
    private static final class ToolCallAcc {
        final String id;
        final String name;
        final StringBuilder args = new StringBuilder();

        ToolCallAcc(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public record Response(String content, List<ToolCall> toolCalls, String reasoning, String finishReason) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        public boolean truncatedByLength() {
            return "length".equals(finishReason);
        }
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    /**
     * 诊断日志钩子(游戏启动时由 RediMod 注入 SLF4J logger,单测环境为 null):
     * 每次 LLM 调用写一行摘要,HTTP 非 2xx 时写完整状态与错误体。
     */
    public static volatile java.util.function.Consumer<String> logHook;

    /** 全链路插桩:每次调用的原始请求/响应字节落盘 config/redi/trace/(复现诊断金标准)。 */
    private static final java.util.concurrent.atomic.AtomicLong TRACE_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private static void traceWrite(java.nio.file.Path p, String content) {
        try {
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, content, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static void traceIndex(String line) {
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("config", "redi", "trace", "index.log"), line + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** 当前在途请求(用于"思考中"的即时打断;join 阻塞被打断后丢弃结果)。 */
    private volatile java.util.concurrent.CompletableFuture<?> inFlight;

    /**
     * assistant 消息带 tool_calls 时是否同时回显 content 字段。
     * 不同服务商校验不同(有的要求省略 content,有的要求 content 为空串),
     * 遇到 HTTP 400 "messages 参数非法" 时由引擎切换此开关重试。
     */
    public boolean echoToolCallContent = false;


    /** 即时中止在途请求:正在等待的 chat() 会立刻抛出"已手动停止"。 */
    public void abortInFlight() {
        var f = inFlight;
        if (f != null) {
            f.cancel(true);
        }
    }

    private final AgentConfig config;

    public LlmClient(AgentConfig config) {
        this.config = config;
    }

    /** 流式回调:思考/正文增量(边生成边推送,解决长上下文等待期界面空转)。 */
    public interface StreamListener {
        default void onReasoning(String delta) {
        }

        default void onContent(String delta) {
        }
    }

    /**
     * 发起一次对话请求(非流式,诊断兜底用)。
     */
    public Response chat(List<LlmMessage> messages, List<JsonObject> tools) throws Exception {
        if (isAnthropic()) {
            return AnthropicAdapter.parseResponse(post(AnthropicAdapter.buildPayload(
                    config.model == null ? "" : config.model.trim(), messages, tools,
                    anthropicMaxTokens > 0 ? anthropicMaxTokens : AnthropicAdapter.MAX_TOKENS)));
        }
        JsonObject body = post(buildPayload(messages, tools));

        // 有的服务在 HTTP 200 里也会带 error 字段,先检查
        if (body.has("error") && body.get("error").isJsonObject()) {
            JsonObject err = body.getAsJsonObject("error");
            String msg = err.has("message") && err.get("message").isJsonPrimitive()
                    ? err.get("message").getAsString() : String.valueOf(err);
            throw new IOException("模型接口返回错误: " + msg);
        }

        JsonArray choices = body.has("choices") && body.get("choices").isJsonArray()
                ? body.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty()) {
            throw new IOException("模型响应里没有 choices(接口可能不兼容或内容被拦截)。");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message") : new JsonObject();

        String content = message.has("content") && message.get("content").isJsonPrimitive()
                ? message.get("content").getAsString() : "";

        // 原始思考文本(GLM reasoning_content / OpenAI 兼容 reasoning 字段),仅展示用
        String reasoning = "";
        for (String k : new String[]{"reasoning_content", "reasoning"}) {
            if (message.has(k) && message.get(k).isJsonPrimitive()) {
                reasoning = message.get(k).getAsString();
                break;
            }
        }

        List<ToolCall> calls = new ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement el : message.getAsJsonArray("tool_calls")) {
                if (!el.isJsonObject()) continue;
                JsonObject tc = el.getAsJsonObject();
                if (!tc.has("function") || !tc.get("function").isJsonObject()) continue;
                JsonObject fn = tc.getAsJsonObject("function");
                String name = fn.has("name") && fn.get("name").isJsonPrimitive()
                        ? fn.get("name").getAsString() : "";
                if (name.isBlank()) continue; // 畸形调用直接跳过
                String args = fn.has("arguments") && fn.get("arguments").isJsonPrimitive()
                        ? fn.get("arguments").getAsString() : "{}";
                if (args.isBlank()) args = "{}"; // 空串按 "{}"
                String id = tc.has("id") && tc.get("id").isJsonPrimitive()
                        ? tc.get("id").getAsString() : ("call_" + calls.size());
                calls.add(new ToolCall(id, name, args));
            }
        }
        return new Response(content, calls, reasoning,
                choice.has("finish_reason") && choice.get("finish_reason").isJsonPrimitive()
                        ? choice.get("finish_reason").getAsString() : "");
    }

    /**
     * 流式对话(SSE):思考/正文增量经 handler 实时回调——长上下文等待期间
     * 界面能看到逐字冒出的思考文本,不再空转。返回组装后的完整 Response。
     */
    public Response chat(List<LlmMessage> messages, List<JsonObject> tools, StreamListener listener) throws Exception {
        if (isAnthropic()) {
            return chatAnthropicStream(messages, tools, listener);
        }
        JsonObject payload = buildPayload(messages, tools);
        payload.addProperty("stream", true);
        String reqJson = GSON.toJson(payload);
        long seq = TRACE_SEQ.incrementAndGet();
        long t0 = System.currentTimeMillis();
        java.nio.file.Path dir = java.nio.file.Path.of("config", "redi", "trace");
        traceWrite(dir.resolve(seq + "_req_stream.json"), reqJson);
        HttpRequest request = baseRequestBuilder(completionsUrl())
                .POST(HttpRequest.BodyPublishers.ofString(reqJson, StandardCharsets.UTF_8))
                .build();
        var fut = clientBuilder().build().sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        inFlight = fut;
        StringBuilder reasoning = new StringBuilder();
        StringBuilder content = new StringBuilder();
        String finish = "";
        Map<Integer, ToolCallAcc> acc = new java.util.TreeMap<>();
        try {
            var lines = fut.join().body();
            for (var line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    if (data.equals("[DONE]")) break;
                    continue;
                }
                JsonObject chunk;
                try {
                    chunk = JsonParser.parseString(data).getAsJsonObject();
                } catch (Exception e) {
                    continue;
                }
                if (chunk.has("error") && chunk.get("error").isJsonObject()) {
                    JsonObject err = chunk.getAsJsonObject("error");
                    throw new IOException("模型接口返回错误: " + (err.has("message")
                            ? err.get("message").getAsString() : err.toString()));
                }
                if (!chunk.has("choices") || !chunk.get("choices").isJsonArray()
                        || chunk.getAsJsonArray("choices").isEmpty()) continue;
                JsonObject ch = chunk.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (ch.has("finish_reason") && ch.get("finish_reason").isJsonPrimitive()
                        && !ch.get("finish_reason").getAsString().isEmpty()) {
                    finish = ch.get("finish_reason").getAsString();
                }
                if (!ch.has("delta") || !ch.get("delta").isJsonObject()) continue;
                JsonObject delta = ch.getAsJsonObject("delta");
                if (delta.has("reasoning_content") && delta.get("reasoning_content").isJsonPrimitive()) {
                    String d = delta.get("reasoning_content").getAsString();
                    if (!d.isEmpty()) {
                        reasoning.append(d);
                        if (listener != null) listener.onReasoning(d);
                    }
                }
                if (delta.has("content") && delta.get("content").isJsonPrimitive()) {
                    String d = delta.get("content").getAsString();
                    if (!d.isEmpty()) {
                        content.append(d);
                        if (listener != null) listener.onContent(d);
                    }
                }
                if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                    for (var el : delta.getAsJsonArray("tool_calls")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject tc = el.getAsJsonObject();
                        int index = tc.has("index") && tc.get("index").isJsonPrimitive()
                                ? tc.get("index").getAsInt() : acc.size();
                        ToolCallAcc cur = acc.get(index);
                        if (cur == null) {
                            String id = tc.has("id") && tc.get("id").isJsonPrimitive()
                                    ? tc.get("id").getAsString() : "";
                            String name = "";
                            if (tc.has("function") && tc.getAsJsonObject("function").has("name")
                                    && tc.getAsJsonObject("function").get("name").isJsonPrimitive()) {
                                name = tc.getAsJsonObject("function").get("name").getAsString();
                            }
                            cur = new ToolCallAcc(id, name);
                            acc.put(index, cur);
                        }
                        if (tc.has("function") && tc.getAsJsonObject("function").isJsonObject()) {
                            JsonObject fn = tc.getAsJsonObject("function");
                            if (fn.has("arguments") && fn.get("arguments").isJsonPrimitive()) {
                                cur.args.append(fn.get("arguments").getAsString());
                            }
                        }
                    }
                }
            }
        } catch (java.util.concurrent.CancellationException ce) {
            throw new IOException("已手动停止本次请求。");
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof java.util.concurrent.CancellationException) {
                throw new IOException("已手动停止本次请求。");
            }
            throw e;
        }
        long ms = System.currentTimeMillis() - t0;
        List<ToolCall> callList = new ArrayList<>();
        for (ToolCallAcc a : acc.values()) {
            callList.add(new ToolCall(a.id, a.name, a.args.isEmpty() ? "{}" : a.args.toString()));
        }
        String respSummary = GSON.toJson(new Response(content.toString(), callList, reasoning.toString(), finish));
        traceWrite(dir.resolve(seq + "_resp_stream.json"), "stream " + ms + "ms\n" + respSummary);
        traceIndex(seq + " STREAM 200 " + ms + "ms reqBytes=" + reqJson.length()
                + " respChars=" + respSummary.length());
        java.util.function.Consumer<String> hook = logHook;
        if (hook != null) hook.accept("[llm] #" + seq + " 流式200(" + ms + "ms, 请求 "
                + reqJson.length() + " 字符)");
        return new Response(content.toString(), callList, reasoning.toString(), finish);
    }

    /** Anthropic 流式(SSE):content_block_delta 增量,tool_use 碎片聚合。 */
    private Response chatAnthropicStream(List<LlmMessage> messages, List<JsonObject> tools,
                                         StreamListener listener) throws Exception {
        JsonObject payload = AnthropicAdapter.buildPayload(
                config.model == null ? "" : config.model.trim(), messages, tools,
                anthropicMaxTokens > 0 ? anthropicMaxTokens : AnthropicAdapter.MAX_TOKENS);
        payload.addProperty("stream", true);
        String reqJson = GSON.toJson(payload);
        long seq = TRACE_SEQ.incrementAndGet();
        long t0 = System.currentTimeMillis();
        java.nio.file.Path dir = java.nio.file.Path.of("config", "redi", "trace");
        traceWrite(dir.resolve(seq + "_req_stream.json"), reqJson);
        HttpRequest request = baseRequestBuilder(completionsUrl())
                .POST(HttpRequest.BodyPublishers.ofString(reqJson, StandardCharsets.UTF_8))
                .build();
        var fut = clientBuilder().build().sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        inFlight = fut;
        AnthropicAdapter.StreamState state = new AnthropicAdapter.StreamState();
        try {
            for (var line : (Iterable<String>) fut.join().body()::iterator) {
                if (!line.startsWith("data:")) continue; // event:/ping 行忽略
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if (!AnthropicAdapter.onStreamLine(data, state, listener)) break;
            }
        } catch (java.util.concurrent.CancellationException ce) {
            throw new IOException("已手动停止本次请求。");
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof java.util.concurrent.CancellationException) {
                throw new IOException("已手动停止本次请求。");
            }
            throw e;
        }
        long ms = System.currentTimeMillis() - t0;
        Response resp = AnthropicAdapter.toResponse(state);
        traceWrite(dir.resolve(seq + "_resp_stream.json"), "stream " + ms + "ms\n" + GSON.toJson(resp));
        traceIndex(seq + " STREAM 200 " + ms + "ms reqBytes=" + reqJson.length());
        java.util.function.Consumer<String> hook = logHook;
        if (hook != null) hook.accept("[llm] #" + seq + " 流式200(" + ms + "ms, 请求 " + reqJson.length() + " 字符)");
        return resp;
    }

    /** 无工具的便捷对话(设置页“测试连接”用):system + user,返回模型文本。 */
    public String simpleChat(String prompt) throws Exception {
        List<LlmMessage> msgs = List.of(
                LlmMessage.system("你是 Minecraft 手机里的智能助手 Redi,请用中文、简短作答。"),
                LlmMessage.user(prompt == null ? "" : prompt));
        Response resp = chat(msgs, null);
        String content = resp.content();
        return content == null || content.isBlank() ? "(模型没有返回内容)" : content;
    }

    /**
     * 拉取该接口可用的模型列表(OpenAI 兼容 GET {base}/models,返回 {"data":[{"id":...}]}),
     * 按名称排序。设置页“模型下拉列表”用。
     *
     * @throws Exception 网络/HTTP/解析错误;消息可直接给玩家看(中文)
     */
    public List<String> fetchModels() throws Exception {
        String url = normalizeBaseUrl();
        // 玩家可能把完整的 /chat/completions 粘进 base_url,拉模型时要去掉这个尾巴
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        String modelsUrl = isAnthropic()
                ? (url.endsWith("/v1") ? url + "/models" : url + "/v1/models")
                : url + "/models";
        HttpRequest request = baseRequestBuilder(modelsUrl).GET().build();
        HttpResponse<String> resp = send(request);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String snippet = resp.body() == null ? "" : resp.body().replace('\n', ' ');
            if (snippet.length() > 200) snippet = snippet.substring(0, 200);
            throw new IOException("获取模型列表返回 HTTP " + resp.statusCode() + ": " + snippet);
        }
        JsonObject body;
        try {
            body = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("模型列表响应不是合法 JSON。");
        }
        if (!body.has("data") || !body.get("data").isJsonArray()) {
            throw new IOException("接口未返回模型列表(data 字段缺失,可能不兼容 /models)。");
        }
        java.util.TreeSet<String> ids = new java.util.TreeSet<>();
        for (JsonElement el : body.getAsJsonArray("data")) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")
                    && el.getAsJsonObject().get("id").isJsonPrimitive()) {
                ids.add(el.getAsJsonObject().get("id").getAsString());
            }
        }
        if (ids.isEmpty()) throw new IOException("模型列表为空。");
        return new ArrayList<>(ids);
    }

    // ------------------------------------------------------------------

    /** 规范化 baseUrl:去尾部斜杠,空则报错。 */
    private String normalizeBaseUrl() {
        String url = config.baseUrl == null ? "" : config.baseUrl.trim();
        if (url.isBlank()) throw new IllegalArgumentException("尚未配置 base_url,请到设置页填写。");
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** 当前配置是否为 Anthropic 兼容风格。 */
    private boolean isAnthropic() {
        return "anthropic".equalsIgnoreCase(config.apiStyle);
    }

    /** Anthropic 风格的输出配额(协议必填字段);0=默认 8192。 */
    private volatile int anthropicMaxTokens = 0;

    /** 还原默认配额(服务商拒绝大配额时)。返回 true=确实有配额被还原。 */
    public boolean resetMaxTokens() {
        if (anthropicMaxTokens > 0) {
            anthropicMaxTokens = 0;
            return true;
        }
        return false;
    }

    /** 扩容 Anthropic 输出配额(×2,封顶 131072);返回 false=已到顶。 */
    public boolean bumpMaxTokens() {
        if (!isAnthropic()) {
            return false; // OpenAI 风格不发 max_tokens,无需扩容
        }
        int cur = anthropicMaxTokens > 0 ? anthropicMaxTokens : AnthropicAdapter.MAX_TOKENS;
        if (cur >= AnthropicAdapter.MAX_TOKENS_CAP) {
            return false;
        }
        anthropicMaxTokens = Math.min(AnthropicAdapter.MAX_TOKENS_CAP, cur * 2);
        return true;
    }

    /** 对话端点:玩家可能直接把完整 /chat/completions 填进 base_url,去重要。 */
    private String completionsUrl() {
        String url = normalizeBaseUrl();
        if (isAnthropic()) {
            return AnthropicAdapter.endpoint(url);
        }
        if (!url.endsWith("/chat/completions")) url = url + "/chat/completions";
        return url;
    }

    /** 组好公共头的请求 Builder(鉴权/超时/Content-Type),供 POST 与 GET 共用。 */
    private HttpRequest.Builder baseRequestBuilder(String url) {
        HttpRequest.Builder rb;
        try {
            rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, config.timeoutSeconds)))
                    .header("Content-Type", "application/json");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("base_url 无效: " + url);
        }
        if (isAnthropic()) {
            if (config.apiKey != null && !config.apiKey.isBlank()) {
                rb.header("x-api-key", config.apiKey.trim());
            }
            rb.header("anthropic-version", "2023-06-01");
        } else if (config.apiKey != null && !config.apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + config.apiKey.trim());
        }
        return rb;
    }

    /** 按当前配置构造 HttpClient Builder(含可选代理)。 */
    private HttpClient.Builder clientBuilder() {
        HttpClient.Builder cb = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (config.proxyHost != null && !config.proxyHost.isBlank() && config.proxyPort > 0) {
            cb.proxy(ProxySelector.of(new InetSocketAddress(config.proxyHost.trim(), config.proxyPort)));
        }
        return cb;
    }

    /** 按当前配置(含可选代理)发送请求,返回字符串响应体。 */
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        var fut = clientBuilder().build()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        inFlight = fut;
        HttpResponse<String> resp;
        try {
            resp = fut.join(); // 引擎线程阻塞等待;abortInFlight() 会立刻以取消异常打断
        } catch (java.util.concurrent.CompletionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof java.util.concurrent.CancellationException) {
                throw new IOException("已手动停止本次请求。");
            }
            throw c instanceof RuntimeException re ? re : new IOException(String.valueOf(c), c);
        }
        return resp;
    }

    /**
     * 清洗消息内容:统一换行(\r\n 与 \r 一律去除,保留 \n)、剔除其余控制字符。
     * 工具结果可能带进模组文件的 \r 等字符,部分服务商(GLM 1214)会因此拒绝整条 messages。
     */
    private static String sanitizeContent(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                continue; // \r 与 \r\n 统一丢弃(保留 \n)
            }
            if (c < 0x20 && c != '\t') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 按 OpenAI 规范组装请求体。 */
    private JsonObject buildPayload(List<LlmMessage> messages, List<JsonObject> tools) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model == null ? "" : config.model.trim());
        payload.addProperty("temperature", config.temperature);
        // 思考强度:低(默认)不发参数兼容性最好;中/高映射 OpenAI reasoning_effort
        if ("medium".equalsIgnoreCase(config.thinkingLevel) || "high".equalsIgnoreCase(config.thinkingLevel)) {
            payload.addProperty("reasoning_effort", config.thinkingLevel.toLowerCase(java.util.Locale.ROOT));
        }

        JsonArray arr = new JsonArray();
        for (LlmMessage m : messages) {
            JsonObject mo = new JsonObject();
            mo.addProperty("role", m.role());
            String content = sanitizeContent(m.content());
            switch (m.role()) {
                case "assistant" -> {
                    boolean withCalls = m.toolCalls() != null && !m.toolCalls().isEmpty();
                    if (withCalls) {
                        // 带 tool_calls 的 assistant 消息:部分服务商(GLM 1214)拒绝
                        // "非空文字 + tool_calls" 并存,默认发显式 null(智谱/OpenAI 标准形态);
                        // 开关打开则回显原文(个别服务商要求空串/原文)
                        if (echoToolCallContent) {
                            mo.addProperty("content", content);
                        } else {
                            mo.add("content", com.google.gson.JsonNull.INSTANCE);
                        }
                    } else if (content != null && !content.isEmpty()) {
                        mo.addProperty("content", content);
                    }
                    if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                        JsonArray calls = new JsonArray();
                        for (ToolCall tc : m.toolCalls()) {
                            JsonObject co = new JsonObject();
                            co.addProperty("id", tc.id());
                            co.addProperty("type", "function");
                            JsonObject fn = new JsonObject();
                            fn.addProperty("name", tc.name());
                            String aj = tc.argumentsJson();
                            fn.addProperty("arguments",
                                    aj == null || aj.isBlank() ? "{}" : sanitizeContent(aj));
                            co.add("function", fn);
                            calls.add(co);
                        }
                        mo.add("tool_calls", calls);
                    }
                    // assistant 既没内容也没调用时补空串,保证消息序列合法
                    if (!mo.has("content") && !mo.has("tool_calls")) {
                        mo.addProperty("content", "");
                    }
                }
                case "tool" -> {
                    mo.addProperty("tool_call_id", m.toolCallId() == null ? "" : m.toolCallId());
                    mo.addProperty("content", sanitizeContent(m.content()));
                }
                default -> mo.addProperty("content", sanitizeContent(m.content() == null ? "" : m.content()));
            }
            arr.add(mo);
        }
        payload.add("messages", arr);

        if (tools != null && !tools.isEmpty()) {
            JsonArray toolArr = new JsonArray();
            for (JsonObject t : tools) toolArr.add(t);
            payload.add("tools", toolArr);
        }
        return payload;
    }

    /** 400 时把被拒的完整请求体转储到 config/redi/last_400_request.json(诊断用,失败静默)。 */
    private static void dumpRequestForDebug(JsonObject payload) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of("config", "redi", "last_400_request.json");
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, GSON.toJson(payload), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** 发送对话请求并解析响应体为 JSON;400 抛 BadRequestException(引擎可裁剪历史重试),其余非 2xx 抛中文错误。 */
    private JsonObject post(JsonObject payload) throws Exception {
        String reqJson = GSON.toJson(payload);
        long seq = TRACE_SEQ.incrementAndGet();
        long t0 = System.currentTimeMillis();
        java.nio.file.Path dir = java.nio.file.Path.of("config", "redi", "trace");
        traceWrite(dir.resolve(seq + "_req.json"), reqJson);
        HttpRequest request = baseRequestBuilder(completionsUrl())
                .POST(HttpRequest.BodyPublishers.ofString(reqJson, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = send(request);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            traceWrite(dir.resolve(seq + "_resp_EXC.txt"),
                    "exception after " + ms + "ms: " + e);
            traceIndex(seq + " EXC " + ms + "ms reqBytes=" + reqJson.length()
                    + " " + e.getMessage());
            java.util.function.Consumer<String> hook = logHook;
            if (hook != null) hook.accept("[llm] #" + seq + " 异常(" + ms + "ms): " + e.getMessage());
            throw e;
        }
        long ms = System.currentTimeMillis() - t0;
        int status = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();
        traceWrite(dir.resolve(seq + "_resp_" + status + ".txt"),
                "HTTP " + status + "  " + ms + "ms\n" + body);
        traceIndex(seq + " HTTP " + status + " " + ms + "ms reqBytes=" + reqJson.length()
                + " respBytes=" + body.length());
        java.util.function.Consumer<String> hook = logHook;
        if (hook != null) {
            if (status == 200) {
                hook.accept("[llm] #" + seq + " 200(" + ms + "ms, 请求 " + reqJson.length()
                        + " 字符)");
            } else {
                String snippet = body.replace('\n', ' ');
                if (snippet.length() > 150) snippet = snippet.substring(0, 150);
                hook.accept("[llm] #" + seq + " HTTP " + status + "(" + ms + "ms): " + snippet
                        + " ← 完整请求在 config/redi/trace/" + seq + "_req.json");
            }
        }
        if (status == 400) {
            String snippet = body.replace('\n', ' ');
            if (snippet.length() > 200) snippet = snippet.substring(0, 200);
            dumpRequestForDebug(payload);
            throw new BadRequestException("模型接口返回 HTTP 400: " + snippet);
        }
        if (status < 200 || status >= 300) {
            String snippet = body.replace('\n', ' ');
            if (snippet.length() > 200) snippet = snippet.substring(0, 200);
            throw new IOException("模型接口返回 HTTP " + status + ": " + snippet);
        }
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("模型响应不是合法 JSON: " + e.getMessage());
        }
    }
}
