/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

/**
 * A fixed server-tick cycle: given its period and one tick at which it fired (the anchor), how many
 * ticks until it fires next. Pure arithmetic on server ticks, so it is correct at any tick rate - the
 * caller converts ticks to seconds for display only.
 *
 * <p>An observed firing re-anchors the cycle ({@link #resync}); the returned drift says how far the
 * prediction was off, which is the number that decides whether a period is right.
 */
public final class TickTimer {

    private int period;
    private double anchor;
    private boolean anchored;

    /** @param periodTicks ticks between firings; {@code <= 0} means unknown, and nothing is predicted */
    public TickTimer(int periodTicks) {
        this.period = periodTicks;
    }

    public int period() {
        return period;
    }

    /** Changes the period, keeping the anchor. */
    public void setPeriod(int periodTicks) {
        this.period = periodTicks;
    }

    /** The cycle fired at {@code tick} (or is assumed to have). */
    public void anchor(double tick) {
        anchor = tick;
        anchored = true;
    }

    /**
     * An observed firing at {@code tick}: re-anchors and returns the drift - observed minus the
     * nearest predicted firing, in ticks (positive = it came late). {@code NaN} when nothing was
     * predicted before, i.e. this is the first anchor or the period is unknown.
     */
    public double resync(double tick) {
        double drift = Double.NaN;
        if (anchored && period > 0) {
            double since = tick - anchor;
            drift = since - Math.rint(since / period) * period;
        }
        anchor(tick);
        return drift;
    }

    public boolean predicting() {
        return anchored && period > 0;
    }

    /**
     * Ticks until the next firing after {@code nowTick}, in {@code (0, period]}; {@code -1} while not
     * anchored, with an unknown period, or with an unknown clock ({@code nowTick < 0}).
     */
    public double ticksUntilNext(double nowTick) {
        if (!predicting() || nowTick < 0) {
            return -1.0;
        }
        double into = (nowTick - anchor) % period;
        if (into < 0) {
            into += period;
        }
        return period - into;
    }

    public void clear() {
        anchored = false;
        anchor = 0.0;
    }

    /**
     * Ticks as real seconds at the measured rate. Rates outside {@code [5, 20]} are a freeze, a world
     * change or a measuring artefact, and count as 20.
     */
    public static double seconds(double ticks, double tps) {
        double rate = tps < 5.0 || tps > 20.0 ? 20.0 : tps;
        return ticks / rate;
    }
}
