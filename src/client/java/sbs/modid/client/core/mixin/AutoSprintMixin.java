/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Sprint without holding the key.
 *
 * <p>Holds the sprint key down each tick rather than calling {@code setSprinting(true)} directly:
 * the key is the input vanilla's own movement code reads, so sprinting starts, stops and resumes
 * exactly as if you were holding it – including when you run out of hunger or hit a wall. Forcing
 * the sprint flag instead would fight that logic every tick.
 *
 * <p>Only pressed, never released: with the toggle off, the key is left entirely alone, so it stays
 * yours to hold normally.
 */
@Mixin(Minecraft.class)
public abstract class AutoSprintMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void sbs$clientTick(CallbackInfo ci) {
        // Closes the previous tick's measurement window (and runs the budget) first.
        sbs.modid.client.core.perf.Perf.endTick();
        // Entity index: drops its buckets once the level they were filled from is gone.
        // BOOKMARK(merge 2026-10-05): tick-context-gates layout + entity index tick + WorldTransitions world leave; old inline resets dropped.
        sbs.modid.client.core.entity.EntityIndex.onClientTick();
        // Shared sidebar snapshot and tab revision: rebuilt here, first, so every tracker below reads
        // this tick's sidebar. Both only do work after a scoreboard or tab packet.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.ScoreboardSnapshots")) {
            sbs.modid.client.core.scoreboard.ScoreboardSnapshots.onClientTick();
            sbs.modid.client.core.tab.TabWidgets.onClientTick();
        }
        // Every tick consumer, in its fixed order, gated on this tick's context (core/tick/TickTasks):
        // one that cannot do anything here is skipped and counted as gated in /sbs perf.
        sbs.modid.client.core.tick.TickTasks.runHead();
        // World leave: every world-bound reset runs from core/world/WorldTransitions.
        sbs$trackWorldChange();
        sbs$autoSprint();
    }

    /** Hands the tick-edge world leave to the registry; every reset is listed in WorldScopedTrackers. */
    @org.spongepowered.asm.mixin.Unique
    private void sbs$trackWorldChange() {
        sbs.modid.client.core.world.WorldTransitions.tick(Minecraft.getInstance().player != null);
    }

    @org.spongepowered.asm.mixin.Unique
    private void sbs$autoSprint() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ConfigManager.getInstance().get().convenience.autoSprint) {
            return;
        }
        // No player = not in a world; a screen open means the player is not steering anyway, and
        // holding sprint through a menu would resume a sprint you never asked for on close.
        // The open screen is read through GuiStateManager because 26.2 moved it off Minecraft.
        if (minecraft.player == null
                || GuiStateManager.getInstance().getCurrentScreen() != null) {
            return;
        }
        minecraft.options.keySprint.setDown(true);
    }
}
