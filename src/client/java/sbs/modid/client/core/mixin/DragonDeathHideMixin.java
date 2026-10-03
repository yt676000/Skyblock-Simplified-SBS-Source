/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Visuals module: <b>Hide Dragon Death Animation</b>. {@code deathTime} is zero for a living dragon
 * and starts counting the moment one begins to die, so cancelling {@code submit} on a non-zero
 * death time drops the dissolving body <i>and</i> the beams of light it fires out (both are drawn
 * from this one method) while leaving healthy dragons untouched.
 */
@Mixin(EnderDragonRenderer.class)
public class DragonDeathHideMixin {

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EnderDragonRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hideDragonDeath(EnderDragonRenderState state, PoseStack poseStack,
                                                    SubmitNodeCollector collector, CameraRenderState camera,
                                                    CallbackInfo ci) {
        if (state.deathTime > 0.0F && ConfigManager.getInstance().get().visuals.hideDragonDeath) {
            ci.cancel();
        }
    }
}
