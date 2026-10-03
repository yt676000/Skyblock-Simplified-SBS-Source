/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

/**
 * The block a ray from the camera points at - what the stick and {@code //pos1}/{@code //pos2} use
 * while freecam is on, instead of the player's own crosshair.
 *
 * <p>A plain voxel walk (each block boundary the ray crosses, in order) over a "does this block
 * count" test, so it works with any origin, including one inside terrain, and is tested without a
 * world. <b>Step deeper</b>: after the first block that counts, the walk goes {@code depth} more
 * blocks along the same line - so the block behind a wall's face can be picked without flying there.
 */
public final class CameraRay {

    private CameraRay() {
    }

    /** Whether a block stops the ray. */
    public interface Solid {
        boolean test(int x, int y, int z);
    }

    /** A hit: the block, and the face side it was entered from (the step that led in, negated). */
    public record Hit(int x, int y, int z, int faceX, int faceY, int faceZ) {
    }

    /**
     * Walks from {@code origin} along {@code direction} for at most {@code maxDistance} blocks and
     * returns the first solid block, then {@code depth} blocks further along the ray; {@code null}
     * when nothing solid is in range. A block the origin itself is inside does not count, so a camera
     * sitting in stone picks what it looks at rather than the stone around it.
     */
    public static Hit cast(double ox, double oy, double oz, double dx, double dy, double dz,
                           double maxDistance, int depth, Solid solid) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) {
            return null;
        }
        dx /= length;
        dy /= length;
        dz /= length;
        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? (x + 1 - ox) : (ox - x)) * tDeltaX;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? (y + 1 - oy) : (oy - y)) * tDeltaY;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? (z + 1 - oz) : (oz - z)) * tDeltaZ;
        int faceX = 0;
        int faceY = 0;
        int faceZ = 0;
        boolean found = false;
        int remaining = Math.max(0, depth);
        double t = 0;
        // Past the first hit the walk may run `depth` blocks beyond the range, never unbounded.
        int guard = (int) Math.ceil(maxDistance * 3) + remaining * 3 + 3;
        while (guard-- > 0) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                t = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
                faceX = -stepX;
                faceY = 0;
                faceZ = 0;
            } else if (tMaxY < tMaxZ) {
                t = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
                faceX = 0;
                faceY = -stepY;
                faceZ = 0;
            } else {
                t = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
                faceX = 0;
                faceY = 0;
                faceZ = -stepZ;
            }
            if (!found) {
                if (t > maxDistance) {
                    return null;
                }
                if (solid.test(x, y, z)) {
                    found = true;
                    if (remaining == 0) {
                        return new Hit(x, y, z, faceX, faceY, faceZ);
                    }
                }
            } else if (--remaining == 0) {
                return new Hit(x, y, z, faceX, faceY, faceZ);
            }
        }
        return null;
    }
}
