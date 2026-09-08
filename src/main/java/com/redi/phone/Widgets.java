package com.redi.phone;

import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 极简控件绘制工具:描边小按钮与矩形命中测试,聊天 / 设置两个视图共用。
 * 只使用 GuiGraphics.fill / renderOutline / drawString 等 1.21.1 标准 UI API。
 */
final class Widgets {
    private Widgets() {
    }

    /** 矩形命中测试(左闭右开,与 PhoneCanvas.hovered 一致)。 */
    static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /**
     * 画一个描边小按钮(文字垂直水平居中),返回鼠标当前是否悬停其上。
     * 禁用时使用主题的 buttonDisabled 配色;悬停时描边提亮为主题 accent 色。
     * 文本超出按钮宽度时按宽截断,避免画到按钮外。
     */
    static boolean button(PhoneCanvas canvas, String text, int x, int y, int w, int h, int textColor, boolean enabled) {
        GuiGraphics g = canvas.graphics();
        boolean hovered = enabled && canvas.hovered(x, y, w, h);
        int bg = enabled
                ? (hovered ? canvas.style().buttonHoverColor() : canvas.style().buttonColor())
                : canvas.style().buttonDisabledColor();
        int fg = enabled ? textColor : canvas.style().buttonDisabledTextColor();
        g.fill(x, y, x + w, y + h, bg);
        g.renderOutline(x, y, w, h, hovered ? canvas.style().accentColor() : canvas.style().subtleColor());
        var font = canvas.font();
        String shown = text;
        if (font.width(shown) > w - 4) {
            shown = font.plainSubstrByWidth(shown, w - 4);
        }
        g.drawString(font, shown, x + (w - font.width(shown)) / 2, y + (h - 8) / 2, fg, false);
        return hovered;
    }
}
