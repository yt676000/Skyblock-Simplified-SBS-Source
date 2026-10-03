/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.visual.model.FireOverlayMode;

/**
 * Visuals module: adjusts the first-person fire overlay so it stops blocking central vision.
 *
 * <p>Wraps the fire submission in {@code ScreenEffectRenderer#submitFire}: <b>Off</b> cancels it
 * entirely; <b>Lowered</b> pushes a scaled-down, shifted matrix so the flames only occupy the bottom
 * of the screen; <b>Default</b> leaves vanilla untouched. The matrix push at HEAD is balanced by a pop
 * at RETURN (the Off path cancels before either, so the stack stays balanced).
 */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectFireMixin {

    private static FireOverlayMode sbs$mode() {
        return ConfigManager.getInstance().get().visuals.fireOverlay;
    }

    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void skyblockSimplified$fireHead(PoseStack pose, SubmitNodeCollector collector,
                                                    TextureAtlasSprite sprite, CallbackInfo ci) {
        FireOverlayMode mode = sbs$mode();
        if (mode == FireOverlayMode.OFF) {
            ci.cancel();
            return;
        }
        if (mode == FireOverlayMode.LOWERED) {
            // Derived from ScreenEffectRenderer#buildFireQuad: the flame quad is y in [-0.5, 0.5] and
            // vanilla shifts it by y=-0.3, so on screen it spans y in [-0.8, 0.2] (Y is up; -0.8 is the
            // bottom edge). Plain translates pushed it off-screen. Instead we SCALE about the bottom
            // anchor (-0.8): the base stays exactly where the visible flames already sit, and the
            // height collapses into the bottom ~20% band, freeing central vision. FIRE_SHRINK is the
            // single tuning knob (smaller = thinner bottom strip).
            final float bottomAnchor = -0.8F;
            final float fireShrink = 0.2F;
            pose.pushPose();
            pose.translate(0.0F, bottomAnchor, 0.0F);
            pose.scale(1.0F, fireShrink, 1.0F);
            pose.translate(0.0F, -bottomAnchor, 0.0F);
        }
    }

    @Inject(method = "submitFire", at = @At("RETURN"))
    private static void skyblockSimplified$fireReturn(PoseStack pose, SubmitNodeCollector collector,
                                                      TextureAtlasSprite sprite, CallbackInfo ci) {
        if (sbs$mode() == FireOverlayMode.LOWERED) {
            pose.popPose();
        }
    }
}
