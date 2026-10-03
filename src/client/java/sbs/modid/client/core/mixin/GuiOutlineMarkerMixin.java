/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.ui.hud.edit.logic.HudOpacity;

/**
 * Tells {@link HudOpacity} that the rectangles about to be recorded are an element's frame.
 *
 * <p>{@code outline} draws its four edges as ordinary filled rectangles, so by the time they reach
 * the render pipeline nothing distinguishes a 1 px frame from the plate behind it. Bracketing the
 * call is what lets the HUD opacity's "Background" scope leave a frame crisp while the background
 * behind it goes translucent – overlays that draw their frame this way (rather than in one of the
 * theme's border colours) would otherwise have it fade along with the body.
 *
 * <p>Free when nothing is being faded: both ends only move a counter.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiOutlineMarkerMixin {

    @Inject(method = "outline(IIIII)V", at = @At("HEAD"))
    private void skyblockSimplified$outlineStart(int x, int y, int width, int height, int color,
                                                 CallbackInfo ci) {
        HudOpacity.beginOutline();
    }

    @Inject(method = "outline(IIIII)V", at = @At("RETURN"))
    private void skyblockSimplified$outlineEnd(int x, int y, int width, int height, int color,
                                               CallbackInfo ci) {
        HudOpacity.endOutline();
    }
}
