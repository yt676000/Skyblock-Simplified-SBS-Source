/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Disable View Bobbing: skips {@code GameRenderer.bobView} while the SBS setting is on.
 *
 * <p>{@code bobView} is the only place the walking sway is applied, and it is called from both
 * the hand pass and the world pass (each behind vanilla's own {@code bobView} option check), so
 * cancelling it at the head removes the camera and the hand bob together. The vanilla option is
 * never read or written here: with the SBS setting off, vanilla's check decides as before.
 *
 * <p>{@code bobHurt} - the damage tilt - is a separate method and stays untouched.
 */
@Mixin(GameRenderer.class)
public abstract class ViewBobbingMixin {

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$disableViewBobbing(CameraRenderState cameraState, PoseStack poseStack,
                                                       CallbackInfo ci) {
        if (ConfigManager.getInstance().get().viewBobbing.disable) {
            ci.cancel();
        }
    }
}
