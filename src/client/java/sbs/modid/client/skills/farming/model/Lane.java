/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

/**
 * One lane of a {@link Farm}: a strip along X or Z with its own two ends. Plain fields so Gson stores
 * it as written. All coordinates are block coordinates.
 *
 * <ul>
 *   <li><b>Along the axis</b>: {@link #start} and {@link #end}, both inclusive, in either order - the
 *       blocks the player stood on when marking the two ends. The lane ends past the far block.</li>
 *   <li><b>Across it</b>: {@link #crossMin}..{@link #crossMax}, both inclusive - how far to either
 *       side of the walked line still counts as "in this lane".</li>
 * </ul>
 *
 * <p>A lane made from a marked rectangle ({@link #rows}) has the rectangle's full width across it:
 * every row inside is a lane with the same two ends, which is what the old lane areas were.
 */
public final class Lane {

    public enum Axis { X, Z }

    /** "X" or "Z". */
    public String axis = "X";
    public int start;
    public int end;
    public int crossMin;
    public int crossMax;
    /** The floor the lane was marked on; drawing only. */
    public int y;
    /** Made from a rectangle: every row inside is a lane (a "rectangle lane group"). */
    public boolean rows;

    public Lane() {
    }

    public Lane(Axis axis, int start, int end, int crossMin, int crossMax, int y) {
        this.axis = axis.name();
        this.start = start;
        this.end = end;
        this.crossMin = Math.min(crossMin, crossMax);
        this.crossMax = Math.max(crossMin, crossMax);
        this.y = y;
    }

    /**
     * The lane walked from block {@code a} to block {@code b} ({x, y, z} each), {@code width} blocks
     * wide, centred on {@code a}'s row (an even width leans to +cross). The axis is the one the two
     * points are further apart on; the other coordinate of {@code b} is ignored, so ending the lane a
     * block off to the side still gives a straight lane. {@code null} when both points are in one
     * block, which has no direction.
     */
    public static Lane between(int[] a, int[] b, int width) {
        int dx = Math.abs(b[0] - a[0]);
        int dz = Math.abs(b[2] - a[2]);
        if (dx == 0 && dz == 0) {
            return null;
        }
        Axis axis = dz > dx ? Axis.Z : Axis.X;
        int w = Math.max(1, width);
        int cross = axis == Axis.X ? a[2] : a[0];
        int low = cross - (w - 1) / 2;
        return axis == Axis.X
                ? new Lane(Axis.X, a[0], b[0], low, low + w - 1, a[1])
                : new Lane(Axis.Z, a[2], b[2], low, low + w - 1, a[1]);
    }

    /** Every row of the rectangle between two corners as lanes along {@code axis}. */
    public static Lane rows(int x1, int z1, int x2, int z2, int y, Axis axis) {
        Lane lane = axis == Axis.X
                ? new Lane(Axis.X, Math.min(x1, x2), Math.max(x1, x2), z1, z2, y)
                : new Lane(Axis.Z, Math.min(z1, z2), Math.max(z1, z2), x1, x2, y);
        lane.rows = true;
        return lane;
    }

    public Axis axis() {
        return "Z".equalsIgnoreCase(axis) ? Axis.Z : Axis.X;
    }

    /** The low end along the axis, in world coordinates. */
    public double low() {
        return Math.min(start, end);
    }

    /** The high end along the axis: past the last block. */
    public double high() {
        return Math.max(start, end) + 1;
    }

    public int length() {
        return (int) (high() - low());
    }

    public int width() {
        return crossMax - crossMin + 1;
    }

    /** A position's coordinate along the lane. */
    public double along(double x, double z) {
        return axis() == Axis.X ? x : z;
    }

    /** A position's coordinate across the lane. */
    public double cross(double x, double z) {
        return axis() == Axis.X ? z : x;
    }

    /** The middle of the lane across it. */
    public double crossCentre() {
        return (crossMin + crossMax + 1) / 2.0;
    }

    /** Whether a position is inside the lane's strip (block edges, so the far block counts). */
    public boolean contains(double x, double z) {
        double a = along(x, z);
        double c = cross(x, z);
        return a >= low() && a < high() && c >= crossMin && c < crossMax + 1;
    }

    /** Sets the width, keeping the lane centred where it was (an even width leans to +cross). */
    public void setWidth(int width) {
        int w = Math.max(1, width);
        int centre = (int) Math.floor(crossCentre() - 0.5 + 1e-9);
        crossMin = centre - (w - 1) / 2;
        crossMax = crossMin + w - 1;
    }

    /** Swaps along and across - only meaningful for a {@link #rows} lane, whose rows run either way. */
    public void flipAxis() {
        int lo = (int) low();
        int hi = (int) high() - 1;
        int cMin = crossMin;
        int cMax = crossMax;
        axis = axis() == Axis.X ? "Z" : "X";
        start = cMin;
        end = cMax;
        crossMin = lo;
        crossMax = hi;
    }

    /** A copy moved {@code offset} blocks across the lane. */
    public Lane shifted(int offset) {
        Lane copy = new Lane(axis(), start, end, crossMin + offset, crossMax + offset, y);
        copy.rows = rows;
        return copy;
    }

    /** World X/Z of a point given along and across the lane. */
    public double worldX(double along, double cross) {
        return axis() == Axis.X ? along : cross;
    }

    public double worldZ(double along, double cross) {
        return axis() == Axis.X ? cross : along;
    }
}
