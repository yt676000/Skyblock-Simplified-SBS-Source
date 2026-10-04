/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.precision.logic;

import sbs.modid.client.skills.mining.precision.model.PrecisionSignals;

import java.util.HashMap;
import java.util.Map;

/**
 * The Precision Mining target on the block being mined: particle events, the mined block and the
 * time in; the current target point, or {@code null}, out.
 *
 * <p><b>The target belongs to one block.</b> A particle only counts when it sits on a face of the
 * block the player is mining right now; a particle on any other block - another player's target one
 * block over included - is ignored. The target is dropped when the mined block changes, when that
 * block's state changes (it broke, or Hypixel replaced it), and {@link PrecisionSignals#TARGET_STALE_MS}
 * after the last matching particle.
 *
 * <p><b>The matcher</b> is the brief's guess, kept in {@link PrecisionSignals} until a probe
 * confirms it: a packet of at most {@link PrecisionSignals#MAX_COUNT} particle(s), within
 * {@link PrecisionSignals#FACE_TOLERANCE} of a face, of a type seen at least
 * {@link PrecisionSignals#REQUIRED_SIGHTINGS} times on this block. Once one type qualifies, the
 * target follows only that type until the block changes.
 *
 * <p>Pure: no world, no clock of its own, so every rule above is tested with synthetic events.
 * Nothing here aims, turns the camera or sends input - it only says where the particle is.
 */
public final class PrecisionTarget {

    /** A face of a block, with its outward normal. */
    public enum Face {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

        public final int nx;
        public final int ny;
        public final int nz;

        Face(int nx, int ny, int nz) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }

    /** The block being mined: its position and its block-state id, so a replacement reads as a change. */
    public record MinedBlock(int x, int y, int z, int stateId) {
    }

    /** A target: where the particle sits, which face it is on, and its type. */
    public record Point(int bx, int by, int bz, double x, double y, double z, Face face, String type) {
    }

    private MinedBlock block;
    private Point target;
    private long seenAt;
    private String lockedType;
    private boolean everSeen;
    private final Map<String, Integer> sightings = new HashMap<>();

    /**
     * The block the player is mining now. A different position, or the same position with a
     * different state, drops the target; {@code null} (not mining this tick) keeps it, so letting go
     * of the mouse for a moment does not lose a target that is still on the block.
     *
     * @return whether the tracked block changed
     */
    public boolean onMinedBlock(MinedBlock next) {
        if (next == null || next.equals(block)) {
            return false;
        }
        block = next;
        resetTarget();
        return true;
    }

    /** Stop tracking any block: world change, feature off, or the block broke. */
    public void clear() {
        block = null;
        resetTarget();
    }

    private void resetTarget() {
        target = null;
        lockedType = null;
        everSeen = false;
        sightings.clear();
    }

    /**
     * One particle packet. Returns the target it set, or {@code null} when the packet is not this
     * block's target (wrong block, off the face, a burst, a type not yet resent, or another type
     * than the one already locked).
     */
    public Point onParticle(String type, int count, double x, double y, double z, long now) {
        if (block == null || count > PrecisionSignals.MAX_COUNT) {
            return null;
        }
        String wanted = PrecisionSignals.PARTICLE_TYPE;
        if (!wanted.isEmpty() && !wanted.equals(type)) {
            return null;
        }
        Point point = locate(block, x, y, z, type);
        if (point == null) {
            return null;
        }
        if (lockedType != null && !lockedType.equals(type)) {
            return null;
        }
        int seen = sightings.merge(type, 1, Integer::sum);
        if (seen < PrecisionSignals.REQUIRED_SIGHTINGS) {
            return null;
        }
        lockedType = type;
        target = point;
        seenAt = now;
        everSeen = true;
        return point;
    }

    /** The target to draw now, or {@code null} when there is none or it has not been resent lately. */
    public Point current(long now) {
        if (target == null) {
            return null;
        }
        if (now - seenAt > PrecisionSignals.TARGET_STALE_MS) {
            target = null;
            return null;
        }
        return target;
    }

    /** The block being tracked, or {@code null}. */
    public MinedBlock block() {
        return block;
    }

    /** Whether a target was ever set on the tracked block - the probe's "target seen" answer. */
    public boolean everSeen() {
        return everSeen;
    }

    /** The locked particle type on this block, or {@code null}. */
    public String lockedType() {
        return lockedType;
    }

    // ------------------------------------------------------------------ geometry

    /**
     * The point a particle at {@code (x, y, z)} marks on {@code block}, or {@code null} when it is
     * not within {@link PrecisionSignals#FACE_TOLERANCE} of one of its faces. Inside the block's
     * volume but away from every face does not count either.
     */
    public static Point locate(MinedBlock block, double x, double y, double z, String type) {
        double tol = PrecisionSignals.FACE_TOLERANCE;
        int bx = block.x();
        int by = block.y();
        int bz = block.z();
        if (x < bx - tol || x > bx + 1 + tol
                || y < by - tol || y > by + 1 + tol
                || z < bz - tol || z > bz + 1 + tol) {
            return null;
        }
        Face face = nearestFace(bx, by, bz, x, y, z);
        if (faceDistance(bx, by, bz, x, y, z, face) > tol) {
            return null;
        }
        return new Point(bx, by, bz, x, y, z, face, type);
    }

    /** The face whose plane is closest to the point. */
    public static Face nearestFace(int bx, int by, int bz, double x, double y, double z) {
        Face best = Face.DOWN;
        double bestDistance = Double.MAX_VALUE;
        for (Face face : Face.values()) {
            double distance = faceDistance(bx, by, bz, x, y, z, face);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = face;
            }
        }
        return best;
    }

    private static double faceDistance(int bx, int by, int bz, double x, double y, double z, Face face) {
        return switch (face) {
            case DOWN -> Math.abs(y - by);
            case UP -> Math.abs(y - (by + 1));
            case NORTH -> Math.abs(z - bz);
            case SOUTH -> Math.abs(z - (bz + 1));
            case WEST -> Math.abs(x - bx);
            case EAST -> Math.abs(x - (bx + 1));
        };
    }

    /**
     * Whether the crosshair is on the target: the point where the player's view ray meets the
     * target's block lies within {@code radius} blocks of the particle. Measured in 3D, so a hit on
     * the neighbouring face right at the edge counts the same as one on the target's own face at
     * that distance.
     */
    public static boolean onTarget(Point target, int hitBx, int hitBy, int hitBz,
                                   double hx, double hy, double hz, double radius) {
        if (target == null || hitBx != target.bx() || hitBy != target.by() || hitBz != target.bz()) {
            return false;
        }
        double dx = hx - target.x();
        double dy = hy - target.y();
        double dz = hz - target.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
