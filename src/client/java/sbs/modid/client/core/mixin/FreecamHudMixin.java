/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.contextualbar.ContextualBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.build.logic.Freecam;
import sbs.modid.client.helper.build.render.FreecamIndicator;

/**
 * Cinematic freecam's clean screen, and the freecam indicator.
 *
 * <p>"Hide HUD" with world markers off: the whole HUD pass is skipped, like F1 - vanilla and every
 * SBS card and marker, since SBS draws its world markers from this pass too. Only the indicator is
 * drawn, for its first 2 s. With "Show World Markers" on the pass runs, but every vanilla part is
 * skipped here and every SBS card through {@code HudLayout.isHidden}, leaving the world-projected
 * markers (holograms, waypoints, highlights).
 */
@Mixin(Hud.class)
public abstract class FreecamHudMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamCleanScreen(GuiGraphicsExtractor g, DeltaTracker deltaTracker,
                                                        CallbackInfo ci) {
        if (Freecam.hidesWorldMarkers()) {
            FreecamIndicator.render(g);
            ci.cancel();
        }
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void skyblockSimplified$freecamIndicator(GuiGraphicsExtractor g, DeltaTracker deltaTracker,
                                                     CallbackInfo ci) {
        FreecamIndicator.render(g);
    }

    private static void sbs$skipWhenHidden(CallbackInfo ci) {
        if (Freecam.hidesHud()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractCameraOverlays", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamOverlays(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamCrosshair(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamHotbar(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamHealth(GuiGraphicsExtractor g, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractVehicleHealth", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamVehicle(GuiGraphicsExtractor g, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamItemName(GuiGraphicsExtractor g, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamEffects(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractChat", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamChat(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamSidebar(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractTabList", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamTabList(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamBossBar(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamTitle(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamActionBar(GuiGraphicsExtractor g, DeltaTracker d, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    @Inject(method = "extractSubtitleOverlay", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamSubtitles(GuiGraphicsExtractor g, boolean deferred, CallbackInfo ci) {
        sbs$skipWhenHidden(ci);
    }

    // The experience / locator bar is drawn inline in extractHotbarAndDecorations, whose TAIL is where
    // the SBS world markers draw - so the bar's own calls are skipped, not the method.

    @WrapWithCondition(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private boolean skyblockSimplified$freecamBarBackground(ContextualBar bar, GuiGraphicsExtractor g, DeltaTracker d) {
        return !Freecam.hidesHud();
    }

    @WrapWithCondition(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private boolean skyblockSimplified$freecamBar(ContextualBar bar, GuiGraphicsExtractor g, DeltaTracker d) {
        return !Freecam.hidesHud();
    }

    @WrapWithCondition(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"))
    private boolean skyblockSimplified$freecamLevel(GuiGraphicsExtractor g, Font font, int level) {
        return !Freecam.hidesHud();
    }
}
