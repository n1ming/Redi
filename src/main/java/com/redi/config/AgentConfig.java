package com.redi.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.redi.RediMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家可配置的助手设置,持久化到 config/redi/settings.json。
 *
 * <p>provider 只决定预设的 base_url/model 建议;玩家也可完全自填
 * (任意 OpenAI 兼容接口,包括本地 ollama / one-api 中转等)。</p>
 */
public final class AgentConfig {
    private static final AgentConfig INSTANCE = new AgentConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 当前选中的预设名;"custom" 表示完全手填。 */
    public String provider = "custom";
    /** OpenAI 兼容接口根地址,如 https://api.deepseek.com/v1 */
    public String baseUrl = "";
    /** 当前激活服务商的 API Key(与 providerKeys[provider] 同步)。 */
    public String apiKey = "";
    /** 每个服务商各自记忆的 Key,切换预设时互不串味。 */
    public Map<String, String> providerKeys = new LinkedHashMap<>();
    public String model = "";
    /** 模型思考强度:low(默认,不发参数)/ medium / high(请求体带 reasoning_effort)。 */
    public String thinkingLevel = "low";
    public double temperature = 0.7;
    public int maxTokens = 8192;
    /** 单次 HTTP 请求超时(秒)。 */
    public int timeoutSeconds = 300;
    /** 工具调用循环的最大轮数;0 = 不限制(仅保留重复调用与绝对上限保护)。 */
    public int maxToolIterations = 0;
    /** 出站代理(访问 OpenAI 等外网接口用),留空 = 直连。 */
    public String proxyHost = "";
    public int proxyPort = 0;

    public static AgentConfig get() {
        return INSTANCE;
    }

    public static Path configFile() {
        return Path.of("config", "redi", "settings.json");
    }

    public synchronized void load() {
        Path p = configFile();
        if (!Files.isRegularFile(p)) {
            save();
            return;
        }
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            AgentConfig loaded = GSON.fromJson(json, AgentConfig.class);
            if (loaded != null) {
                this.provider = loaded.provider;
                this.baseUrl = loaded.baseUrl;
                this.apiKey = loaded.apiKey;
                this.providerKeys = loaded.providerKeys == null ? new LinkedHashMap<>() : loaded.providerKeys;
                this.model = loaded.model;
                this.thinkingLevel = loaded.thinkingLevel == null ? "low" : loaded.thinkingLevel;
                this.temperature = loaded.temperature;
                this.maxTokens = loaded.maxTokens;
                this.timeoutSeconds = loaded.timeoutSeconds;
                this.maxToolIterations = Math.max(0, loaded.maxToolIterations);
                this.proxyHost = loaded.proxyHost;
                this.proxyPort = loaded.proxyPort;
            }
        } catch (Exception e) {
            RediMod.LOGGER.warn("[redi] 读取设置失败,使用默认值: {}", e.toString());
        }
    }

    public synchronized void save() {
        try {
            Path p = configFile();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            RediMod.LOGGER.warn("[redi] 保存设置失败: {}", e.toString());
        }
    }

    /** 是否已配置到可用状态。 */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && model != null && !model.isBlank();
    }

    /** apiKey 在聊天页状态栏等处只显示尾 4 位。 */
    public String maskedKey() {
        if (apiKey == null || apiKey.length() <= 4) return "----";
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 常见服务商预设(全部 OpenAI 兼容,均支持 GET /models 拉模型列表)。玩家可随时改用自定义 base_url。 */
    public static Map<String, Preset> presets() {
        Map<String, Preset> m = new LinkedHashMap<>();
        m.put("deepseek", new Preset("https://api.deepseek.com/v1", "deepseek-chat",
                new String[]{"deepseek-chat", "deepseek-reasoner"}));
        m.put("zhipu", new Preset("https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
                new String[]{"glm-4-flash", "glm-4-air", "glm-4-plus"}));
        m.put("moonshot", new Preset("https://api.moonshot.cn/v1", "moonshot-v1-8k",
                new String[]{"moonshot-v1-8k", "moonshot-v1-32k", "kimi-k2-0711-preview"}));
        m.put("qwen", new Preset("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
                new String[]{"qwen-turbo", "qwen-plus", "qwen-max"}));
        m.put("siliconflow", new Preset("https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3",
                new String[]{"deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-7B-Instruct", "THUDM/glm-4-9b-chat"}));
        m.put("openai", new Preset("https://api.openai.com/v1", "gpt-4o-mini",
                new String[]{"gpt-4o-mini", "gpt-4o"}));
        m.put("custom", new Preset("", "", new String[0]));
        return m;
    }

    /** 该预设是否为官方服务商(非 custom):官方只需填 API Key,模型走在线列表选择。 */
    public static boolean isOfficialProvider(String providerId) {
        return providerId != null && !"custom".equals(providerId);
    }

    public record Preset(String baseUrl, String defaultModel, String[] models) {
    }
}
