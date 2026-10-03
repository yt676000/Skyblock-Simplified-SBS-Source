/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.terrain.FarTerrainFog;

/**
 * The second half of the fog setting - the half whose absence kept far terrain washed out after the
 * first half shipped.
 *
 * <p>Fog is not one thing. The render-distance fog (handled in {@code FarTerrainFogMixin}) is the
 * curtain at the edge of the drawn world; this class handles the <b>atmospheric haze</b>, the much
 * softer fog the air itself carries everywhere, thickened by rain. Pushing the first back without
 * the second removed the curtain but kept the haze, so distant terrain still faded toward the sky
 * colour - the "still whiteish" report, from a screenshot where pale mountains stood against a night
 * sky that distance fog would have tinted dark.
 *
 * <p><b>Why this hooks the atmospheric environment and not the fog fields.</b> The
 * {@code environmental*} fields are written by every fog environment - air, water, lava, blindness,
 * darkness, powder snow are separate {@link net.minecraft.client.renderer.fog.environment.FogEnvironment}
 * classes all filling the same struct. Editing the fields from outside would need a correct guess
 * about which environment produced them, and guessing wrong turns "clear air" into "see through
 * lava", which is a cheat. Hooking {@link AtmosphericFogEnvironment} alone means the other
 * environments are untouched by construction, whatever they write.
 */
@Mixin(AtmosphericFogEnvironment.class)
public class FarTerrainAtmosphericFogMixin {

    @Inject(method = "setupFog", at = @At("TAIL"))
    private void skyblockSimplified$clearAirHaze(FogData data, Camera camera, ClientLevel level,
                                                 float renderDistance, DeltaTracker deltaTracker,
                                                 CallbackInfo ci) {
        if (FarTerrainFog.removeDistanceFog()) {
            // Same construction as the render-distance planes: finite, ordered, and past anything
            // drawable - never an infinity for the fog maths to choke on. Rain's pulled-in values
            // are overwritten with the rest; at "no fog" a rainy horizon stays a visible horizon.
            float far = Math.max(4096.0f,
                    Minecraft.getInstance().options.renderDistance().get() * 16.0f * 4.0f);
            data.environmentalStart = far;
            data.environmentalEnd = far * 1.25f;
            return;
        }
        float scale = FarTerrainFog.distanceScale();
        if (scale <= 1.0f) {
            return;
        }
        data.environmentalStart *= scale;
        data.environmentalEnd *= scale;
    }
}
