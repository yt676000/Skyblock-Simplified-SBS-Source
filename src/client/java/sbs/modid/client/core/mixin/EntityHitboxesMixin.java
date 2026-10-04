/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.hitboxes.EntityHitboxes;

/**
 * Entity Hitboxes: adds the chosen entities' hitboxes to the frame's debug gizmos. At the TAIL of
 * {@code DebugRenderer.emitGizmos}, which {@code LevelExtractor.extract} calls every frame inside the
 * gizmo scope (verified on the 26.2 jar) - the same place vanilla's F3+B boxes come from, so they are
 * depth-tested the same way. Off: one boolean check inside {@link EntityHitboxes#emit}.
 */
@Mixin(DebugRenderer.class)
public abstract class EntityHitboxesMixin {

    @Inject(method = "emitGizmos(Lnet/minecraft/client/renderer/culling/Frustum;DDDF)V", at = @At("TAIL"))
    private void skyblockSimplified$entityHitboxes(Frustum frustum, double camX, double camY, double camZ,
                                                   float partialTick, CallbackInfo ci) {
        EntityHitboxes.getInstance().emit(frustum, camX, camY, camZ, partialTick);
        // Reward chest boxes: the same depth-tested gizmo scope.
        sbs.modid.client.dungeons.chest.RewardChestGlow.getInstance().emit(camX, camY, camZ);
    }
}
