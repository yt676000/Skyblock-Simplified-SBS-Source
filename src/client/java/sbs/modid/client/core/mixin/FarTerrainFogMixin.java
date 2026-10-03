/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.terrain.FarTerrainFog;

/**
 * Pushes the distance fog back so remembered terrain is actually visible.
 *
 * <p>Vanilla ends its fog just short of the render distance, which is correct when that is where the
 * world stops - but with far terrain the world keeps going, and the fog then hides precisely what
 * the module exists to show. {@code setupFog} returns a mutable {@link FogData}, so the two
 * distance-fog fields are simply pushed outward by the player's chosen strength on the way out.
 *
 * <p>Only the <b>render-distance</b> planes are touched here. The {@code environmental*} fields are
 * a shared struct that every fog environment writes - the air's own haze included, which is why
 * clearing this fog alone still left far terrain washed out. The air half lives in
 * {@code FarTerrainAtmosphericFogMixin}, hooked on {@code AtmosphericFogEnvironment} so that the
 * water, lava and blindness environments stay untouched by construction - moving those would let
 * you see through a lava bath, a cheat rather than a view.
 */
@Mixin(FogRenderer.class)
public class FarTerrainFogMixin {

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void skyblockSimplified$pushBackFog(net.minecraft.client.Camera camera, int renderDistance,
                                                net.minecraft.client.DeltaTracker deltaTracker,
                                                float partialTick,
                                                net.minecraft.client.multiplayer.ClientLevel level,
                                                CallbackInfoReturnable<FogData> cir) {
        FogData data = cir.getReturnValue();
        if (data == null) {
            return;
        }
        if (FarTerrainFog.removeDistanceFog()) {
            // Pushed so far out that nothing drawable can reach it, which is how you get rid of fog
            // without a second code path: the fog factor is derived from where a fragment sits
            // between start and end, and every fragment now sits before the start.
            //
            // Derived from the render distance rather than a huge constant on purpose - the planes
            // stay ordered and finite, so the arithmetic behind them cannot produce an infinity or a
            // NaN and paint the world a solid colour.
            float far = Math.max(4096.0f, renderDistance * 16.0f * 4.0f);
            data.renderDistanceStart = far;
            data.renderDistanceEnd = far * 1.25f;
            data.skyEnd = far;
            data.cloudEnd = far * 1.25f;
            return;
        }
        float scale = FarTerrainFog.distanceScale();
        if (scale <= 1.0f) {
            return;   // vanilla strength - leave every value exactly as it was
        }
        data.renderDistanceStart *= scale;
        data.renderDistanceEnd *= scale;
        // The sky and cloud planes have to move with it, or a pushed-back horizon meets an
        // un-pushed sky and the seam is more obvious than the fog was.
        data.skyEnd *= scale;
        data.cloudEnd *= scale;
    }
}
