/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One source's route: its goal set, its own search, the finished path and the state a HUD shows.
 *
 * <p>This is what {@link PathfindingManager} used to hold exactly once. Moving it into its own
 * object is the whole of the "one path per feature" change - every rule below (when to re-search,
 * trimming the walked part, the proven-no-route memo, the arrival check) is the old single-path rule,
 * now applied per source so the sources stop taking the path away from each other.
 *
 * <p><b>Grace.</b> A source whose goals vanish does not lose its route at once: it is marked
 * {@linkplain #stale() stale} and kept for {@link #GRACE_MS}. A scoreboard objective line flickers,
 * and arrival clears a marker for a moment; without the grace the route blinked out and - back when
 * there was one path - the next source's route snapped in for those frames. If the goals come back
 * inside the grace the route carries on, and the old path stays drawn until the new search lands.
 *
 * <p>Client thread only, like the manager.
 */
public final class Route {

    /** How long a route outlives its source's goals before it is dropped. */
    public static final long GRACE_MS = 3_000L;

    /** Recompute at least this often, so terrain changes are eventually picked up. */
    static final long REFRESH_MS = 8_000L;

    /** Close enough to the target waypoint that there is nothing left to route. */
    static final double ARRIVED_DISTANCE = 2.0;

    /** How far the player must move before a proven "no route" is searched again. */
    private static final int NO_ROUTE_RETRY_BLOCKS = 12;

    /** What the HUD list and the marker say about a route. */
    public enum State {
        SEARCHING, ROUTE, PARTIAL, NO_ROUTE, ARRIVED, STALE, PAUSED
    }

    private final RouteSource source;

    private PathfinderTask task;
    private List<BlockPos> path = List.of();
    private Set<BlockPos> teleports = Set.of();
    private Set<BlockPos> padLandings = Set.of();
    private Set<BlockPos> sneaks = Set.of();
    private Waypoint target;
    private List<Waypoint> goals = List.of();
    private PlayerMobility.Mobility mobility;
    private long lastComputed;
    private boolean reachedGoal;
    private boolean noRoute;
    private BlockPos noRouteFrom;
    private int noRoutePads = -1;
    private BlockPos searchedFrom;
    private boolean arrived;

    /** When the goals disappeared, or {@code -1} while they are present. */
    private long staleSince = -1;

    /** Set by the manager when the route cap leaves this source out. */
    private boolean paused;

    Route(RouteSource source) {
        this.source = source;
    }

    // ------------------------------------------------------------------ published state

    public RouteSource source() {
        return source;
    }

    public List<BlockPos> path() {
        return path;
    }

    public Set<BlockPos> teleportNodes() {
        return teleports;
    }

    public Set<BlockPos> padNodes() {
        return padLandings;
    }

    /** Path nodes the player can only pass sneaking (1.5-block headroom). */
    public Set<BlockPos> sneakNodes() {
        return sneaks;
    }

    /** The goal the route leads to - for a set, the one the search picked. */
    public Waypoint target() {
        return target;
    }

    public List<Waypoint> goals() {
        return goals;
    }

    public boolean searching() {
        return task != null;
    }

    public boolean noRoute() {
        return noRoute;
    }

    public boolean reachedGoal() {
        return reachedGoal;
    }

    public PlayerMobility.Mobility mobility() {
        return mobility;
    }

    public boolean stale() {
        return staleSince >= 0;
    }

    public boolean paused() {
        return paused;
    }

    public State state() {
        if (paused) {
            return State.PAUSED;
        }
        if (stale()) {
            return State.STALE;
        }
        if (arrived) {
            return State.ARRIVED;
        }
        if (task != null && path.isEmpty()) {
            return State.SEARCHING;
        }
        if (noRoute) {
            return State.NO_ROUTE;
        }
        if (path.isEmpty()) {
            return State.SEARCHING;
        }
        return reachedGoal ? State.ROUTE : State.PARTIAL;
    }

    /**
     * The route's length in blocks along the path, from its first node to its last - what the marker
     * and the HUD list show. Teleport and pad hops count their straight-line length, which is not a
     * walk but is still the honest "how far away is it".
     */
    public double length() {
        double sum = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            int dx = a.getX() - b.getX();
            int dy = a.getY() - b.getY();
            int dz = a.getZ() - b.getZ();
            sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return sum;
    }

    // ------------------------------------------------------------------ lifecycle

    /** The source has goals this tick: clears any stale mark. */
    void seen(List<Waypoint> current) {
        staleSince = -1;
        if (current.isEmpty()) {
            return;
        }
        // The identity check alone would count a re-published marker as a new goal; the search
        // restarts then, but the path is kept until it finishes, so nothing blinks.
        if (target != null && !current.contains(target)) {
            arrived = false;
        }
    }

    /** The source has no goals this tick: starts (or continues) the grace period. */
    void missing(long now) {
        if (staleSince < 0) {
            staleSince = now;
            task = null;   // nothing to search for; the drawn path is what the grace keeps
        }
    }

    /** Whether the grace has run out and the route should be dropped. */
    boolean expired(long now) {
        return staleSince >= 0 && now - staleSince >= GRACE_MS;
    }

    void pause(boolean paused) {
        if (paused && !this.paused) {
            task = null;
        }
        this.paused = paused;
    }

    /** Drops the path and forces a fresh search, keeping the route itself. */
    void reset() {
        task = null;
        path = List.of();
        teleports = Set.of();
        padLandings = Set.of();
        sneaks = Set.of();
        noRoute = false;
        noRouteFrom = null;
        target = null;
        goals = List.of();
        mobility = null;
        lastComputed = 0;
        arrived = false;
    }

    // ------------------------------------------------------------------ search

    /**
     * Spends up to {@code budget} nodes on the running search.
     *
     * @return the nodes it was allowed, for the manager's calibration; 0 when nothing ran
     */
    int step(int budget) {
        if (task == null) {
            return 0;
        }
        if (task.step(budget)) {
            path = task.path();
            teleports = task.teleportNodes();
            padLandings = task.padNodes();
            sneaks = task.sneakNodes();
            reachedGoal = task.reachedGoal();
            noRoute = task.noRoute();
            if (noRoute) {
                noRouteFrom = searchedFrom;
                noRoutePads = JumpPads.getInstance().generation();
            }
            if (goals.size() != 1) {
                target = resolveReached(task.reachedGoalPos());
            }
            lastComputed = System.currentTimeMillis();
            task = null;
        }
        return budget;
    }

    /**
     * Which of the searched goals the finished path ended at.
     *
     * <p>Falls back to the nearest goal to the path's end when the search reached none of them:
     * leaving {@link #target} null would make {@link #needsRecompute} restart the search every single
     * tick, turning one unreachable soul into a permanent CPU burn.
     */
    private Waypoint resolveReached(BlockPos reached) {
        if (goals.isEmpty()) {
            return null;
        }
        if (reached != null) {
            for (Waypoint waypoint : goals) {
                if (waypoint.x == reached.getX() && waypoint.y == reached.getY()
                        && waypoint.z == reached.getZ()) {
                    return waypoint;
                }
            }
        }
        BlockPos end = path.isEmpty() ? null : path.getLast();
        return end == null ? goals.getFirst() : WaypointStore.nearestTo(end, goals);
    }

    /**
     * Drops the nodes the player has already walked past, so the route ends at the target and starts
     * roughly at their feet.
     */
    void trimWalkedPart(Vec3 playerPos) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            BlockPos node = path.get(i);
            double distance = playerPos.distanceToSqr(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best > 0 && best < path.size()) {
            path = new ArrayList<>(path.subList(best, path.size()));
        }
    }

    /**
     * The cheap checks that decide whether the cached path is still good enough. The same rules the
     * single path had - see the comments on each - so a source's route behaves exactly as the one
     * route used to.
     */
    boolean needsRecompute(BlockPos playerBlock, Vec3 playerPos, List<Waypoint> current,
                           PlayerMobility.Mobility now, double tolerance, double verticalTolerance) {
        if (task != null) {
            return false;   // one search at a time per route; finishing beats restarting
        }
        // Mid-flight from a jump pad: off every route by design. Re-plan once it lands.
        if (JumpPads.getInstance().inFlight()) {
            return false;
        }
        // A proven "no route" stands until something could change it.
        if (noRoute && sameGoals(current, goals) && noRouteFrom != null
                && playerBlock.distManhattan(noRouteFrom) < NO_ROUTE_RETRY_BLOCKS
                && JumpPads.getInstance().generation() == noRoutePads) {
            return false;
        }
        // Standing on the routed waypoint: nothing to search for. Checked against the goal actually
        // routed to, so one arrived-at candidate cannot pin a whole multi-goal set.
        Waypoint routed = target != null && current.contains(target) ? target
                : current.size() == 1 ? current.getFirst() : null;
        if (routed != null && playerPos.distanceToSqr(routed.x + 0.5, routed.y, routed.z + 0.5)
                <= ARRIVED_DISTANCE * ARRIVED_DISTANCE) {
            arrived = true;
            return false;
        }
        arrived = false;
        if (path.size() < 2 || target == null) {
            return true;
        }
        if (!sameGoals(current, goals)) {
            return true;
        }
        if (!now.equals(mobility)) {
            return true;
        }
        if (!isOnPath(playerPos, tolerance, verticalTolerance)) {
            return true;
        }
        // Only refresh on the timer when the last search reached its goal - an unreachable one
        // would burn the full budget every few seconds for a result that cannot change.
        return reachedGoal && System.currentTimeMillis() - lastComputed > REFRESH_MS;
    }

    /** Horizontal and vertical slack measured separately - see the old manager's note. */
    private boolean isOnPath(Vec3 playerPos, double horizontal, double vertical) {
        for (BlockPos node : path) {
            double dx = playerPos.x - (node.getX() + 0.5);
            double dz = playerPos.z - (node.getZ() + 0.5);
            if (dx * dx + dz * dz > horizontal * horizontal) {
                continue;
            }
            if (Math.abs(playerPos.y - node.getY()) <= vertical) {
                return true;
            }
        }
        return false;
    }

    /** Identity comparison on purpose: waypoints are the live objects, see the store. */
    static boolean sameGoals(List<Waypoint> a, List<Waypoint> b) {
        return a.size() == b.size() && a.containsAll(b);
    }

    /**
     * Starts a search over {@code current}. The previous path stays published until this one lands,
     * which is what keeps a re-search from blinking the line.
     */
    void startSearch(Level level, BlockPos from, List<Waypoint> current, PlayerMobility.Mobility now,
                     int maxNodes, int maxFall, List<JumpPad> pads) {
        goals = List.copyOf(current);
        // One goal: the target is known up front. Several: resolved when the search lands - keep the
        // previous pick meanwhile so the marker does not vanish for the search's duration.
        if (current.size() == 1) {
            target = current.getFirst();
        } else if (target != null && !current.contains(target)) {
            target = null;
        }
        mobility = now;
        List<BlockPos> positions = new ArrayList<>(current.size());
        for (Waypoint waypoint : current) {
            positions.add(waypoint.pos());
        }
        searchedFrom = from;
        task = new PathfinderTask(level, from, positions, maxNodes, now, maxFall, pads);
    }
}
