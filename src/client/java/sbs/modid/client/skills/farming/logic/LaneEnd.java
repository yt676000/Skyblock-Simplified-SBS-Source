/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import sbs.modid.client.skills.farming.model.Lane;

/**
 * The game-free half of the Lane End Warning. <b>Manual only</b>: the lanes and their ends are the
 * ones the player marked ({@link Lane}, grouped into farms - see {@link LaneFarms}). Nothing here
 * reads blocks or works out a lane from how the player walks - the maintainer ruled out any automatic lane analysis so nothing about it can look
 * like macro assistance. All this does is compare the player's position with edges the player drew.
 *
 * <ul>
 *   <li><b>Edge ahead</b>: which of the lane's two ends lies in the direction of the player's last
 *       position change along its axis ({@link Heading}).</li>
 *   <li><b>Warning</b>: once per approach, when the distance to that edge is at most N blocks or at
 *       most T seconds at the current speed, and only while crops are being broken ({@link Warner}).
 *       Re-armed by turning round, entering another lane, or moving back out of range.</li>
 * </ul>
 */
public final class LaneEnd {

    /** Broken a crop this recently = actively farming. */
    public static final long ACTIVE_MS = 1_000L;
    /** A position change along the axis below this, between two samples, keeps the last heading. */
    public static final double MIN_STEP = 0.05;
    /** The speed estimate spans about this long. */
    public static final long SPEED_WINDOW_MS = 1_000L;
    /** Blocks past the warning distance that re-arm the warning. */
    public static final double REARM_MARGIN = 2.0;

    private LaneEnd() {
    }

    /** Distance from {@code along} to the end ahead in direction {@code sign} (+1 or -1). */
    public static double remaining(Lane lane, double along, int sign) {
        return sign > 0 ? lane.high() - along : along - lane.low();
    }

    /** Whether N blocks or T seconds says "now". Speed {@code <= 0} leaves only the block rule. */
    public static boolean due(double remaining, double speed, int blocks, double seconds) {
        if (remaining <= blocks) {
            return true;
        }
        return speed > 0 && seconds > 0 && remaining / speed <= seconds;
    }

    /**
     * Which way the player last moved along one axis, and how fast. Fed a position each tick; a step
     * too small to mean anything leaves the heading as it was, so standing still between crops does
     * not forget which edge you were heading to.
     */
    public static final class Heading {
        private double lastAlong = Double.NaN;
        private int sign;
        private long windowStart = -1;
        private double windowAlong;
        private double speed;

        public void reset() {
            lastAlong = Double.NaN;
            sign = 0;
            windowStart = -1;
            speed = 0;
        }

        public void record(long now, double along) {
            if (Double.isNaN(lastAlong)) {
                lastAlong = along;
                windowStart = now;
                windowAlong = along;
                return;
            }
            double step = along - lastAlong;
            if (Math.abs(step) >= MIN_STEP) {
                int newSign = step > 0 ? 1 : -1;
                if (sign != 0 && newSign != sign) {
                    windowStart = now;   // turned round (not the first move): speed restarts here
                    windowAlong = lastAlong;
                }
                sign = newSign;
                lastAlong = along;
            }
            long span = now - windowStart;
            if (span >= SPEED_WINDOW_MS) {
                speed = Math.abs(along - windowAlong) * 1000.0 / span;
                windowStart = now;
                windowAlong = along;
            }
        }

        /** +1 / -1 along the axis, or 0 before the position has changed. */
        public int sign() {
            return sign;
        }

        /** Blocks per second along the axis, over the last full window. */
        public double speed() {
            return speed;
        }
    }

    /** Fires once per approach to an edge. */
    public static final class Warner {
        private long lastCrop = Long.MIN_VALUE / 2;
        private String armedFor;
        private boolean fired;

        public void onCropBroken(long now) {
            lastCrop = now;
        }

        /**
         * @param edgeKey   which end of which lane is ahead ({@code null} outside every lane or
         *                  before any heading); a different key is a new approach
         * @return whether to warn now
         */
        public boolean tick(long now, String edgeKey, double remaining, double speed, int blocks,
                            double seconds) {
            if (edgeKey == null) {
                armedFor = null;
                fired = false;
                return false;
            }
            if (!edgeKey.equals(armedFor)) {
                armedFor = edgeKey;
                fired = false;
            }
            if (fired && remaining > blocks + REARM_MARGIN && !due(remaining, speed, blocks, seconds)) {
                fired = false;   // walked back out of range along the same heading
            }
            if (fired || now - lastCrop > ACTIVE_MS) {
                return false;
            }
            if (due(remaining, speed, blocks, seconds)) {
                fired = true;
                return true;
            }
            return false;
        }
    }
}
