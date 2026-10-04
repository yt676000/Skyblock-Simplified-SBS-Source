/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two-tier planner for long routes: straight {@link OpenCorridor} pieces across open terrain,
 * plain {@link PathfinderTask} A* only for the stretches that are not open (covered, cave, house,
 * steep, liquid). See {@code docs/features/hybrid-pathfinding.md}.
 *
 * <p><b>Walk order.</b> From the current point {@code cur} it aims one {@link #SEGMENT} along the
 * line to the goal. Open → the corridor is the piece, {@code cur} moves to its end. Not open → a few
 * sideways detours through a midpoint are tried (bending round a large obstacle). Still not open → an
 * A* piece runs from {@code cur} to the next open-ground anchor further along the line, and the
 * corridor resumes <i>from where that search actually ended</i>. Every piece starts exactly where the
 * last one stopped, and every step in every piece is a move A* itself would make, so a join can never
 * cut through a block. A final pass removes any loop a join created.
 *
 * <p><b>Incremental,</b> like {@link PathfinderTask}: {@link #step} does a budget's worth of work per
 * tick on the client thread, where the terrain reads must happen. Corridor work is charged at
 * {@link #CORRIDOR_COST} nodes per segment; A* pieces spend nodes as A* does. Jump pads and the
 * Transmission hops are A* edges and stay available in every A* piece.
 */
final class HybridPlanner {

    /** Corridor segment length in blocks. */
    static final int SEGMENT = 24;

    /** Sideways offsets tried for a detour midpoint, in blocks off the straight line. */
    private static final int[] DETOURS = {6, -6, 12, -12};

    /** Vertical band an anchor's ground is searched in, around the previous point. */
    private static final int ANCHOR_UP = 24;
    private static final int ANCHOR_DOWN = 32;

    /** How many segments ahead the A* piece may aim for the next open-ground anchor. */
    private static final int MAX_ANCHOR_LOOKAHEAD = 6;

    /** Node-budget charge for classifying one segment (a few hundred block reads). */
    private static final int CORRIDOR_COST = 150;

    private final Terrain terrain;
    private final BlockPos goal;
    private final int maxNodes;
    private final PlayerMobility.Mobility mobility;
    private final int maxStepUp;
    private final int maxFall;
    private final List<JumpPad> pads;

    private BlockPos cur;
    private final List<BlockPos> path = new ArrayList<>();
    private final Set<BlockPos> teleports = new HashSet<>();
    private final Set<BlockPos> padLandings = new HashSet<>();
    private final Set<BlockPos> sneaks = new HashSet<>();

    /** The running A* piece, or {@code null} while walking corridors. */
    private PathfinderTask fine;

    private boolean finished;
    private boolean reachedGoal;
    private int openSegments;
    private int fineSegments;
    private int nodes;

    HybridPlanner(Terrain terrain, BlockPos start, BlockPos goal, int maxNodes,
                  PlayerMobility.Mobility mobility, int maxFall, List<JumpPad> pads) {
        this.terrain = terrain;
        this.goal = goal;
        this.maxNodes = maxNodes;
        this.mobility = mobility;
        this.maxStepUp = Math.max(1, mobility.maxStepUp());
        this.maxFall = Math.max(1, maxFall);
        this.pads = pads == null ? List.of() : pads;
        this.cur = start;
        path.add(start);
    }

    boolean finished() {
        return finished;
    }

    boolean reachedGoal() {
        return reachedGoal;
    }

    List<BlockPos> path() {
        return path;
    }

    Set<BlockPos> teleportNodes() {
        return teleports;
    }

    Set<BlockPos> padNodes() {
        return padLandings;
    }

    Set<BlockPos> sneakNodes() {
        return sneaks;
    }

    int openSegments() {
        return openSegments;
    }

    int fineSegments() {
        return fineSegments;
    }

    int nodes() {
        return nodes;
    }

    /** Horizontal distance from where the plan has got to, to the goal. */
    double remaining() {
        return horizontal(cur, goal);
    }

    /**
     * Does up to {@code budget} nodes' worth of work.
     *
     * @return {@code true} once done - goal reached, or an A* piece could not get through
     */
    boolean step(int budget) {
        int left = budget;
        while (!finished && left > 0) {
            if (fine != null) {
                int before = fine.expanded();
                boolean done = fine.step(left);
                int spent = Math.max(1, fine.expanded() - before);
                nodes += spent;
                left -= spent;
                if (done) {
                    finishFine();
                }
                continue;
            }
            left -= CORRIDOR_COST;
            nodes += CORRIDOR_COST;
            advanceCorridor();
        }
        return finished;
    }

    /** One segment of corridor work from {@link #cur}, or the start of an A* piece. */
    private void advanceCorridor() {
        double distance = horizontal(cur, goal);
        if (distance <= 1.5 && Math.abs(cur.getY() - goal.getY()) <= 2) {
            finish(true);
            return;
        }
        if (distance <= SEGMENT) {
            // Last stretch: straight onto the goal when that is open, else A* the rest.
            BlockPos end = OpenCorridor.ground(terrain, goal.getX(), goal.getZ(), goal.getY(), 2, 2);
            List<BlockPos> walk = end == null ? null
                    : OpenCorridor.walk(terrain, cur, end, maxStepUp, maxFall);
            if (walk != null) {
                append(walk);
                openSegments++;
                finish(true);
            } else {
                startFine(goal);
            }
            return;
        }
        BlockPos aim = along(cur, goal, SEGMENT, 0);
        BlockPos anchor = OpenCorridor.ground(terrain, aim.getX(), aim.getZ(), cur.getY(), ANCHOR_UP, ANCHOR_DOWN);
        if (anchor != null) {
            List<BlockPos> walk = OpenCorridor.walk(terrain, cur, anchor, maxStepUp, maxFall);
            if (walk == null) {
                walk = detour(anchor);
            }
            if (walk != null) {
                append(walk);
                openSegments++;
                return;
            }
        }
        startFine(nextAnchor());
    }

    /** A bend round an obstacle: cur → a sideways midpoint → anchor, both legs open. */
    private List<BlockPos> detour(BlockPos anchor) {
        for (int offset : DETOURS) {
            BlockPos mid = along(cur, anchor, SEGMENT / 2, offset);
            BlockPos midGround = OpenCorridor.ground(terrain, mid.getX(), mid.getZ(), cur.getY(), ANCHOR_UP, ANCHOR_DOWN);
            if (midGround == null) {
                continue;
            }
            List<BlockPos> first = OpenCorridor.walk(terrain, cur, midGround, maxStepUp, maxFall);
            if (first == null) {
                continue;
            }
            List<BlockPos> second = OpenCorridor.walk(terrain, midGround, anchor, maxStepUp, maxFall);
            if (second == null) {
                continue;
            }
            List<BlockPos> both = new ArrayList<>(first);
            both.addAll(second.subList(1, second.size()));
            return both;
        }
        return null;
    }

    /**
     * Where an A* piece should aim: the first open-ground anchor further along the line (skipping the
     * blocked stretch), or the goal itself when none is found within the lookahead.
     */
    private BlockPos nextAnchor() {
        for (int k = 2; k <= MAX_ANCHOR_LOOKAHEAD; k++) {
            if (horizontal(cur, goal) <= (double) SEGMENT * k) {
                break;
            }
            BlockPos aim = along(cur, goal, SEGMENT * k, 0);
            BlockPos anchor = OpenCorridor.ground(terrain, aim.getX(), aim.getZ(), cur.getY(), ANCHOR_UP, ANCHOR_DOWN);
            if (anchor != null) {
                return anchor;
            }
        }
        return goal;
    }

    private void startFine(BlockPos target) {
        fineSegments++;
        fine = new PathfinderTask(terrain, cur, List.of(target), maxNodes, mobility, maxFall, pads);
    }

    private void finishFine() {
        PathfinderTask task = fine;
        fine = null;
        List<BlockPos> piece = task.path();
        teleports.addAll(task.teleportNodes());
        padLandings.addAll(task.padNodes());
        sneaks.addAll(task.sneakNodes());
        if (!piece.isEmpty()) {
            append(piece);
        }
        if (!task.reachedGoal()) {
            finish(false);   // this stretch has no known way through; the caller decides what next
        }
    }

    /** Appends a piece that starts at {@link #cur}, and moves {@link #cur} to its end. */
    private void append(List<BlockPos> piece) {
        int from = !piece.isEmpty() && piece.getFirst().equals(cur) ? 1 : 0;
        for (int i = from; i < piece.size(); i++) {
            path.add(piece.get(i));
        }
        if (!piece.isEmpty()) {
            cur = piece.getLast();
        }
    }

    private void finish(boolean reached) {
        finished = true;
        reachedGoal = reached;
        List<BlockPos> clean = withoutLoops(path);
        path.clear();
        path.addAll(clean);
    }

    /**
     * Cuts every loop out of a path: when a block comes up a second time, everything between the two
     * visits goes. Joins between pieces are where this happens - an A* piece that starts by stepping
     * back over the corridor it came from. Package-private for the tests.
     */
    static List<BlockPos> withoutLoops(List<BlockPos> path) {
        List<BlockPos> out = new ArrayList<>(path.size());
        Map<BlockPos, Integer> index = new HashMap<>();
        for (BlockPos pos : path) {
            Integer seen = index.get(pos);
            if (seen != null) {
                for (int i = out.size() - 1; i > seen; i--) {
                    index.remove(out.remove(i));
                }
                continue;
            }
            index.put(pos, out.size());
            out.add(pos);
        }
        return out;
    }

    /**
     * The point {@code distance} blocks from {@code from} towards {@code to} (horizontally), shifted
     * {@code sideways} blocks to the right of that line.
     */
    static BlockPos along(BlockPos from, BlockPos to, double distance, double sideways) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-6) {
            return from;
        }
        double ux = dx / length;
        double uz = dz / length;
        double x = from.getX() + ux * distance - uz * sideways;
        double z = from.getZ() + uz * distance + ux * sideways;
        return new BlockPos((int) Math.round(x), from.getY(), (int) Math.round(z));
    }

    static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
