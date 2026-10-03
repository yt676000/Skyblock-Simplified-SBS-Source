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
import sbs.modid.client.helper.visual.logic.DarkMode;
import sbs.modid.client.helper.visual.logic.Fullbright;

/**
 * Dark Mode's uniform brightness: the whole world lit at one chosen level, with no light gradient
 * left at all.
 *
 * <p>Same seam as {@link LightmapFullbrightMixin} - {@code LightmapRenderStateExtractor.extract}
 * fills the state the lightmap shader reads through its UBO - but a different edit. {@code
 * lightmap.fsh} builds each texel as {@code max(AmbientColor, NightVisionColor * NightVisionFactor)}
 * plus {@code SkyLightColor * sky} plus {@code BlockLightColor * block}: zeroing the two factors and
 * handing it a flat grey ambient colour leaves every texel exactly that grey. A cave, a lit island
 * and a dungeon corridor then all read identically bright, which is what "uniform" means here and
 * what a light-level floor (Fullbright's edit) does not give.
 *
 * <p>The night-vision pair is zeroed with them, deliberately. It is a second, higher floor in the
 * same {@code max()}, and half of SkyBlock plays with a permanent night-vision talisman - left
 * alone, it would sit above the chosen level and the setting would look broken for exactly those
 * players.
 *
 * <p><b>Fullbright wins.</b> If both are on this does nothing: Fullbright is the "I need to see
 * right now" toggle, and having a darkening slider quietly override it would read as Fullbright
 * being broken. Checked here rather than relying on which mixin runs last, so the outcome does not
 * depend on injection order.
 *
 * <p>Tail rather than every {@code RETURN}, for the reason the Fullbright hook documents: the method
 * returns early when the lightmap needs no update and that path leaves the state deliberately stale.
 * The extractor's {@code tick()} raises {@code needsUpdate} every tick, so a moved slider still
 * lands within one tick. Nothing is written to the player's options, so there is no brightness to
 * restore.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class DarkModeLightmapMixin {

    @Inject(method = "extract", at = @At("TAIL"))
    private void skyblockSimplified$uniformBrightness(LightmapRenderState state, float partialTick,
                                                      CallbackInfo ci) {
        if (Fullbright.active()) {
            return;
        }
        float level = DarkMode.uniformLevel();
        if (level < 0f) {
            return;
        }
        state.blockFactor = 0f;
        state.skyFactor = 0f;
        state.nightVisionEffectIntensity = 0f;
        state.darknessEffectScale = 0f;
        state.ambientColor = DarkMode.uniformColor(level);
    }
}
