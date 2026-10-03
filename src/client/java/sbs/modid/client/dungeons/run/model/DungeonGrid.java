/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.model;

import net.minecraft.core.BlockPos;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * The fixed 32x32 dungeon cell grid.
 *
 * <p>Every dungeon room is built from one or more 32-block cells on a global grid. This class snaps
 * world coordinates to that grid so a room complex can be described by whole cells.
 *
 * <p><b>Calibration:</b> {@link #ORIGIN_X}/{@link #ORIGIN_Z} are the one thing that must be tuned to a
 * live server – they say where a cell boundary falls in world coordinates. Turn on {@code /sbsdev} and
 * read the "cell" line / the grid box: nudge the origin until a cell box lines up exactly with a room's
 * 32x32 footprint. (Rotation matching itself uses the cell-complex <i>centre</i>, which is invariant, so
 * an off-by-a-few origin only shifts which cell you're "in", not the match once calibrated.)
 */
public final class DungeonGrid {

    /** Cell size in blocks. */
    public static final int CELL = 32;

    /** World coordinate offset of the grid (calibration – see class doc). */
    public static int ORIGIN_X = 0;
    public static int ORIGIN_Z = 0;

    private DungeonGrid() {
    }

    /** The cell index (can be negative) containing {@code worldX}. */
    public static int cellIndexX(int worldX) {
        return Math.floorDiv(worldX - ORIGIN_X, CELL);
    }

    public static int cellIndexZ(int worldZ) {
        return Math.floorDiv(worldZ - ORIGIN_Z, CELL);
    }

    /** North-west corner (min X/Z) world coordinate of the cell with the given index. */
    public static int cellMinX(int cellIndexX) {
        return cellIndexX * CELL + ORIGIN_X;
    }

    public static int cellMinZ(int cellIndexZ) {
        return cellIndexZ * CELL + ORIGIN_Z;
    }

    /** The NW corner of the cell the position is in (its {@code y} is kept). */
    public static BlockPos cellCorner(BlockPos pos) {
        return new BlockPos(cellMinX(cellIndexX(pos.getX())), pos.getY(), cellMinZ(cellIndexZ(pos.getZ())));
    }

    /** Loads the calibrated origin from the config (called on init and after {@code /sbsdev origin}). */
    public static void loadFromConfig() {
        SBSConfig.DungeonsSettings dungeons = ConfigManager.getInstance().get().dungeons;
        ORIGIN_X = dungeons.gridOriginX;
        ORIGIN_Z = dungeons.gridOriginZ;
    }
}
