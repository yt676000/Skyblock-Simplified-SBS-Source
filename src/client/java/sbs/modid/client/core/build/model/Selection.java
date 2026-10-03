/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.model;

/**
 * A box between two corner blocks, inclusive on both ends. Immutable; every edit returns a new one.
 *
 * <p>The corners are kept as the player set them (corner 1 and corner 2), not normalised to min/max,
 * so "set corner 2 again" moves the corner the player means. The derived {@code min*}/{@code max*}
 * and sizes are what every consumer reads.
 */
public record Selection(int x1, int y1, int z1, int x2, int y2, int z2) {

    /** A one-block selection at a single position. */
    public static Selection single(int x, int y, int z) {
        return new Selection(x, y, z, x, y, z);
    }

    public int minX() {
        return Math.min(x1, x2);
    }

    public int minY() {
        return Math.min(y1, y2);
    }

    public int minZ() {
        return Math.min(z1, z2);
    }

    public int maxX() {
        return Math.max(x1, x2);
    }

    public int maxY() {
        return Math.max(y1, y2);
    }

    public int maxZ() {
        return Math.max(z1, z2);
    }

    public int width() {
        return maxX() - minX() + 1;
    }

    public int height() {
        return maxY() - minY() + 1;
    }

    public int length() {
        return maxZ() - minZ() + 1;
    }

    public long volume() {
        return (long) width() * height() * length();
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX() && x <= maxX() && y >= minY() && y <= maxY() && z >= minZ() && z <= maxZ();
    }

    /** {@code W×H×L}. */
    public String sizeLabel() {
        return width() + "×" + height() + "×" + length();
    }

    public Selection withCorner1(int x, int y, int z) {
        return new Selection(x, y, z, x2, y2, z2);
    }

    public Selection withCorner2(int x, int y, int z) {
        return new Selection(x1, y1, z1, x, y, z);
    }

    /** Both corners moved {@code n} blocks along {@code dir} (negative {@code n} moves back). */
    public Selection shift(int n, BuildDir dir) {
        int dx = dir.dx * n;
        int dy = dir.dy * n;
        int dz = dir.dz * n;
        return new Selection(x1 + dx, y1 + dy, z1 + dz, x2 + dx, y2 + dy, z2 + dz);
    }

    /**
     * The face on the {@code dir} side pushed out by {@code n} blocks. A negative {@code n} is a
     * {@link #contract}. Whichever corner currently forms that face is the one that moves; when both
     * corners lie on it (a one-thick box), corner 2 moves.
     */
    public Selection expand(int n, BuildDir dir) {
        if (n < 0) {
            return contract(-n, dir);
        }
        return moveFace(dir, n);
    }

    /** Every face pushed out by {@code n}. */
    public Selection expandAll(int n) {
        Selection out = this;
        for (BuildDir dir : BuildDir.values()) {
            out = out.expand(n, dir);
        }
        return out;
    }

    /**
     * The face on the {@code dir} side pulled in by {@code n} blocks, never past the opposite face -
     * a selection cannot be contracted below one block thick.
     */
    public Selection contract(int n, BuildDir dir) {
        if (n < 0) {
            return expand(-n, dir);
        }
        int thickness = switch (dir) {
            case UP, DOWN -> height();
            case NORTH, SOUTH -> length();
            case EAST, WEST -> width();
        };
        int step = Math.min(n, thickness - 1);
        return moveFace(dir, -step);
    }

    /** Every face pulled in by {@code n}, each clamped on its own. */
    public Selection contractAll(int n) {
        Selection out = this;
        for (BuildDir dir : BuildDir.values()) {
            out = out.contract(n, dir);
        }
        return out;
    }

    /** Moves the face on the {@code dir} side outward by {@code delta} (inward when negative). */
    private Selection moveFace(BuildDir dir, int delta) {
        int ax1 = x1;
        int ay1 = y1;
        int az1 = z1;
        int ax2 = x2;
        int ay2 = y2;
        int az2 = z2;
        switch (dir) {
            case EAST -> {
                if (ax2 >= ax1) {
                    ax2 += delta;
                } else {
                    ax1 += delta;
                }
            }
            case WEST -> {
                if (ax2 <= ax1) {
                    ax2 -= delta;
                } else {
                    ax1 -= delta;
                }
            }
            case UP -> {
                if (ay2 >= ay1) {
                    ay2 += delta;
                } else {
                    ay1 += delta;
                }
            }
            case DOWN -> {
                if (ay2 <= ay1) {
                    ay2 -= delta;
                } else {
                    ay1 -= delta;
                }
            }
            case SOUTH -> {
                if (az2 >= az1) {
                    az2 += delta;
                } else {
                    az1 += delta;
                }
            }
            case NORTH -> {
                if (az2 <= az1) {
                    az2 -= delta;
                } else {
                    az1 -= delta;
                }
            }
        }
        return new Selection(ax1, ay1, az1, ax2, ay2, az2);
    }
}
