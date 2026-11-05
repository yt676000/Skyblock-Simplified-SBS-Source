/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.visual.render.GradientPanes;

/**
 * Gradient filler panes ({@link GradientPanes}): a filler pane's slot is painted as part of the
 * gradient surface and its own item render is skipped.
 *
 * <p>Anchored like the rarity tint ({@code ItemRarityMixin}): the per-menu decision at the HEAD of
 * {@code extractSlots}, the per-slot draw at the HEAD of {@code extractSlot}, which runs after any
 * container reskin has painted its cells, so the surface is never buried under them. Cancelling
 * {@code extractSlot} skips only that slot's item - the hover highlight, the tooltip and every
 * click are handled elsewhere and are unchanged.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class GradientPanesMixin {

    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void skyblockSimplified$decideGradientPanes(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                        CallbackInfo ci) {
        GradientPanes.begin((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$gradientPane(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY,
                                                 CallbackInfo ci) {
        if (GradientPanes.draw(g, slot)) {
            ci.cancel();
        }
    }
}
