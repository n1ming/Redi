package com.redi.phone;

import com.november.mcphone.api.client.ui.PhoneStyle;

/**
 * 平铺模式({@link AgentFlatScreen})的固定深色配色,实现 mcphone 的
 * {@link PhoneStyle}。仅供 PhoneCanvas 体系视图(聊天/设置/工作台/熔炉)取色。
 *
 * <p>色值来源(反编译只读参考,不 import com.november.mcphone.core 包,自行落地同款数值):
 * <ul>
 *   <li>文字三色按 FontPalette(WHITE 预设:strong=0xFFFFFF / weak=0x8A8A8A,深色底亮文字档):
 *       {@code neutral(p) = weak + (strong-weak)*p/10(逐通道)}——
 *       title=neutral(10)=0xFFFFFFFF、body=neutral(7)=0xFFDBDBDB、subtle=neutral(3)=0xFFADADAD;</li>
 *   <li>accent = FontPalette.price()(darkText=false 时取 onDarkBackground 字面量
 *       -10929 = 0xFFFFD54E,琥珀金);</li>
 *   <li>pressedOverlay 与按钮四色照抄 ThemeStyle 的字面量:
 *       0x44FFFFFF / -13730510(0xFF2E7D32)/ -12345273(0xFF43A047)/
 *       -12961222(0xFF3A3A3A)/ -7829368(0xFF888888);</li>
 *   <li>screenBackground 用与平铺面板底(0xC0101420)同色系的不透明深色
 *       0xFF101420——视图本身不取该色,仅保持接口完整且视觉一致。</li>
 * </ul></p>
 */
public final class FlatStyle implements PhoneStyle {

    public static final FlatStyle INSTANCE = new FlatStyle();

    private FlatStyle() {
    }

    @Override
    public int titleColor() {
        return 0xFFFFFFFF; // FontPalette.title() = neutral(10),WHITE 预设纯白
    }

    @Override
    public int bodyColor() {
        return 0xFFDBDBDB; // FontPalette.body() = neutral(7):138 + 117*7/10 = 219(0xDB)
    }

    @Override
    public int subtleColor() {
        return 0xFFADADAD; // FontPalette.subtle() = neutral(3):138 + 117*3/10 = 173(0xAD)
    }

    @Override
    public int accentColor() {
        return 0xFFFFD54F; // FontPalette.price() 字面量 -10929(琥珀金)
    }

    @Override
    public int screenBackground() {
        return 0xFF101420; // 深色版手机屏底:与平铺面板底 0xC0101420 同色系的不透明值
    }

    @Override
    public int pressedOverlay() {
        return 0x44FFFFFF; // ThemeStyle 原值(busy 带 / 悬停行压亮)
    }

    @Override
    public int buttonColor() {
        return 0xFF2E7D32; // ThemeStyle -13730510(深绿)
    }

    @Override
    public int buttonHoverColor() {
        return 0xFF43A047; // ThemeStyle -12345273(亮绿)
    }

    @Override
    public int buttonDisabledColor() {
        return 0xFF3A3A3A; // ThemeStyle -12961222
    }

    @Override
    public int buttonDisabledTextColor() {
        return 0xFF888888; // ThemeStyle -7829368
    }
}
