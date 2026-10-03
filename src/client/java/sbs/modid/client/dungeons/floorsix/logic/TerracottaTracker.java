/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorsix.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Terracotta respawn timers for Sadan's first phase (F6 / M6).
 *
 * <p><b>What the phase actually is.</b> Sadan's interest meter runs down for two minutes and every
 * terracotta killed takes a second off it, so the phase is a kill-rate race. A killed terracotta
 * does not just despawn: it collapses into a <b>flower pot</b> on the block it stood in, the pot
 * later hardens into a clay block, and the terracotta re-forms out of that block at full health -
 * 15 seconds after the death on F6, 12 on M6 (300 / 240 server ticks; both measured against the
 * live fight, and settings because Hypixel can retune them). That makes each dead block the most
 * valuable square in the arena for the next few seconds - and nothing in the game counts it down.
 * That is the whole feature: a countdown standing on every dead terracotta's block, so the next
 * one dies the moment it exists instead of after it has been found again.
 *
 * <p><b>The death is read off the block, not the mob.</b> The server announces every collapse by
 * placing that flower pot, so the block-update funnel ({@code setServerVerifiedBlockState}, fed by
 * the mixin) sees every single death in the arena - a teammate's kill on the far side included -
 * with the exact position and the exact moment for free. Nametag watching, which this tracker used
 * to do, only ever caught the deaths whose tags happened to be readable at the fatal tick, which in
 * practice marked one corpse out of a dozen. Air turning into a pot while Sadan's first phase is
 * running is a terracotta death; nothing else in that room does it.
 *
 * <p><b>A marker is cleared by the block that made it.</b> When the pot (or the clay it hardened
 * into) is swapped away, the terracotta is back and the countdown has nothing left to say - so the
 * per-tick sweep drops any marker whose block has gone back to air, and an overdue grace catches a
 * cleanup the server did without one (the phase ending, for instance). The clock is a prediction;
 * the block is the truth.
 */
public final class TerracottaTracker {

    private static final TerracottaTracker INSTANCE = new TerracottaTracker();

    /** A spot keeps its marker this long past zero - in case the re-form runs a beat late. */
    private static final long OVERDUE_MS = 3_000L;

    /** A dead terracotta's block, counting down to the mob that will come back out of it. */
    public record Respawn(BlockPos block, long dueAt) {

        /** Milliseconds until it is due; negative once it is overdue. */
        public long remainingMs() {
            return dueAt - System.currentTimeMillis();
        }
    }

    private final List<Respawn> pending = new ArrayList<>();
    private volatile List<Respawn> snapshot = List.of();

    /** True only while Sadan's fight is on and the toggle is set - the mixin's cheap gate. */
    private volatile boolean armed;

    private TerracottaTracker() {
    }

    public static TerracottaTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** The spots waiting for a terracotta, soonest first. Read by the renderer. */
    public List<Respawn> respawns() {
        return snapshot;
    }

    /** Read by the block-update mixin before it does any work at all. */
    public boolean armed() {
        return armed;
    }

    /** Called every client tick: arms the block watch and sweeps the delivered markers. */
    public void onClientTick() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        // Floor 6 boss room only. Terracotta exist as ordinary mobs elsewhere in the Catacombs, and
        // those do not come back on a timer - marking their corpses would be marking nothing.
        armed = cfg().terracottaTimer && state.inDungeon() && state.floorNumber() == 6
                && state.phase() == DungeonEvents.Phase.BOSS
                && level != null && minecraft.player != null;
        if (!armed) {
            if (!pending.isEmpty()) {
                pending.clear();
                snapshot = List.of();
            }
            return;
        }
        // The block gone back to air is the respawn itself - the marker has done its job. The
        // overdue grace mops up a pot the server cleared some other way.
        pending.removeIf(spot -> level.getBlockState(spot.block()).isAir()
                || spot.remainingMs() < -OVERDUE_MS);
        pending.sort((a, b) -> Long.compare(a.dueAt(), b.dueAt()));
        snapshot = List.copyOf(pending);
    }

    /**
     * Called from the block-update mixin for every server-verified block change, before it is
     * applied - so {@code oldState} really is the state the block had. Air becoming a flower pot
     * while the fight is armed is a terracotta going down on that block, whoever killed it.
     */
    public void onBlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!armed || !oldState.isAir() || !(newState.getBlock() instanceof FlowerPotBlock)) {
            return;
        }
        SBSConfig.DungeonsSettings cfg = cfg();
        int seconds = DungeonStateManager.getInstance().floorType() == 'M'
                ? cfg.terracottaSecondsMaster : cfg.terracottaSecondsFloor;
        // The multi-block packet walks its entries with one mutable cursor - copy the position.
        book(pos.immutable(), System.currentTimeMillis() + Math.max(1, seconds) * 1000L);
    }

    /**
     * Books one countdown per block. A block that already has a marker keeps it and takes the new
     * clock, so a re-sent update refreshes a spot instead of stacking a second marker on it.
     */
    private void book(BlockPos block, long dueAt) {
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).block().equals(block)) {
                pending.set(i, new Respawn(block, dueAt));
                return;
            }
        }
        pending.add(new Respawn(block, dueAt));
    }
}
