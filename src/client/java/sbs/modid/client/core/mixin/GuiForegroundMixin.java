/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.render.ScreenForeground;

/**
 * Draws the mod's top layer over an open menu <b>last in the GUI frame</b>, so another mod's overlay
 * cannot land on top of it.
 *
 * <p><b>Why here and not in the screen.</b> Everything any mod paints over a screen is an injection
 * into {@code Screen.extractRenderStateWithTooltipAndSubtitles}, and which of those injections ends
 * up on top is decided by the order the mixin processor happened to apply them in. Nobody chose that
 * order and nobody can see it; the symptom is a panel with somebody else's overlay through the
 * middle of it. {@code Gui.extractRenderState} is the method that <i>calls</i> the screen's - so its
 * tail is after every one of those injections by construction, not by priority, which this
 * repository has already learned not to trust for z-order.
 *
 * <p>{@code nextStratum} then lifts what follows above everything already extracted this frame, the
 * HUD and vanilla's own flushed tooltips included. Between the two, the layer is on top of anything
 * drawn into the screen.
 *
 * <p><b>It is still cooperative, and the class doc for the setting says so.</b> A mod that also
 * draws from the end of the GUI frame is in the same place we are, and then it is whichever
 * injection runs last again. There is no unconditional foreground to claim.
 *
 * <p>Off - {@link ScreenForeground#onTop()} false - this draws nothing and the layer stays in the
 * screen's own tail, where a mod drawing after us covers it. That is the point of the setting.
 */
@Mixin(Gui.class)
public abstract class GuiForegroundMixin {

    /**
     * @param renderScreen vanilla's own "was the screen drawn this frame" flag - the screen is
     *                     skipped entirely when it is false, and so is this
     * @param g            the frame's extractor, the one vanilla built at the top of the method and
     *                     handed to the HUD and the screen
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void skyblockSimplified$drawForeground(DeltaTracker delta, boolean renderHud,
                                                   boolean renderScreen, CallbackInfo ci,
                                                   @Local GuiGraphicsExtractor g) {
        if (!ScreenForeground.onTop()) {
            return;   // the screen's own tail has the layer instead
        }
        Gui gui = (Gui) (Object) this;
        // An overlay (the resource-pack loading screen) replaces the screen for the frame rather
        // than sitting under it, so a screen reference alone is not proof the screen was drawn.
        if (!renderScreen || gui.overlay() != null
                || !(gui.screen() instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        // Vanilla's own two lines, recomputed rather than captured: they are the mouse position this
        // very frame, and reading them back is cheaper to keep correct than pinning down which local
        // slot they occupy.
        Minecraft minecraft = Minecraft.getInstance();
        int mouseX = (int) minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
        int mouseY = (int) minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());

        g.nextStratum();
        ScreenForeground.drawTopMost(container, g, mouseX, mouseY);
    }
}
