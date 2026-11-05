/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import sbs.modid.client.dungeons.floorseven.model.ArrowAlignGrid;

import java.util.Arrays;
import java.util.List;

/**
 * Matches the live Arrow Align grid against the captured solved layouts and turns the match into a
 * click count per cell. Pure Java, unit-tested.
 *
 * <p>A layout matches when it has the same shape ({@link ArrowAlignGrid#sameShape}). Two matching
 * layouts with the same solution are one; with different solutions the answer is
 * {@link Status#AMBIGUOUS} and no count is shown - a wrong count is worse than none.
 */
public final class ArrowAlignSolver {

    public enum Status {
        /** No device in range. */
        NO_GRID,
        /** The frames found are spread wider than the device can be - not the device. */
        TOO_LARGE,
        /** No captured layout has this shape: the probe is recording it. */
        UNKNOWN,
        /** Several captured layouts fit with different solutions. */
        AMBIGUOUS,
        SOLVED
    }

    /** One captured solution, parsed. */
    public record Target(String id, ArrowAlignGrid grid) {
    }

    /**
     * {@code clicks[cell]} = right clicks still needed on that cell's frame, {@code -1} for cells
     * without an arrow; {@code null} unless {@link Status#SOLVED}.
     */
    public record Result(Status status, String layoutId, int[] clicks) {
        public static final Result NO_GRID = new Result(Status.NO_GRID, null, null);

        public int clicksAt(int cell) {
            return clicks == null || cell < 0 || cell >= clicks.length ? -1 : clicks[cell];
        }

        /** Total clicks left on the whole device, or {@code -1} when not solved. */
        public int remaining() {
            if (clicks == null) {
                return -1;
            }
            int sum = 0;
            for (int c : clicks) {
                sum += Math.max(0, c);
            }
            return sum;
        }
    }

    private ArrowAlignSolver() {
    }

    public static Result solve(ArrowAlignGrid live, List<Target> targets) {
        if (live == null) {
            return Result.NO_GRID;
        }
        if (!live.fitsDevice()) {
            return new Result(Status.TOO_LARGE, null, null);
        }
        Target match = null;
        for (Target target : targets) {
            if (target.grid() == null || !target.grid().sameShape(live)) {
                continue;
            }
            if (match == null) {
                match = target;
            } else if (!match.grid().equals(target.grid())) {
                return new Result(Status.AMBIGUOUS, null, null);
            }
        }
        if (match == null || live.arrowCount() == 0) {
            return new Result(Status.UNKNOWN, null, null);
        }
        int[] clicks = new int[live.size()];
        Arrays.fill(clicks, -1);
        for (int cell = 0; cell < clicks.length; cell++) {
            int current = live.cellAt(cell);
            if (current >= 0) {
                clicks[cell] = ArrowAlignGrid.clicks(current, match.grid().cellAt(cell));
            }
        }
        return new Result(Status.SOLVED, match.id(), clicks);
    }
}
