/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;

/**
 * Feeds the {@link GuiStateManager} from the client thread:
 * <ul>
 *   <li>{@code setScreenAndShow} – records the active screen whenever it changes
 *       (this version has no public {@code screen} field, so we track it here).</li>
 *   <li>{@code tick} – rebuilds the GUI state snapshot once per client tick.</li>
 * </ul>
 *
 * <p>Both run on the client thread, which is exactly where Minecraft objects may be
 * read; the captured snapshot is then served to the HTTP layer without any further
 * Minecraft access.
 */
@Mixin(Minecraft.class)
public class GuiTrackingMixin {

    @Inject(method = "setScreenAndShow", at = @At("HEAD"))
    private void skyblockSimplified$trackScreen(Screen screen, CallbackInfo ci) {
        GuiStateManager.getInstance().setCurrentScreen(screen);
        // A screen change always releases the overlay search bar's keyboard focus.
        SearchHighlightState.getInstance().setSearchFocused(false);
        // ...and always closes an open "click again to confirm" window. A confirmation is consent to
        // one action in one menu; letting it survive into the next menu would turn a refusal the
        // player already answered into a free pass somewhere they never saw the question.
        sbs.modid.client.helper.itemprotection.logic.ProtectionConfirm.clear();
        // A storage page just closed (or changed): write its last change now, not after the throttle.
        sbs.modid.client.helper.storage.StorageIndex.getInstance().flushPending();
        // Settings screens close through here: write a slider's debounced last value now.
        sbs.modid.client.core.config.ConfigManager.getInstance().flush();
        // Per-stack caches hold the old menu's stacks; let them go with it.
        sbs.modid.client.core.item.StackCache.clearAll();
        sbs.modid.client.core.item.ItemAnalysis.clear();
        sbs.modid.client.core.item.ItemTooltip.clear();
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void skyblockSimplified$flushConfigOnStop(CallbackInfo ci) {
        // The game is closing: a config change still inside its debounce window is written now.
        sbs.modid.client.core.config.ConfigManager.getInstance().flush();
        sbs.modid.client.core.alert.NarratorVoice.shutdown();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void skyblockSimplified$tickGuiState(CallbackInfo ci) {
        // Every tick consumer, in its fixed order, gated on this tick's context (core/tick/TickTasks).
        // BOOKMARK(merge 2026-10-05): tick-context-gates runTail kept; WIP consumer list lives in TickTasks.
        sbs.modid.client.core.tick.TickTasks.runTail();
    }
}
