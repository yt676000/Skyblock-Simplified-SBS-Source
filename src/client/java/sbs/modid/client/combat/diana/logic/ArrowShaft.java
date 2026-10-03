/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import net.minecraft.world.phys.Vec3;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The arrow particle cloud, and the search for the straight run inside it.
 *
 * <h2>Why a spatial hash</h2>
 *
 * <p>The search only ever asks "which points are within {@link #STEP_TOLERANCE} of this one". The
 * first version answered that by scanning the whole cloud, inside a loop over the whole cloud, up to
 * twenty times per start point - and ran on every arrow particle, on the render thread. A cloud that
 * never fitted grew to four thousand points and froze the client after the first burrow of a chain
 * (tester report, 1.0.0-beta.10).
 *
 * <p>Here every point is bucketed into a cube one tolerance wide, so its possible neighbours are
 * exactly the 27 cubes around its own. That lookup is done <b>once, when the point arrives</b>, and
 * the answer kept on both points as an adjacency list. The search then walks those lists and never
 * touches the hash: a start point with nothing near it costs one empty-list check, which is what
 * nearly every point in a cloud of dust is.
 *
 * <h2>A set, not a list</h2>
 *
 * <p>A point that is already held is not added again. The server redraws the arrow while it is up,
 * and a redraw on the same coordinates adds nothing a fit can use - but it did inflate the neighbour
 * counts the head test reads, which demands exact numbers, and it filled the cloud with copies.
 *
 * <p>Pure: no game state, so the timing bound is a unit test rather than a hope.
 */
public final class ArrowShaft {

    /** How many collinear points a run needs before it is believed to be the arrow. */
    public static final int SHAFT_POINTS = 20;

    /** How far apart two consecutive arrow particles may be. Also the hash's cell size. */
    public static final double STEP_TOLERANCE = 0.12;

    /** Cross-product magnitude below which three points count as collinear. */
    static final double COLLINEAR_EPSILON = 1.0E-6;

    /** Bits per axis in a packed cell key: +-1M cells of 0.12 is far beyond any Hub coordinate. */
    private static final int KEY_BITS = 21;

    private static final long KEY_MASK = (1L << KEY_BITS) - 1;

    /** One held point and every other held point within the step of it. */
    private static final class Node {
        final Vec3 point;
        final List<Node> near = new ArrayList<>(2);
        /** The search pass that last put this node in a run - the visited set, as a mark. */
        int visitedIn;

        Node(Vec3 point) {
            this.point = point;
        }
    }

    /** Every point, in arrival order. The order the search walks, and what a snapshot shows. */
    private final List<Node> nodes = new ArrayList<>();

    /** The same points by value, for the duplicate check and for {@link #neighbours}. */
    private final Map<Vec3, Node> byPoint = new HashMap<>();

    /** The same points again, bucketed by cell. Only read when a point arrives. */
    private final Map<Long, List<Node>> cells = new HashMap<>();

    /** Increments per run extended, so {@link Node#visitedIn} never needs clearing. */
    private int pass;

    /** Adds a point. {@code false} when it was already held. */
    public boolean add(Vec3 point) {
        if (byPoint.containsKey(point)) {
            return false;
        }
        Node node = new Node(point);
        double limit = STEP_TOLERANCE * STEP_TOLERANCE;
        int cx = cell(point.x);
        int cy = cell(point.y);
        int cz = cell(point.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Node> bucket = cells.get(key(cx + dx, cy + dy, cz + dz));
                    if (bucket == null) {
                        continue;
                    }
                    for (Node other : bucket) {
                        if (other.point.distanceToSqr(point) <= limit) {
                            other.near.add(node);
                            node.near.add(other);
                        }
                    }
                }
            }
        }
        nodes.add(node);
        byPoint.put(point, node);
        cells.computeIfAbsent(key(cx, cy, cz), k -> new ArrayList<>(2)).add(node);
        return true;
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public void clear() {
        nodes.clear();
        byPoint.clear();
        cells.clear();
    }

    /** The held points in arrival order, read-only. */
    public List<Vec3> points() {
        return new AbstractList<>() {
            @Override
            public Vec3 get(int index) {
                return nodes.get(index).point;
            }

            @Override
            public int size() {
                return nodes.size();
            }
        };
    }

    // ------------------------------------------------------------------
    // The search
    // ------------------------------------------------------------------

    /**
     * The straight run of points inside the cloud, or an empty list.
     *
     * <p>Greedy: from each point in turn, keep taking the nearest unvisited point that is close
     * enough and collinear with the run's first two, until the run is long enough to be an arrow.
     * Every completed run is then scored by how far its points stray from the straight line through
     * its own ends, and the tightest wins.
     *
     * <p>The scoring is deliberately crude. It does not need to measure the arrow accurately - the
     * ray is built from the endpoints, not from the fit - it needs to reject a run that has wandered
     * into a second arrow.
     */
    public List<Vec3> findShaft() {
        if (nodes.size() < SHAFT_POINTS) {
            return List.of();
        }
        List<Vec3> best = List.of();
        double bestScore = Double.MAX_VALUE;
        for (Node start : nodes) {
            if (start.near.isEmpty()) {
                continue;
            }
            List<Vec3> run = extend(start);
            if (run.size() < SHAFT_POINTS) {
                continue;
            }
            double score = straightness(run);
            if (score < bestScore) {
                bestScore = score;
                best = run;
            }
        }
        return best;
    }

    private List<Vec3> extend(Node start) {
        int mark = ++pass;
        List<Vec3> run = new ArrayList<>(SHAFT_POINTS);
        run.add(start.point);
        start.visitedIn = mark;
        Node tail = start;
        while (run.size() < SHAFT_POINTS) {
            Vec3 first = run.get(0);
            Vec3 second = run.size() > 1 ? run.get(1) : first;
            Node next = null;
            double nearest = Double.MAX_VALUE;
            for (Node candidate : tail.near) {
                if (candidate.visitedIn == mark) {
                    continue;
                }
                double distance = tail.point.distanceTo(candidate.point);
                if (distance >= nearest) {
                    continue;
                }
                if (run.size() > 1 && !collinear(first, second, candidate.point)) {
                    continue;
                }
                nearest = distance;
                next = candidate;
            }
            if (next == null) {
                return run;
            }
            run.add(next.point);
            next.visitedIn = mark;
            tail = next;
        }
        return run;
    }

    /** How many held points lie within {@link #STEP_TOLERANCE} of {@code point}, itself excluded. */
    public int neighbours(Vec3 point) {
        Node node = byPoint.get(point);
        return node == null ? 0 : node.near.size();
    }

    /** Total distance of the run's points from the straight line through its two ends. */
    private static double straightness(List<Vec3> run) {
        Vec3 origin = run.get(0);
        Vec3 direction = run.get(run.size() - 1).subtract(origin);
        if (direction.lengthSqr() < COLLINEAR_EPSILON) {
            return Double.MAX_VALUE;
        }
        Vec3 unit = direction.normalize();
        double total = 0.0;
        for (Vec3 point : run) {
            Vec3 offset = point.subtract(origin);
            double along = unit.dot(offset);
            total += offset.subtract(unit.scale(along)).length();
        }
        return total;
    }

    private static boolean collinear(Vec3 a, Vec3 b, Vec3 c) {
        return b.subtract(a).cross(c.subtract(a)).lengthSqr() < COLLINEAR_EPSILON;
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate / STEP_TOLERANCE);
    }

    private static long key(int x, int y, int z) {
        return ((x & KEY_MASK) << (2 * KEY_BITS)) | ((y & KEY_MASK) << KEY_BITS) | (z & KEY_MASK);
    }
}
