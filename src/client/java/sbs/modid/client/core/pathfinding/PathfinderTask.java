/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * An A* search over the world, run <b>incrementally</b>, in either walking or flying mode.
 *
 * <p><b>Why incremental.</b> A long path can take tens of thousands of node expansions, far too much
 * for one frame, and running it on a worker thread would mean reading {@code ClientLevel} chunks off
 * the main thread – where they can be unloaded mid-read. So {@link #step} expands a budget of nodes
 * per client tick and remembers where it was: block reads stay on the thread that owns them, and no
 * tick ever stalls. A search simply finishes a few ticks later.
 *
 * <p><b>Walking.</b> A node is a spot the player could stand: solid floor, two blocks of body space.
 * Moves are the eight horizontal directions, each able to climb up to {@code maxStepUp} (which comes
 * from the player's real jump height – see {@link PlayerMobility} – so a jump-boost potion actually
 * opens up the routes it should) or drop up to {@code maxFall}. Diagonals require both adjacent
 * cardinals to be open so the path never cuts a wall corner; a climb requires the column above the
 * player to be clear all the way to the landing; a drop requires the whole column to be clear so it
 * never falls through a ceiling.
 *
 * <p><b>Flying.</b> Gravity stops mattering: any position with body space is a node and all 26
 * directions are available, cost being the true 3D distance. Diagonals still require their
 * axis-aligned neighbours to be open, so a route never squeezes through a sealed diagonal seam.
 *
 * <p><b>Teleporting.</b> When the player carries an Aspect of the End or of the Void
 * ({@link Transmission}), two more move kinds open up, and they are what make the difference between
 * a route that goes the long way round a canyon and one that crosses it:
 * <ul>
 *   <li><b>Instant Transmission</b> – a straight dash along a look direction, quantised to the
 *       seventeen upward-and-level directions a player can comfortably aim. The dash stops where a
 *       wall does, and the player falls at the far end exactly as they would in game.</li>
 *   <li><b>Ether Transmission</b> – aimed at the goal (or, when that is out of reach, at the ground
 *       ahead of it), landing on top of whatever the ray hits first. Aiming it anywhere else is not
 *       modelled: a warp is worth taking when it closes the distance, and two rays per node keeps
 *       this affordable where casting rays in every direction across sixty blocks would not.</li>
 * </ul>
 *
 * <p><b>Jump pads.</b> A learned pad ({@link JumpPad}) is one more edge: from its standing block to
 * where the launch came down. Its cost is set so the heuristic stays a lower bound - see
 * {@link #padCost}.
 *
 * <p><b>Returns something useful - or says there is nothing.</b> When the node budget runs out, or
 * the goal sits a few blocks inside a wall, the path to whichever node got closest is returned: that
 * is a partial route on the right ground. When the search <i>exhausts</i> every reachable node and
 * still ends far from the goal, the goal is on ground nothing known connects to - another island
 * without a known pad - and {@link #noRoute()} is reported with an empty path. A confident route to
 * the nearest tree branch was worse than no route: it looked like a plan.
 */
final class PathfinderTask {

    private static final double DIAGONAL_COST = Math.sqrt(2.0);

    /**
     * What a step into a 1.5-block sneak gap costs, per walk step: sneaking moves at about 30% of
     * walking speed (0.3 movement factor), so ~3.3x. Only ever more than a walk step, so the
     * heuristic's cheapest-step bound stays the walk cost and A* stays admissible.
     */
    static final double SNEAK_COST_FACTOR = 3.3;

    /** Extra cost for a climb / for each dropped block – both are slower than flat ground. */
    private static final double JUMP_PENALTY = 0.6;
    private static final double FALL_PENALTY = 0.35;

    /** Reaching within this distance of the goal counts as arrival (it may sit inside a block). */
    private static final double GOAL_RADIUS = 1.6;

    /**
     * Floor for how far from the start nodes may be expanded, so a failed search stays local.
     *
     * <p>It is a <b>floor</b>, not the limit: the real limit is widened to cover the goals (see
     * {@link #rangeLimit}). A fixed 256 silently made anything further unroutable - which is not
     * hypothetical, the Hub alone spans some five hundred blocks, so a Fairy Soul at its far edge
     * could never be reached however long the search ran. Work stays bounded by {@code maxNodes},
     * which is the guard that actually costs time.
     */
    private static final int MIN_RANGE = 256;

    /** Extra room past the furthest goal, so a route may detour around what sits between. */
    private static final int RANGE_MARGIN = 128;

    /**
     * What a teleport costs before any distance: aiming it, and the mana or soulflow it burns.
     *
     * <p>It exists so that chaining three short hops is never cheaper than one long one, which is
     * what stops a route across open ground from turning into teleport confetti.
     */
    private static final double TELEPORT_SETUP_COST = 1.0;

    /**
     * Cost of one block travelled by teleport, against walking's {@code 1.0}.
     *
     * <p>A teleport is far faster than half a walk in real time, and it is tempting to price it that
     * way – but the number is also the ceiling on how much the {@link #heuristic} may claim, because
     * the estimate has to stay a lower bound on what any move could achieve (see there). Pricing a
     * hop at a tenth of a walk would flatten the heuristic to nearly nothing and turn A* into a
     * breadth-first crawl over the whole island. Half a walk keeps teleports clearly preferable
     * wherever they fit while leaving the search pointed at the goal.
     */
    private static final double TELEPORT_BLOCK_COST = 0.5;

    /**
     * What a jump pad costs before any distance: walking onto it, and the flight you cannot steer.
     * Higher than a teleport's setup, because a pad is a fixed detour rather than an aim.
     */
    private static final double PAD_SETUP_COST = 2.0;

    /** Walking speed used to turn a pad's flight time into walk-equivalent blocks. */
    private static final double WALK_BLOCKS_PER_SECOND = 4.3;

    /**
     * An exhausted search ending closer than this to the goal keeps its partial route: that is a goal
     * inside a wall or behind a fence on the same ground, not one on another island.
     */
    private static final double SAME_GROUND_DISTANCE = 8.0;

    /** Sampling step of the etherwarp ray, in blocks. Below 1 so it cannot skip past a thin wall. */
    private static final double RAY_STEP = 0.25;

    /** {@link #landing} found nowhere to come to rest. */
    private static final int NO_LANDING = Integer.MIN_VALUE;

    /** The eight horizontal walking moves; the last four are the diagonals. */
    private static final int[][] WALK_MOVES = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** All 26 flying moves (every dx/dy/dz combination except standing still). */
    private static final int[][] FLY_MOVES;

    /**
     * The directions an Instant Transmission may be aimed: the eight horizontal ones, the same eight
     * angled upward, and straight up.
     *
     * <p>Downward aims are deliberately absent. They are not lost – a level dash that ends over a
     * drop falls, which is what the ability really does anyway – and leaving them out halves the
     * rays cast per node for no loss of reachable ground.
     */
    private static final int[][] INSTANT_MOVES;

    static {
        List<int[]> moves = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        moves.add(new int[] {dx, dy, dz});
                    }
                }
            }
        }
        FLY_MOVES = moves.toArray(new int[0][]);

        List<int[]> dashes = new ArrayList<>(17);
        for (int[] move : WALK_MOVES) {
            dashes.add(new int[] {move[0], 0, move[1]});
            dashes.add(new int[] {move[0], 1, move[1]});
        }
        dashes.add(new int[] {0, 1, 0});
        INSTANT_MOVES = dashes.toArray(new int[0][]);
    }

    private final Terrain terrain;
    private final BlockPos start;

    /** The jump pads this search may use; empty disables them. */
    private final List<JumpPad> pads;

    /** The most height any pad gains, for the heuristic's vertical bound. */
    private final int padLift;

    /**
     * The goals, any one of which ends the search - the "nearest by route cost" primitive.
     *
     * <p>A single search over a goal <i>set</i> is what makes "nearest" mean what it should. Running
     * one search per candidate and comparing lengths would cost N searches and still be measuring the
     * wrong thing until each one finished; expanding outward until the first goal is settled answers
     * it exactly once, and the answer is the true cheapest route rather than the shortest straight
     * line. The single-goal case is just a one-element set.
     *
     * <p>Cost is bounded by the caller keeping the set small: goals are island-scoped, so this is
     * tens of positions, and the per-node work is a min over that list.
     */
    private final List<BlockPos> goals;

    private final int maxNodes;
    private final PathMode mode;
    private final int maxStepUp;
    private final int maxFall;

    /** The teleports available for this search; {@link Transmission#NONE} disables them entirely. */
    private final Transmission transmission;

    /** How far from the start nodes may be expanded: {@link #MIN_RANGE}, or enough to reach a goal. */
    private final int rangeLimit;

    /**
     * The open set, holding <b>immutable snapshots</b> rather than the nodes themselves.
     *
     * <p>A* lowers a node's {@code f} when it finds a better route to it. Mutating an object that is
     * already inside a {@link PriorityQueue} silently breaks the heap invariant – the queue does not
     * re-order on mutation, so {@code poll()} stops returning the true minimum and the search can
     * settle a node before its best cost is known, yielding a needlessly long path. Queueing a fresh
     * snapshot per improvement and skipping already-closed entries when polled (lazy deletion) is
     * the standard fix and keeps the result optimal.
     */
    private final PriorityQueue<Entry> open =
            new PriorityQueue<>(Comparator.comparingDouble(Entry::f));
    private final Map<Long, Node> nodes = new HashMap<>();

    /** The node that got closest to the goal – the fallback when the goal is unreachable. */
    private Node best;
    private double bestDistance;
    private int expanded;
    private boolean finished;
    private boolean reachedGoal;
    private List<BlockPos> result = List.of();

    /**
     * The path nodes that are reached by a <b>teleport</b> rather than a step.
     *
     * <p>A set of positions rather than flags alongside the path, because the manager trims the
     * walked part off the front of the route as the player follows it – a parallel array would have
     * to be sliced in step with it, and a set simply keeps answering correctly.
     */
    private Set<BlockPos> teleports = Set.of();
    private Set<BlockPos> padLandings = Set.of();
    /** The path nodes only reachable sneaking (1.5-block headroom). */
    private Set<BlockPos> sneaks = Set.of();
    private boolean noRoute;

    PathfinderTask(Level level, BlockPos start, BlockPos goal, int maxNodes,
                   PlayerMobility.Mobility mobility, int maxFall) {
        this(Terrain.of(level), start, List.of(goal), maxNodes, mobility, maxFall, List.of());
    }

    PathfinderTask(Level level, BlockPos start, List<BlockPos> goals, int maxNodes,
                   PlayerMobility.Mobility mobility, int maxFall, List<JumpPad> pads) {
        this(Terrain.of(level), start, goals, maxNodes, mobility, maxFall, pads);
    }

    PathfinderTask(Terrain terrain, BlockPos start, List<BlockPos> goals, int maxNodes,
                   PlayerMobility.Mobility mobility, int maxFall, List<JumpPad> pads) {
        this.terrain = terrain;
        this.start = start;
        this.pads = pads == null ? List.of() : List.copyOf(pads);
        int lift = 0;
        for (JumpPad pad : this.pads) {
            lift = Math.max(lift, Math.abs(pad.landing().getY() - pad.stand().getY()));
        }
        this.padLift = lift;
        this.goals = List.copyOf(goals);
        this.maxNodes = Math.max(256, maxNodes);
        this.mode = mobility.mode();
        this.maxStepUp = Math.max(1, mobility.maxStepUp());
        this.maxFall = Math.max(1, maxFall);
        this.transmission = mobility.transmission() == null
                ? Transmission.NONE : mobility.transmission();

        int furthest = 0;
        // A pad's landing can lie beyond every goal (you fly past, then walk back); it has to be
        // inside the search area or its edge would be dropped by relax.
        for (JumpPad pad : this.pads) {
            furthest = Math.max(furthest, Math.abs(pad.landing().getX() - start.getX()));
            furthest = Math.max(furthest, Math.abs(pad.landing().getZ() - start.getZ()));
        }
        for (BlockPos target : this.goals) {
            furthest = Math.max(furthest, Math.abs(target.getX() - start.getX()));
            furthest = Math.max(furthest, Math.abs(target.getZ() - start.getZ()));
        }
        this.rangeLimit = Math.max(MIN_RANGE, furthest + RANGE_MARGIN);

        Node first = new Node(start.getX(), start.getY(), start.getZ());
        first.g = 0;
        first.h = heuristic(start.getX(), start.getY(), start.getZ());
        nodes.put(first.key, first);
        open.add(new Entry(first, first.h));
        best = first;
        bestDistance = distanceToGoal(start.getX(), start.getY(), start.getZ());
    }

    boolean finished() {
        return finished;
    }

    boolean reachedGoal() {
        return reachedGoal;
    }

    int expanded() {
        return expanded;
    }

    /** The path once {@link #finished()} – to the goal, or as close as the search could get. */
    List<BlockPos> path() {
        return result;
    }

    /** Which of the {@link #path()} nodes are arrived at by a teleport rather than on foot. */
    Set<BlockPos> teleportNodes() {
        return teleports;
    }

    /** Which of the {@link #path()} nodes are the landing of a jump pad. */
    Set<BlockPos> padNodes() {
        return padLandings;
    }

    /** Which of the {@link #path()} nodes the player can only pass sneaking. */
    Set<BlockPos> sneakNodes() {
        return sneaks;
    }

    /**
     * Whether the search proved the goal unreachable with what it knows: every reachable node was
     * expanded, and none got within {@link #SAME_GROUND_DISTANCE}. {@link #path()} is empty then.
     */
    boolean noRoute() {
        return noRoute;
    }

    /**
     * Expands at most {@code budget} nodes.
     *
     * @return {@code true} once the search is done (goal found, budget spent, or nowhere left to go)
     */
    boolean step(int budget) {
        if (finished) {
            return true;
        }
        for (int i = 0; i < budget; i++) {
            if (open.isEmpty()) {
                return finish(false, true);
            }
            Node node = open.poll().node();
            if (node.closed) {
                continue; // a stale snapshot of a node we already settled
            }
            node.closed = true;
            expanded++;

            double distance = distanceToGoal(node.x, node.y, node.z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = node;
            }
            if (distance <= GOAL_RADIUS) {
                best = node;
                return finish(true, false);
            }
            if (expanded >= maxNodes) {
                return finish(false, false);
            }
            if (mode == PathMode.FLY) {
                expandFlying(node);
            } else {
                expandWalking(node);
            }
            if (transmission.instantRange() > 0) {
                expandInstant(node);
            }
            if (transmission.etherRange() > 0) {
                expandEther(node);
            }
            if (!pads.isEmpty()) {
                expandPads(node);
            }
        }
        return false;
    }

    /**
     * Builds the result from {@link #best} and marks the search complete.
     *
     * @param exhausted the open set ran dry - every reachable node was expanded - as opposed to the
     *                  node budget running out, which proves nothing about reachability
     */
    private boolean finish(boolean reached, boolean exhausted) {
        finished = true;
        reachedGoal = reached;
        if (!reached) {
            logNoRoute(exhausted);
        }
        if (!reached && exhausted && bestDistance > SAME_GROUND_DISTANCE) {
            noRoute = true;   // other ground, nothing known connects it: say so, draw nothing
            result = List.of();
            return true;
        }
        List<BlockPos> path = new ArrayList<>();
        Set<BlockPos> hops = new HashSet<>();
        Set<BlockPos> landings = new HashSet<>();
        for (Node node = best; node != null; node = node.parent) {
            BlockPos pos = new BlockPos(node.x, node.y, node.z);
            path.add(pos);
            if (node.teleport) {
                hops.add(pos);
            }
            if (node.pad) {
                landings.add(pos);
            }
        }
        Collections.reverse(path);
        result = path;
        Set<BlockPos> crouched = new HashSet<>();
        if (mode != PathMode.FLY) {
            for (BlockPos pos : path) {
                if (terrain.standKind(pos.getX(), pos.getY(), pos.getZ()) == Walkability.SNEAK) {
                    crouched.add(pos);
                }
            }
        }
        sneaks = crouched.isEmpty() ? Set.of() : crouched;
        teleports = hops.isEmpty() ? Set.of() : hops;
        padLandings = landings.isEmpty() ? Set.of() : landings;
        return true;
    }

    /**
     * One line per search that did not reach its goal, so a "it showed no way" report can be checked
     * against what the search actually saw.
     */
    /** The last request {@link #logNoRoute} reported. */
    private static volatile String lastNoRouteLogged;

    private void logNoRoute(boolean exhausted) {
        BlockPos goal = goals.isEmpty() ? start : goals.getFirst();
        // Routes are recomputed every few seconds; the same request is logged once, not each time.
        String key = start + ">" + goal + ">" + exhausted;
        if (key.equals(lastNoRouteLogged)) {
            return;
        }
        lastNoRouteLogged = key;
        String reason = exhausted ? "every reachable spot searched" : "node budget spent";
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Path] no route from {} {} {} to {} {} {}{}: {} ({} nodes expanded, closest point "
                        + "{} {} {}, {} blocks away)",
                start.getX(), start.getY(), start.getZ(), goal.getX(), goal.getY(), goal.getZ(),
                goals.size() > 1 ? " (+" + (goals.size() - 1) + " more goals)" : "", reason, expanded,
                best.x, best.y, best.z, String.format(java.util.Locale.ROOT, "%.1f", bestDistance));
    }

    // ------------------------------------------------------------------
    // Expansion – jump pads
    // ------------------------------------------------------------------

    /**
     * The node standing exactly on a pad gets the pad's flight as an edge. Exactly, not "near": the
     * route has to lead onto the pad for the hint to point at the right block, and walking onto it is
     * an ordinary step the search already knows how to make.
     */
    private void expandPads(Node node) {
        for (JumpPad pad : pads) {
            BlockPos stand = pad.stand();
            if (node.x == stand.getX() && node.y == stand.getY() && node.z == stand.getZ()) {
                BlockPos land = pad.landing();
                relax(node, land.getX(), land.getY(), land.getZ(), padCost(pad), false, true);
            }
        }
    }

    /**
     * What one flight costs, in walk-equivalent blocks - and why it is not simply the flight time.
     *
     * <p>The heuristic claims that no move covers a block of distance for less than
     * {@link #cheapestBlockCost()}. A pad that crossed sixty blocks for the price of three seconds'
     * walk would break that claim, the estimate would overshoot on every node past the pad, and A*
     * would stop being optimal (the same trap the teleport costs document). So a flight is charged
     * <b>at least</b> the octile distance it covers at that per-block rate, plus
     * {@link #PAD_SETUP_COST}; and at least its flight time as walking, when that is more. It is still
     * the only way across a void, which is what makes it win - not being artificially cheap.
     *
     * <p>Octile, not straight-line: the heuristic's horizontal bound is octile, and octile is never
     * shorter than the straight line, so charging the straight line would undercut it.
     */
    double padCost(JumpPad pad) {
        double dx = Math.abs(pad.landing().getX() - pad.stand().getX());
        double dz = Math.abs(pad.landing().getZ() - pad.stand().getZ());
        double octile = DIAGONAL_COST * Math.min(dx, dz) + Math.abs(dx - dz);
        double byDistance = octile * cheapestBlockCost();
        double byTime = pad.flightMs() / 1000.0 * WALK_BLOCKS_PER_SECOND;
        return PAD_SETUP_COST + Math.max(byDistance, byTime);
    }

    // ------------------------------------------------------------------
    // Expansion – walking
    // ------------------------------------------------------------------

    private void expandWalking(Node node) {
        // Whether the player is crouched here: no jumping out of (or within) a sneak gap.
        boolean crouched = terrain.standKind(node.x, node.y, node.z) == Walkability.SNEAK;
        for (int[] move : WALK_MOVES) {
            int dx = move[0];
            int dz = move[1];
            boolean diagonal = dx != 0 && dz != 0;

            // A diagonal may only be taken when both cardinal neighbours are open, otherwise the
            // path would slice through the corner of a wall the player cannot actually pass.
            if (diagonal && !(bodyClear(node.x + dx, node.y, node.z)
                    && bodyClear(node.x, node.y, node.z + dz))) {
                continue;
            }

            // Prefer the highest reachable landing: climb first, then level, then a drop.
            for (int dy = maxStepUp; dy >= -maxFall; dy--) {
                int nx = node.x + dx;
                int ny = node.y + dy;
                int nz = node.z + dz;
                int kind = terrain.standKind(nx, ny, nz);
                if (kind == Walkability.NO_STAND || !walkReachable(node, nx, ny, nz)) {
                    continue;
                }
                boolean sneak = kind == Walkability.SNEAK;
                // A jump needs standing headroom at both ends: never into, out of or within a gap.
                if (dy > 0 && (crouched || sneak)) {
                    continue;
                }
                double cost = diagonal ? DIAGONAL_COST : 1.0;
                if (sneak) {
                    cost *= SNEAK_COST_FACTOR;
                }
                if (dy > 0) {
                    cost += JUMP_PENALTY * dy;   // a taller hop is a slower one
                } else if (dy < 0) {
                    cost += FALL_PENALTY * -dy;
                }
                relax(node, nx, ny, nz, cost, false);
                break; // only the first (highest) landing in this direction
            }
        }
    }

    /**
     * Whether a walking move from {@code node} into {@code (nx, ny, nz)} is physically possible.
     *
     * <p>A climb needs the column above the player clear all the way to the landing – with a
     * jump-boost potion that can be several blocks, so checking only head height (as a
     * one-block-step-up assumption would) is not enough. A drop needs the whole column open so the
     * route can never fall through a ceiling.
     */
    private boolean walkReachable(Node node, int nx, int ny, int nz) {
        if (ny > node.y) {
            for (int y = node.y + 2; y <= ny + 1; y++) {
                if (!isPassable(node.x, y, node.z)) {
                    return false;
                }
            }
            return true;
        }
        if (ny < node.y) {
            for (int y = node.y; y > ny + 1; y--) {
                if (!bodyClear(nx, y, nz)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Expansion – flying
    // ------------------------------------------------------------------

    private void expandFlying(Node node) {
        for (int[] move : FLY_MOVES) {
            int dx = move[0];
            int dy = move[1];
            int dz = move[2];
            int nx = node.x + dx;
            int ny = node.y + dy;
            int nz = node.z + dz;
            if (!bodyClear(nx, ny, nz) || !stepClear(node.x, node.y, node.z, dx, dy, dz)) {
                continue;
            }
            relax(node, nx, ny, nz, Math.sqrt(dx * dx + dy * dy + dz * dz), false);
        }
    }

    /**
     * A diagonal step needs its axis-aligned neighbours open, so it cannot slip through a seam.
     * Shared by flying and by the teleport dash, which travels the same unit steps.
     */
    private boolean stepClear(int x, int y, int z, int dx, int dy, int dz) {
        if (dx != 0 && !bodyClear(x + dx, y, z)) {
            return false;
        }
        if (dy != 0 && !bodyClear(x, y + dy, z)) {
            return false;
        }
        return dz == 0 || bodyClear(x, y, z + dz);
    }

    // ------------------------------------------------------------------
    // Expansion – teleporting
    // ------------------------------------------------------------------

    /**
     * Instant Transmission: a straight dash of up to {@code instantRange} blocks in each aimable
     * direction, ending wherever the player would come to rest.
     *
     * <p>Only the <b>furthest</b> landing per direction is offered, not every cell along the way.
     * The nearer ones are what walking already covers, so emitting them would multiply the branching
     * factor by the dash length to say nothing new – the whole point of the ability is the far end.
     */
    private void expandInstant(Node node) {
        int range = transmission.instantRange();
        for (int[] direction : INSTANT_MOVES) {
            int x = node.x;
            int y = node.y;
            int z = node.z;
            int landX = 0;
            int landY = NO_LANDING;
            int landZ = 0;

            for (int step = 1; step <= range; step++) {
                if (!stepClear(x, y, z, direction[0], direction[1], direction[2])) {
                    break;
                }
                x += direction[0];
                y += direction[1];
                z += direction[2];
                if (!bodyClear(x, y, z)) {
                    break;   // the dash stops at the wall, exactly as the ability does
                }
                int rest = landing(x, y, z);
                if (rest != NO_LANDING) {
                    landX = x;
                    landY = rest;
                    landZ = z;
                }
            }
            if (landY != NO_LANDING) {
                teleport(node, landX, landY, landZ);
            }
        }
    }

    /**
     * Ether Transmission: up to two warps aimed the way a player would aim them, each landing on top
     * of the first block its ray meets.
     *
     * <p>The first is aimed at the block <b>under</b> the goal rather than at the goal itself,
     * because that is the block a player would put their crosshair on – the ability lands you on
     * what you clicked, so clicking the floor is what puts you on the floor. When something is in the
     * way the ray stops there instead, which is not a failure but the other half of the feature:
     * landing on top of the wall between you and the goal is how a warp gets over it.
     *
     * <p>The second only exists for goals beyond the ability's reach, and is aimed at the ground
     * ahead rather than at anything in particular. Only <b>two</b> aims either way: rays are the
     * expensive part of this search at sixty blocks each, and these are the two a player actually
     * takes. Warping sideways for its own sake is left to Instant Transmission, which is short
     * enough to afford in every direction.
     */
    private void expandEther(Node node) {
        BlockPos goal = nearestGoal(node.x, node.y, node.z);
        if (goal == null) {
            return;
        }
        // Straight at the block under the goal. When the goal is in range this is the whole trip.
        warpToward(node, goal.getX() + 0.5, goal.getY() - 0.5, goal.getZ() + 0.5);

        double dx = goal.getX() - node.x;
        double dz = goal.getZ() - node.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int range = transmission.etherRange();
        if (horizontal <= range) {
            return;
        }
        // Further than one warp: aim at the ground ahead instead, which is what a player does. A ray
        // at a distant goal is almost horizontal and sails over open terrain hitting nothing at all,
        // so without this second aim the ability would go unused on exactly the long flat runs it is
        // best at. Ending half a block below the feet gives a shallow angle that meets the floor
        // well down range rather than at the player's toes.
        double scale = range / horizontal;
        warpToward(node, node.x + 0.5 + dx * scale, node.y - 0.5, node.z + 0.5 + dz * scale);
    }

    /**
     * Casts one etherwarp ray from {@code node} at {@code (aimX, aimY, aimZ)} and offers the landing
     * it produces, if any.
     *
     * <p>Marched by hand rather than through {@link Level#clip}: the vanilla ray reports a miss in
     * unloaded terrain, where {@link Walkability} deliberately reads a full block, and a route that
     * warps into the unknown is exactly what that rule exists to prevent.
     */
    private void warpToward(Node node, double aimX, double aimY, double aimZ) {
        double originX = node.x + 0.5;
        double originY = node.y + 1.5;      // roughly eye height, where the real ray starts
        double originZ = node.z + 0.5;
        double dx = aimX - originX;
        double dy = aimY - originY;
        double dz = aimZ - originZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0) {
            return;
        }

        double travel = Math.min(length, transmission.etherRange());
        int samples = (int) Math.ceil(travel / RAY_STEP);
        int lastX = node.x;
        int lastY = node.y + 1;   // the cell the ray starts in, so it is not tested against itself
        int lastZ = node.z;
        for (int i = 1; i <= samples; i++) {
            double along = Math.min(travel, i * RAY_STEP) / length;
            int cx = Mth.floor(originX + dx * along);
            int cy = Mth.floor(originY + dy * along);
            int cz = Mth.floor(originZ + dz * along);
            if (cx == lastX && cy == lastY && cz == lastZ) {
                continue;   // still inside the block sampled last time
            }
            lastX = cx;
            lastY = cy;
            lastZ = cz;
            if (isPassable(cx, cy, cz)) {
                continue;
            }
            // Impact. A partial block (slab, carpet) is stood *in*, a full one is stood *on*.
            if (canStand(cx, cy, cz)) {
                teleport(node, cx, cy, cz);
            } else if (canStand(cx, cy + 1, cz)) {
                teleport(node, cx, cy + 1, cz);
            }
            return;
        }
    }

    /**
     * Where a player teleported into {@code (x, y, z)} ends up, or {@link #NO_LANDING} when nothing
     * catches them – the {@code y} they come to rest at, having fallen if there was nothing underfoot.
     *
     * <p>The fall is the same one a walking move is allowed, so a dash can never end in a drop the
     * player has already said they do not want to take.
     */
    private int landing(int x, int y, int z) {
        if (mode == PathMode.FLY) {
            return y;   // no gravity to fall under
        }
        if (canStand(x, y, z)) {
            return y;
        }
        for (int drop = 1; drop <= maxFall; drop++) {
            if (canStand(x, y - drop, z)) {
                return y - drop;
            }
            if (!bodyClear(x, y - drop, z)) {
                return NO_LANDING;   // sealed underneath, so there is no falling any further
            }
        }
        return NO_LANDING;
    }

    /** Offers a teleport to {@code (nx, ny, nz)}, priced by the distance actually covered. */
    private void teleport(Node from, int nx, int ny, int nz) {
        double dx = nx - from.x;
        double dy = ny - from.y;
        double dz = nz - from.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        relax(from, nx, ny, nz, TELEPORT_SETUP_COST + distance * TELEPORT_BLOCK_COST, true, false);
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /** Adds / improves the neighbour node. */
    private void relax(Node from, int nx, int ny, int nz, double cost, boolean teleport) {
        relax(from, nx, ny, nz, cost, teleport, false);
    }

    private void relax(Node from, int nx, int ny, int nz, double cost, boolean teleport, boolean pad) {
        if (Math.abs(nx - start.getX()) > rangeLimit || Math.abs(nz - start.getZ()) > rangeLimit) {
            return;
        }
        long key = BlockPos.asLong(nx, ny, nz);
        Node neighbour = nodes.get(key);
        double g = from.g + cost;
        if (neighbour == null) {
            neighbour = new Node(nx, ny, nz);
            neighbour.h = heuristic(nx, ny, nz);
            nodes.put(key, neighbour);
        } else if (neighbour.closed || g >= neighbour.g) {
            return;
        }
        neighbour.g = g;
        neighbour.parent = from;
        // Set with the parent, never separately: the two together are "how this node is reached",
        // and a cheaper route arriving on foot has to clear a teleport flag left by an earlier one.
        neighbour.teleport = teleport;
        neighbour.pad = pad;
        open.add(new Entry(neighbour, g + neighbour.h));
    }

    /** Whether a player standing at {@code (x, y, z)} would be supported and fit. */
    private boolean canStand(int x, int y, int z) {
        return terrain.canStand(x, y, z);
    }

    /** Whether the two blocks a player occupies at {@code (x, y, z)} are free. */
    private boolean bodyClear(int x, int y, int z) {
        return terrain.bodyClear(x, y, z);
    }

    private boolean isPassable(int x, int y, int z) {
        return terrain.isPassable(x, y, z);
    }

    /**
     * Straight-line distance to the <b>nearest</b> goal – the mode-independent "how close did we
     * get". With one goal this is exactly what it always was.
     */
    private double distanceToGoal(int x, int y, int z) {
        double best = Double.MAX_VALUE;
        for (BlockPos target : goals) {
            double dx = x - target.getX();
            double dy = y - target.getY();
            double dz = z - target.getZ();
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    /**
     * The goal nearest in a straight line – which of them a player at {@code (x, y, z)} would aim an
     * etherwarp at. Nearest by line rather than by route is right here and only here: the warp needs
     * to <i>see</i> its target, and what a route costs has no bearing on where a ray goes.
     */
    private BlockPos nearestGoal(int x, int y, int z) {
        BlockPos closest = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos target : goals) {
            double dx = x - target.getX();
            double dy = y - target.getY();
            double dz = z - target.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = target;
            }
        }
        return closest;
    }

    /**
     * The goal the finished path actually ends at, or {@code null} when none was reached.
     *
     * <p>What the caller needs to know after a multi-goal search: <i>which</i> of the candidates
     * turned out to be nearest by route.
     */
    BlockPos reachedGoalPos() {
        if (!reachedGoal || best == null) {
            return null;
        }
        BlockPos closest = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos target : goals) {
            double dx = best.x - target.getX();
            double dy = best.y - target.getY();
            double dz = best.z - target.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = target;
            }
        }
        return closest;
    }

    /**
     * A lower bound on the remaining cost, which is what keeps A* optimal.
     *
     * <p>Flying costs true 3D distance per block, so the straight line is exactly admissible.
     *
     * <p>Walking is the subtle one: the old "horizontal + vertical" sum silently stopped being a
     * lower bound once {@code maxStepUp} grew, because a jump-boosted move can gain several blocks
     * of height for the price of one step – the sum would then <i>overestimate</i> and A* would
     * happily return a worse path. Taking the <b>maximum</b> of two independent lower bounds (the
     * horizontal distance, and the fewest moves that could cover the height difference) is
     * admissible for any jump height.
     *
     * <p>Teleports are the same trap one step further on. Once a hop can cover sixty blocks, both
     * bounds have to be re-priced or they stop being bounds: distance is charged at
     * {@link #TELEPORT_BLOCK_COST} rather than a walk's {@code 1.0}, and the vertical reach counts a
     * teleport's range as one move's worth of climb. That costs the search some of its focus, which
     * is the real reason a hop is not priced at what it is worth in seconds – see
     * {@link #TELEPORT_BLOCK_COST}.
     */
    private double heuristic(int x, int y, int z) {
        double best = Double.MAX_VALUE;
        for (BlockPos target : goals) {
            best = Math.min(best, heuristicTo(x, y, z, target));
        }
        return best;
    }

    /**
     * The lower bound for one goal. Taking the <b>minimum</b> across the goal set stays admissible:
     * reaching whichever goal is cheapest cannot cost less than the cheapest individual bound.
     */
    private double heuristicTo(int x, int y, int z, BlockPos target) {
        double dx = Math.abs(x - target.getX());
        double dy = Math.abs(y - target.getY());
        double dz = Math.abs(z - target.getZ());
        double perBlock = cheapestBlockCost();
        if (mode == PathMode.FLY) {
            return Math.sqrt(dx * dx + dy * dy + dz * dz) * perBlock;
        }
        double diagonal = Math.min(dx, dz);
        double straight = Math.abs(dx - dz);
        double horizontal = (DIAGONAL_COST * diagonal + straight) * perBlock;
        // A pad can gain its whole lift in one move, so it widens the vertical reach like a teleport.
        int verticalReach = Math.max(Math.max(Math.max(maxStepUp, maxFall), transmission.longestRange()),
                padLift);
        double vertical = Math.ceil(dy / verticalReach);
        return Math.max(horizontal, vertical);
    }

    /**
     * The least any single block of travel can cost, whichever move covers it.
     *
     * <p>Charging {@link #TELEPORT_BLOCK_COST} flat is a slightly looser bound than pricing a
     * full-length hop exactly – a hop also pays {@link #TELEPORT_SETUP_COST} – but it is one that
     * cannot be wrong. Working the setup cost into the rate would make it depend on the hop's
     * length, and a landing that falls a few blocks past the range covers more distance than the
     * range allows for, which is precisely how an "exact" bound quietly becomes an overestimate and
     * costs A* its optimality.
     *
     * <p>The vertical half of the estimate needs no such discount: every move there is, teleport or
     * step, costs at least {@code 1.0}.
     */
    private double cheapestBlockCost() {
        return transmission.any() ? TELEPORT_BLOCK_COST : 1.0;
    }

    /**
     * An immutable open-set entry: the node plus the {@code f} it had when queued. Never re-read
     * from the node, so the queue's ordering cannot be invalidated by a later improvement.
     */
    private record Entry(Node node, double f) {
    }

    /** One search node. Mutable and pooled in {@link #nodes} – A* rewrites g as it improves. */
    private static final class Node {
        private final long key;
        private final int x;
        private final int y;
        private final int z;
        private double g;
        private double h;
        private Node parent;
        private boolean closed;

        /** Whether the move from {@link #parent} into this node is a teleport rather than a step. */
        private boolean teleport;

        /** Whether the move from {@link #parent} into this node is a jump pad's flight. */
        private boolean pad;

        private Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = BlockPos.asLong(x, y, z);
        }
    }
}
