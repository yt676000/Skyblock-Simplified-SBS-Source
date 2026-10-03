/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.build.logic.Freecam;

/**
 * Freecam's view: after the camera has lined itself up with the player ({@code alignWithEntity}),
 * it is moved to the freecam position and rotation and marked detached, so the player's own model is
 * drawn where they stand. Nothing about the player entity changes.
 *
 * <p>The second hook does for freecam what vanilla does for a spectator inside a block
 * ({@code Camera.extractRenderState}, checked in the 26.2 bytecode): occlusion culling is switched off
 * while the camera sits in a solid block, or the chunk graph would cull everything around it. The
 * in-block screen overlay needs nothing: vanilla takes it from the player's eyes, not the camera.
 * The zoom ({@code calculateFov}) is untouched and keeps working.
 */
@Mixin(Camera.class)
public abstract class FreecamCameraMixin {

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void skyblockSimplified$freecamView(float partialTick, CallbackInfo ci) {
        if (Freecam.active()) {
            setPosition(Freecam.position(partialTick));
            setRotation(Freecam.yaw(), Freecam.pitch());
            this.detached = true;
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void skyblockSimplified$freecamCulling(CameraRenderState state, float partialTick, CallbackInfo ci) {
        if (Freecam.cameraInSolid()) {
            state.smartCull = false;
        }
    }
}
