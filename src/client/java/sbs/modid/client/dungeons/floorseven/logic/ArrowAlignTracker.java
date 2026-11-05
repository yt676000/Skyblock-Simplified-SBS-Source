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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.floorseven.model.ArrowAlignGrid;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the phase-3 Arrow Align device: the wall of item frames whose arrows are rotated into a path.
 *
 * <p><b>The device's shape is an ESTIMATE</b> (5x5 frames, arrows turned one of 8 steps per right
 * click). Nothing here hard-codes it: every item frame within {@link #RADIUS} blocks is read, the
 * wall holding the most frames is taken as the device, and the result is a cropped
 * {@link ArrowAlignGrid} in the device's own coordinates.
 *
 * <p><b>Probe.</b> {@code [SBS][ArrowAlign]} lines, never per frame:
 * <ul>
 *   <li>{@code first sight} - once per approach: every frame with its cell, item id and rotation;</li>
 *   <li>{@code grid} - every time any rotation changes (yours or a teammate's click);</li>
 *   <li>{@code CAPTURE} - shortly after "completed a device!" while the grid was in view: the
 *       grid at that moment as a ready-to-paste entry for the bundled layout file. That state is the
 *       solved layout.</li>
 * </ul>
 * Nothing is clicked, rotated or sent.
 */
public final class ArrowAlignTracker {

    private static final ArrowAlignTracker INSTANCE = new ArrowAlignTracker();

    /** Frames are read from where the device is worked - arm's length plus some. */
    private static final double RADIUS = 6.0;

    /** Teammate clicks should show up promptly; a 12-block box of item frames is a cheap query. */
    private static final long SCAN_INTERVAL_MS = 100L;

    /** A completion line counts as "this device" only while the grid was seen this recently. */
    private static final long COMPLETION_WINDOW_MS = 3_000L;

    /** Wait after the completion line before capturing, so the last rotation packet has landed. */
    private static final long CAPTURE_DELAY_MS = 300L;

    /** A capture is CONFIRMED when the grid was being worked (changed) this shortly before solving. */
    private static final long WORKED_WINDOW_MS = 10_000L;

    /** Scan scratch, reused every scan. */
    private final List<ArrowAlignGrid.Frame> scratch = new ArrayList<>();
    private final List<ItemFrame> scratchEntities = new ArrayList<>();

    /** The device as last seen, or {@code null} when none is in range. */
    private ArrowAlignGrid.Placement placement;
    /** The frame entities in the same order as {@code placement.frames()}. */
    private List<ItemFrame> entities = List.of();
    /** Bumped whenever the grid changes, so readers can cache derived state. */
    private int revision;
    /** The match for the current grid; recomputed on change only, never per frame. */
    private ArrowAlignSolver.Result result = ArrowAlignSolver.Result.NO_GRID;
    /** The status last logged, so a status is logged once and again only when it changes. */
    private ArrowAlignSolver.Status loggedStatus;

    private long lastScanMs;
    private long lastSeenMs;
    private long lastChangeMs;
    private long captureDueMs;
    private boolean firstSightLogged;

    private ArrowAlignTracker() {
        ChatPatternRegistry.getInstance().register(
                "completed a device!",
                matcher -> onDeviceCompleted(),
                "f7 arrow align: device completed");
    }

    public static ArrowAlignTracker getInstance() {
        return INSTANCE;
    }

    /** The device as last seen, or {@code null}. */
    public ArrowAlignGrid.Placement placement() {
        return placement;
    }

    /** Frame entity for a scanned frame index, or {@code null}. */
    public ItemFrame entity(int frameIndex) {
        return frameIndex >= 0 && frameIndex < entities.size() ? entities.get(frameIndex) : null;
    }

    public int revision() {
        return revision;
    }

    /** Clicks per cell for the current grid, or why there are none. */
    public ArrowAlignSolver.Result result() {
        return result;
    }

    /**
     * Misclick guard: whether a right click on {@code target} should be swallowed. True only when the
     * guard is on, the device is solved against a known layout and that exact frame is already right.
     * Everything else - unknown layout, a frame outside the grid, sneaking - lets the click through.
     */
    public boolean blocksClick(net.minecraft.world.entity.Entity target, boolean sneaking) {
        if (sneaking || !(target instanceof ItemFrame)
                || !ConfigManager.getInstance().get().dungeons.arrowAlignMisclickGuard
                || result.status() != ArrowAlignSolver.Status.SOLVED || placement == null || !enabled()) {
            return false;
        }
        int[] frameAt = placement.frameAt();
        for (int cell = 0; cell < frameAt.length; cell++) {
            if (frameAt[cell] >= 0 && entities.get(frameAt[cell]) == target) {
                return result.clicksAt(cell) == 0;
            }
        }
        return false;
    }

    /** Whether Arrow Align should be reading right now: toggle on, F7/M7 boss, Goldor phase. */
    public boolean enabled() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return ConfigManager.getInstance().get().dungeons.arrowAlign
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
        scratch.clear();
        scratchEntities.clear();
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class,
                player.getBoundingBox().inflate(RADIUS))) {
            Direction facing = frame.getDirection();
            BlockPos pos = frame.blockPosition();
            scratch.add(new ArrowAlignGrid.Frame(pos.getX(), pos.getY(), pos.getZ(),
                    facing.getStepX(), facing.getStepZ(), code(frame)));
            scratchEntities.add(frame);
        }
        ArrowAlignGrid.Placement found = scratch.isEmpty() ? null : ArrowAlignGrid.fromFrames(scratch);
        if (found == null) {
            if (placement != null) {
                placement = null;
                entities = List.of();
                result = ArrowAlignSolver.Result.NO_GRID;
                revision++;
            }
            return;
        }
        lastSeenMs = now;
        List<ItemFrame> ordered = new ArrayList<>(found.frames().size());
        for (ArrowAlignGrid.Frame frame : found.frames()) {
            ordered.add(scratchEntities.get(scratch.indexOf(frame)));
        }
        boolean changed = placement == null || !placement.grid().equals(found.grid());
        placement = found;
        entities = ordered;
        if (changed) {
            revision++;
            result = ArrowAlignSolver.solve(found.grid(), ArrowAlignLayouts.targets());
            if (!firstSightLogged) {
                firstSightLogged = true;
                logFirstSight(found, ordered);
            } else {
                lastChangeMs = now;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][ArrowAlign] grid {}x{} {}",
                        found.grid().width(), found.grid().height(), found.grid().rowsJson());
            }
        }
        if (result.status() != loggedStatus) {
            loggedStatus = result.status();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ArrowAlign] status {}{}", result.status(),
                    result.layoutId() == null ? " - recording" : " via " + result.layoutId());
        }
        if (captureDueMs != 0 && now >= captureDueMs) {
            captureDueMs = 0;
            logCapture(found.grid(), now);
        }
    }

    /** Cell code for one frame: arrow → its rotation, anything else → a plain frame. */
    private static int code(ItemFrame frame) {
        ItemStack item = frame.getItem();
        return item.is(Items.ARROW) ? Math.floorMod(frame.getRotation(), ArrowAlignGrid.STEPS)
                : ArrowAlignGrid.FRAME;
    }

    private void onDeviceCompleted() {
        if (placement != null && System.currentTimeMillis() - lastSeenMs <= COMPLETION_WINDOW_MS) {
            captureDueMs = System.currentTimeMillis() + CAPTURE_DELAY_MS;
        }
    }

    private static void logFirstSight(ArrowAlignGrid.Placement found, List<ItemFrame> frames) {
        StringBuilder text = new StringBuilder();
        int[] frameAt = found.frameAt();
        ArrowAlignGrid grid = found.grid();
        for (int cell = 0; cell < frameAt.length; cell++) {
            if (frameAt[cell] < 0) {
                continue;
            }
            ItemFrame frame = frames.get(frameAt[cell]);
            text.append(cell % grid.width()).append(',').append(cell / grid.width()).append('=')
                    .append(BuiltInRegistries.ITEM.getKey(frame.getItem().getItem()).getPath())
                    .append('@').append(frame.getRotation()).append(' ');
        }
        ArrowAlignGrid.Frame anchor = found.frames().get(0);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][ArrowAlign] first sight: {}x{} facing ({},{}) near {},{},{} | grid {} | {}",
                grid.width(), grid.height(), found.nx(), found.nz(), anchor.x(), anchor.y(), anchor.z(),
                grid.rowsJson(), text.toString().trim());
    }

    private void logCapture(ArrowAlignGrid grid, long now) {
        boolean worked = lastChangeMs != 0 && now - lastChangeMs <= WORKED_WINDOW_MS;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][ArrowAlign] CAPTURE {\"id\":\"capture-{}\",\"rows\":{},\"certainty\":\"{}\","
                        + "\"captured\":\"{}\"}",
                Integer.toHexString(grid.hashCode()), grid.rowsJson(),
                worked ? "CONFIRMED" : "ESTIMATED", LocalDate.now());
    }

    private void reset() {
        if (placement != null) {
            placement = null;
            entities = List.of();
            result = ArrowAlignSolver.Result.NO_GRID;
            revision++;
        }
        firstSightLogged = false;
        loggedStatus = null;
        lastChangeMs = 0;
        captureDueMs = 0;
    }
}
