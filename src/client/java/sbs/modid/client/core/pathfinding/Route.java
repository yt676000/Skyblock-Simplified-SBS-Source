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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p><b>Phases.</b> A search starts as plain A*. A long walking route to one goal may hand over to
 * the {@link HybridPlanner} - straight away when start and goal are both under open sky and far apart,
 * or when A* outlives its {@link SwitchBudget}. If A* and the hybrid planner both fail, a background
 * <b>deep search</b> covers the whole loaded island at a low per-tick slice (or reuses a route an
 * earlier deep search found - {@link DeepRouteCache}). Every switch logs one line, kept in
 * {@link #lastSwitch()}; the finished route logs its per-phase totals once. See
 * {@code docs/features/hybrid-pathfinding.md}.
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

    /** Start and goal both under open sky and at least this far apart: hybrid from the start. */
    static final double HYBRID_DIRECT_DISTANCE = 64;

    /** Per-tick wall time the deep search may use - small, so frame time is unaffected. */
    private static final long DEEP_SLICE_NANOS = 1_000_000L;

    /** Window half-width of the deep search: the whole loaded area of any island. */
    private static final int DEEP_RANGE = 1_024;

    /**
     * Memory guard, not a work limit: the deep search is bounded by its time, but every expanded node
     * is an object until the search ends. 1.5 million is roughly the most a minute of 1 ms slices
     * expands; past it the search ends as if the time ran out.
     */
    private static final int DEEP_MAX_NODES = 1_500_000;

    /** A deep search for the same goal is not repeated within this time, wherever the player goes. */
    private static final long DEEP_RETRY_MS = 5 * 60_000L;

    /** On a cached deep route, no progress for this long while on it means it is blocked. */
    private static final long BLOCKED_MS = 20_000L;

    /** Completion summaries for the same route are not logged more often than this. */
    private static final long SUMMARY_LOG_MS = 30_000L;
    private static final Map<String, Long> SUMMARY_LOGGED = new HashMap<>();

    /** How a route's search is currently running. */
    enum Phase { ASTAR, HYBRID, CACHED, DEEP }

    /** What the HUD list and the marker say about a route. */
    public enum State {
        SEARCHING, DEEP_SEARCH, ROUTE, PARTIAL, NO_ROUTE, ARRIVED, STALE, PAUSED
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

    // ---- phases (see the class note) ----
    private Terrain terrain;
    private int maxNodes;
    private int maxFall;
    private List<JumpPad> pads = List.of();
    private Phase phase;
    private HybridPlanner hybrid;
    /** The deep search, or the A* stitching the player onto a cached deep route. */
    private PathfinderTask deep;
    private boolean hybridTried;
    private long searchStartNanos;
    private long phaseStartNanos;
    private double startDistance;
    private long switchBudgetMs;
    private int phaseNodes;
    private final Map<Phase, long[]> phaseTotals = new HashMap<>();   // ms, nodes
    private int openSegments;
    private int fineSegments;
    private String lastSwitch = "";
    private double deepProgress;
    private boolean deepExhausted;
    private boolean unverified;
    private final Map<String, Long> deepTried = new HashMap<>();
    private List<BlockPos> cachedRoute;
    private String cachedKey;
    private boolean fromDeepCache;
    private long blockedSince;
    private double blockedLength;

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
        return task != null || hybrid != null || deep != null;
    }

    /** The last phase switch, as logged ("switch astar->hybrid d=143 blocks ..."), or empty. */
    public String lastSwitch() {
        return lastSwitch;
    }

    /** Deep search progress 0..1 (share of its time limit used), while one runs. */
    public double deepProgress() {
        return deepProgress;
    }

    /** Whether the deep search searched everything reachable and found no way. */
    public boolean deepExhausted() {
        return deepExhausted;
    }

    /**
     * Whether the route crosses terrain the Far Terrain module served from memory rather than the
     * server sent live - it may be stale.
     */
    public boolean unverified() {
        return unverified;
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
        if (deep != null && phase == Phase.DEEP) {
            return State.DEEP_SEARCH;
        }
        if (searching() && path.isEmpty()) {
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
            cancel();   // nothing to search for; the drawn path is what the grace keeps
        }
    }

    /** Whether the grace has run out and the route should be dropped. */
    boolean expired(long now) {
        return staleSince >= 0 && now - staleSince >= GRACE_MS;
    }

    void pause(boolean paused) {
        if (paused && !this.paused) {
            cancel();
        }
        this.paused = paused;
    }

    /** Drops the path and forces a fresh search, keeping the route itself. */
    void reset() {
        cancel();
        deepExhausted = false;
        unverified = false;
        fromDeepCache = false;
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

    /** Stops every running search; the published path stays. Also the player-cancel path. */
    void cancel() {
        task = null;
        hybrid = null;
        deep = null;
        phase = null;
        deepProgress = 0;
    }

    /**
     * Spends up to {@code budget} nodes on the running search.
     *
     * @return the nodes it was allowed, for the manager's calibration; 0 when nothing ran - and for
     *         the deep search, which runs on its own small time slice, not the shared node budget
     */
    int step(int budget) {
        if (task != null) {
            stepAstar(task, budget);
            return budget;
        }
        if (hybrid != null) {
            stepHybrid(budget);
            return budget;
        }
        if (deep != null) {
            if (phase == Phase.CACHED) {
                stepCached(budget);
                return budget;
            }
            stepDeep();
        }
        return 0;
    }

    private void stepAstar(PathfinderTask running, int budget) {
        int before = running.expanded();
        boolean done = running.step(budget);
        phaseNodes += running.expanded() - before;
        if (!done) {
            if (hybridEligible() && !hybridTried && SwitchBudget.shouldSwitch(elapsedMs(phaseStartNanos),
                    switchBudgetMs, startDistance, running.bestDistance())) {
                logSwitch("astar", "hybrid", running.bestDistance());
                task = null;
                startHybrid();
            }
            return;
        }
        task = null;
        if (running.reachedGoal()) {
            publish(running);
            complete("astar");
            return;
        }
        if (hybridEligible() && !hybridTried) {
            logSwitch("astar", "hybrid", running.bestDistance());
            publish(running);   // the partial path stays drawn meanwhile
            startHybrid();
            return;
        }
        if (deepWanted(running)) {
            publish(running);
            startDeepOrCached("astar", running.bestDistance());
            return;
        }
        publish(running);
        complete("astar");
    }

    private void stepHybrid(int budget) {
        HybridPlanner running = hybrid;
        int before = running.nodes();
        boolean done = running.step(budget);
        phaseNodes += running.nodes() - before;
        if (!done) {
            return;
        }
        hybrid = null;
        openSegments = running.openSegments();
        fineSegments = running.fineSegments();
        if (running.reachedGoal() || !cfg().deepSearch || !deepAllowed()) {
            publishPath(running.path(), running.teleportNodes(), running.padNodes(), running.sneakNodes(),
                    running.reachedGoal(), false);
            complete("hybrid");
            return;
        }
        publishPath(running.path(), running.teleportNodes(), running.padNodes(), running.sneakNodes(),
                false, false);
        startDeepOrCached("hybrid", running.remaining());
    }

    /** The A* that joins the player onto a cached deep route, then the cached rest of it. */
    private void stepCached(int budget) {
        PathfinderTask running = deep;
        int before = running.expanded();
        boolean done = running.step(budget);
        phaseNodes += running.expanded() - before;
        if (!done) {
            return;
        }
        deep = null;
        BlockPos joined = running.reachedGoalPos();
        int at = joined == null ? -1 : cachedRoute.indexOf(joined);
        if (!running.reachedGoal() || at < 0) {
            startDeep("cached", running.bestDistance());   // could not reach the cached route
            return;
        }
        List<BlockPos> stitched = new ArrayList<>(running.path());
        stitched.addAll(cachedRoute.subList(at + 1, cachedRoute.size()));
        publishPath(HybridPlanner.withoutLoops(stitched), running.teleportNodes(), running.padNodes(),
                running.sneakNodes(), true, false);
        fromDeepCache = true;
        blockedSince = System.currentTimeMillis();
        blockedLength = length();
        unverified = crossesServedTerrain(path);
        complete("cached");
    }

    private void stepDeep() {
        PathfinderTask running = deep;
        long sliceStart = System.nanoTime();
        int before = running.expanded();
        boolean done = false;
        while (System.nanoTime() - sliceStart < DEEP_SLICE_NANOS) {
            if (running.step(256)) {
                done = true;
                break;
            }
        }
        phaseNodes += running.expanded() - before;
        long limitMs = Math.max(10, cfg().deepSearchMaxSeconds) * 1000L;
        long elapsed = elapsedMs(phaseStartNanos);
        deepProgress = Math.min(1.0, (double) elapsed / limitMs);
        if (!done && elapsed < limitMs) {
            return;
        }
        boolean timedOut = !done;
        if (timedOut) {
            running.stop();
        }
        deep = null;
        deepProgress = 0;
        if (running.reachedGoal()) {
            publish(running);
            unverified = crossesServedTerrain(path);
            String key = cacheKey();
            if (key != null) {
                DeepRouteCache.getInstance().put(key, path);
            }
            complete("deep");
            return;
        }
        if (running.exhausted() && !running.hitRangeLimit()) {
            deepExhausted = true;   // everything reachable searched: say so, draw nothing
            publishPath(List.of(), Set.of(), Set.of(), Set.of(), false, true);
            complete("deep");
            return;
        }
        // Out of time (or memory guard): keep whichever partial is longer-reaching to draw.
        if (!running.path().isEmpty()) {
            publish(running);
        }
        lastSwitch = lastSwitch + (lastSwitch.isEmpty() ? "" : " | ") + "deep search stopped after "
                + elapsed / 1000 + " s";
        complete("deep");
    }

    private boolean hybridEligible() {
        return cfg().fastOpenTerrain && mobility != null && mobility.mode() == PathMode.WALK && goals.size() == 1;
    }

    /** Budget spent, or exhausted inside a window too small to prove anything - and not tried lately. */
    private boolean deepWanted(PathfinderTask finished) {
        if (!cfg().deepSearch || !deepAllowed()) {
            return false;
        }
        return !finished.exhausted() || finished.hitRangeLimit();
    }

    private boolean deepAllowed() {
        if (mobility == null || mobility.mode() != PathMode.WALK) {
            return false;
        }
        Long last = deepTried.get(goalsKey());
        return last == null || System.currentTimeMillis() - last > DEEP_RETRY_MS;
    }

    private void startHybrid() {
        hybridTried = true;
        endPhase();
        phase = Phase.HYBRID;
        hybrid = new HybridPlanner(terrain, searchedFrom, goals.getFirst().pos(), maxNodes, mobility, maxFall, pads);
    }

    /** A cached deep route for one of the goals, joined by a normal A*; else a real deep search. */
    private void startDeepOrCached(String from, double remaining) {
        for (Waypoint goal : goals) {
            String key = DeepRouteCache.key(sbs.modid.client.core.location.SkyBlockLocation.island(), goal);
            List<BlockPos> cached = DeepRouteCache.getInstance().get(key);
            if (cached == null) {
                continue;
            }
            cachedRoute = cached;
            cachedKey = key;
            List<BlockPos> joinPoints = new ArrayList<>();
            int stride = Math.max(1, cached.size() / 64);
            for (int i = 0; i < cached.size(); i += stride) {
                joinPoints.add(cached.get(i));
            }
            logSwitch(from, "cached", remaining);
            endPhase();
            phase = Phase.CACHED;
            deep = new PathfinderTask(terrain, searchedFrom, joinPoints, maxNodes, mobility, maxFall, pads);
            return;
        }
        startDeep(from, remaining);
    }

    private void startDeep(String from, double remaining) {
        deepTried.put(goalsKey(), System.currentTimeMillis());
        logSwitch(from, "deep", remaining);
        endPhase();
        phase = Phase.DEEP;
        deepProgress = 0;
        List<BlockPos> positions = new ArrayList<>(goals.size());
        for (Waypoint waypoint : goals) {
            positions.add(waypoint.pos());
        }
        deep = new PathfinderTask(terrain, searchedFrom, positions, DEEP_MAX_NODES, mobility, maxFall, pads,
                DEEP_RANGE, BreakableWalls.NONE);
    }

    /** Publishes a finished A* task's result, resolving the target for a goal set. */
    private void publish(PathfinderTask finished) {
        publishPath(finished.path(), finished.teleportNodes(), finished.padNodes(), finished.sneakNodes(),
                finished.reachedGoal(), finished.noRoute());
        if (goals.size() != 1) {
            target = resolveReached(finished.reachedGoalPos());
        }
    }

    private void publishPath(List<BlockPos> newPath, Set<BlockPos> hops, Set<BlockPos> landings,
                             Set<BlockPos> crouched, boolean reached, boolean proven) {
        path = newPath;
        teleports = hops;
        padLandings = landings;
        sneaks = crouched;
        reachedGoal = reached;
        noRoute = proven;
        if (noRoute) {
            noRouteFrom = searchedFrom;
            noRoutePads = JumpPads.getInstance().generation();
        }
        lastComputed = System.currentTimeMillis();
    }

    /** Closes the running phase's totals. */
    private void endPhase() {
        if (phase != null) {
            long[] totals = phaseTotals.computeIfAbsent(phase, p -> new long[2]);
            totals[0] += elapsedMs(phaseStartNanos);
            totals[1] += phaseNodes;
        }
        phaseNodes = 0;
        phaseStartNanos = System.nanoTime();
    }

    /** One line per switch; kept for the route HUD ({@link #lastSwitch()}). */
    private void logSwitch(String from, String to, double remaining) {
        double distance = goals.isEmpty() ? 0 : HybridPlanner.horizontal(searchedFrom, goals.getFirst().pos());
        long elapsed = elapsedMs(phaseStartNanos);
        int progress = (int) Math.round(100 * SwitchBudget.progress(startDistance, remaining));
        lastSwitch = String.format(Locale.ROOT,
                "switch %s->%s d=%d blocks budget=%d ms elapsed=%d ms nodes=%d progress=%d%% (remaining %d blocks)",
                from, to, Math.round(distance), switchBudgetMs, elapsed, phaseNodes, progress, Math.round(remaining));
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Path] {}", lastSwitch);
    }

    /** The route is done: the per-phase totals, once per route (throttled). */
    private void complete(String mode) {
        endPhase();
        phase = null;
        String key = source + ">" + goalsKey();
        long now = System.currentTimeMillis();
        Long last = SUMMARY_LOGGED.get(key);
        if (last != null && now - last < SUMMARY_LOG_MS) {
            return;
        }
        SUMMARY_LOGGED.put(key, now);
        long totalMs = elapsedMs(searchStartNanos);
        long totalNodes = 0;
        StringBuilder phases = new StringBuilder();
        for (Phase each : Phase.values()) {
            long[] totals = phaseTotals.get(each);
            if (totals == null) {
                continue;
            }
            totalNodes += totals[1];
            phases.append(' ').append(each.name().toLowerCase(Locale.ROOT)).append('=')
                    .append(totals[0]).append("ms/").append(totals[1]).append('n');
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Path] mode={} ms={} nodes={} segments open={} fine={} reached={} |{}",
                mode, totalMs, totalNodes, openSegments, fineSegments, reachedGoal, phases);
    }

    private String goalsKey() {
        StringBuilder key = new StringBuilder();
        for (Waypoint goal : goals) {
            key.append(goal.x).append(',').append(goal.y).append(',').append(goal.z).append(';');
        }
        return key.toString();
    }

    /** The deep-route cache key for the goal this route reached, or {@code null}. */
    private String cacheKey() {
        Waypoint reached = goals.size() == 1 ? goals.getFirst() : target;
        return reached == null ? null
                : DeepRouteCache.key(sbs.modid.client.core.location.SkyBlockLocation.island(), reached);
    }

    /**
     * Whether any node lies in a chunk the Far Terrain module served from memory: outside the live
     * radius around the player while it is active. An approximation - the module keeps no per-chunk
     * "served" flag - that errs towards "unverified".
     */
    private static boolean crossesServedTerrain(List<BlockPos> nodes) {
        if (!sbs.modid.client.helper.terrain.FarTerrainManager.active()) {
            return false;
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        int live = sbs.modid.client.helper.terrain.FarTerrainManager.liveRadius();
        int px = minecraft.player.blockPosition().getX() >> 4;
        int pz = minecraft.player.blockPosition().getZ() >> 4;
        for (BlockPos node : nodes) {
            if (Math.max(Math.abs((node.getX() >> 4) - px), Math.abs((node.getZ() >> 4) - pz)) > live) {
                return true;
            }
        }
        return false;
    }

    /**
     * A cached deep route that stops making progress while the player is on it is blocked on the
     * ground (a door shut, a block placed): drop it from the cache and search afresh.
     */
    void checkBlocked(Vec3 playerPos, long now) {
        if (!fromDeepCache || searching() || path.size() < 2) {
            return;
        }
        double remaining = length();
        if (remaining < blockedLength - 2) {
            blockedLength = remaining;
            blockedSince = now;
            return;
        }
        if (now - blockedSince > BLOCKED_MS && isOnPath(playerPos, 2, 3)) {
            if (cachedKey != null) {
                DeepRouteCache.getInstance().invalidate(cachedKey);
            }
            deepTried.remove(goalsKey());
            reset();
        }
    }

    private static long elapsedMs(long since) {
        return (System.nanoTime() - since) / 1_000_000L;
    }

    private static sbs.modid.client.core.config.SBSConfig.PathfindingSettings cfg() {
        return sbs.modid.client.core.config.ConfigManager.getInstance().get().pathfinding;
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
        // A deep search can run for a minute: a changed target ends it rather than waiting it out.
        if (phase == Phase.DEEP && !sameGoals(current, goals)) {
            cancel();
        }
        if (searching()) {
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
        this.terrain = Terrain.of(level);
        this.maxNodes = maxNodes;
        this.maxFall = maxFall;
        this.pads = pads == null ? List.of() : pads;
        cancel();
        hybridTried = false;
        deepExhausted = false;
        unverified = false;
        fromDeepCache = false;
        phaseTotals.clear();
        openSegments = 0;
        fineSegments = 0;
        lastSwitch = "";
        searchStartNanos = System.nanoTime();
        phaseStartNanos = searchStartNanos;
        phaseNodes = 0;
        double distance = HybridPlanner.horizontal(from, positions.getFirst());
        switchBudgetMs = SwitchBudget.budgetMs(distance, cfg().switchBaseMs, cfg().switchPerBlockMs);
        BlockPos goal = positions.getFirst();
        if (hybridEligible() && distance > HYBRID_DIRECT_DISTANCE
                && terrain.openSky(from.getX(), from.getY() + 1, from.getZ())
                && terrain.openSky(goal.getX(), goal.getY() + 1, goal.getZ())) {
            startDistance = distance;
            startHybrid();   // both ends open and far apart: no point letting A* try first
            return;
        }
        phase = Phase.ASTAR;
        task = new PathfinderTask(terrain, from, positions, maxNodes, now, maxFall, this.pads);
        startDistance = task.bestDistance();
    }
}
