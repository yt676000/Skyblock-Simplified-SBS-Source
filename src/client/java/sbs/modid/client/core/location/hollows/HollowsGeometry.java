/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.location.hollows;

/**
 * Where the Crystal Hollows' regions lie. <b>Every number in this class is ESTIMATED.</b>
 *
 * <p>Verified (wiki): the Crystal Nucleus is in the centre of the area. Everything else - the
 * extent, the centre coordinate, which quadrant lies in which corner, the height below which Magma
 * Fields begins and the size of the Nucleus - is taken from wiki mirrors and memory and has not been
 * checked against a single captured zone/position pair. {@link HollowsDetector} logs every zone
 * change next to what {@link #classify} predicts, plus a {@code layout disagrees} line whenever the
 * two differ; one session on the Hollows settles this table.
 *
 * <p>It is one table on purpose. Every consumer - the schematic map, the minimap, the trail's layer
 * split, the SkyBlock Map's Hollows frame - reads it here, so correcting it is one edit.
 */
public final class HollowsGeometry {

    /** The island these regions belong to, as {@code SkyBlockLocation} names it. */
    public static final String ISLAND = "Crystal Hollows";

    /** ESTIMATED: lowest X and Z of the Hollows. */
    public static final int MIN = 202;

    /** ESTIMATED: highest X and Z of the Hollows. */
    public static final int MAX = 823;

    /** ESTIMATED: the X and Z of the middle, where the Nucleus is. */
    public static final int CENTER = 512;

    /** ESTIMATED: lowest Y of the playable cave. */
    public static final int MIN_Y = 31;

    /** ESTIMATED: highest Y of the playable cave. */
    public static final int MAX_Y = 188;

    /** ESTIMATED: below this Y you are in Magma Fields, whatever the X and Z. */
    public static final int MAGMA_Y = 64;

    /** ESTIMATED: half the side of the Nucleus square around {@link #CENTER}. */
    public static final int NUCLEUS_HALF = 64;

    // The plausibility checks Structure Sharing applies to every position it sends or receives. They
    // are deliberately more generous than the drawing layout above: a check that is too tight rejects
    // a real structure, one that is too loose only lets an odd position through to the next check.

    /** How far past the centre line a quadrant's structure is still accepted. */
    public static final int QUADRANT_MARGIN = 64;

    /** ESTIMATED, deliberately deep: the highest Y a Magma Fields structure is accepted at. */
    public static final int MAGMA_MAX_Y = 100;

    /** The widest a structure's walked-around box may be on any axis. */
    public static final int MAX_BOX_EXTENT = 192;

    private HollowsGeometry() {
    }

    /**
     * Which region the ESTIMATED layout puts a position in.
     *
     * <p>Order matters: Magma Fields is a layer under everything, so height is asked first; then the
     * Nucleus square in the middle; then the quadrant. North is -Z and west is -X, so the north-west
     * quadrant is the one with both coordinates below the centre.
     */
    public static HollowsRegion classify(int x, int y, int z) {
        if (y < MAGMA_Y) {
            return HollowsRegion.MAGMA_FIELDS;
        }
        if (Math.abs(x - CENTER) <= NUCLEUS_HALF && Math.abs(z - CENTER) <= NUCLEUS_HALF) {
            return HollowsRegion.CRYSTAL_NUCLEUS;
        }
        boolean west = x < CENTER;
        boolean north = z < CENTER;
        if (north) {
            return west ? HollowsRegion.JUNGLE : HollowsRegion.MITHRIL_DEPOSITS;
        }
        return west ? HollowsRegion.GOBLIN_HOLDOUT : HollowsRegion.PRECURSOR_REMNANTS;
    }

    /** Whether a position's Y puts it on the Magma Fields layer. */
    public static boolean belowMagma(int y) {
        return y < MAGMA_Y;
    }

    /**
     * The X/Z rectangle a region is drawn as on the schematic map, {@code {minX, minZ, maxX, maxZ}}.
     *
     * <p>Quadrants are drawn as the full corner square; the Nucleus is drawn on top of them, so the
     * overlap in the middle reads as the Nucleus. Magma Fields covers the whole square on its own
     * layer.
     */
    public static int[] rect(HollowsRegion region) {
        return switch (region) {
            case JUNGLE -> new int[]{MIN, MIN, CENTER, CENTER};
            case MITHRIL_DEPOSITS -> new int[]{CENTER, MIN, MAX, CENTER};
            case GOBLIN_HOLDOUT -> new int[]{MIN, CENTER, CENTER, MAX};
            case PRECURSOR_REMNANTS -> new int[]{CENTER, CENTER, MAX, MAX};
            case CRYSTAL_NUCLEUS -> new int[]{CENTER - NUCLEUS_HALF, CENTER - NUCLEUS_HALF,
                    CENTER + NUCLEUS_HALF, CENTER + NUCLEUS_HALF};
            case MAGMA_FIELDS -> new int[]{MIN, MIN, MAX, MAX};
        };
    }

    /** Whether an X/Z position is inside the ESTIMATED square at all. */
    public static boolean inBounds(int x, int z) {
        return x >= MIN && x <= MAX && z >= MIN && z <= MAX;
    }

    /** Whether a position is inside the ESTIMATED box at all, height included. */
    public static boolean inBounds(int x, int y, int z) {
        return inBounds(x, z) && y >= MIN_Y && y <= MAX_Y;
    }

    /**
     * Whether a position inside the bounds could belong to a structure expected in {@code region}
     * ({@code null}: anywhere). Each quadrant reaches {@link #QUADRANT_MARGIN} past the centre line;
     * Magma Fields is anything up to {@link #MAGMA_MAX_Y}; the Nucleus is its square plus that margin.
     */
    public static boolean plausible(HollowsRegion region, int x, int y, int z) {
        if (region == null) {
            return true;
        }
        boolean west = x <= CENTER + QUADRANT_MARGIN;
        boolean east = x >= CENTER - QUADRANT_MARGIN;
        boolean north = z <= CENTER + QUADRANT_MARGIN;
        boolean south = z >= CENTER - QUADRANT_MARGIN;
        return switch (region) {
            case JUNGLE -> west && north;
            case MITHRIL_DEPOSITS -> east && north;
            case GOBLIN_HOLDOUT -> west && south;
            case PRECURSOR_REMNANTS -> east && south;
            case MAGMA_FIELDS -> y <= MAGMA_MAX_Y;
            case CRYSTAL_NUCLEUS -> Math.abs(x - CENTER) <= NUCLEUS_HALF + QUADRANT_MARGIN
                    && Math.abs(z - CENTER) <= NUCLEUS_HALF + QUADRANT_MARGIN;
        };
    }
}
