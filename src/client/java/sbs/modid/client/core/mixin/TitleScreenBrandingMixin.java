/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import sbs.modid.client.ui.titlescreen.TitleBranding;

/**
 * Puts the SBS brand banner on the title screen in place of the Minecraft logo.
 *
 * <p>The hook is the {@link LogoRenderer} call inside {@code TitleScreen.extractRenderState}, which
 * is exactly the right slot: it runs after the panorama and after the menu widgets, and it is
 * handed the fade-in factor the whole screen is coming up with. Redirecting it - rather than
 * injecting at the tail and drawing on top - also means switching the feature off restores vanilla
 * completely, because the original call is simply made instead.
 *
 * <p>{@link TitleBranding} draws both halves of the banner (the mark above the menu, the wordmark
 * below it), so the vanilla logo's position is not reused at all.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenBrandingMixin extends Screen {

    protected TitleScreenBrandingMixin(Component title) {
        super(title);
    }

    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState"
                            + "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"))
    private void skyblockSimplified$brandTitleScreen(LogoRenderer logoRenderer, GuiGraphicsExtractor g,
                                                     int screenWidth, float fade) {
        if (!TitleBranding.enabled()) {
            logoRenderer.extractRenderState(g, screenWidth, fade);
            return;
        }
        TitleBranding.render(g, this, this.width, this.height, fade);
    }

    /**
     * Replaces the rotating panorama with a flat black backdrop. Redirecting the panorama call
     * rather than painting over it also skips rendering the scene entirely, so the title screen
     * stops spending a frame's worth of work on something that is then covered up.
     */
    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/TitleScreen;extractPanorama"
                            + "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V"))
    private void skyblockSimplified$blackBackground(TitleScreen screen, GuiGraphicsExtractor g,
                                                    float partialTick) {
        if (!TitleBranding.enabled()) {
            // extractPanorama is protected on Screen, which this mixin extends, so the original call
            // is reachable directly - and `screen` is this very instance.
            this.extractPanorama(g, partialTick);
            return;
        }
        TitleBranding.renderBackground(g, this.width, this.height);
    }

    /**
     * Drops the splash text while the SBS banner is up. Its position is derived from the vanilla
     * logo's right-hand corner, so against a mark that is both wider and taller it lands squarely on
     * top of the letters. Vanilla is untouched with the feature off.
     */
    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/SplashRenderer;extractRenderState"
                            + "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V"))
    private void skyblockSimplified$hideSplash(SplashRenderer splash, GuiGraphicsExtractor g,
                                               int screenWidth, Font font, float fade) {
        if (!TitleBranding.enabled()) {
            splash.extractRenderState(g, screenWidth, font, fade);
        }
    }
}
