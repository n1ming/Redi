package com.redi.phone;

import com.redi.RediMod;
import com.redi.agent.AgentEngine;
import com.redi.agent.ChatModel;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 「AI 助手」App —— 通过 Java SPI 被 mcphone 发现(见 META-INF/services)。
 * 默认预装;点开进入聊天页。
 */
public final class AgentApp implements IPhoneApp {
    private final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(RediMod.MODID, "agent");
    private final ResourceLocation icon = ResourceLocation.fromNamespaceAndPath(RediMod.MODID, "textures/gui/agent_icon.png");

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("redi.app.agent");
    }

    @Override
    public ResourceLocation getIconTexture() {
        return icon;
    }

    /**
     * 平铺模式:返回 null —— mcphone 的 PhoneScreen.launchApp(反编译只读:PhoneScreen.java
     * 「page = app.openPage()」,page==null 时回退调 onPress())不会 openAddonPage,
     * 因此不再进入手机全屏。
     */
    @Override
    public IPhonePage openPage() {
        return null;
    }

    /**
     * openPage()==null 时被 mcphone launchApp 回退调用(已在主线程/渲染线程):
     * 直接把当前屏幕替换为平铺面板(不进手机全屏,物品栏与移动照常可用)。
     */
    @Override
    public void onPress() {
        com.redi.RediMod.LOGGER.info("[redi] onPress → 打开平铺界面");
        Minecraft.getInstance().setScreen(new AgentFlatScreen());
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getAuthor() {
        return "redi";
    }

    @Override
    public String getDescription() {
        return Component.translatable("redi.app.agent.desc").getString();
    }

    @Override
    public boolean isPreinstalled() {
        return true;
    }
}
