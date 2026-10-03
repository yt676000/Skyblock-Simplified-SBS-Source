/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.model;

/**
 * The fixed 96x96 plot grid of the SkyBlock Garden.
 *
 * <p>The Garden tiles into 96-block plots on a grid centred on the world origin, so the plot a
 * coordinate belongs to is a pure calculation, no menu scan and no
 * area selection needed. Cell 0 on an axis spans {@code [-48, 47]} (96 blocks centred on 0); the next
 * plot starts exactly 96 blocks over, with no gap.
 *
 * <p>A {@link Bounds} is the whole square footprint of one plot in world coordinates; the vertical
 * extent is chosen by the caller (the Garden is flat, so a fixed absolute Y band is captured).
 */
public final class GardenPlot {

    /** Edge length of a Garden plot in blocks. */
    public static final int PLOT_SIZE = 96;

    private static final int HALF = PLOT_SIZE / 2;

    private GardenPlot() {
    }

    /** The plot cell index along one axis for a world coordinate (…-1, 0, 1…). */
    public static int cell(double coord) {
        return Math.floorDiv((int) Math.floor(coord) + HALF, PLOT_SIZE);
    }

    /** The inclusive minimum world coordinate of the given cell along one axis. */
    public static int minCorner(int cell) {
        return cell * PLOT_SIZE - HALF;
    }

    /** The plot footprint containing world coordinates {@code (x, z)}. */
    public static Bounds at(double x, double z) {
        int cellX = cell(x);
        int cellZ = cell(z);
        return new Bounds(cellX, cellZ, minCorner(cellX), minCorner(cellZ));
    }

    /** One plot's square footprint: its grid cell and its inclusive min corner in world space. */
    public record Bounds(int cellX, int cellZ, int minX, int minZ) {

        /** Inclusive maximum X of the plot. */
        public int maxX() {
            return minX + PLOT_SIZE - 1;
        }

        /** Inclusive maximum Z of the plot. */
        public int maxZ() {
            return minZ + PLOT_SIZE - 1;
        }

        /** A short "cellX,cellZ" label for chat / debug. */
        public String label() {
            return cellX + "," + cellZ;
        }
    }
}
