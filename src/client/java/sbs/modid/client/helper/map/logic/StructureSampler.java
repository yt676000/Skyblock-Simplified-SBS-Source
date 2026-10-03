/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import sbs.modid.client.core.location.hollows.HollowsStructure;

/**
 * What the player has seen of one structure in one lobby: the positions they stood at while the
 * sidebar named it, reduced to a centroid and a bounding box, and when that is worth reporting.
 *
 * <p>Fed one position per poll (every 10 ticks) while the zone is the structure. It answers with a
 * {@link Report} when one is due:
 * <ul>
 *   <li><b>the first</b> once the player has been inside for {@link #MIN_DWELL_MS} in total, so
 *       walking past a doorway reports nothing;</li>
 *   <li><b>another</b> only when the box has grown by more than {@link #REGROW_BLOCKS} on some side
 *       since the last report, and at most once per {@link #MIN_REPORT_GAP_MS}. Standing still
 *       inside a structure sends nothing more.</li>
 * </ul>
 *
 * <p>Pure: no game state, the time is passed in. A new lobby gets new samplers.
 */
public final class StructureSampler {

    /** Time inside before the first report. */
    public static final long MIN_DWELL_MS = 3_000L;

    /** How much the box must grow on one side before it is reported again. */
    public static final int REGROW_BLOCKS = 8;

    /** The shortest time between two reports of one structure. */
    public static final long MIN_REPORT_GAP_MS = 30_000L;

    /**
     * A gap between samples longer than this means the player left and came back. The time away
     * does not count as time inside.
     */
    static final long CONTINUOUS_GAP_MS = 2_000L;

    /** An axis-aligned box in block coordinates, both corners inclusive. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        /** The largest distance any side of this box has moved outward from {@code older}. */
        public int growthOver(Box older) {
            int growth = 0;
            growth = Math.max(growth, older.minX - minX);
            growth = Math.max(growth, older.minY - minY);
            growth = Math.max(growth, older.minZ - minZ);
            growth = Math.max(growth, maxX - older.maxX);
            growth = Math.max(growth, maxY - older.maxY);
            growth = Math.max(growth, maxZ - older.maxZ);
            return growth;
        }

        /** The longest edge. */
        public int maxExtent() {
            return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        }

        /** Whether a point lies inside, corners included. */
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** One report: the centroid, the box and how many positions they rest on. */
    public record Report(HollowsStructure structure, int x, int y, int z, Box box, int samples) {
    }

    private final HollowsStructure structure;

    private long sumX;
    private long sumY;
    private long sumZ;
    private int samples;
    private int minX = Integer.MAX_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int maxY = Integer.MIN_VALUE;
    private int maxZ = Integer.MIN_VALUE;

    private long dwellMs;
    private long lastSampleAt = -1L;

    private Box reportedBox;
    private long reportedAt = -1L;

    public StructureSampler(HollowsStructure structure) {
        this.structure = structure;
    }

    /**
     * Adds one position seen inside the structure.
     *
     * @return the report to send now, or {@code null} when none is due
     */
    public Report sample(int x, int y, int z, long nowMs) {
        if (lastSampleAt >= 0L) {
            long gap = nowMs - lastSampleAt;
            if (gap > 0L && gap <= CONTINUOUS_GAP_MS) {
                dwellMs += gap;
            }
        }
        lastSampleAt = nowMs;

        sumX += x;
        sumY += y;
        sumZ += z;
        samples++;
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);

        if (dwellMs < MIN_DWELL_MS) {
            return null;
        }
        Box box = box();
        if (reportedBox != null) {
            boolean grew = box.growthOver(reportedBox) > REGROW_BLOCKS;
            boolean rested = nowMs - reportedAt >= MIN_REPORT_GAP_MS;
            if (!grew || !rested) {
                return null;
            }
        }
        reportedBox = box;
        reportedAt = nowMs;
        return current();
    }

    /** What has been seen so far, as a report-shaped value, or {@code null} before any sample. */
    public Report current() {
        if (samples == 0) {
            return null;
        }
        return new Report(structure, centroid(sumX), centroid(sumY), centroid(sumZ), box(), samples);
    }

    /**
     * What to send again after a reconnect: the latest state, once a report has gone out, or
     * {@code null} while none has. The latest rather than the last sent, because the server only
     * keeps the newest report per reporter anyway.
     */
    public Report resendable() {
        if (reportedBox == null) {
            return null;
        }
        return current();
    }

    /** The total time spent inside so far. */
    public long dwellMs() {
        return dwellMs;
    }

    private Box box() {
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private int centroid(long sum) {
        return (int) Math.round((double) sum / samples);
    }
}
