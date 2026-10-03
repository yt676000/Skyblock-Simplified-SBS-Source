/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.visual.logic.Fullbright;

/**
 * The Fullbright module: floors every lightmap texel at white while the feature is on.
 *
 * <p>{@code LightmapRenderStateExtractor.extract} fills the {@link LightmapRenderState} the lightmap
 * shader reads through its UBO. Injecting at the tail – after vanilla has populated every field –
 * lets {@link Fullbright} overwrite just the night-vision pair, which {@code lightmap.fsh} folds in
 * as {@code max(AmbientColor, NightVisionColor * NightVisionFactor)}: a white colour at factor 1
 * floors the whole map at white. Sky and block light still add on top and are clamped away, so the
 * result is uniform full brightness.
 *
 * <p>Tail rather than every {@code RETURN} on purpose: the method returns early when the lightmap
 * needs no update, and that path leaves the state deliberately stale. The extractor's {@code tick()}
 * raises {@code needsUpdate} every tick, so a toggle still lands within one tick.
 *
 * <p>Purely additive – with the module off nothing is touched, and no option is ever written, so
 * there is no saved brightness to restore or to strand.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapFullbrightMixin {

    @Inject(method = "extract", at = @At("TAIL"))
    private void skyblockSimplified$fullbright(LightmapRenderState state, float partialTick,
                                               CallbackInfo ci) {
        if (Fullbright.active()) {
            state.nightVisionColor = LightmapRenderStateExtractor.WHITE;
            state.nightVisionEffectIntensity = 1.0f;
        }
    }
}
