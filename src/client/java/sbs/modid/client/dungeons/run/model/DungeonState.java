/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import sbs.modid.client.core.dev.RoomMapReader;
import sbs.modid.client.dungeons.run.logic.DungeonScoreboard;

/**
 * Holds the live dungeon state (in-dungeon flag + the cell-resolved map snapshot) and refreshes it only
 * on a real <b>block event</b>.
 *
 * <p>Performance rule (as required): the heavy work – scanning the sidebar scoreboard and re-reading the
 * map pixels – runs <b>only when {@code player.blockPosition()} changes</b>, never in a permanent time
 * loop. {@link #updateIfMoved()} is called each frame from the HUD renderer but returns immediately (a
 * single {@link BlockPos} comparison) while the player stays on the same block.
 */
public final class DungeonState {

    private static final DungeonState INSTANCE = new DungeonState();

    private BlockPos lastBlock;
    private boolean inDungeon;
    private RoomMapReader.MapSnapshot snapshot;

    private DungeonState() {
    }

    public static DungeonState getInstance() {
        return INSTANCE;
    }

    /** Refreshes the state, but only when the player has moved to a different block since last time. */
    public void updateIfMoved() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            reset();
            return;
        }
        BlockPos current = player.blockPosition();
        if (current.equals(lastBlock)) {
            return; // same block -> no block event -> skip all heavy work
        }
        lastBlock = current.immutable();

        // Heavy work, gated on the block change:
        inDungeon = DungeonScoreboard.isInDungeon();
        snapshot = inDungeon ? RoomMapReader.readSnapshot() : null;
    }

    public boolean isInDungeon() {
        return inDungeon;
    }

    /** The current cell-resolved map snapshot, or {@code null} when unavailable. */
    public RoomMapReader.MapSnapshot snapshot() {
        return snapshot;
    }

    private void reset() {
        lastBlock = null;
        inDungeon = false;
        snapshot = null;
    }
}
