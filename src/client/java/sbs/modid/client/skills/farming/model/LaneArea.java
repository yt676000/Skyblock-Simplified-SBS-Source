/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

import java.util.Locale;

/**
 * <b>Legacy</b>: the pre-farm lane area, read only to migrate {@code lane-areas.json} into farms
 * ({@code LaneFarms.fromAreas}); nothing writes one any more. One area the player marked with two
 * corners, every row inside it a lane along {@link #axis()}, ending at the area's two edges on that
 * axis. The corners are block coordinates, both inclusive, in any order.
 */
public final class LaneArea {

    public String name = "";
    public int x1;
    public int y1;
    public int z1;
    public int x2;
    public int y2;
    public int z2;
    /** "X", "Z", or empty for "the longer side". */
    public String axis = "";
    /** The Garden plot the area was marked in, for the list; display only. */
    public String plot = "";

    public enum Axis { X, Z }

    public LaneArea() {
    }

    public LaneArea(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.x2 = x2;
        this.y2 = y2;
        this.z2 = z2;
    }

    public int minX() {
        return Math.min(x1, x2);
    }

    public int maxX() {
        return Math.max(x1, x2);
    }

    public int minY() {
        return Math.min(y1, y2);
    }

    public int maxY() {
        return Math.max(y1, y2);
    }

    public int minZ() {
        return Math.min(z1, z2);
    }

    public int maxZ() {
        return Math.max(z1, z2);
    }

    /** The lane direction: set explicitly, else the longer side (X on a square). */
    public Axis axis() {
        String set = axis == null ? "" : axis.trim().toUpperCase(Locale.ROOT);
        if (set.equals("X")) {
            return Axis.X;
        }
        if (set.equals("Z")) {
            return Axis.Z;
        }
        return maxZ() - minZ() > maxX() - minX() ? Axis.Z : Axis.X;
    }

    public boolean axisSetExplicitly() {
        return axis != null && !axis.isBlank();
    }

    /** Whether a position is inside the area's footprint (block edges, so the far block counts). */
    public boolean contains(double x, double z) {
        return x >= minX() && x < maxX() + 1 && z >= minZ() && z < maxZ() + 1;
    }

    /** The low edge along the lane axis, in world coordinates. */
    public double lowEdge() {
        return axis() == Axis.X ? minX() : minZ();
    }

    /** The high edge along the lane axis: past the last block. */
    public double highEdge() {
        return (axis() == Axis.X ? maxX() : maxZ()) + 1;
    }

    /** A position's coordinate along the lane axis. */
    public double along(double x, double z) {
        return axis() == Axis.X ? x : z;
    }

    public int length() {
        return (int) (highEdge() - lowEdge());
    }
}
