/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the pathfinder: keeps <b>one route per source</b> ({@link RouteSource} → {@link Route}),
 * feeds their searches a shared slice of work each client tick, and publishes the finished paths for
 * the renderer.
 *
 * <p><b>Why one route per source.</b> This used to hold exactly one path, and {@link PathRouting}
 * chose which source got it. A higher source losing its target for a frame handed the path to the
 * next one and took it back - the fairy soul / "Bartender" snapping. Now every switched-on source
 * has its own route, drawn in its own colour; priority only picks the {@linkplain #primary() primary}
 * (on top, and what the single-path API below answers), which is searched first when the budget is
 * tight, and which is paused first under the route cap. A source that loses its goals keeps its route
 * for {@link Route#GRACE_MS} before it goes.
 *
 * <p><b>When a route recomputes</b> is unchanged, per route: a different goal set, the player leaving
 * the path, a mobility change, or the periodic refresh - see {@link Route#needsRecompute}.
 *
 * <p><b>Cost.</b> All searches together spend the one calibrated budget the single route had
 * ({@link #TIME_BUDGET_NANOS} per tick), split by {@link RoutePlanner#slices}; at most one new search
 * starts per tick, so switching four sources on does not start four searches in the same frame. With
 * nothing routed {@link #tick} returns immediately.
 *
 * <p>All state is touched from the client thread only ({@code Minecraft.tick} and the render hook,
 * which do not overlap), so no locking is needed.
 */
public final class PathfindingManager {

    // NOTE: constants before INSTANCE - a singleton above them would read them as 0/null.

    /**
     * How much of a tick all searches together may spend. A time, not a node count: the cost of
     * expanding a node varies by an order of magnitude between machines, so the node budget is
     * calibrated to fit this.
     */
    private static final long TIME_BUDGET_NANOS = 2_000_000L;   // 2 ms

    /** Clamps on the calibrated budget - see {@link #calibrate}. */
    private static final int MIN_NODE_BUDGET = 200;
    private static final int MAX_NODE_BUDGET = 40_000;

    /** Starting guess before anything has been measured; the old fixed value. */
    private static final int INITIAL_NODE_BUDGET = 3_000;

    /** How fast the budget follows a measurement (0-1); slow, so one GC pause only nudges it. */
    private static final double CALIBRATION_RATE = 0.25;

    private static final PathfindingManager INSTANCE = new PathfindingManager();

    /** The live, hardware-calibrated node budget for one tick, shared by every search. */
    private int nodeBudget = INITIAL_NODE_BUDGET;

    /** Every source's route, including paused and stale ones. Iterates in priority order. */
    private final EnumMap<RouteSource, Route> routes = new EnumMap<>(RouteSource.class);

    /** The goal sets from the last {@link #sync}. */
    private Map<RouteSource, List<Waypoint>> lastGoals = Map.of();

    /** The highest-priority running route, or {@code null}. */
    private Route primary;

    /** Nanoseconds the searches took last tick - the readout for the frame-time check. */
    private long lastTickNanos;

    /** The route cap; a field so the tests can set it without a config. */
    private int capOverride = -1;

    PathfindingManager() {
    }

    public static PathfindingManager getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.PathfindingSettings cfg() {
        return ConfigManager.getInstance().get().pathfinding;
    }

    // ------------------------------------------------------------------
    // Published state
    // ------------------------------------------------------------------

    /** Every live route, highest priority first. Paused and stale ones included. */
    public List<Route> routes() {
        return new ArrayList<>(routes.values());
    }

    /** The route of one source, or {@code null} when it has none. */
    public Route route(RouteSource source) {
        return routes.get(source);
    }

    /** The primary route - highest priority among the running ones - or {@code null}. */
    public Route primary() {
        return primary;
    }

    /** The waypoint {@code source}'s route leads to, or {@code null}. */
    public Waypoint target(RouteSource source) {
        Route route = routes.get(source);
        return route == null ? null : route.target();
    }

    // The single-path API: answers for the primary route, for callers that want one answer.

    /** The primary route's path, or an empty list. */
    public List<BlockPos> path() {
        return primary == null ? List.of() : primary.path();
    }

    /** The primary route's teleport landings - see {@link PathfinderTask#teleportNodes}. */
    public Set<BlockPos> teleportNodes() {
        return primary == null ? Set.of() : primary.teleportNodes();
    }

    /** The waypoint the primary route leads to, or {@code null}. */
    public Waypoint target() {
        return primary == null ? null : primary.target();
    }

    /** The primary route's jump-pad landings. */
    public Set<BlockPos> padNodes() {
        return primary == null ? Set.of() : primary.padNodes();
    }

    /** Whether the primary route proved there is no known way to its goal. */
    public boolean noRoute() {
        return primary != null && primary.noRoute();
    }

    /** Whether the primary route actually reached its waypoint (false = partial). */
    public boolean reachedGoal() {
        return primary != null && primary.reachedGoal();
    }

    /** Whether any search is running. */
    public boolean searching() {
        for (Route route : routes.values()) {
            if (route.searching()) {
                return true;
            }
        }
        return false;
    }

    /** Whether any route has a path to draw - the renderer's cheap early-out. */
    public boolean hasAnyPath() {
        for (Route route : routes.values()) {
            if (!route.paused() && !route.path().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The mobility the primary route assumes, or {@code null}. */
    public PlayerMobility.Mobility mobility() {
        return primary == null ? null : primary.mobility();
    }

    /** Search time all routes spent last tick, in nanoseconds. */
    public long lastTickNanos() {
        return lastTickNanos;
    }

    /** The live node budget, for the settings page / debug output. */
    public int nodeBudget() {
        return nodeBudget;
    }

    /** Drops every cached path and forces fresh searches (waypoints, world or settings changed). */
    public void invalidate() {
        for (Route route : routes.values()) {
            route.reset();
        }
        // A settings change reaches the pathfinder through here, and the teleport capability is
        // cached for a moment - without this the re-search would run against the old answer.
        Transmission.refresh();
    }

    private void clear() {
        routes.clear();
        lastGoals = Map.of();
        primary = null;
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /** Called once per client tick from {@code GuiTrackingMixin}. */
    public void tick(Minecraft minecraft) {
        lastTickNanos = 0;
        if (!PathRouting.routing()) {
            if (!routes.isEmpty()) {
                clear();
            }
            return;
        }
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            clear();
            return;
        }
        sync(PathRouting.goals(player.blockPosition()), System.currentTimeMillis());
        plan();

        stepSearches();

        // Maintain the rest; start at most one new search per tick so they stagger.
        Vec3 position = player.position();
        BlockPos block = player.blockPosition();
        PlayerMobility.Mobility mobility = PlayerMobility.of(player);
        boolean startedOne = false;
        for (Route route : routes.values()) {
            if (route.paused() || route.stale() || route.searching()) {
                continue;
            }
            List<Waypoint> current = lastGoals.getOrDefault(route.source(), List.of());
            if (current.isEmpty()) {
                continue;
            }
            // Walking the route consumes it rather than dragging a tail behind the player.
            route.trimWalkedPart(position);
            route.checkBlocked(position, System.currentTimeMillis());
            if (!startedOne && route.needsRecompute(block, position, current, mobility, tolerance(),
                    verticalTolerance(mobility))) {
                route.startSearch(level, searchStart(player, level, mobility), current, mobility,
                        cfg().maxNodes, cfg().maxFall, JumpPads.getInstance().forIsland(
                                sbs.modid.client.core.location.SkyBlockLocation.island()));
                startedOne = true;
            }
        }
    }

    /** Advances every running search, in priority order, within the one shared budget. */
    private void stepSearches() {
        List<Route> running = new ArrayList<>();
        for (Route route : routes.values()) {
            if (!route.paused() && route.searching()) {
                running.add(route);
            }
        }
        if (running.isEmpty()) {
            return;
        }
        int[] slices = RoutePlanner.slices(nodeBudget, running.size());
        long started = System.nanoTime();
        int spent = 0;
        for (int i = 0; i < running.size(); i++) {
            spent += running.get(i).step(slices[i]);
        }
        long elapsed = System.nanoTime() - started;
        lastTickNanos = elapsed;
        calibrate(elapsed, spent);
    }

    /**
     * Brings the route map in line with this tick's goal sets: a source switched off loses its route
     * at once, one that lost its goals goes stale and is dropped after the grace, a new one gets a
     * route. Package-private for the tests.
     */
    void sync(Map<RouteSource, List<Waypoint>> goals, long now) {
        lastGoals = goals;
        routes.keySet().removeIf(source -> !goals.containsKey(source));
        for (Map.Entry<RouteSource, List<Waypoint>> entry : goals.entrySet()) {
            Route route = routes.get(entry.getKey());
            if (entry.getValue().isEmpty()) {
                if (route != null) {
                    route.missing(now);
                    if (route.expired(now)) {
                        routes.remove(entry.getKey());
                    }
                }
                continue;
            }
            if (route == null) {
                route = new Route(entry.getKey());
                routes.put(entry.getKey(), route);
            }
            route.seen(entry.getValue());
        }
    }

    /** Applies the route cap (lowest priority paused first) and picks the primary. */
    void plan() {
        List<RouteSource> admitted = RoutePlanner.admit(routes.keySet(), maxRoutes());
        List<RouteSource> running = new ArrayList<>();
        for (Route route : routes.values()) {
            boolean paused = !admitted.contains(route.source());
            route.pause(paused);
            if (!paused) {
                running.add(route.source());
            }
        }
        // Stale routes can be primary: they are still drawn, and the grace exists precisely so the
        // primary does not hop to another source for the frames an objective line flickers.
        RouteSource source = RoutePlanner.primary(running);
        primary = source == null ? null : routes.get(source);
    }

    /** For the tests: the cap without a config. */
    void capForTest(int cap) {
        capOverride = cap;
    }

    /** The route cap from the settings, never below one. */
    private int maxRoutes() {
        return Math.max(1, capOverride > 0 ? capOverride : cfg().maxRoutes);
    }

    /**
     * Moves {@link #nodeBudget} toward the node count that fits in {@link #TIME_BUDGET_NANOS}, from
     * how long {@code nodes} nodes' worth of slices actually took. A slice that finished early is
     * counted as if spent in full, which only ever makes the estimate more conservative.
     */
    private void calibrate(long elapsedNanos, int nodes) {
        if (elapsedNanos <= 0 || nodes <= 0) {
            return;
        }
        double perNode = (double) elapsedNanos / nodes;
        double fits = TIME_BUDGET_NANOS / perNode;
        double moved = nodeBudget + (fits - nodeBudget) * CALIBRATION_RATE;
        nodeBudget = (int) Math.max(MIN_NODE_BUDGET, Math.min(MAX_NODE_BUDGET, Math.round(moved)));
    }

    /** Vertical slack: whatever the player can clear in one move, plus room for the arc itself. */
    private static double verticalTolerance(PlayerMobility.Mobility mobility) {
        return Math.max(mobility.maxStepUp(), cfg().maxFall) + 2.0;
    }

    /** How far off the route the player may stray before it is re-searched, in blocks. */
    private static double tolerance() {
        return Math.max(1, cfg().pathTolerance);
    }

    /**
     * Where the search should be rooted: flying, the player's own position; walking, the ground they
     * are about to land on - mid-jump their block has no floor and a walking search rooted there dies
     * instantly, leaving no path at all.
     */
    private static BlockPos searchStart(Player player, Level level, PlayerMobility.Mobility mobility) {
        BlockPos pos = player.blockPosition();
        if (mobility.mode() == PathMode.FLY) {
            return pos;
        }
        BlockPos ground = Walkability.groundBelow(level, pos);
        return ground != null ? ground : pos;
    }
}
