/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.helper.timers.ServerWorldTime;

/**
 * Reads the phase-3 "Shoot the Target" device off block changes and keeps a {@link ShootTargetBoard}
 * for {@code ShootTargetMarks} to draw. Display only: it never aims, never moves the camera, never
 * shoots - it only says which block is the target right now and how many hits are done.
 *
 * <p>What the device is, as far as anyone here knows (ESTIMATED, never recorded): a wall of blocks
 * of which one at a time turns into a different block (an emerald block is the guess), and that one
 * has to be hit with an arrow; the device completes after some number of hits. Neither the block ids
 * nor the grid size nor the hit count is verified, so this class also stays a probe and writes down
 * what the server actually does while the Goldor phase is running:
 *
 * <ul>
 *   <li>the grid around the first target seen - every candidate block in a small cube, once;</li>
 *   <li>every candidate block change, with wall-clock and server game time;</li>
 *   <li>for a target turning back, whether an arrow was right there - the only hit evidence the
 *       client has without an arrow-impact hook;</li>
 *   <li>the hit count the board had when Hypixel said the device was completed.</li>
 * </ul>
 *
 * <p>Everything is {@code [SBS][ShootTarget]}, on events only, and capped per phase.
 */
public final class ShootTargetTracker {

    private static final ShootTargetTracker INSTANCE = new ShootTargetTracker();

    /** Lines one phase may write - a mis-guessed candidate block must not flood the log. */
    private static final int LOG_CAP = 400;
    /** Half-size of the cube dumped around the first target seen. */
    private static final int GRID_SCAN = 4;
    /** How close an arrow must be to the block for the turn-back to count as arrow evidence. */
    private static final double ARROW_NEAR = 1.5;

    /** A device line this soon after the board's last change is taken as this device finishing. */
    private static final long COMPLETE_WINDOW_MS = 5_000L;
    /** Nothing lit or hit for this long: the player has moved on, the marks go. */
    private static final long IDLE_MS = 60_000L;

    private final ShootTargetBoard board = new ShootTargetBoard();
    private volatile boolean armed;
    private boolean gridLogged;
    private int lines;
    private long lastChangeMs;

    private ShootTargetTracker() {
    }

    public static ShootTargetTracker getInstance() {
        return INSTANCE;
    }

    /** Read by the block-update mixin before anything else. */
    public boolean armed() {
        return armed;
    }

    /** F7/M7 boss, Goldor (phase 3) - the only place the device exists. */
    public void onClientTick() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        boolean now = state.inDungeon() && state.floorNumber() == 7
                && state.phase() == DungeonEvents.Phase.BOSS
                && FloorSevenPhaseTimer.getInstance().current() == FloorSevenPhaseTimer.Phase.GOLDOR
                && Minecraft.getInstance().level != null;
        if (now != armed) {
            armed = now;
            if (!now) {
                board.reset();
                gridLogged = false;
                lines = 0;
                lastChangeMs = 0;
            }
        }
        if (armed && board.idle(System.currentTimeMillis(), IDLE_MS)) {
            board.reset();
        }
    }

    /** The board the renderer draws from (render thread = client thread, so no copy is needed). */
    public ShootTargetBoard board() {
        return board;
    }

    /**
     * Hypixel's "completed a device! (n/m)" line, via {@link TerminalTracker}. Closes the board only if
     * it moved within the last few seconds, so a teammate's Simon Says finishing does not.
     */
    public void onDeviceCompleted() {
        if (!armed) {
            return;
        }
        int hits = board.hits();
        if (board.onDeviceCompleted(System.currentTimeMillis(), COMPLETE_WINDOW_MS)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ShootTarget] device completed after {} counted hits (estimate was {})",
                    hits, ShootTargetBoard.ESTIMATED_HITS);
        }
    }

    /** Block-update mixin, HEAD of {@code setServerVerifiedBlockState}: old state still in the level. */
    public void onBlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!armed || !DungeonStateManager.getInstance().inDungeon()) {
            return; // the tick that would disarm does not run outside the Catacombs
        }
        long nowMs = System.currentTimeMillis();
        boolean becameTarget = isTarget(newState) && !isTarget(oldState);
        boolean leftTarget = isTarget(oldState) && !isTarget(newState);
        if (becameTarget) {
            board.onLit(pos.asLong(), nowMs);
        } else if (leftTarget) {
            board.onUnlit(pos.asLong(), nowMs);
        }
        if (lines >= LOG_CAP) {
            return;
        }
        boolean oldCandidate = candidate(oldState);
        boolean newCandidate = candidate(newState);
        if (!oldCandidate && !newCandidate) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long sinceMs = lastChangeMs == 0 ? -1 : nowMs - lastChangeMs;
        lastChangeMs = nowMs;
        if (becameTarget && !gridLogged) {
            gridLogged = true;
            logGrid(level, pos.immutable(), oldState);
        }
        int arrows = leftTarget ? arrowsNear(level, pos) : -1;
        lines++;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][ShootTarget] change {},{},{} {} -> {}{} sinceLastMs={} gameTime={} hits={}{}",
                pos.getX(), pos.getY(), pos.getZ(), id(oldState), id(newState),
                becameTarget ? " [TARGET ON]" : leftTarget ? " [TARGET OFF]" : "",
                sinceMs, ServerWorldTime.gameTime(), board.hits(),
                arrows >= 0 ? " arrowsNear=" + arrows : "");
    }

    /** Only the guessed target block counts as the target. UNVERIFIED. */
    static boolean isTarget(BlockState state) {
        return state.is(Blocks.EMERALD_BLOCK);
    }

    /**
     * Blocks worth logging a change of: the guessed target and the usual "wall" families. Broad on
     * purpose - a wrong target guess should still leave the real one in the log.
     */
    private static boolean candidate(BlockState state) {
        if (isTarget(state) || state.is(Blocks.DIAMOND_BLOCK) || state.is(Blocks.GOLD_BLOCK)) {
            return true;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("terracotta") || path.endsWith("_wool") || path.endsWith("_concrete");
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static int arrowsNear(ClientLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(AbstractArrow.class, new AABB(pos).inflate(ARROW_NEAR)).size();
    }

    /** One dump: every candidate block in a cube around the first target, as x,y,z=id. */
    private void logGrid(ClientLevel level, BlockPos center, BlockState base) {
        Block baseBlock = base.getBlock();
        StringBuilder text = new StringBuilder();
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = GRID_SCAN; dy >= -GRID_SCAN; dy--) {
            for (int dx = -GRID_SCAN; dx <= GRID_SCAN; dx++) {
                for (int dz = -GRID_SCAN; dz <= GRID_SCAN; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if ((!base.isAir() && state.getBlock() == baseBlock) || candidate(state)) {
                        count++;
                        text.append(cursor.getX()).append(',').append(cursor.getY()).append(',')
                                .append(cursor.getZ()).append('=').append(id(state)).append(' ');
                    }
                }
            }
        }
        lines++;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][ShootTarget] grid at first target {},{},{} (base {}): {} cells | {}",
                center.getX(), center.getY(), center.getZ(), id(base), count, text.toString().trim());
    }
}
