package com.mcagent.phone;

import com.mcagent.agent.AgentEngine;
import com.mcagent.agent.ChatModel;
import com.mcagent.config.AgentConfig;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天主视图。内容区 120x176,以下 y 偏移均相对 canvas.x()/canvas.y() 左上角:
 * <ul>
 *   <li>标题行 y+2..y+11:左「AI 助手」,右「历史」+「新对话」+ 齿轮「⚙」(历史列表/设置);
 *       y+13 分隔线;</li>
 *   <li>消息列表 y+14..y+136(busy 时列表底边上移到 y+126,让出 y+126..y+136 的加载带);</li>
 *   <li>输入区两段式:EditBox(x+2, 宽 116 全宽, 高 12,y+140..y+152);工具栏行 y+155..y+170 ——
 *       左「思考:低 ∨」chip(x+2, 46x13,点击循环 low→medium→high 并立即保存)、
 *       右「▶/■」按钮(x+98, 20x13;busy 时点它 = cancel),中间留白。</li>
 * </ul>
 * 消息按 112px 折行并缓存;新消息自动吸底,滚轮翻历史。未配置接口时列表顶部
 * 常驻 mcagent.chat.not_configured 提示带。ASSISTANT 消息中的 [grid]…[/grid] 块
 * 解析为配方网格行:16px 物品图标槽位(深色底 + 描边),可选「▶ + 产物图标」。
 */
final class ChatView {
    private static final int LINE_H = 9;
    /** 配方网格槽位:16px 格子 + 2px 间距;3 列整体宽 = 3*18-2 = 52px。 */
    private static final int CELL = 16;
    private static final int CELL_STEP = CELL + 2;
    private static final int GRID_COLS = 3;
    private static final int GRID_W = GRID_COLS * CELL_STEP - 2;
    /** 槽位深色底与 1px 描边(模拟物品栏格子)。 */
    private static final int COLOR_SLOT_BG = 0x66000000;
    private static final int COLOR_SLOT_EDGE = 0x33FFFFFF;
    /** 无效注册名的缺失遮罩:MC 经典洋红/黑棋盘。 */
    private static final int COLOR_MISSING_A = 0xFFF800F8;
    private static final int COLOR_MISSING_B = 0xFF000000;
    /** 消息折行宽度(120 宽减左右留白后取整)。 */
    private static final int WRAP_W = 112;
    /** 规约指定的错误红。 */
    private static final int COLOR_ERROR = 0xFFFF5555;
    /** 分隔线 / 高亮的半透明白。 */
    private static final int COLOR_DIVIDER = 0x33FFFFFF;

    /** 折行后的一行。行高可变:文本行 9px、网格行 rows*18+2px;行尾附加像素(消息间空隙)。 */
    private sealed interface ChatLine {
        /** 本行占用高度(不含消息间空隙),吸底 / 滚动钳制 / contentH 都用它。 */
        int height();

        int gapAfter();
    }

    /** 文本行:内容、颜色、是否右对齐(USER)。 */
    private record TextLine(FormattedCharSequence text, int color, boolean right, int gapAfter) implements ChatLine {
        @Override
        public int height() {
            return LINE_H;
        }
    }

    /** 配方网格行:rows 每行 ≤3 格,格子为物品注册名或 null(空槽);result 为产物注册名或 null。 */
    private record GridLine(ResourceLocation[][] rows, ResourceLocation result, int gapAfter) implements ChatLine {
        @Override
        public int height() {
            return rows.length * 18 + 2; // rows*18 + 上下各 1px
        }
    }

    /** 思考过程块:默认折叠只显示一行标题,点击展开逐步显示(思考原文/工具调用),长文本按宽换行。 */
    private static final class ThinkLine implements ChatLine {
        final long msgTs;
        final java.util.List<String> details;
        int gapAfter;
        boolean expanded = false;
        /** 步骤折行宽度(缩进比正文多,略窄于 WRAP_W)。 */
        int wrapW = WRAP_W - 6;

        ThinkLine(long msgTs, java.util.List<String> details, int gapAfter) {
            this.msgTs = msgTs;
            this.details = details;
            this.gapAfter = gapAfter;
        }

        @Override
        public int height() {
            if (!expanded) {
                return LINE_H + 2;
            }
            Font font = net.minecraft.client.Minecraft.getInstance().font;
            int lines = 1; // 标题一行
            for (String d : details) {
                lines += font.split(Component.literal("· " + d), wrapW).size();
            }
            return lines * LINE_H + 2;
        }

        @Override
        public int gapAfter() {
            return gapAfter;
        }
    }

    /** 思考块标题的点击命中区(渲染帧刷新,点击判定复用)。 */
    private record ThinkHit(ThinkLine line, int y0, int y1) {
    }

    private final java.util.ArrayList<ThinkHit> thinkHits = new java.util.ArrayList<>();
    /** 展开状态的思考块(msg 时间戳)。 */
    private final java.util.HashSet<Long> thinkExpanded = new java.util.HashSet<>();

    // ---- 固定思考区域(任务进行中)----
    /** 面板是否展开(默认收缩:小条只看最新;展开:完整思考过程)。 */
    private boolean thinkPanelExpanded = false;
    /** 面板滚轮偏移(0=跟随最新;展开时向上翻回看)。 */
    private int thinkPanelScroll = 0;
    /** 活动行点击区(渲染帧刷新,x0,y0,x1,y1)。 */
    private final int[] toggleHit = new int[4];
    /** 面板框矩形(渲染帧刷新,滚轮命中用,x0,y0,x1,y1)。 */
    private final int[] panelRect = new int[4];
    private int panelTotalLines, panelVisibleLines;

    private final Runnable openSettings;
    /** 「历史」入口回调:打开历史会话列表(接线由外层屏/页完成)。 */
    private final Runnable openHistory;
    private EditBox input;

    // 渲染期刷新的命中区与悬停态(点击判定在 mouseClicked 里复用,与 mcphone 内置页同套路)
    private int gearX, newChatX, histX, sendX, sendY, chipX, chipY;
    private boolean gearHovered, newHovered, histHovered;

    // 折行缓存与滚动状态:pinned = 吸附底部(有新消息自动跟随)
    private final List<ChatLine> lines = new ArrayList<>();
    private long cacheRevision = Long.MIN_VALUE;
    private int scroll;
    private boolean pinned = true;

    ChatView(Runnable openSettings, Runnable openHistory) {
        this.openSettings = openSettings;
        this.openHistory = openHistory;
    }

    /** 从设置页返回聊天时,把键盘焦点交回输入框。 */
    void focusInput() {
        if (input != null) {
            input.setFocused(true);
        }
    }

    /**
     * 平铺 Screen({@link AgentFlatScreen})的热键透传守卫:聊天输入框是否持有键盘焦点。
     * 只查不写;EditBox 聚焦时 keyPressed 对数字键返回 false,平铺屏必须显式查焦点
     * 才能安全放行数字 1..9 切快捷栏(否则打字会误切槽位)。
     */
    boolean isInputFocused() {
        return input != null && input.isFocused(); // javap: AbstractWidget.isFocused()
    }

    void render(PhoneCanvas canvas) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        int x = canvas.x();
        int y = canvas.y();
        int w = canvas.width();

        thinkHits.clear(); // 每渲染帧刷新思考块命中区
        renderTitle(canvas, x, y, w);

        boolean busy = ChatModel.get().busy();
        // 任务进行中:聊天列表让位给底部固定思考区域(展开时让位更多)
        int listTop = y + 14;
        int listBottom = busy ? (thinkPanelExpanded ? y + 32 : y + 93) : y + 136;

        // 未配置接口:列表顶部常驻提示带,消息内容从提示带下方开始
        int contentTop = listTop;
        if (!AgentConfig.get().isConfigured()) {
            contentTop = renderNotConfigured(canvas, x, listTop, w);
        }

        rebuildIfNeeded(canvas);

        // 滚动钳制 + 吸底跟随
        int contentH = 0;
        for (ChatLine l : lines) {
            contentH += l.height() + l.gapAfter();
        }
        int visible = Math.max(LINE_H, listBottom - contentTop);
        int maxScroll = Math.max(0, contentH - visible);
        if (pinned) {
            scroll = maxScroll;
        } else {
            scroll = Math.min(Math.max(0, scroll), maxScroll);
            if (scroll >= maxScroll) {
                pinned = true; // 向下翻到底后恢复自动跟随
            }
        }

        // 消息列表(裁剪在列表区内,不侵入标题/输入行)
        if (contentTop < listBottom) {
            g.enableScissor(x, contentTop, x + w, listBottom);
            int drawY = contentTop - scroll;
            if (lines.isEmpty()) {
                for (FormattedCharSequence l : font.split(Component.translatable("mcagent.chat.empty"), WRAP_W)) {
                    if (drawY >= listBottom) {
                        break;
                    }
                    if (drawY + LINE_H > contentTop) {
                        g.drawString(font, l, x + 2, drawY, canvas.style().subtleColor(), false);
                    }
                    drawY += LINE_H;
                }
            } else {
                for (ChatLine l : lines) {
                    int h = l.height();
                    if (drawY + h > contentTop && drawY < listBottom) {
                        if (l instanceof TextLine t) {
                            if (t.right()) {
                                g.drawString(font, t.text(), x + w - 2 - font.width(t.text()), drawY, t.color(), false);
                            } else {
                                g.drawString(font, t.text(), x + 2, drawY, t.color(), false);
                            }
                        } else if (l instanceof ThinkLine tl) {
                            // 命中区只挂标题行(高度 LINE_H),展开内容由 mouseClicked 里切换
                            thinkHits.add(new ThinkHit(tl, drawY, drawY + LINE_H));
                            renderThink(canvas, tl, drawY);
                        } else {
                            renderGrid(canvas, (GridLine) l, drawY);
                        }
                    }
                    drawY += h + l.gapAfter();
                }
            }
            g.disableScissor();
        }

        // busy:框外活动行(转台动画+流光“思考中…”) + 固定思考区域(默认收缩,点活动行展开)
        if (busy) {
            renderThinkArea(canvas, x, y, w);
        }

        renderComposer(canvas, x, y, w, busy);
    }

    /**
     * 固定思考区域(任务进行中):
     * <ul>
     * <li>活动行在<b>框外</b>:转台动画 + 流光活动文字 + 右端 ▾/▴ 展开指示,整行可点击切换;</li>
     * <li><b>收缩(默认)</b>:小面板只滚动显示最近的思考/工具步骤(新内容在最下);</li>
     * <li><b>展开</b>:加高的面板显示<b>完整</b>思考过程,滚轮上翻回看,翻到底自动跟随最新。</li>
     * </ul>
     * 步骤文字用 0.75x 小字号;任务结束整个区域消失,完整记录在聊天流的「思考过程」块里。
     */
    private void renderThinkArea(PhoneCanvas canvas, int x, int y, int w) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        // ---- 面板框(收缩=小条,展开=大区域);活动行永远贴在框顶边外侧,随框位置移动 ----
        int top = thinkPanelExpanded ? y + 46 : y + 107;
        int h = (y + 138) - top;
        int actY = top - 12;
        String act = ChatModel.get().activity();
        String busyText = act != null && !act.isBlank()
                ? act.trim().replaceAll("\\s+", " ")
                : Component.translatable("mcagent.chat.busy").getString();
        drawThinkingAnim(canvas, x + 3, actY, 13, 10); // 缩小版转台
        drawShimmerText(canvas, busyText, x + 19, actY + 1, x + w - (x + 19) - 16);
        g.drawString(font, thinkPanelExpanded ? "▴" : "▾", x + w - 10, actY,
                canvas.style().subtleColor(), false);
        toggleHit[0] = x;
        toggleHit[1] = actY - 1;
        toggleHit[2] = x + w;
        toggleHit[3] = actY + 11;
        g.fill(x, top, x + w, top + h, COLOR_SLOT_BG);
        g.renderOutline(x, top, w, h, COLOR_DIVIDER);
        panelRect[0] = x;
        panelRect[1] = top;
        panelRect[2] = x + w;
        panelRect[3] = top + h;

        java.util.List<String> steps = ChatModel.get().liveSteps();
        if (steps.isEmpty()) {
            return;
        }
        // ---- 步骤行(0.75x 小字号;收缩只取尾部 12 步省帧,展开全量)----
        float s = 0.75f;
        int from = thinkPanelExpanded ? 0 : Math.max(0, steps.size() - 12);
        java.util.ArrayList<TLine> all = new java.util.ArrayList<>();
        int wrapW = Math.round((w - 10) / s);
        for (int i = from; i < steps.size(); i++) {
            String step = steps.get(i);
            boolean err = step.endsWith("✗");
            for (FormattedCharSequence l : font.split(Component.literal("· " + step), wrapW)) {
                all.add(new TLine(l, err));
            }
        }
        int innerPad = 3;
        int lineHpx = Math.round(LINE_H * s); // 视觉行高 ≈ 7px
        int maxVisible = Math.max(1, (h - innerPad * 2) / lineHpx);
        panelTotalLines = all.size();
        panelVisibleLines = maxVisible;
        int maxScroll = Math.max(0, panelTotalLines - maxVisible);
        if (!thinkPanelExpanded) {
            thinkPanelScroll = 0; // 收缩恒看最新
        }
        thinkPanelScroll = Math.min(thinkPanelScroll, maxScroll);
        int first = Math.max(0, panelTotalLines - maxVisible - thinkPanelScroll);
        int last = Math.min(panelTotalLines, first + maxVisible);

        g.enableScissor(x, top + 2, x + w, top + h - 2);
        var pose = g.pose();
        pose.pushPose();
        pose.scale(s, s, 1.0f);
        int lyScaled = Math.round((top + innerPad) / s);
        for (int i = first; i < last; i++) {
            TLine ln = all.get(i);
            g.drawString(font, ln.text(), Math.round((x + 5) / s), lyScaled,
                    ln.err() ? COLOR_ERROR : canvas.style().subtleColor(), false);
            lyScaled += LINE_H;
        }
        pose.popPose();
        g.disableScissor();
    }

    /** 思考面板步骤行(折行后的一行 + 是否失败行)。 */
    private record TLine(FormattedCharSequence text, boolean err) {
    }

    /** 思考动画 sprite sheet:30 帧竖排单纹理(48x1140),零纹理切换不掉帧。 */
    private static final net.minecraft.resources.ResourceLocation THINK_SHEET =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mcagent", "textures/gui/thinking/sheet.png");
    private static final int THINK_FRAMES = 30;
    private static final int THINK_SRC_H = 38;
    /** 每帧显示时长(毫秒):100ms/帧 = 3 秒/圈。 */
    private static final long THINK_FRAME_MS = 100L;

    /**
     * 思考动画:从单张 sprite sheet 上取当前帧绘制,按给定尺寸缩放(w/h 可小于原生 20x16)。
     * 单纹理零切换,blit 采样区域 = 竖排第 f 帧。
     */
    static void drawThinkingAnim(PhoneCanvas canvas, int left, int top, int w, int h) {
        GuiGraphics g = canvas.graphics();
        int f = (int) ((System.currentTimeMillis() / THINK_FRAME_MS) % THINK_FRAMES);
        g.blit(THINK_SHEET, left, top, w, h, 0.0F, f * (float) THINK_SRC_H, 48, THINK_SRC_H, 48, THINK_SRC_H * THINK_FRAMES);
    }

    /**
     * 流光文字:先画暗底文本,再在一条随时间往返的 10px 高亮窗口内
     * (scissor 裁剪)重画亮色文本——视觉上像一道光扫过文字。
     */
    static void drawShimmerText(PhoneCanvas canvas, String text, int left, int top, int maxWidth) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        String shown = font.plainSubstrByWidth(text, Math.max(8, maxWidth));
        long t = System.currentTimeMillis() % 2400L;
        float k = t < 1200L ? t / 1200.0f : 1.0f - (t - 1200L) / 1200.0f;
        int textW = font.width(shown);
        int sweepX = left + (int) ((textW - 10) * k);
        g.drawString(font, shown, left, top, 0xFF7E8AA5, false);
        g.enableScissor(Math.min(sweepX, left + textW), top - 1, Math.min(sweepX + 10, left + textW), top + 9);
        g.drawString(font, shown, left, top, 0xFFFFFFFF, false);
        g.disableScissor();
    }

    /** 标题行:应用名 + 「历史」+「新对话」+ 齿轮 + 分隔线。 */
    private void renderTitle(PhoneCanvas canvas, int x, int y, int w) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        g.drawString(font, Component.translatable("mcagent.app.agent"), x + 2, y + 2, canvas.style().titleColor(), true);

        String gear = "⚙";
        int gearW = font.width(gear);
        gearX = x + w - gearW - 2;
        gearHovered = canvas.hovered(gearX - 3, y, gearW + 6, LINE_H + 4);
        g.drawString(font, gear, gearX, y + 2, gearHovered ? canvas.style().accentColor() : canvas.style().titleColor(), true);

        String newChat = Component.translatable("mcagent.chat.new").getString();
        int newW = font.width(newChat);
        newChatX = gearX - 4 - newW;
        newHovered = canvas.hovered(newChatX - 3, y, newW + 6, LINE_H + 4);
        g.drawString(font, newChat, newChatX, y + 2, newHovered ? canvas.style().accentColor() : canvas.style().subtleColor(), false);

        // 「历史」在「新对话」左侧,间距收紧到 3px(「新对话」与齿轮仍留 4px),给左侧标题让空间
        String hist = "历史";
        int histW = font.width(hist);
        histX = newChatX - 3 - histW;
        histHovered = canvas.hovered(histX - 3, y, histW + 6, LINE_H + 4);
        g.drawString(font, hist, histX, y + 2, histHovered ? canvas.style().accentColor() : canvas.style().subtleColor(), false);

        g.fill(x + 2, y + 13, x + w - 2, y + 14, COLOR_DIVIDER);
    }

    /** 未配置提示带(淡琥珀底 + accent 文字),返回消息内容应开始的 y。 */
    private int renderNotConfigured(PhoneCanvas canvas, int x, int topY, int w) {
        Font font = canvas.font();
        List<FormattedCharSequence> wrapped = font.split(Component.translatable("mcagent.chat.not_configured"), WRAP_W);
        int shown = Math.min(wrapped.size(), 6);
        int bandH = shown * LINE_H + 4;
        GuiGraphics g = canvas.graphics();
        g.fill(x, topY, x + w, topY + bandH, 0x30E0A000);
        int yy = topY + 2;
        for (int i = 0; i < shown; i++) {
            g.drawString(font, wrapped.get(i), x + 4, yy, canvas.style().accentColor(), false);
            yy += LINE_H;
        }
        return topY + bandH + 1;
    }

    /** 输入区(两段式):全宽 EditBox 一行 + 工具栏行(左思考强度 chip、右发送/停止)。 */
    private void renderComposer(PhoneCanvas canvas, int x, int y, int w, boolean busy) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();

        if (input == null) {
            input = new EditBox(font, x + 2, y + 140, 116, 12, Component.translatable("mcagent.chat.send"));
            input.setMaxLength(1024);
            input.setFocused(true);
        } else {
            input.setX(x + 2);
            input.setY(y + 140);
            input.setWidth(116); // 发送键已移到工具栏行,输入框占满整行宽度
        }
        input.render(g, canvas.mouseX(), canvas.mouseY(), canvas.partialTick());

        // 工具栏行 y+155..170:左 chip(x+2, 46x13)、右发送(x+98, 20x13),中间留白
        chipX = x + 2;
        chipY = y + 156;
        Widgets.button(canvas, chipLabel(font), chipX, chipY, 46, 13, canvas.style().bodyColor(), true);

        sendX = x + 98;
        sendY = y + 156;
        String glyph = busy ? "■" : "▶";
        Widgets.button(canvas, glyph, sendX, sendY, 20, 13, canvas.style().accentColor(), true);
    }

    /** chip 文案:「思考:低 ∨」;加箭头后若超出按钮可容纳宽度(46-4,超出会被 Widgets 截断)则省去箭头,保证级别文本完整。 */
    private static String chipLabel(Font font) {
        String level = levelDisplay(AgentConfig.get().thinkingLevel);
        String full = "思考:" + level + " ∨";
        return font.width(full) <= 42 ? full : "思考:" + level;
    }

    /** thinkingLevel → 中文单字;null / 异常值按「低」显示。 */
    private static String levelDisplay(String level) {
        return switch (level == null ? "low" : level) {
            case "medium" -> "中";
            case "high" -> "高";
            default -> "低";
        };
    }

    /** 点击 chip:thinkingLevel 循环 low → medium → high → low,并立即持久化(只写 settings.json,不碰输入框草稿)。 */
    private static void cycleThinkingLevel() {
        AgentConfig cfg = AgentConfig.get();
        String cur = cfg.thinkingLevel;
        cfg.thinkingLevel = switch (cur == null ? "low" : cur) {
            case "low" -> "medium";
            case "medium" -> "high";
            default -> "low";
        };
        cfg.save();
    }

    /** ChatModel 变化时重建折行缓存:按 112px 折行,USER 右对齐,消息间留 3px;
     *  ASSISTANT 消息中的 [grid]…[/grid] 块转为配方网格行(无收尾标签则块延伸到消息尾)。 */
    private void rebuildIfNeeded(PhoneCanvas canvas) {
        ChatModel model = ChatModel.get();
        long rev = model.revision();
        if (rev == cacheRevision) {
            return;
        }
        cacheRevision = rev;
        lines.clear();
        var style = canvas.style();
        for (ChatModel.Msg m : model.snapshot()) {
            boolean user = m.role() == ChatModel.Role.USER;
            int color = switch (m.role()) {
                case USER -> style.accentColor();
                case ASSISTANT -> style.bodyColor();
                case ERROR -> COLOR_ERROR;
                case NOTE -> style.subtleColor();
            };
            if (m.details() != null) {
                ThinkLine tl = new ThinkLine(m.ts(), m.details(), 3);
                tl.expanded = thinkExpanded.contains(m.ts());
                lines.add(tl);
                continue;
            }
            String body = m.text() == null ? "" : m.text();
            String[] paragraphs = body.split("\n", -1);
            List<ChatLine> block = new ArrayList<>();
            int p = 0;
            while (p < paragraphs.length) {
                if (m.role() == ChatModel.Role.ASSISTANT
                        && paragraphs[p].trim().toLowerCase(java.util.Locale.ROOT).startsWith("[grid]")) {
                    String t = paragraphs[p].trim();
                    String first = t.substring("[grid]".length()).trim();
                    int close = first.toLowerCase(java.util.Locale.ROOT).indexOf("[/grid]");
                    if (close >= 0) {
                        // 单行形式:[grid]a|b|c => 产物[/grid] 全在一行(部分模型不换行),兜底渲染
                        parseGridInline(first.substring(0, close), block);
                        String tail = first.substring(close + "[/grid]".length()).trim();
                        if (!tail.isEmpty()) {
                            for (FormattedCharSequence l : canvas.font().split(Component.literal(tail), WRAP_W)) {
                                block.add(new TextLine(l, color, false, 0));
                            }
                        }
                    } else {
                        // 多行形式;标记同行可能还带了第一行内容
                        p = collectGrid(paragraphs, p, block, first.isEmpty() ? null : first);
                        continue;
                    }
                    p++;
                    continue;
                }
                String src = (user && p == 0) ? "> " + paragraphs[p] : paragraphs[p];
                List<FormattedCharSequence> wrapped = src.isEmpty()
                        ? List.of(FormattedCharSequence.EMPTY)
                        : canvas.font().split(Component.literal(src), WRAP_W);
                for (FormattedCharSequence l : wrapped) {
                    block.add(new TextLine(l, color, user, 0));
                }
                p++;
            }
            if (!block.isEmpty()) {
                ChatLine last = block.remove(block.size() - 1);
                block.add(withGap(last, 3));
                lines.addAll(block);
            }
        }
    }

    /** 给某行换一个消息间空隙(record 不可变,返回替换副本)。 */
    private static ChatLine withGap(ChatLine line, int gap) {
        if (line instanceof TextLine t) {
            return new TextLine(t.text(), t.color(), t.right(), gap);
        }
        if (line instanceof ThinkLine tl) {
            tl.gapAfter = gap;
            return tl;
        }
        GridLine gl = (GridLine) line;
        return new GridLine(gl.rows(), gl.result(), gap);
    }

    /**
     * 收集一个 [grid] 块(从 paragraphs[start] 的下一行到 [/grid] 前或消息尾),
     * 解析为网格行追加到 out(无有效行则不产出),返回消息中下一个待处理的行索引。
     * 宽容:畸形行直接忽略,不抛异常。
     */
    /** 网格块解析;firstLine = [grid] 标记同行携带的首行内容(模型把第一行写在标记后时非空)。 */
    private static int collectGrid(String[] paragraphs, int start, List<ChatLine> out, String firstLine) {
        List<ResourceLocation[]> rows = new ArrayList<>();
        ResourceLocation result = null;
        int p = start + 1;
        while (p < paragraphs.length && !paragraphs[p].trim().equalsIgnoreCase("[/grid]")) {
            String line = paragraphs[p].trim();
            if (!line.isEmpty()) {
                // 行尾内联产物:"…|feather => goety:x"(模型没把 => 单独放一行时)
                int arrow = line.indexOf("=>");
                if (arrow >= 0) {
                    if (result == null) {
                        result = ResourceLocation.tryParse(normalizeId(line.substring(arrow + 2)));
                    }
                    line = line.substring(0, arrow).trim();
                }
                if (!line.isEmpty()) {
                    addGridCells(line.split("\\|", -1), rows);
                }
            }
            p++;
        }
        if (firstLine != null && !firstLine.isBlank()) {
            String line = firstLine.trim();
            int arrow = line.indexOf("=>");
            if (arrow >= 0) {
                if (result == null) {
                    result = ResourceLocation.tryParse(normalizeId(line.substring(arrow + 2)));
                }
                line = line.substring(0, arrow).trim();
            }
            if (!line.isEmpty()) {
                addGridCells(line.split("\\|", -1), rows);
            }
        }
        if (!rows.isEmpty()) {
            out.add(new GridLine(rows.toArray(new ResourceLocation[0][]), result, 0));
        }
        return p < paragraphs.length ? p + 1 : p; // 跳过 [/grid](若存在)
    }

    /** 把一行按 | 拆出的单元格摊进网格行:≤3 格一行;超 3 格按 3 格一行摊开;最多 3 行;全空忽略。 */
    private static void addGridCells(String[] parts, List<ResourceLocation[]> rows) {
        for (int i = 0; i < parts.length && rows.size() < 3; i += 3) {
            int n = Math.min(3, parts.length - i);
            ResourceLocation[] row = new ResourceLocation[n];
            boolean any = false;
            for (int j = 0; j < n; j++) {
                row[j] = cellId(parts[i + j]);
                any = any || row[j] != null;
            }
            if (any) {
                rows.add(row);
            }
        }
    }

    /**
     * 单行网格兜底:[grid]a|b|c|d|e => 产物[/grid] 全在一行(模型没按格式换行)。
     * 摊平全部单元格按 3 格一行摆放,最多 3 行 9 格。
     */
    private static void parseGridInline(String inner, List<ChatLine> out) {
        String s = inner.trim();
        if (s.isEmpty()) {
            return;
        }
        ResourceLocation result = null;
        int arrow = s.indexOf("=>");
        if (arrow >= 0) {
            result = ResourceLocation.tryParse(normalizeId(s.substring(arrow + 2)));
            s = s.substring(0, arrow).trim();
        }
        String[] cells = s.split("\\|", -1);
        List<ResourceLocation[]> rows = new ArrayList<>();
        addGridCells(cells, rows);
        if (!rows.isEmpty()) {
            out.add(new GridLine(rows.toArray(new ResourceLocation[0][]), result, 0));
        }
    }

    /** 一行 "a|b|c" → ≤3 格(注册名或 null);超过 3 格返回 null(非法行忽略);全空行也返回 null。 */
    private static ResourceLocation[] parseGridRow(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length > 3) {
            return null;
        }
        ResourceLocation[] cells = new ResourceLocation[parts.length];
        boolean any = false;
        for (int i = 0; i < parts.length; i++) {
            cells[i] = cellId(parts[i]);
            any = any || cells[i] != null;
        }
        return any ? cells : null;
    }

    /** 归一化物品 id:去所有空白;无命名空间时补 minecraft:;空串原样(→ cellId 判空)。 */
    private static String normalizeId(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\s+", "");
        if (!s.isEmpty() && s.indexOf(':') < 0) {
            s = "minecraft:" + s;
        }
        return s;
    }

    /**
     * 单格解析:空串/空/空气/air/empty/none 等一律视为空格子(null);
     * 其余归一化为注册名。玩家要求“空就是空”,未知 id 渲染为空槽而不是缺失遮罩。
     */
    private static ResourceLocation cellId(String raw) {
        String s = normalizeId(raw);
        if (s.isEmpty()) {
            return null;
        }
        String low = s.toLowerCase(java.util.Locale.ROOT);
        String path = low.startsWith("minecraft:") ? low.substring("minecraft:".length()) : low;
        if (path.equals("kong") || path.equals("kongqi") || path.equals("air") || path.equals("empty")
                || path.equals("none") || path.equals("-")) {
            return null;
        }
        return ResourceLocation.tryParse(s);
    }

    /**
     * 配方网格:整体水平居中(3 列宽 52px),每格 16px 深色底 + 1px 描边模拟槽位,
     * 格间 2px;空槽只画槽位底;不足 3 格的行按实际格数画。产物在网格右侧 6px 处
     * 画 accent 色「▶」+ 16px 图标,垂直居中。无效注册名画洋红/黑缺失遮罩。
     * 贴近列表底部被裁掉的部分由外层 scissor 处理。
     */
    /** 思考过程块:折叠显示一行标题,展开逐步显示(思考原文与工具调用,长文本自动换行,灰色)。 */
    private static void renderThink(PhoneCanvas canvas, ThinkLine tl, int drawY) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        int x = canvas.x();
        int w = canvas.width();
        tl.wrapW = Math.max(40, w - 14);
        String title = (tl.expanded ? "▾ " : "▸ ") + "思考过程 · " + tl.details.size() + " 步";
        g.drawString(font, title, x + 6, drawY, canvas.style().subtleColor(), tl.expanded);
        if (tl.expanded) {
            int ly = drawY + LINE_H;
            for (String d : tl.details) {
                for (FormattedCharSequence line : font.split(Component.literal("· " + d), tl.wrapW)) {
                    g.drawString(font, line, x + 10, ly, canvas.style().subtleColor(), false);
                    ly += LINE_H;
                }
            }
        }
    }

    private static void renderGrid(PhoneCanvas canvas, GridLine gl, int drawY) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        int gx = canvas.x() + (canvas.width() - GRID_W) / 2;
        ResourceLocation[][] rows = gl.rows();
        for (int r = 0; r < rows.length; r++) {
            ResourceLocation[] row = rows[r];
            for (int c = 0; c < row.length; c++) {
                int cx = gx + c * CELL_STEP;
                int cy = drawY + 1 + r * CELL_STEP;
                g.fill(cx, cy, cx + CELL, cy + CELL, COLOR_SLOT_BG);
                g.renderOutline(cx, cy, CELL, CELL, COLOR_SLOT_EDGE);
                if (row[c] == null) {
                    continue; // 空槽只画底
                }
                Item item = itemOrNull(row[c]);
                if (item != null) {
                    g.renderItem(new ItemStack(item), cx, cy);
                } // 查不到的 id 按空槽显示(玩家要求:空就是空,不要乱码遮罩)
            }
        }
        ResourceLocation result = gl.result();
        if (result == null) {
            return;
        }
        int groupH = gl.height();
        int ax = gx + GRID_W + 6;
        int arrowY = drawY + groupH / 2 - 4; // 文本高 8px,取中
        g.drawString(font, "▶", ax, arrowY, canvas.style().accentColor(), false);
        int ix = ax + font.width("▶") + 1;
        int iy = drawY + (groupH - CELL) / 2;
        Item item = itemOrNull(result);
        if (item != null) {
            g.renderItem(new ItemStack(item), ix, iy);
        } // 查不到按空槽显示
    }

    /** MC 缺失材质风格:16x16 洋红/黑四象限棋盘遮罩。 */
    private static void drawMissing(GuiGraphics g, int x0, int y0) {
        int mx = x0 + CELL / 2;
        int my = y0 + CELL / 2;
        g.fill(x0, y0, mx, my, COLOR_MISSING_A);
        g.fill(mx, y0, x0 + CELL, my, COLOR_MISSING_B);
        g.fill(x0, my, mx, y0 + CELL, COLOR_MISSING_B);
        g.fill(mx, my, x0 + CELL, y0 + CELL, COLOR_MISSING_A);
    }

    /** 注册表查物品;查不到(缺省回退到 air 时 key 不符,或 null)返回 null。 */
    private static Item itemOrNull(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        return item != null && id.equals(BuiltInRegistries.ITEM.getKey(item)) ? item : null;
    }

    // ---------------------------------------------------------------- 交互

    boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (gearHovered) {
                openSettings.run();
                return true;
            }
            if (newHovered) {
                // 新对话:清空显示记录 + 重置发给模型的上下文(下次存档开新会话文件)
                ChatModel.get().clear();
                AgentEngine.get().resetConversation();
                com.mcagent.agent.ChatStore.markNewSession();
                pinned = true;
                scroll = 0;
                return true;
            }
            if (histHovered) {
                openHistory.run();
                return true;
            }
            if (Widgets.hit(mx, my, chipX, chipY, 46, 13)) {
                cycleThinkingLevel();
                return true;
            }
            if (Widgets.hit(mx, my, sendX, sendY, 20, 13)) {
                if (ChatModel.get().busy()) {
                    AgentEngine.get().cancel();
                } else {
                    send();
                }
                return true;
            }
        }
        // 活动行(框外)点击:切换思考区域 收缩/展开
        if (ChatModel.get().busy()
                && Widgets.hit(mx, my, toggleHit[0], toggleHit[1],
                        toggleHit[2] - toggleHit[0], toggleHit[3] - toggleHit[1])) {
            thinkPanelExpanded = !thinkPanelExpanded;
            thinkPanelScroll = 0;
            return true;
        }
        for (ThinkHit th : thinkHits) {
            if (my >= th.y0() && my <= th.y1()) {
                th.line().expanded = !th.line().expanded;
                if (th.line().expanded) {
                    thinkExpanded.add(th.line().msgTs);
                } else {
                    thinkExpanded.remove(th.line().msgTs);
                }
                return true;
            }
        }
        return input != null && input.mouseClicked(mx, my, button);
    }

    boolean mouseScrolled(double mx, double my, double amount) {
        // 展开的思考面板:滚轮先给它(上翻回看完整过程,翻到底自动跟随最新)
        if (ChatModel.get().busy() && thinkPanelExpanded
                && mx >= panelRect[0] && mx < panelRect[2]
                && my >= panelRect[1] && my < panelRect[3]) {
            int maxScroll = Math.max(0, panelTotalLines - panelVisibleLines);
            if (amount > 0) {
                thinkPanelScroll = Math.max(0, thinkPanelScroll - 3);
            } else if (amount < 0) {
                thinkPanelScroll = Math.min(maxScroll, thinkPanelScroll + 3);
            }
            return true;
        }
        if (amount > 0) {
            pinned = false;
            scroll = Math.max(0, scroll - 18);
            return true;
        }
        if (amount < 0) {
            scroll += 18; // 越界由渲染端钳制并自动恢复吸底
            return true;
        }
        return false;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter / 小键盘 Enter = 发送
            send();
            return true;
        }
        return input != null && input.keyPressed(keyCode, scanCode, modifiers);
    }

    boolean charTyped(char codePoint, int modifiers) {
        return input != null && input.charTyped(codePoint, modifiers);
    }

    /** 发送输入框内容(非空才提交);任务进行中按 Enter 不清草稿也不提交。 */
    private void send() {
        if (input == null) {
            return;
        }
        if (ChatModel.get().busy()) {
            return; // 忙碌时 Enter 不吞草稿;要停止请点「■」
        }
        String text = input.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        input.setValue("");
        pinned = true;
        AgentEngine.get().submit(text);
    }
}
