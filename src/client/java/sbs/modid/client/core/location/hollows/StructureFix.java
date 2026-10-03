/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.location.hollows;

/**
 * Where a structure is, as measured by standing in it: a running centroid of every position sampled
 * while the zone line named it, plus the bounding box of those positions.
 *
 * <p>The centroid drifts from the doorway you came in by toward the middle of the place, which is
 * the point a marker should sit on. The box is kept for consumers that want an extent, Structure
 * Sharing among them.
 *
 * <p>Mutable and owned by {@link HollowsDetector}; everyone else gets a {@link #copy()}.
 */
public final class StructureFix {

    private final HollowsStructure structure;
    private final long firstSeenAt;
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

    public StructureFix(HollowsStructure structure, long firstSeenAt) {
        this.structure = structure;
        this.firstSeenAt = firstSeenAt;
    }

    /**
     * A fix rebuilt from stored values: the centroid weighted by its sample count, so further samples
     * move it exactly as much as they would have before the restart.
     */
    public static StructureFix restore(HollowsStructure structure, long firstSeenAt, int x, int y, int z,
                                       int samples, int minX, int minY, int minZ, int maxX, int maxY,
                                       int maxZ) {
        StructureFix fix = new StructureFix(structure, firstSeenAt);
        int n = Math.max(1, samples);
        fix.sumX = (long) x * n;
        fix.sumY = (long) y * n;
        fix.sumZ = (long) z * n;
        fix.samples = n;
        fix.minX = Math.min(minX, x);
        fix.minY = Math.min(minY, y);
        fix.minZ = Math.min(minZ, z);
        fix.maxX = Math.max(maxX, x);
        fix.maxY = Math.max(maxY, y);
        fix.maxZ = Math.max(maxZ, z);
        return fix;
    }

    public void sample(int x, int y, int z) {
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
    }

    public StructureFix copy() {
        StructureFix copy = new StructureFix(structure, firstSeenAt);
        copy.sumX = sumX;
        copy.sumY = sumY;
        copy.sumZ = sumZ;
        copy.samples = samples;
        copy.minX = minX;
        copy.minY = minY;
        copy.minZ = minZ;
        copy.maxX = maxX;
        copy.maxY = maxY;
        copy.maxZ = maxZ;
        return copy;
    }

    public HollowsStructure structure() {
        return structure;
    }

    public long firstSeenAt() {
        return firstSeenAt;
    }

    public int samples() {
        return samples;
    }

    public int x() {
        return samples == 0 ? 0 : (int) Math.floorDiv(sumX, samples);
    }

    public int y() {
        return samples == 0 ? 0 : (int) Math.floorDiv(sumY, samples);
    }

    public int z() {
        return samples == 0 ? 0 : (int) Math.floorDiv(sumZ, samples);
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }
}
