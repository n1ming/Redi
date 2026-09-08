package com.redi.client;

import com.redi.plugin.PluginManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * “Agent 插件”面板:从 ESC 暂停菜单进入,列出当前已装载的插件与各自登记的工具。
 * 每个插件一个独立区间(描边盒子),整体居中、滚轮翻阅;ESC 返回。
 */
public final class AgentPluginsScreen extends Screen {
    private static final int LINE_H = 12;
    private static final int PAD = 5;
    private static final int GAP = 8;
    private final Screen parent;
    private int scroll;

    public AgentPluginsScreen(Screen parent) {
        super(Component.literal("Agent 插件"));
        this.parent = parent;
    }

    /** 一个插件区间:标题 + 工具行,渲染前算好高度。 */
    private record Box(String id, List<String> tools, int height) {
    }

    private List<Box> boxes() {
        List<Box> out = new ArrayList<>();
        for (String id : PluginManager.loadedIds()) {
            List<String> tools = PluginManager.toolsOf(id);
            int h = PAD + LINE_H + 3 + tools.size() * LINE_H + PAD;
            out.add(new Box(id, tools, h));
        }
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        List<Box> list = boxes();
        int n = 0;
        for (Box b : list) {
            n += b.tools().size();
        }
        g.drawCenteredString(this.font,
                Component.literal("已装载 " + list.size() + " 个插件 · 共 " + n + " 个工具 · 滚轮翻阅 · ESC 返回"),
                this.width / 2, 24, 0xFF909090);

        // 内容区(标题带以下全部可用高度),整体裁剪 + 滚动
        int top = 40;
        int bottom = this.height - 6;
        int boxW = Math.min(320, this.width - 16);
        int x = (this.width - boxW) / 2;

        int contentH = 0;
        for (int i = 0; i < list.size(); i++) {
            contentH += list.get(i).height() + (i < list.size() - 1 ? GAP : 0);
        }
        int maxScroll = Math.max(0, contentH - (bottom - top));
        scroll = Math.min(scroll, maxScroll);

        g.enableScissor(0, top, this.width, bottom);
        int y = top - scroll;
        for (Box b : list) {
            if (y + b.height() >= top && y <= bottom) {
                // 插件区间盒子:半透明底 + 描边
                g.fill(x, y, x + boxW, y + b.height(), 0x98000000);
                g.renderOutline(x, y, boxW, b.height(), 0xFF5A7A9A);
                g.drawString(this.font, "▸ " + b.id() + "  (" + b.tools().size() + " 个工具)",
                        x + PAD + 2, y + PAD, 0xFF7FD57F, false);
                int ly = y + PAD + LINE_H + 3;
                for (String t : b.tools()) {
                    g.drawString(this.font, "· " + t, x + PAD + 10, ly, 0xFFC8C8C8, false);
                    ly += LINE_H;
                }
            }
            y += b.height() + GAP;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll = Math.max(0, scroll - (int) (sy * 30));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
