/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The levers and lamps of one Lights device as seen in the world, in a stable order.
 *
 * <p>Positions are kept absolute, and every relative view is taken against {@link #anchor()}: the
 * minimum corner over all levers and lamps. That corner is the one thing every capture of the same
 * build agrees on whatever block of it the player first saw, so it is what the bundled wiring data is
 * written against.
 *
 * <p>Index {@code i} of {@link #levers()} is bit {@code i} of a lever mask, and index {@code j} of
 * {@link #lamps()} bit {@code j} of a lamp mask - so a layout supports at most 64 of each.
 */
public final class LightsLayout {

    /** Bit width of the masks the solver works in. */
    public static final int MAX_CELLS = 64;

    private final LightsCell anchor;
    private final List<LightsCell> levers;
    private final List<LightsCell> lamps;

    private LightsLayout(LightsCell anchor, List<LightsCell> levers, List<LightsCell> lamps) {
        this.anchor = anchor;
        this.levers = levers;
        this.lamps = lamps;
    }

    /** A layout of these absolute positions; {@code null} when either side is empty. */
    public static LightsLayout of(Collection<LightsCell> levers, Collection<LightsCell> lamps) {
        if (levers.isEmpty() || lamps.isEmpty()) {
            return null;
        }
        List<LightsCell> sortedLevers = sorted(levers);
        List<LightsCell> sortedLamps = sorted(lamps);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (List<LightsCell> side : List.of(sortedLevers, sortedLamps)) {
            for (LightsCell cell : side) {
                minX = Math.min(minX, cell.x());
                minY = Math.min(minY, cell.y());
                minZ = Math.min(minZ, cell.z());
            }
        }
        return new LightsLayout(new LightsCell(minX, minY, minZ), sortedLevers, sortedLamps);
    }

    private static List<LightsCell> sorted(Collection<LightsCell> cells) {
        List<LightsCell> list = new ArrayList<>(Set.copyOf(cells));
        Collections.sort(list);
        return List.copyOf(list);
    }

    public LightsCell anchor() {
        return anchor;
    }

    /** Absolute lever positions, sorted. */
    public List<LightsCell> levers() {
        return levers;
    }

    /** Absolute lamp positions, sorted. */
    public List<LightsCell> lamps() {
        return lamps;
    }

    /** Whether every lever and lamp fits in a 64-bit mask. */
    public boolean supported() {
        return levers.size() <= MAX_CELLS && lamps.size() <= MAX_CELLS;
    }

    public int leverIndex(LightsCell absolute) {
        int index = Collections.binarySearch(levers, absolute);
        return index < 0 ? -1 : index;
    }

    public int lampIndex(LightsCell absolute) {
        int index = Collections.binarySearch(lamps, absolute);
        return index < 0 ? -1 : index;
    }

    /** Lever positions relative to the anchor, in index order. */
    public List<LightsCell> relativeLevers() {
        return relative(levers);
    }

    /** Lamp positions relative to the anchor, in index order. */
    public List<LightsCell> relativeLamps() {
        return relative(lamps);
    }

    private List<LightsCell> relative(List<LightsCell> cells) {
        List<LightsCell> out = new ArrayList<>(cells.size());
        for (LightsCell cell : cells) {
            out.add(cell.minus(anchor));
        }
        return out;
    }

    /** The lamp mask of a set of absolute lamp positions; positions not in this layout are ignored. */
    public long lampMask(Collection<LightsCell> absoluteLamps) {
        long mask = 0L;
        for (LightsCell lamp : absoluteLamps) {
            int index = lampIndex(lamp);
            if (index >= 0 && index < MAX_CELLS) {
                mask |= 1L << index;
            }
        }
        return mask;
    }

    /** The relative positions of the set bits of a lamp mask, for log lines. */
    public String describeLamps(long mask) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < lamps.size() && i < MAX_CELLS; i++) {
            if ((mask & (1L << i)) != 0) {
                if (text.length() > 1) {
                    text.append(' ');
                }
                text.append('#').append(i).append('(').append(lamps.get(i).minus(anchor)).append(')');
            }
        }
        return text.append(']').toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LightsLayout that && levers.equals(that.levers) && lamps.equals(that.lamps);
    }

    @Override
    public int hashCode() {
        return 31 * levers.hashCode() + lamps.hashCode();
    }
}
