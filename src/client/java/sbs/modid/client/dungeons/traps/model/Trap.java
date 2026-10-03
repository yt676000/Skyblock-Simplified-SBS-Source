/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.traps.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * One indexed trap block.
 *
 * <p><b>Facing is read, never inferred.</b> A dispenser's {@code FACING} says which way its arrows
 * come from, and that is the single most useful thing about it - so it is taken off the blockstate
 * rather than guessed from which side of the block happens to touch air, which is wrong the moment a
 * dispenser sits in a corner.
 *
 * <p><b>{@code triggered} is recorded and deliberately not acted on.</b> Vanilla tripwire carries
 * {@code POWERED} and {@code DISARMED}, and it is tempting to dim a wire that reads as already
 * sprung. Whether a triggered trap stays dangerous in the Catacombs is not something anybody here has
 * confirmed, and hiding a live trap is a much worse failure than drawing a dead one - so every trap
 * is drawn the same and this flag exists only so the question can be answered from the log.
 *
 * @param kind      which of the three blocks this is
 * @param pos       where it sits
 * @param facing    the dispenser's direction, or {@code null} for wire and hooks
 * @param triggered whether the blockstate says it is powered or disarmed; diagnostic only
 */
public record Trap(Kind kind, BlockPos pos, Direction facing, boolean triggered) {

    public enum Kind {
        /** The wire itself - nearly invisible in game, which is the whole reason for this feature. */
        TRIPWIRE,
        /** The hook a wire is strung between; the endpoints a run is anchored on. */
        HOOK,
        /** Arrow dispensers, usually sunk into a wall or ceiling where nobody is looking. */
        DISPENSER
    }

    /** Whether this block belongs to a wire line rather than being a dispenser. */
    public boolean isWire() {
        return kind == Kind.TRIPWIRE || kind == Kind.HOOK;
    }
}
