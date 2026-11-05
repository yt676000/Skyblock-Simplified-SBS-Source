/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.model;

/**
 * One block position of the phase-3 Lights device - a lever or a lamp - as plain integers.
 *
 * <p>Deliberately not {@code BlockPos}: the layout, attribution and solver code is pure logic and is
 * tested without a game, and a record gives a stable {@code equals}/{@code hashCode} and a sort order
 * that makes "the same device" mean the same list in every run.
 */
public record LightsCell(int x, int y, int z) implements Comparable<LightsCell> {

    /** This cell relative to {@code origin}. */
    public LightsCell minus(LightsCell origin) {
        return new LightsCell(x - origin.x, y - origin.y, z - origin.z);
    }

    /** This (relative) cell placed at {@code origin}. */
    public LightsCell plus(LightsCell origin) {
        return new LightsCell(x + origin.x, y + origin.y, z + origin.z);
    }

    /** Sort order: Y, then X, then Z - top-to-bottom rows of a wall read in a stable order. */
    @Override
    public int compareTo(LightsCell other) {
        int c = Integer.compare(y, other.y);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(x, other.x);
        return c != 0 ? c : Integer.compare(z, other.z);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
