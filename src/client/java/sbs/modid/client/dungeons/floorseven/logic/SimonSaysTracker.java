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
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the phase-3 "Simon Says" device: the wall of buttons whose lights flash a sequence you then
 * repeat. Records <b>the order the lights come on in</b>, which is the one thing about that device
 * you cannot get back once it has finished flashing.
 *
 * <p><b>Nothing about the device's shape is assumed.</b> No coordinates, no grid, no wall detection:
 * the tracker looks for buttons near the player and asks, per button, whether the block it is stuck
 * to is giving off light. That is the entire model. A device Hypixel rebuilds one block to the left,
 * or lays out five wide instead of four, or lights with a different block, keeps working - which
 * matters for a feature whose exact build cannot be checked from outside a run.
 *
 * <p><b>What it does not do.</b> It does not track which buttons you have already pressed, and it
 * deliberately does not guess: a button's powered state lasts a moment and says nothing about whether
 * the device accepted the press. So the whole remembered sequence stays on screen with its order
 * numbers, and the player reads it off - the information that was on the wall a second ago, still
 * there when it is needed. Nothing is ever pressed for you.
 *
 * <p>A sequence that replays from the start each round (which is how the puzzle grows) folds into the
 * same list: a position already recorded keeps its number, and only the new tail is appended.
 *
 * <p>The first sighting of a lit device is logged once with every button and its state
 * ({@code [SBS][SimonSays] ...}), so what the wall really looked like can be read out of the instance
 * log afterwards rather than remembered.
 */
public final class SimonSaysTracker {

    private static final SimonSaysTracker INSTANCE = new SimonSaysTracker();

    /** How far around the player buttons are looked for. The device is read from arm's length. */
    private static final int RADIUS_XZ = 8;
    private static final int RADIUS_Y = 4;

    /** Four scans a second: the flashes last far longer than that, and a scan is a small cube. */
    private static final long SCAN_INTERVAL_MS = 250L;

    /** Light level at which a backing block counts as "on". Sea lanterns and glowstone are 15. */
    private static final int LIT_LEVEL = 10;

    /** Forget a sequence nothing has touched for this long - the device has moved on without us. */
    private static final long IDLE_RESET_MS = 90_000L;

    /** The buttons whose light has come on, in the order it did. */
    private final List<BlockPos> sequence = new ArrayList<>();

    /** Which buttons were lit at the previous scan, so only a NEW light appends to the sequence. */
    private Set<BlockPos> litBefore = Set.of();

    private long lastScanMs;
    private long lastChangeMs;
    private boolean logged;

    private SimonSaysTracker() {
    }

    public static SimonSaysTracker getInstance() {
        return INSTANCE;
    }

    /** The remembered sequence, first press first. Empty when there is nothing to show. */
    public List<BlockPos> sequence() {
        return sequence;
    }

    /** Whether the device solver should be reading and drawing right now. */
    public boolean enabled() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return ConfigManager.getInstance().get().dungeons.deviceSolver
                && state.inDungeon() && state.floorNumber() == 7
                && state.phase() == DungeonEvents.Phase.BOSS
                && FloorSevenPhaseTimer.getInstance().current() == FloorSevenPhaseTimer.Phase.GOLDOR;
    }

    /** Called every client tick; throttles itself and does nothing outside the Goldor phase. */
    public void onClientTick() {
        if (!enabled()) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanMs < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanMs = now;
        scan(now);
    }

    private void scan(long now) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        BlockPos centre = player.blockPosition();
        Set<BlockPos> lit = new LinkedHashSet<>();
        List<BlockPos> buttons = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                centre.offset(-RADIUS_XZ, -RADIUS_Y, -RADIUS_XZ),
                centre.offset(RADIUS_XZ, RADIUS_Y, RADIUS_XZ))) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ButtonBlock)) {
                continue;
            }
            BlockPos button = pos.immutable();
            buttons.add(button);
            if (backingLit(level, button)) {
                lit.add(button);
            }
        }
        if (buttons.isEmpty()) {
            return; // nowhere near the device
        }
        for (BlockPos position : lit) {
            // Only a light that was off a moment ago is a new step; one that simply stayed on is the
            // same flash still burning, and appending it again would double every entry.
            if (!litBefore.contains(position) && !sequence.contains(position)) {
                sequence.add(position);
                lastChangeMs = now;
            }
        }
        if (!lit.equals(litBefore)) {
            lastChangeMs = now;
        }
        litBefore = lit;
        if (!lit.isEmpty() && !logged) {
            logged = true;
            log(level, buttons, lit);
        }
        if (lastChangeMs != 0 && now - lastChangeMs > IDLE_RESET_MS) {
            reset();
        }
    }

    /**
     * Whether the block this button is stuck to is giving off light.
     *
     * <p>Both blocks along each axis are tried rather than the button's own facing: the facing tells
     * us where the button points, and reading the attachment out of the block state means trusting
     * that Hypixel attached it the way the vanilla property implies. Asking the six neighbours costs
     * nothing and cannot be wrong about it.
     */
    private static boolean backingLit(ClientLevel level, BlockPos button) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(button.relative(direction)).getLightEmission() >= LIT_LEVEL) {
                return true;
            }
        }
        return false;
    }

    /** One-shot dump of the device as it was first seen lit, for checking the reading after a run. */
    private static void log(ClientLevel level, List<BlockPos> buttons, Set<BlockPos> lit) {
        StringBuilder text = new StringBuilder();
        for (BlockPos button : buttons) {
            text.append(button.getX()).append(',').append(button.getY()).append(',').append(button.getZ())
                    .append(lit.contains(button) ? "=LIT" : "=off").append(' ');
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][SimonSays] {} buttons, {} lit | {}",
                buttons.size(), lit.size(), text.toString().trim());
    }

    private void reset() {
        if (!sequence.isEmpty()) {
            sequence.clear();
        }
        litBefore = Set.of();
        lastChangeMs = 0;
        logged = false;
    }
}
