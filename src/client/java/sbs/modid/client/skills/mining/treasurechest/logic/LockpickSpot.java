/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.treasurechest.logic;

import sbs.modid.client.skills.mining.treasurechest.model.TreasureChestSignals;

/**
 * Where on a chest the current lockpick burst is, and when to stop showing it.
 *
 * <p>{@link #locate} is the gate: a particle packet belongs to a chest only when its position falls
 * inside that chest's block grown by {@link TreasureChestSignals#PARTICLE_GROWTH}. That is what keeps
 * another player's chest one block over from moving your marker - the growth is smaller than the
 * gap to the next block's centre.
 *
 * <p>{@link Marker} is the display state: the last spot, hidden once the burst stops, cleared by the
 * player's own click. Only drawing ever comes out of this; nothing here clicks or aims.
 *
 * <p>Pure, so both can be tested without a world.
 */
public final class LockpickSpot {

    /** Which side of the block a spot is on - for the log, so a probe says where the burst sits. */
    public enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }

    /** A burst position on a chest. */
    public record Spot(double x, double y, double z, Face face) {
        boolean near(Spot other, double within) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz <= within * within;
        }
    }

    private LockpickSpot() {
    }

    /**
     * The spot a particle at {@code (px, py, pz)} marks on the chest block at {@code (bx, by, bz)},
     * or {@code null} when the particle is not this chest's.
     */
    public static Spot locate(int bx, int by, int bz, double px, double py, double pz) {
        double grow = TreasureChestSignals.PARTICLE_GROWTH;
        if (px < bx - grow || px > bx + 1 + grow
                || py < by - grow || py > by + 1 + grow
                || pz < bz - grow || pz > bz + 1 + grow) {
            return null;
        }
        return new Spot(px, py, pz, nearestFace(bx, by, bz, px, py, pz));
    }

    private static Face nearestFace(int bx, int by, int bz, double px, double py, double pz) {
        double[] distances = {
                Math.abs(py - by), Math.abs(py - (by + 1)),
                Math.abs(pz - bz), Math.abs(pz - (bz + 1)),
                Math.abs(px - bx), Math.abs(px - (bx + 1)),
        };
        Face[] faces = {Face.DOWN, Face.UP, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST};
        int best = 0;
        for (int i = 1; i < distances.length; i++) {
            if (distances[i] < distances[best]) {
                best = i;
            }
        }
        return faces[best];
    }

    /** The marker for one chest: which spot to draw, if any. */
    public static final class Marker {

        private Spot spot;
        private long seenAt;
        /** The spot the player last clicked on, ignored for a short grace. */
        private Spot clicked;
        private long clickedAt;

        /** A burst landed on this chest at {@code spot}. */
        public void onBurst(Spot burst, long now) {
            if (clicked != null && now - clickedAt < TreasureChestSignals.CLICK_GRACE_MS
                    && burst.near(clicked, TreasureChestSignals.SAME_SPOT)) {
                return;   // the tail of the burst just clicked; a correct click leaves it cleared
            }
            spot = burst;
            seenAt = now;
        }

        /**
         * The player right-clicked this chest. The marker clears at once; if the click was wrong the
         * burst stays where it was and brings the marker back after the grace.
         */
        public void onClick(long now) {
            if (spot != null) {
                clicked = spot;
                clickedAt = now;
            }
            spot = null;
        }

        /** The spot to draw now, or {@code null} when there is none or the burst has stopped. */
        public Spot current(long now) {
            if (spot == null || now - seenAt > TreasureChestSignals.MARKER_STALE_MS) {
                return null;
            }
            return spot;
        }
    }
}
