/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.traps.model;

import net.minecraft.core.BlockPos;

/**
 * A run of tripwire drawn as one thing.
 *
 * <p>A trap line across a corridor is a dozen wire blocks in a row. Boxing each of them draws twelve
 * cubes that say nothing the first one did not; the information a player needs is <i>where the line
 * is and how far it reaches</i>, which is one shape from end to end. So contiguous wire is grouped
 * and the group is what gets drawn.
 *
 * <p>The bounds are inclusive block coordinates. Hooks are included in the run when they sit against
 * its ends - they are the anchors the wire is strung between, and a run that stops one block short of
 * its hook reads as a gap the player could step through.
 *
 * @param min    lowest corner of the run, inclusive
 * @param max    highest corner, inclusive
 * @param blocks how many wire blocks and hooks the run is made of
 */
public record TripwireRun(BlockPos min, BlockPos max, int blocks) {

    /** The run's centre, for a distance test that treats the whole line as one target. */
    public double centreX() {
        return (min.getX() + max.getX() + 1) / 2.0;
    }

    public double centreY() {
        return (min.getY() + max.getY() + 1) / 2.0;
    }

    public double centreZ() {
        return (min.getZ() + max.getZ() + 1) / 2.0;
    }
}
