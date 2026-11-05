/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import sbs.modid.client.dungeons.floorseven.model.LightsCell;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turns the stream of block changes around the Lights device into "this lever toggled these lamps".
 *
 * <p>A lever flip opens a short window; every lamp that changes inside it is charged to that lever.
 * Lamps are folded in by XOR, so a lamp that went on and off again inside one window counts as
 * unchanged. Lamp updates that arrive a tick <i>before</i> their lever (both land in the same packet
 * batch and the order is not guaranteed) are held for {@link #ORPHAN_TICKS} and folded into the next
 * lever's window.
 *
 * <p>The window is only trusted when one lever flipped an odd number of times inside it. A second
 * lever inside the window - a teammate flipping at the same moment - or one lever flipped twice makes
 * the lamps impossible to attribute, and the window is reported as {@link Ambiguous} rather than
 * guessed at. Wrong wiring would make the solver confidently wrong for the rest of the run; a missed
 * observation costs one more flip.
 *
 * <p>Pure logic over ticks - no game classes - so it is tested with scripted event streams.
 */
public final class LightsFlipAttributor {

    /** Ticks a lever's window stays open for lamp changes. Half a second covers a redstone delay. */
    public static final int WINDOW_TICKS = 10;

    /** Ticks a lamp change may precede its lever and still be charged to it. */
    public static final int ORPHAN_TICKS = 2;

    /** What a closed window, or a stray lamp change, came to. */
    public sealed interface Result permits Flip, Ambiguous, Unattributed {
    }

    /** One lever flipped once (or an odd number of times) and these lamps changed. */
    public record Flip(LightsCell lever, Set<LightsCell> lamps) implements Result {
    }

    /** Several levers, or one lever an even number of times, in one window: not used for wiring. */
    public record Ambiguous(Set<LightsCell> levers, int flips, Set<LightsCell> lamps) implements Result {
    }

    /** Lamps that changed with no lever anywhere near in time - a reset, or a missed lever update. */
    public record Unattributed(Set<LightsCell> lamps) implements Result {
    }

    private final Set<LightsCell> windowLevers = new LinkedHashSet<>();
    private final Set<LightsCell> windowLamps = new LinkedHashSet<>();
    private int windowFlips;
    private long windowStart;

    private final Set<LightsCell> orphanLamps = new LinkedHashSet<>();
    private long orphanTick;

    /** A lever's powered state changed at {@code tick}. */
    public void onLever(LightsCell lever, long tick) {
        if (windowFlips == 0) {
            windowStart = tick;
            if (!orphanLamps.isEmpty() && tick - orphanTick <= ORPHAN_TICKS) {
                for (LightsCell lamp : orphanLamps) {
                    toggle(windowLamps, lamp);
                }
                orphanLamps.clear();
            }
        }
        windowLevers.add(lever);
        windowFlips++;
    }

    /** A lamp's lit state changed at {@code tick}. */
    public void onLamp(LightsCell lamp, long tick) {
        if (windowFlips > 0) {
            toggle(windowLamps, lamp);
            return;
        }
        if (orphanLamps.isEmpty()) {
            orphanTick = tick;
        }
        toggle(orphanLamps, lamp);
    }

    /**
     * Advances to {@code tick} and returns what closed, or {@code null}. At most one result per call:
     * the open window, then stale orphans.
     */
    public Result tick(long tick) {
        if (windowFlips > 0 && tick - windowStart >= WINDOW_TICKS) {
            Result result;
            Set<LightsCell> lamps = Set.copyOf(windowLamps);
            if (windowLevers.size() == 1 && (windowFlips & 1) == 1) {
                result = new Flip(windowLevers.iterator().next(), lamps);
            } else {
                result = new Ambiguous(Set.copyOf(windowLevers), windowFlips, lamps);
            }
            windowLevers.clear();
            windowLamps.clear();
            windowFlips = 0;
            return result;
        }
        if (windowFlips == 0 && !orphanLamps.isEmpty() && tick - orphanTick > ORPHAN_TICKS) {
            Result result = new Unattributed(Set.copyOf(orphanLamps));
            orphanLamps.clear();
            return result;
        }
        return null;
    }

    /** Drops everything in flight. */
    public void reset() {
        windowLevers.clear();
        windowLamps.clear();
        windowFlips = 0;
        orphanLamps.clear();
    }

    private static void toggle(Set<LightsCell> set, LightsCell cell) {
        if (!set.remove(cell)) {
            set.add(cell);
        }
    }
}
