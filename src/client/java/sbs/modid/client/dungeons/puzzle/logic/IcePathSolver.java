/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.dev.RoomRotation;
import sbs.modid.client.dungeons.puzzle.model.PuzzleStatus;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.List;

/**
 * Ice Path: reads the room and solves it with {@link IcePathBoard}.
 * Draws the fewest hits from the fish's rest into the exit pocket, from the moment the room is entered.
 *
 * <p>Every {@link #READ_EVERY} ticks it looks for the one silverfish inside the room footprint. The
 * first time it finds one, it reads a single block layer at the silverfish's feet across the room's
 * bounding box (anything not air, or outside the footprint, is blocked) and keeps it as an
 * {@link IcePathBoard}. After that it only logs when the silverfish comes to rest on a new cell,
 * with the cells one hit would send it to - that is what a run needs to confirm the slide rule.
 *
 * <p>Unverified in 26.2: that the silverfish is a plain {@code Silverfish} entity, that its feet
 * layer is the layer the obstacles stand in, and that the footprint box contains the whole board.
 */
public final class IcePathSolver implements PuzzleStrategy {

    static final int READ_EVERY = 10;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private IcePathBoard board;
    /** The board's exit pocket, found once per capture. */
    private boolean[] exit;
    /** The drawn route from the fish's current rest to the exit; empty when there is none. */
    private volatile List<PuzzleHighlight> route = List.of();
    private int originX;
    private int originZ;
    private int layerY;
    private int lastCell = -2;
    /** The cell of the previous still read; a rest counts once two reads agree. */
    private int candidateCell = -3;
    /** The fish's last confirmed rest, world x/z; {@code Integer.MIN_VALUE} before the first. */
    private int restX = Integer.MIN_VALUE;
    private int restZ;
    private boolean exitLogged;
    /** The fish's position on the last tick it was seen, and whether its disappearance was logged. */
    private double seenX;
    private double seenZ;
    private boolean seenFish;
    private boolean vanishLogged;
    private int ticks;

    @Override
    public PuzzleType type() {
        return PuzzleType.ICE_PATH;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        trackEveryTick();
        if (ticks++ % READ_EVERY != 0) {
            return;
        }
        logExitOnce();
        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        if (borders == null) {
            return;
        }
        Entity fish = findSilverfish(borders);
        if (fish == null) {
            if (lastCell != -1) {
                lastCell = -1;
                log("no silverfish inside the room footprint");
            }
            return;
        }
        BlockPos feet = fish.blockPosition();
        if (board == null) {
            capture(level, borders, feet.getY());
        }
        // Mid-slide the silverfish moves every tick; only a cell it is still on a read later counts.
        if (feet.getY() != layerY || fish.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) {
            return;
        }
        int cell = board.index(feet.getX() - originX, feet.getZ() - originZ);
        // Two reads on one cell before it counts: a slow stretch of a slide reads as still for a
        // moment, and the 2026-10-16 log listed cells the fish only passed through. Not for the
        // first rest of the visit: the fish has not been hit yet when the room is entered, and the
        // route is wanted as the player walks in, not a read later (asked 2026-10-17).
        boolean firstRest = restX == Integer.MIN_VALUE;
        if (!firstRest && cell != candidateCell) {
            candidateCell = cell;
            return;
        }
        if (cell == lastCell) {
            return;
        }
        lastCell = cell;
        if (cell >= 0) {
            restX = feet.getX();
            restZ = feet.getZ();
        }
        if (cell < 0) {
            log("silverfish at " + feet.toShortString() + " is outside the captured board");
            return;
        }
        StringBuilder sb = new StringBuilder("silverfish rests at ").append(describe(cell)).append(", one hit ->");
        for (int dir = 0; dir < 4; dir++) {
            sb.append(' ').append(IcePathBoard.describe(new int[] {dir})).append('=')
                    .append(describe(board.slide(cell, dir)));
        }
        log(sb.toString());
        plan(cell);
    }

    /** Re-plans from the cell the fish rests on: the fewest hits into the exit pocket, drawn as lines. */
    private void plan(int cell) {
        if (exit == null) {
            exit = board.exitCells();
        }
        List<Integer> stops = new java.util.ArrayList<>();
        int[] dirs = board.solveToExit(cell, exit, stops);
        if (dirs == null) {
            route = List.of();
            log("no hit sequence from " + describe(cell) + " reaches the exit - nothing drawn");
            return;
        }
        List<PuzzleHighlight> out = new java.util.ArrayList<>(dirs.length);
        int color = PuzzleSettings.argb(PuzzleType.ICE_PATH);
        int from = cell;
        for (int i = 0; i < dirs.length; i++) {
            int to = stops.get(i);
            String label = i == 0 ? "§a1: push " + IcePathBoard.describe(new int[] {dirs[0]}) : "§7" + (i + 1);
            out.add(i == 0
                    ? PuzzleHighlight.line(centre(from), centre(to), color, label)
                    : new PuzzleHighlight(new AABB(centre(from), centre(from)), 0x80FFFFFF, label, false, centre(to)));
            from = to;
        }
        route = List.copyOf(out);
        log("route " + IcePathBoard.describe(dirs) + " from " + describe(cell) + " to the exit at " + describe(from));
    }

    private Vec3 centre(int cell) {
        return new Vec3(originX + cell % board.width() + 0.5, layerY + 0.5, originZ + cell / board.width() + 0.5);
    }

    /**
     * Where the fish was on the last tick it existed, every tick, sliding or not. The goal swallows
     * it: in the 2026-10-16 room it slid from its last rest and was gone on the next read, so the
     * last rest was not the goal. The tick before it vanishes is the closest read of the goal.
     */
    private void trackEveryTick() {
        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        if (borders == null || board == null) {
            return;
        }
        Entity fish = findSilverfish(borders);
        if (fish != null) {
            seenX = fish.getX();
            seenZ = fish.getZ();
            seenFish = true;
            return;
        }
        if (seenFish && !vanishLogged) {
            vanishLogged = true;
            route = List.of();   // the fish reached the exit: nothing left to show
            int x = (int) Math.floor(seenX);
            int z = (int) Math.floor(seenZ);
            StringBuilder sb = new StringBuilder("fish vanished - last seen at world (")
                    .append(String.format(java.util.Locale.ROOT, "%.2f,%.2f", seenX, seenZ))
                    .append(") = board cell (").append(x - originX).append(',').append(z - originZ).append(')');
            DungeonRoomMatcher.RoomMatch match = DungeonRoomTracker.getInstance().activeMatch();
            if (match != null && match.facing() != null && match.anchor() != null) {
                Vec3 rel = RoomRotation.actualToRelative(match.facing(), match.anchor(), seenX, layerY, seenZ);
                sb.append(String.format(java.util.Locale.ROOT, " room-relative (%.2f,%.2f) facing %s",
                        rel.x, rel.z, match.facing()));
            }
            log(sb.toString());
        }
    }

    private static Entity findSilverfish(DungeonRoomBorders.Borders borders) {
        List<Tracked> living = EntityIndex.bucket(EntityKind.LIVING);
        for (int i = 0, n = living.size(); i < n; i++) {
            Entity entity = living.get(i).entity;
            if (entity instanceof Silverfish && entity.isAlive() && borders.contains(entity.blockPosition())) {
                return entity;
            }
        }
        return null;
    }

    /** One block layer over the room's bounding box, once per visit. */
    private void capture(ClientLevel level, DungeonRoomBorders.Borders borders, int y) {
        originX = borders.min().getX();
        originZ = borders.min().getZ();
        layerY = y;
        int width = borders.max().getX() - originX + 1;
        int depth = borders.max().getZ() - originZ + 1;
        boolean[] blocked = new boolean[width * depth];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                cursor.set(originX + x, y, originZ + z);
                blocked[z * width + x] = !borders.contains(cursor) || !level.getBlockState(cursor).isAir();
            }
        }
        board = new IcePathBoard(width, depth, blocked);
        exit = board.exitCells();
        int exits = 0;
        for (boolean e : exit) {
            exits += e ? 1 : 0;
        }
        log("captured " + width + "x" + depth + " layer at y=" + y + " from " + originX + "," + originZ
                + " - exit pocket " + exits + " cell(s)");
        // Once per room, outside developer mode too: the goal is not known yet, and the board as
        // read is what it gets found from (one line; asked 2026-10-16).
        log("layout " + board.dump());
    }

    private String describe(int cell) {
        return cell < 0 ? "?" : "(" + (originX + cell % board.width()) + "," + (originZ + cell / board.width()) + ")";
    }

    /**
     * When the tab turns Ice Path solved, where the fish last rested - the goal - in the room's
     * canonical frame, so one line per room is enough to place the goal in every rotation. The one
     * thing the solver lacks before it can draw (2026-10-16: the goal was seen in world coordinates
     * only, with no rotation to turn it into room ones).
     */
    private void logExitOnce() {
        if (exitLogged || restX == Integer.MIN_VALUE
                || PuzzleTabReader.getInstance().statusOf(PuzzleType.ICE_PATH) != PuzzleStatus.COMPLETE) {
            return;
        }
        exitLogged = true;
        DungeonRoomMatcher.RoomMatch match = DungeonRoomTracker.getInstance().activeMatch();
        if (match == null || match.facing() == null || match.anchor() == null) {
            log("solved - fish last rested at (" + restX + "," + restZ + "), room frame unknown");
            return;
        }
        Vec3 rel = RoomRotation.actualToRelative(match.facing(), match.anchor(), restX + 0.5, layerY, restZ + 0.5);
        log("solved - goal (fish's last rest) at world (" + restX + "," + restZ + ") = room-relative ("
                + (int) Math.floor(rel.x) + "," + (int) Math.floor(rel.z) + ") y=" + layerY + " facing "
                + match.facing() + " anchor " + match.anchor().toShortString());
    }

    private static void log(String line) {
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] Ice Path {}", line);
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        return route;
    }

    @Override
    public void reset() {
        board = null;
        exit = null;
        route = List.of();
        lastCell = -2;
        candidateCell = -3;
        restX = Integer.MIN_VALUE;
        exitLogged = false;
        seenFish = false;
        vanishLogged = false;
        ticks = 0;
    }
}
