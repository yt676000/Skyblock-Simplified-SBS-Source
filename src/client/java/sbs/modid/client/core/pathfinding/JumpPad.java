/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.core.BlockPos;

/**
 * One jump pad as the pathfinder uses it: where you stand to be launched, where you come down, and
 * how sure that is.
 *
 * @param stand    the standing position on the pad (the block above the slime block)
 * @param landing  the standing position where the launch came down
 * @param flightMs how long the flight took
 * @param launches consistent launches seen
 * @param certainty {@link Certainty#CONFIRMED} after two consistent launches (or a manual record),
 *                  {@link Certainty#LEARNED} after one
 */
public record JumpPad(BlockPos stand, BlockPos landing, long flightMs, int launches, Certainty certainty) {

    public enum Certainty {
        LEARNED, CONFIRMED
    }

    /** Horizontal straight-line length of the flight, in blocks. */
    public double horizontalDistance() {
        double dx = landing.getX() - stand.getX();
        double dz = landing.getZ() - stand.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
