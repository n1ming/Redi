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
    private volatile java.util.concurrent.CompletableFuture<HttpResponse<String>> inFlight;

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

    /**
     * 发起一次对话请求。
     *
     * @param tools function 定义(schema 列表),可为 null/空表示不带工具
     * @throws Exception 网络/HTTP/配置错误;消息要能直接给玩家看(中文)
     */
    public Response chat(List<LlmMessage> messages, List<JsonObject> tools) throws Exception {
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
        HttpRequest request = baseRequestBuilder(url + "/models").GET().build();
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

    /** 对话端点:玩家可能直接把完整 /chat/completions 填进 base_url,去重要。 */
    private String completionsUrl() {
        String url = normalizeBaseUrl();
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
        if (config.apiKey != null && !config.apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + config.apiKey.trim());
        }
        return rb;
    }

    /** 按当前配置(含可选代理)发送请求,返回字符串响应体。 */
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        HttpClient.Builder cb = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (config.proxyHost != null && !config.proxyHost.isBlank() && config.proxyPort > 0) {
            cb.proxy(ProxySelector.of(new InetSocketAddress(config.proxyHost.trim(), config.proxyPort)));
        }
        var fut = cb.build().sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        payload.addProperty("max_tokens", config.maxTokens);
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
