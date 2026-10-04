/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The hybrid planner's cheap pass: is the straight line between two ground points simply walkable
 * open terrain? If so, the walk along it <i>is</i> the route piece, and A* is not needed there.
 *
 * <p><b>No second walkability definition.</b> Every step taken here is a move {@link PathfinderTask}
 * would also take: the landing is {@link Terrain#standKind} {@code STAND}, the climb is at most
 * {@code maxStepUp} with clear headroom above the player, the drop at most {@code maxFall} through an
 * open column, and a diagonal needs both cardinal neighbours clear (no corner cutting). What this adds
 * is only <i>stricter</i>: open sky over the head, no liquid underfoot or in the body, nothing taller
 * than a block (a fence or wall the step rules would otherwise stand on top of). Anything else -
 * covered, cave, house, too steep, water - is "not open" and goes to A*.
 *
 * <p>It samples every block (not every second one): the result is drawn as the route, and a sample
 * gap would be a line through whatever stood in it. Pure: everything comes through {@link Terrain}.
 */
final class OpenCorridor {

    private OpenCorridor() {
    }

    /**
     * Walks the straight line from {@code from} (a standing spot) towards {@code to}'s column.
     *
     * @return the standing spots along the line, {@code from} first and the spot in {@code to}'s
     *         column last - or {@code null} when the line is not open terrain all the way
     */
    static List<BlockPos> walk(Terrain terrain, BlockPos from, BlockPos to, int maxStepUp, int maxFall) {
        if (terrain.standKind(from.getX(), from.getY(), from.getZ()) != Walkability.STAND
                || !openAt(terrain, from.getX(), from.getY(), from.getZ())) {
            return null;
        }
        List<BlockPos> out = new ArrayList<>();
        out.add(from);
        int x = from.getX();
        int y = from.getY();
        int z = from.getZ();
        int dx = to.getX() - x;
        int dz = to.getZ() - z;
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int i = 1; i <= steps; i++) {
            // One block per step along the dominant axis; the other rounds, so moves are the eight
            // A* directions and never skip a column.
            int nx = from.getX() + Math.round((float) dx * i / steps);
            int nz = from.getZ() + Math.round((float) dz * i / steps);
            int mx = nx - x;
            int mz = nz - z;
            if (mx != 0 && mz != 0
                    && !(terrain.bodyClear(x + mx, y, z) && terrain.bodyClear(x, y, z + mz))) {
                return null;   // a diagonal through a wall corner
            }
            int ny = landing(terrain, x, y, z, nx, nz, maxStepUp, maxFall);
            if (ny == Integer.MIN_VALUE || !openAt(terrain, nx, ny, nz)) {
                return null;
            }
            x = nx;
            y = ny;
            z = nz;
            out.add(new BlockPos(x, y, z));
        }
        return out;
    }

    /**
     * The landing for one step into column {@code (nx, nz)}, highest first like A*: climb, level,
     * drop. {@link Integer#MIN_VALUE} for none - a wall too tall, or a hole deeper than a fall.
     */
    private static int landing(Terrain terrain, int x, int y, int z, int nx, int nz,
                               int maxStepUp, int maxFall) {
        for (int dy = maxStepUp; dy >= -maxFall; dy--) {
            int ny = y + dy;
            if (terrain.standKind(nx, ny, nz) != Walkability.STAND) {
                continue;
            }
            if (dy > 0) {
                for (int h = y + 2; h <= ny + 1; h++) {
                    if (!terrain.isPassable(x, h, z)) {
                        return Integer.MIN_VALUE;   // the jump would hit a ceiling
                    }
                }
                // A fence or wall: standable on top per the step rules, not jumpable onto.
                if (terrain.tall(nx, ny - 1, nz)) {
                    return Integer.MIN_VALUE;
                }
            } else if (dy < 0) {
                for (int h = y; h > ny + 1; h--) {
                    if (!terrain.bodyClear(nx, h, nz)) {
                        return Integer.MIN_VALUE;
                    }
                }
            }
            return ny;
        }
        return Integer.MIN_VALUE;
    }

    /** Open air over the head, no liquid in the body or underfoot, no fence or wall underfoot. */
    private static boolean openAt(Terrain terrain, int x, int y, int z) {
        return terrain.openSky(x, y + 1, z)
                && !terrain.liquid(x, y, z) && !terrain.liquid(x, y + 1, z) && !terrain.liquid(x, y - 1, z)
                && !terrain.tall(x, y - 1, z) && !terrain.tall(x, y, z);
    }

    /**
     * The topmost open-air standing spot in column {@code (x, z)} near {@code hintY} - where a
     * corridor anchor sits. Searched from {@code hintY + up} down to {@code hintY - down}.
     *
     * @return the spot, or {@code null} when the column has none in that band
     */
    static BlockPos ground(Terrain terrain, int x, int z, int hintY, int up, int down) {
        for (int y = hintY + up; y >= hintY - down; y--) {
            if (terrain.standKind(x, y, z) == Walkability.STAND && openAt(terrain, x, y, z)) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }
}
