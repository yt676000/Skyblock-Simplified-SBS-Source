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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Teleport Maze: boxes the pad predicted to lead to the centre, and dims pads already used.
 *
 * <p>Display only. Teleports are the player's own steps onto pads; this only watches where the player
 * lands and which way they face. Nothing clicks, aims, moves or sends.
 *
 * <ol>
 *   <li><b>Pads</b> - on entering, the room's footprint from {@link DungeonRoomTracker#borders()} is
 *       scanned for end portal frames, a fixed budget per tick. The layout is read by {@link PadMaze};
 *       a layout that is not the 3 x 3 grid draws nothing.</li>
 *   <li><b>Teleports</b> - a horizontal jump of more than {@link #TELEPORT_JUMP} blocks between two
 *       ticks that lands on a pad. No packet hook: the landing position and the yaw on the tick after
 *       are what the server set (a mouse movement in that same tick can skew the yaw; a skewed
 *       landing only leaves the prediction unresolved or, rarely, wrong - check in a run).</li>
 * </ol>
 *
 * <p>The pads are on the room's floor, in the player's view; no wall is seen through.
 */
public final class PadMazeSolver implements PuzzleStrategy {

    private static final int SCAN_BUDGET = 4096;
    private static final int SCAN_BELOW = 3;
    private static final int SCAN_ABOVE = 6;
    /** Horizontal blocks moved in one tick above which movement is a teleport, not walking. */
    private static final double TELEPORT_JUMP = 3.0;
    private static final int USED_COLOR = 0x70FF5555;
    /** Pads of the known way to the predicted one, numbered in stepping order. */
    private static final int ROUTE_COLOR = 0xFFFFFF55;

    private DungeonRoomBorders.Borders scanBorders;
    private int scanCursor;
    private int padY = Integer.MIN_VALUE;
    private final List<PadMaze.Pad> found = new ArrayList<>();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private PadMaze maze;

    private boolean hasLast;
    private double lastX;
    private double lastZ;

    private volatile List<PuzzleHighlight> drawn = List.of();
    private boolean dirty;
    private int drawnColor;

    @Override
    public PuzzleType type() {
        return PuzzleType.TELEPORT_MAZE;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null) {
            return;
        }
        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        if (borders == null) {
            return;
        }
        if (borders != scanBorders) {
            reset();
            scanBorders = borders;
        }
        if (scanCursor >= 0) {
            scanStep(minecraft.level, borders);
        }
        double x = player.getX();
        double z = player.getZ();
        if (hasLast && maze != null) {
            double dx = x - lastX;
            double dz = z - lastZ;
            if (dx * dx + dz * dz > TELEPORT_JUMP * TELEPORT_JUMP) {
                onTeleport(x, z, player.getYRot());
            }
        }
        hasLast = true;
        lastX = x;
        lastZ = z;
        int color = PuzzleSettings.argb(type());
        if (dirty || color != drawnColor) {
            rebuild(color);
        }
    }

    private void onTeleport(double x, double z, float yaw) {
        PadMaze.Hop hop = maze.observe(lastX, lastZ, x, z, yaw);
        if (hop == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] tpmaze: jump ({},{}) -> ({},{}) yaw {} "
                    + "not onto a pad - ignored", r(lastX), r(lastZ), r(x), r(z), r(yaw));
            return;
        }
        if (hop.reachedGoal()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] tpmaze: {} -> goal {} after {} landing(s); "
                    + "showing nothing more", hop.from(), hop.to(), maze.landings());
        } else {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] tpmaze: hop {} (cell {}) -> {} (cell {}) "
                            + "landed ({},{}) yaw {}; used={} predict={} lead={}",
                    hop.from(), hop.from() == null ? -1 : maze.cellOf(hop.from()), hop.to(),
                    maze.cellOf(hop.to()), r(x), r(z), r(yaw), maze.used().size(),
                    hop.prediction() == null ? "none" : hop.prediction(),
                    hop.lead() == Double.MAX_VALUE ? "-" : r(hop.lead()));
        }
        dirty = true;
    }

    private static String r(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private void scanStep(ClientLevel level, DungeonRoomBorders.Borders borders) {
        int minX = borders.min().getX();
        int minZ = borders.min().getZ();
        int sizeX = borders.max().getX() - minX + 1;
        int sizeZ = borders.max().getZ() - minZ + 1;
        int floor = borders.min().getY();
        int columns = sizeX * sizeZ;
        int budget = SCAN_BUDGET;
        while (scanCursor < columns && budget > 0) {
            int x = minX + scanCursor % sizeX;
            int z = minZ + scanCursor / sizeX;
            scanCursor++;
            probe.set(x, floor, z);
            if (!borders.contains(probe)) {
                continue;
            }
            for (int dy = -SCAN_BELOW; dy <= SCAN_ABOVE; dy++) {
                probe.set(x, floor + dy, z);
                if (level.getBlockState(probe).is(Blocks.END_PORTAL_FRAME)) {
                    found.add(new PadMaze.Pad(x, z));
                    padY = floor + dy;
                    break;
                }
            }
            budget -= SCAN_BELOW + SCAN_ABOVE + 1;
        }
        if (scanCursor >= columns) {
            scanCursor = -1;
            maze = PadMaze.of(found);
            if (maze == null) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] tpmaze: room scan found {} frame(s) at y {}, "
                        + "not a 3x3 pad grid - showing nothing; pads={}", found.size(), padY, found);
            } else {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] tpmaze: room scan found {} pad(s) at y {} "
                                + "roles={} entry={} goal={}", found.size(), padY, maze.roleCounts(),
                        maze.padsOf(PadMaze.Role.ENTRY), maze.padsOf(PadMaze.Role.GOAL));
            }
            dirty = true;
        }
    }

    private void rebuild(int color) {
        dirty = false;
        drawnColor = color;
        if (maze == null || maze.done()) {
            drawn = List.of();
            return;
        }
        List<PuzzleHighlight> out = new ArrayList<>(maze.used().size() + 1);
        PadMaze.Pad best = maze.prediction();
        List<PadMaze.Pad> route = List.of();
        if (best != null) {
            out.add(PuzzleHighlight.answer(box(best).inflate(0.02), color, "§aPAD"));
            // The known way there: pads already used whose teleports lead into the target's cell,
            // numbered in order (asked 2026-10-17). Unknown links cannot be shown - none until used.
            var player = Minecraft.getInstance().player;
            if (player != null) {
                List<PadMaze.Pad> known = maze.routeTo(maze.cellAt(player.getX(), player.getZ()), best);
                route = known == null ? List.of() : known;
            }
            for (int i = 0; i < route.size(); i++) {
                out.add(PuzzleHighlight.answer(box(route.get(i)).inflate(0.02), ROUTE_COLOR, "§e" + (i + 1)));
            }
        }
        for (PadMaze.Pad pad : maze.used()) {
            if (!route.contains(pad)) {
                out.add(PuzzleHighlight.context(box(pad), USED_COLOR, null));
            }
        }
        drawn = List.copyOf(out);
    }

    private AABB box(PadMaze.Pad pad) {
        return new AABB(pad.x(), padY, pad.z(), pad.x() + 1, padY + 1, pad.z() + 1);
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        return drawn;
    }

    @Override
    public void reset() {
        scanBorders = null;
        scanCursor = 0;
        padY = Integer.MIN_VALUE;
        found.clear();
        maze = null;
        hasLast = false;
        drawn = List.of();
        dirty = false;
        drawnColor = 0;
    }
}
