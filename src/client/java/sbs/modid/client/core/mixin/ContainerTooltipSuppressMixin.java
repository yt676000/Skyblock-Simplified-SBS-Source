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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.economy.recipe.ui.RecipeOverlay;

/**
 * Stops the vanilla hovered-slot tooltip from bleeding out from BEHIND an SBS overlay.
 *
 * <p>{@code AbstractContainerScreen.extractTooltip} draws the tooltip of whatever slot the mouse is
 * over - it has no idea an overlay (the Recipe Viewer panel / window) is painted on top of that slot.
 * The overlay renders later in the frame, so it visually covers the slot but the vanilla tooltip
 * still flushes and pokes out beside the overlay (an item's tooltip showing under the recipe grid).
 * This cancels {@code extractTooltip} whenever the cursor is over such an overlay, so only the
 * overlay's own tooltip shows.
 *
 * <p><b>Every {@code coversForTooltip} here answers for the FOREGROUND only.</b> An overlay may
 * claim a rectangle solely where it actually paints this frame - a minimized or empty overlay
 * claims nothing. Swallowing the lore behind an invisible rectangle looks exactly like "tooltips
 * are broken" from the player's side, and leaves them no overlay to close to get them back.
 * With developer mode on, a cancellation is logged (throttled) naming the overlay responsible, so
 * "my tooltips are gone" is one grep rather than a guess.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerTooltipSuppressMixin {

    /** Last logged culprit and when, so a per-frame path does not write 60 lines a second. */
    private static String sbs$lastReason;
    private static long sbs$lastLogMillis;

    /** Quiet period between two identical dev-mode reports. */
    private static final long SBS_LOG_INTERVAL_MS = 3_000L;

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$suppressTooltipBehindOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                                 CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        String reason = sbs$suppressor(self, mouseX, mouseY);
        if (reason == null) {
            return;
        }
        sbs$report(reason, mouseX, mouseY);
        ci.cancel();
    }

    /** Which overlay covers this point, or {@code null} when the vanilla tooltip may draw. */
    private static String sbs$suppressor(AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
        if (RecipeOverlay.getInstance().coversForTooltip(screen, mouseX, mouseY)) {
            return "Recipe Viewer";
        }
        if (sbs.modid.client.economy.calculator.CalculatorOverlay.getInstance()
                .coversForTooltip(screen, mouseX, mouseY)) {
            return "Calculator";
        }
        if (sbs.modid.client.skills.garden.ui.GardenPlotsOverlay.getInstance()
                .coversForTooltip(screen, mouseX, mouseY)) {
            return "Garden Plots";
        }
        if (sbs.modid.client.dungeons.terminal.TerminalBoardOverlay.getInstance()
                .coversForTooltip(screen, mouseX, mouseY)) {
            return "Terminal Board";
        }
        return null;
    }

    private static void sbs$report(String reason, int mouseX, int mouseY) {
        if (!sbs.modid.client.core.dev.DevMode.ACTIVE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (reason.equals(sbs$lastReason) && now - sbs$lastLogMillis < SBS_LOG_INTERVAL_MS) {
            return;
        }
        sbs$lastReason = reason;
        sbs$lastLogMillis = now;
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Tooltip] item tooltip suppressed at ({}, {}) - the {} overlay reports it covers "
                        + "that point", mouseX, mouseY, reason);
    }
}
