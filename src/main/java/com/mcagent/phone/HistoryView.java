package com.mcagent.phone;

import com.mcagent.agent.ChatStore;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 历史会话列表视图。画布高度由外层决定(平铺屏给的高约 140),以下 y 偏移相对 canvas
 * 左上角,宽度按 canvas.width() 撑满:
 * <ul>
 *   <li>顶行 y+2..y+11:左「历史会话」,右「&lt; 返回」({@code openBack} 回调);y+13 分隔线;</li>
 *   <li>列表 y+14 .. y+h-2:每行 18px(两行文本——标题 + 小字「时间 · N 条」);
 *       点行 = ChatStore.loadInto + {@code openBack} 回聊天页;行尾「×」= 只删文件并刷新,不加载;</li>
 *   <li>滚轮:按行高 18 步进翻动(越界由渲染端钳制);空会话居中显示「还没有历史会话」。</li>
 * </ul>
 * 列表数据在渲染线程按 {@link ChatStore#revision()} 变化自动重载(保存/删除都会推进),
 * 也可由外层在切入本页时调 {@link #reload()} 立即刷新。深色主题,无冗余提示文字。
 */
final class HistoryView {
    private static final int LINE_H = 9;
    /** 列表行高:标题 9px + 小字 9px。 */
    private static final int ROW_H = 18;
    private static final int COLOR_DIVIDER = 0x33FFFFFF;
    /** 删除「×」悬停红(与 ChatView 的错误红同值)。 */
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final Runnable openBack;

    // 列表数据与滚动;seenRevision 用于跟随 ChatStore 的保存/删除自动重载
    private List<ChatStore.SessionMeta> items = null;
    private long seenRevision = Long.MIN_VALUE;
    private int scroll = 0;

    // 渲染期刷新的命中区与悬停态(点击判定复用,与 ChatView/SettingsView 同套路)
    private boolean backHovered;
    private int rowHovered = -1;
    private int delHovered = -1;

    HistoryView(Runnable openBack) {
        this.openBack = openBack;
    }

    /** 立即重载列表(外层切入历史页时可调;平时渲染端按 revision 自动刷新)。 */
    void reload() {
        seenRevision = ChatStore.revision();
        items = ChatStore.list();
    }

    // ---------------------------------------------------------------- 渲染

    void render(PhoneCanvas canvas) {
        GuiGraphics g = canvas.graphics();
        Font font = canvas.font();
        int x = canvas.x();
        int y = canvas.y();
        int w = canvas.width();

        // 顶行:标题 + 「< 返回」
        g.drawString(font, Component.literal("历史会话"), x + 2, y + 2, canvas.style().titleColor(), true);
        String back = "< " + Component.translatable("mcagent.chat.back").getString();
        int backW = font.width(back);
        int backX = x + w - backW - 2;
        backHovered = canvas.hovered(backX - 3, y, backW + 6, LINE_H + 4);
        g.drawString(font, back, backX, y + 2, backHovered ? canvas.style().accentColor() : canvas.style().subtleColor(), false);
        g.fill(x + 2, y + 13, x + w - 2, y + 14, COLOR_DIVIDER);

        // 保存/删除会推进 ChatStore.revision(),比对后自动重载(首次 items==null 也重载)
        long rev = ChatStore.revision();
        if (items == null || rev != seenRevision) {
            reload();
        }

        int listTop = y + 14;
        int listBottom = y + canvas.height() - 2;
        int visible = Math.max(LINE_H, listBottom - listTop);

        rowHovered = -1;
        delHovered = -1;

        if (items.isEmpty()) {
            String empty = "还没有历史会话";
            g.drawString(font, empty, x + (w - font.width(empty)) / 2,
                    listTop + Math.max(0, (visible - LINE_H) / 2), canvas.style().subtleColor(), false);
            return;
        }

        int contentH = items.size() * ROW_H;
        scroll = Math.min(Math.max(0, scroll), Math.max(0, contentH - visible));

        // 标题/小字可用宽:行尾「×」留 12px,左右各留 2px
        int textW = w - 16;
        g.enableScissor(x, listTop, x + w, listBottom);
        int ry = listTop - scroll;
        for (int i = 0; i < items.size(); i++) {
            if (ry + ROW_H > listTop && ry < listBottom) {
                ChatStore.SessionMeta m = items.get(i);
                int delX = x + w - 10;
                boolean overDel = canvas.hovered(delX - 1, ry, 11, ROW_H);
                boolean overRow = !overDel && canvas.hovered(x, ry, w, ROW_H);
                if (overRow) {
                    rowHovered = i;
                }
                if (overDel) {
                    delHovered = i;
                }
                if (overRow || overDel) {
                    g.fill(x, ry, x + w, ry + ROW_H, canvas.style().pressedOverlay());
                }
                g.drawString(font, clip(font, m.title(), textW), x + 2, ry, canvas.style().bodyColor(), false);
                String sub = TIME_FMT.format(Instant.ofEpochMilli(m.savedAt()).atZone(ZoneId.systemDefault()))
                        + " · " + m.messageCount() + " 条";
                g.drawString(font, clip(font, sub, textW), x + 2, ry + LINE_H, canvas.style().subtleColor(), false);
                g.drawString(font, "×", delX, ry + 5, overDel ? COLOR_ERROR : canvas.style().subtleColor(), false);
            }
            ry += ROW_H;
        }
        g.disableScissor();
    }

    private static String clip(Font font, String text, int maxW) {
        if (font.width(text) <= maxW) {
            return text;
        }
        return font.plainSubstrByWidth(text, maxW - font.width("…")) + "…";
    }

    // ---------------------------------------------------------------- 交互

    boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        if (backHovered) {
            openBack.run();
            return true;
        }
        if (items == null || items.isEmpty()) {
            return false;
        }
        if (delHovered >= 0 && delHovered < items.size()) {
            ChatStore.delete(items.get(delHovered).fileName());
            reload(); // 只删文件并刷新列表,不加载
            return true;
        }
        if (rowHovered >= 0 && rowHovered < items.size()) {
            ChatStore.loadInto(items.get(rowHovered).fileName());
            openBack.run(); // 回灌后返回聊天页
            return true;
        }
        return false;
    }

    boolean mouseScrolled(double amount) {
        if (amount > 0) {
            scroll = Math.max(0, scroll - ROW_H);
        }
        if (amount < 0) {
            scroll += ROW_H; // 越界由渲染端钳制
        }
        return true;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false; // 本页无输入控件
    }

    boolean charTyped(char codePoint, int modifiers) {
        return false;
    }
}
