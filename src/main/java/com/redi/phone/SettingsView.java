package com.redi.phone;

import com.redi.config.AgentConfig;
import com.redi.llm.LlmClient;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置视图(表单 + 模型选择器两个内部模式)。内容区 120x176,布局:
 * <ul>
 *   <li>标题行 y+2..11 +「&lt; 返回」;服务商 label y+15、chip y+26..38;</li>
 *   <li>官方服务商(非自定义):只需填 API Key(label y+41、框 y+52..64,明文),
 *       模型不预填——按钮 y+78..90 显示「获取模型」,点开自动 GET /models
 *       并全量列出(滚轮下滑、点击选中);</li>
 *   <li>自定义:Base URL / API Key / 模型 三个输入框全手填(y+41/y+67/y+93 各 label+框),
 *       不提供在线拉取;</li>
 *   <li>「测试连接」「保存」按钮 y+119..131;状态行 y+134 起(最多 4 行)。</li>
 * </ul>
 * <p>所有输入框明文显示;官方模式下焦点守卫保证键盘焦点永远只落在可见框上,
 * 隐形框不参与命中测试也不吃按键。</p>
 * <p>模型列表与测试连接都在后台线程执行;拉取请求按参数指纹"接管",
 * 过期结果发布时静默丢弃;请求参数取自当前表单快照,绝不阻塞渲染线程。</p>
 */
final class SettingsView {
    private static final int LINE_H = 9;
    private static final int FORM_W = 116;
    private static final int BOX_H = 12;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_DIVIDER = 0x33FFFFFF;
    private static final int LIST_TOP = 14;
    private static final int LIST_BOTTOM = 171;

    /** 内部模式:表单 / 模型在线列表。 */
    private enum Mode {FORM, MODELS}

    private final Runnable backToChat;
    private Mode mode = Mode.FORM;

    // ------- 表单控件 -------
    private final List<EditBox> boxes = new ArrayList<>();
    private EditBox baseUrlBox;
    private EditBox apiKeyBox;
    private EditBox modelBox;
    private String providerId = "custom";
    /** 本次编辑期间每个服务商各自的 Key;切换预设时互不串味,保存时并入配置。 */
    private final Map<String, String> sessionKeys = new LinkedHashMap<>();

    // ------- 模型在线列表 -------
    private volatile boolean fetching = false;
    private volatile List<String> fetchedModels = null;
    private volatile String fetchError = "";
    /** 拉取时的参数指纹:服务商/base_url/key 变了就重新拉。 */
    private volatile String fetchFingerprint = "";
    private int modelScroll = 0;
    private int listHoveredIndex = -1;
    private boolean retryHovered = false;

    // 测试连接状态(后台线程写,渲染线程读)
    private volatile boolean testing = false;
    private volatile boolean statusOk = true;
    private volatile String status = "";

    // 渲染期刷新的悬停态(点击判定复用,与 mcphone 内置页同套路)
    private boolean backHovered, chipHovered, testHovered, saveHovered, modelBtnHovered;

    SettingsView(Runnable backToChat) {
        this.backToChat = backToChat;
    }

    // ---------------------------------------------------------------- 渲染

    void render(PhoneCanvas canvas) {
        if (mode == Mode.MODELS) {
            renderModels(canvas);
        } else {
            renderForm(canvas);
        }
    }

    private void renderForm(PhoneCanvas canvas) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        int x = canvas.x();
        int y = canvas.y();
        int w = canvas.width();
        ensureBoxes(canvas);
        boolean official = AgentConfig.isOfficialProvider(providerId);

        renderHeader(canvas, x, y, w);

        // 服务商 chip:点击循环 deepseek→zhipu→moonshot→qwen→siliconflow→openai→custom
        g.drawString(font, Component.translatable("redi.settings.provider"), x + 2, y + 15, canvas.style().subtleColor(), false);
        chipHovered = Widgets.button(canvas, providerDisplay() + " ▸", x + 2, y + 26, FORM_W, BOX_H, canvas.style().bodyColor(), true);

        if (official) {
            // 官方服务商:只填 Key,base_url 跟随预设不用展示;模型点开在线列表选
            g.drawString(font, Component.translatable("redi.settings.api_key"), x + 2, y + 41, canvas.style().subtleColor(), false);
            positionAndRender(apiKeyBox, g, x + 2, y + 52, canvas);
            g.drawString(font, Component.translatable("redi.settings.model"), x + 2, y + 67, canvas.style().subtleColor(), false);
            String shown = modelBox.getValue().isBlank()
                    ? Component.translatable("redi.settings.fetch_models").getString()
                    : clip(font, modelBox.getValue(), FORM_W - 6);
            modelBtnHovered = Widgets.button(canvas, shown, x + 2, y + 78, FORM_W, BOX_H,
                    modelBox.getValue().isBlank() ? canvas.style().accentColor() : canvas.style().bodyColor(), true);
            // 焦点守卫:隐形框绝不能占着键盘焦点,否则玩家打的字全进看不见的框
            if (baseUrlBox.isFocused() || modelBox.isFocused() || !apiKeyBox.isFocused()) {
                baseUrlBox.setFocused(false);
                modelBox.setFocused(false);
                apiKeyBox.setFocused(true);
            }
        } else {
            g.drawString(font, Component.translatable("redi.settings.base_url"), x + 2, y + 41, canvas.style().subtleColor(), false);
            positionAndRender(baseUrlBox, g, x + 2, y + 52, canvas);
            g.drawString(font, Component.translatable("redi.settings.api_key"), x + 2, y + 67, canvas.style().subtleColor(), false);
            positionAndRender(apiKeyBox, g, x + 2, y + 78, canvas);
            g.drawString(font, Component.translatable("redi.settings.model"), x + 2, y + 93, canvas.style().subtleColor(), false);
            positionAndRender(modelBox, g, x + 2, y + 104, FORM_W, canvas);
            // 自定义模式纯手填,不提供在线拉取;三个框都可见,焦点跟随点击即可
        }

        // 操作按钮(两种布局统一锚在 y+119..131)
        testHovered = Widgets.button(canvas, Component.translatable("redi.settings.test").getString(),
                x + 2, y + 119, 54, BOX_H, canvas.style().accentColor(), !testing);
        saveHovered = Widgets.button(canvas, Component.translatable("redi.settings.save").getString(),
                x + 64, y + 119, 54, BOX_H, canvas.style().bodyColor(), true);

        // 状态行
        if (!status.isEmpty()) {
            int color = testing ? canvas.style().subtleColor() : (statusOk ? canvas.style().accentColor() : COLOR_ERROR);
            int sy = y + 134;
            for (FormattedCharSequence line : font.split(Component.literal(status), FORM_W)) {
                if (sy > y + 166) break;
                g.drawString(font, line, x + 2, sy, color, false);
                sy += LINE_H;
            }
        }
    }

    /** 模型在线列表(占满内容区):加载中=草方块动画,失败=错误+重试,成功=可点列表。 */
    private void renderModels(PhoneCanvas canvas) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        int x = canvas.x();
        int y = canvas.y();
        int w = canvas.width();

        g.drawString(font, Component.translatable("redi.settings.pick"), x + 2, y + 2, canvas.style().titleColor(), true);
        String back = "< " + Component.translatable("redi.chat.back").getString();
        int backW = font.width(back);
        int backX = x + w - backW - 2;
        backHovered = canvas.hovered(backX - 3, y, backW + 6, LINE_H + 4);
        g.drawString(font, back, backX, y + 2, backHovered ? canvas.style().accentColor() : canvas.style().subtleColor(), false);
        g.fill(x + 2, y + 13, x + w - 2, y + 14, COLOR_DIVIDER);

        if (fetching) {
            ChatView.drawThinkingAnim(canvas, x + (w - 14) / 2, y + 50, 14, 11);
            centerLines(canvas, Component.translatable("redi.settings.fetching").getString(), y + 78);
            return;
        }
        List<String> models = fetchedModels;
        if (models == null) {
            int sy = y + 40;
            String errText = fetchError.isEmpty()
                    ? Component.translatable("redi.settings.fetch_fail").getString()
                    : fetchError;
            for (FormattedCharSequence line : font.split(Component.literal(errText), FORM_W)) {
                if (sy > y + 90) break;
                g.drawString(font, line, x + 2, sy, COLOR_ERROR, false);
                sy += LINE_H;
            }
            retryHovered = Widgets.button(canvas, Component.translatable("redi.settings.retry").getString(),
                    x + 33, y + 100, 54, BOX_H, canvas.style().accentColor(), true);
            return;
        }

        // 列表:每行一个模型,当前选中的加 ● 前缀
        int listTop = y + LIST_TOP;
        int visible = LIST_BOTTOM - LIST_TOP;
        String current = modelBox.getValue();
        int contentH = models.size() * LINE_H;
        modelScroll = Math.min(Math.max(0, modelScroll), Math.max(0, contentH - visible));
        listHoveredIndex = -1;
        g.enableScissor(x, listTop, x + w, listTop + visible);
        int ry = listTop - modelScroll;
        for (int i = 0; i < models.size(); i++) {
            if (ry + LINE_H > listTop && ry < listTop + visible) {
                boolean sel = models.get(i).equals(current);
                if (canvas.hovered(x, ry, w, LINE_H)) {
                    listHoveredIndex = i;
                    g.fill(x, ry, x + w, ry + LINE_H, canvas.style().pressedOverlay());
                }
                String label = clip(font, sel ? "● " + models.get(i) : models.get(i), FORM_W);
                g.drawString(font, label, x + 2, ry, sel ? canvas.style().accentColor() : canvas.style().bodyColor(), false);
            }
            ry += LINE_H;
        }
        g.disableScissor();
    }

    private void renderHeader(PhoneCanvas canvas, int x, int y, int w) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        g.drawString(font, Component.translatable("redi.settings.title"), x + 2, y + 2, canvas.style().titleColor(), true);
        String back = "< " + Component.translatable("redi.chat.back").getString();
        int backW = font.width(back);
        int backX = x + w - backW - 2;
        backHovered = canvas.hovered(backX - 3, y, backW + 6, LINE_H + 4);
        g.drawString(font, back, backX, y + 2, backHovered ? canvas.style().accentColor() : canvas.style().subtleColor(), false);
        g.fill(x + 2, y + 13, x + w - 2, y + 14, COLOR_DIVIDER);
    }

    private void centerLines(PhoneCanvas canvas, String text, int topY) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        int x = canvas.x();
        int w = canvas.width();
        int ry = topY;
        for (FormattedCharSequence line : font.split(Component.literal(text), FORM_W)) {
            g.drawString(font, line, x + (w - font.width(line)) / 2, ry, canvas.style().subtleColor(), false);
            ry += LINE_H;
        }
    }

    private static String clip(Font font, String text, int maxW) {
        if (font.width(text) <= maxW) return text;
        return font.plainSubstrByWidth(text, maxW - font.width("…")) + "…";
    }

    // ---------------------------------------------------------------- 表单初始化

    private void ensureBoxes(PhoneCanvas canvas) {
        if (baseUrlBox != null) return;
        AgentConfig cfg = AgentConfig.get();
        baseUrlBox = makeBox(canvas.font(), cfg.baseUrl, 256, "redi.settings.base_url");
        apiKeyBox = makeBox(canvas.font(), cfg.apiKey, 256, "redi.settings.api_key");
        modelBox = makeBox(canvas.font(), cfg.model, 128, "redi.settings.model");
        providerId = AgentConfig.presets().containsKey(cfg.provider) ? cfg.provider : "custom";
        // 跨会话记忆:把已保存的各服务商 Key 灌进会话缓存,切回时才能回显
        sessionKeys.putAll(cfg.providerKeys);
        boxes.add(baseUrlBox);
        boxes.add(apiKeyBox);
        boxes.add(modelBox);
        focusFirstVisible();
    }

    /** 把键盘焦点给当前服务商模式下第一个可见的框(隐形框绝不占焦点)。 */
    private void focusFirstVisible() {
        boolean official = AgentConfig.isOfficialProvider(providerId);
        for (EditBox box : boxes) {
            boolean target = official ? box == apiKeyBox : box == baseUrlBox;
            box.setFocused(target);
        }
    }

    private static EditBox makeBox(Font font, String value, int maxLength, String labelKey) {
        EditBox box = new EditBox(font, 0, 0, FORM_W, BOX_H, Component.translatable(labelKey));
        box.setMaxLength(maxLength);
        box.setHint(Component.translatable(labelKey));
        box.setValue(value == null ? "" : value);
        return box;
    }

    private void positionAndRender(EditBox box, GuiGraphics g, int bx, int by, PhoneCanvas canvas) {
        positionAndRender(box, g, bx, by, FORM_W, canvas);
    }

    private void positionAndRender(EditBox box, GuiGraphics g, int bx, int by, int bw, PhoneCanvas canvas) {
        box.setX(bx);
        box.setY(by);
        box.setWidth(bw);
        box.render(g, canvas.mouseX(), canvas.mouseY(), canvas.partialTick());
    }

    private String providerDisplay() {
        return Component.translatable("redi.provider." + providerId).getString();
    }

    // ---------------------------------------------------------------- 交互

    boolean mouseClicked(double mx, double my, int button) {
        if (mode == Mode.MODELS) {
            return mouseClickedModels(mx, my, button);
        }
        if (button == 0) {
            if (backHovered) {
                backToChat.run();
                return true;
            }
            if (chipHovered) {
                cycleProvider();
                return true;
            }
            boolean official = AgentConfig.isOfficialProvider(providerId);
            if (official && modelBtnHovered) {
                openModelPicker();
                return true;
            }
            if (testHovered) {
                testConnection();
                return true;
            }
            if (saveHovered) {
                save();
                return true;
            }
        }
        // 输入框焦点:命中框(带 2px 容差)就把焦点给它;点空白处不清焦点,保证能继续打字
        for (EditBox box : boxes) {
            if (!isBoxVisible(box)) continue; // 隐形框不参与点击命中,防止抢焦点/吞光标
            boolean over = Widgets.hit(mx, my, box.getX() - 2, box.getY() - 2, box.getWidth() + 4, box.getHeight() + 4);
            if (over) {
                for (EditBox other : boxes) {
                    other.setFocused(other == box);
                }
                box.mouseClicked(mx, my, button);
                status = "";
                return true;
            }
        }
        return false;
    }

    private boolean mouseClickedModels(double mx, double my, int button) {
        if (button != 0) return false;
        if (backHovered) {
            mode = Mode.FORM;
            return true;
        }
        if (fetching) return false;
        if (fetchedModels == null) {
            if (retryHovered) startFetch();
            return retryHovered;
        }
        if (listHoveredIndex >= 0 && listHoveredIndex < fetchedModels.size()) {
            String id = fetchedModels.get(listHoveredIndex);
            modelBox.setValue(id);
            mode = Mode.FORM;
            statusOk = true;
            status = Component.translatable("redi.settings.picked").getString() + " " + id;
            return true;
        }
        return false;
    }

    boolean mouseScrolled(double amount) {
        if (mode != Mode.MODELS) return false;
        if (amount > 0) modelScroll = Math.max(0, modelScroll - 18);
        if (amount < 0) modelScroll += 18; // 越界由渲染端钳制
        return true;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (mode != Mode.FORM) return false;
        EditBox focused = focusedBox();
        return focused != null && focused.keyPressed(keyCode, scanCode, modifiers);
    }

    boolean charTyped(char codePoint, int modifiers) {
        if (mode != Mode.FORM) return false;
        EditBox focused = focusedBox();
        return focused != null && focused.charTyped(codePoint, modifiers);
    }

    /** 当前聚焦的框;绝不兜底到隐形框——没有聚焦就返回 null,按键不进任何框。 */
    private EditBox focusedBox() {
        for (EditBox box : boxes) {
            if (box.isFocused() && isBoxVisible(box)) return box;
        }
        return null;
    }

    /** 该框在当前服务商模式下是否可见。 */
    private boolean isBoxVisible(EditBox box) {
        if (AgentConfig.isOfficialProvider(providerId)) {
            return box == apiKeyBox; // 官方模式只有 API Key 框可见
        }
        return true;
    }

    // ---------------------------------------------------------------- 动作

    /** 循环切换预设:官方预设自动填 base_url 与默认模型;每个服务商的 Key 独立记忆。 */
    private void cycleProvider() {
        List<String> ids = new ArrayList<>(AgentConfig.presets().keySet());
        sessionKeys.put(providerId, apiKeyBox.getValue());
        int idx = Math.max(0, ids.indexOf(providerId));
        String next = ids.get((idx + 1) % ids.size());
        providerId = next;
        AgentConfig.Preset preset = AgentConfig.presets().get(next);
        if (preset == null || "custom".equals(next)) {
            baseUrlBox.setValue("");
            modelBox.setValue("");
        } else {
            baseUrlBox.setValue(preset.baseUrl());
            modelBox.setValue(""); // 官方服务商不预填模型:点「获取模型」在线拉取后自己选
        }
        apiKeyBox.setValue(sessionKeys.getOrDefault(next, ""));
        status = "";
        fetchedModels = null;
        fetchFingerprint = "";
        focusFirstVisible();
    }

    /** 打开模型在线列表;参数指纹变了(换服务商/改 key)就自动重新拉取。 */
    private void openModelPicker() {
        mode = Mode.MODELS;
        modelScroll = 0;
        String fp = fingerprint();
        if (!fp.equals(fetchFingerprint) || (fetchedModels == null && !fetching)) {
            startFetch();
        }
    }

    private String fingerprint() {
        AgentConfig s = snapshot();
        return providerId + "|" + s.baseUrl + "|" + s.apiKey;
    }

    /** 后台线程拉取模型列表(用当前表单快照);新请求按指纹"接管",旧结果发布时自动作废。 */
    private void startFetch() {
        String fp = fingerprint();
        if (fetching && fp.equals(fetchFingerprint)) return; // 同参数已在拉取中
        AgentConfig snap = snapshot();
        if (snap.baseUrl.isBlank()) {
            fetchedModels = null;
            fetchError = Component.translatable("redi.settings.fetch_fail").getString() + ": base_url 为空";
            fetchFingerprint = "";
            fetching = false;
            return;
        }
        fetching = true;
        fetchError = "";
        fetchFingerprint = fp;
        Thread worker = new Thread(() -> {
            List<String> result;
            String err = "";
            try {
                result = new LlmClient(snap).fetchModels();
            } catch (Exception e) {
                result = null;
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                err = Component.translatable("redi.settings.fetch_fail").getString() + ": " + truncate(msg, 120);
            }
            if (!fp.equals(fetchFingerprint)) return; // 参数已被更新的请求接管:静默丢弃,fetching 归新请求管
            fetchedModels = result;
            fetchError = err;
            fetching = false;
        }, "redi-fetch-models");
        worker.setDaemon(true);
        worker.start();
    }

    /** 后台线程测试连接:用编辑框当前值拼配置快照(不动全局单例)。 */
    private void testConnection() {
        if (testing) return;
        AgentConfig snapshot = snapshot();
        if (snapshot.baseUrl.isBlank() || snapshot.model.isBlank()) {
            statusOk = false;
            status = Component.translatable("redi.settings.test.fail").getString() + ": "
                    + Component.translatable("redi.settings.test.empty").getString();
            return;
        }
        testing = true;
        statusOk = true;
        status = Component.translatable("redi.settings.testing").getString();
        Thread worker = new Thread(() -> {
            String text;
            boolean ok;
            try {
                String reply = new LlmClient(snapshot).simpleChat("ping");
                String excerpt = reply == null ? "" : reply.trim().replace('\n', ' ');
                text = Component.translatable("redi.settings.test.ok").getString()
                        + (excerpt.isEmpty() ? "" : " | " + truncate(excerpt, 80));
                ok = true;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                text = Component.translatable("redi.settings.test.fail").getString() + ": " + truncate(msg, 100);
                ok = false;
            }
            this.status = text;
            this.statusOk = ok;
            this.testing = false;
        }, "redi-test-connection");
        worker.setDaemon(true);
        worker.start();
    }

    /** 三个输入框 + 当前预设 → 一份独立配置快照(继承代理/温度等全局参数)。 */
    private AgentConfig snapshot() {
        AgentConfig src = AgentConfig.get();
        AgentConfig c = new AgentConfig();
        c.provider = providerId;
        c.baseUrl = baseUrlBox.getValue().trim();
        c.apiKey = apiKeyBox.getValue().trim();
        c.model = modelBox.getValue().trim();
        c.temperature = src.temperature;
        c.maxTokens = src.maxTokens;
        c.timeoutSeconds = src.timeoutSeconds;
        c.maxToolIterations = src.maxToolIterations;
        c.proxyHost = src.proxyHost;
        c.proxyPort = src.proxyPort;
        return c;
    }

    /** 写回 AgentConfig 单例并持久化:当前 Key + 各服务商记忆的 Key 一并入库。 */
    private void save() {
        AgentConfig cfg = AgentConfig.get();
        sessionKeys.put(providerId, apiKeyBox.getValue().trim());
        cfg.provider = providerId;
        cfg.baseUrl = baseUrlBox.getValue().trim();
        cfg.apiKey = apiKeyBox.getValue().trim();
        cfg.model = modelBox.getValue().trim();
        for (Map.Entry<String, String> e : sessionKeys.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                cfg.providerKeys.put(e.getKey(), e.getValue());
            }
        }
        cfg.save();
        statusOk = true;
        status = Component.translatable("redi.settings.saved").getString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
