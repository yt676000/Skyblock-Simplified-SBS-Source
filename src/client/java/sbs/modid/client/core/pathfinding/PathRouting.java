/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.core.BlockPos;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.dev.DevMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides <b>whether</b> to route and <b>to what</b> – the one place that knows the pathfinder now
 * has two callers with different rules.
 *
 * <ul>
 *   <li><b>Quest Guide</b> – a shipped feature. Routes to the current objective, and only that: a
 *       quest waypoint always wins, because "walk me to my objective" must not be hijacked by a
 *       personal marker that happens to be closer.</li>
 *   <li><b>SkyBlock map</b> – a shipped feature. Routes to the place you clicked on the map, and
 *       outranks everything: it is the most recent thing the player explicitly asked for, and it
 *       clears itself on arrival.</li>
 *   <li><b>Fairy Souls</b> and <b>dungeon secrets</b> – shipped features, and the two that route to a
 *       <i>set</i>: every uncollected candidate is handed over at once and one multi-goal search
 *       decides which is nearest by route. See {@link #goals}.</li>
 *   <li><b>Hideyho finder</b> – a shipped feature, and the third that routes to a <i>set</i>: the
 *       hiding places still unchecked this round, published occluded because that feature is a walk
 *       between places rather than an alert about something behind cover.</li>
 *   <li><b>Developer waypoints</b> – still dev-gated while the module is being tested. Routes to the
 *       nearest one.</li>
 * </ul>
 *
 * <p>Splitting this out of {@link PathfindingManager} keeps the gate honest: without it the manager
 * would need a dev-mode check that the Quest Guide has to defeat, which is how a "dev only" feature
 * accidentally ships.
 */
public final class PathRouting {

    private PathRouting() {
    }

    private static SBSConfig cfg() {
        return ConfigManager.getInstance().get();
    }

    /** Whether the Quest Guide currently wants its objective routed. */
    public static boolean questRouting() {
        SBSConfig config = cfg();
        return config.questGuide.enabled && config.questGuide.showWaypoint;
    }

    /** Whether the developer waypoint pathfinding is on. */
    public static boolean devRouting() {
        // DEV-ONLY: personal dev waypoints/routes; player sources have their own terms
        return DevMode.ACTIVE && cfg().pathfinding.renderPaths;
    }

    /** Whether anything should be routed at all. */
    public static boolean routing() {
        return questRouting() || devRouting() || mapRouting() || objectiveRouting() || fairySoulRouting()
                || secretRouting() || commissionRouting() || hideyhoRouting() || npcRouting();
    }

    /**
     * Whether the Recipe Viewer's "path to this NPC" wants a route. The NPC marker exists only while
     * the player asked for the path and stands on the NPC's island (NpcLocator), so its presence is
     * the area term; the setting is checked as well so switching it off drops the route at once.
     */
    public static boolean npcRouting() {
        return cfg().recipeViewer.npcPathfinding && WaypointStore.hasSource(Waypoint.SOURCE_NPC);
    }

    /**
     * Whether the Commission Helper wants a route to the place its commissions are done.
     *
     * <p>Nested under the Mining Helpers master toggle rather than standing alone: the route is one
     * more thing the commission card does, not a feature of its own, so switching the card off takes
     * the route with it.
     */
    public static boolean commissionRouting() {
        var mining = cfg().miningHelpers;
        return mining.enabled && mining.commissions && mining.commissionRoute;
    }

    /**
     * Whether the Hideyho finder wants a route to the next hiding spot it has published.
     *
     * <p>Two terms, not one: the markers are the feature and the route is the half a player may not
     * want, exactly as {@code pathfind} is separable from the markers for Fairy Souls. With routing
     * off the spots still stand and the walk is the player's own.
     */
    public static boolean hideyhoRouting() {
        var hideyho = cfg().hideyho;
        return hideyho.enabled && hideyho.pathfind;
    }

    /**
     * Whether waypoints should be drawn <b>by this renderer</b>.
     *
     * <p>Fairy Souls are deliberately absent: there can be dozens on an island and they need
     * collected/uncollected styling, so they are drawn by their own renderer. Their routed target is
     * still a waypoint here, which is what gives it the marker and the route.
     */
    public static boolean drawingWaypoints() {
        // DEV-ONLY: personal dev waypoints/routes; player sources have their own terms
        return (DevMode.ACTIVE && cfg().pathfinding.renderWaypoints) || questRouting()
                || objectiveMarking() || mapMarking() || commissionRouting() || publishedMarking();
    }

    /**
     * Whether a publisher-owned marker set has anything out right now - the shipped location presets
     * or the Critter Safari's Gemzie spots.
     *
     * <p>Asked of the store rather than of either module, so {@code core} keeps not depending on a
     * feature package. There is no config term because there is nothing here a config could add:
     * each publisher emits a point only when its own toggle is on and its own area is underfoot, so
     * "something is published" already means "the player asked for this".
     */
    private static boolean publishedMarking() {
        return WaypointStore.hasSource(Waypoint.SOURCE_PRESET)
                || WaypointStore.hasSource(Waypoint.SOURCE_GEMZIE)
                // Hideyho spots are the same arrangement with a round as their area term: one exists
                // exactly while the finder has a hide-and-seek running (or the player asked for the
                // spots to stand permanently), inside the Safari.
                || WaypointStore.hasSource(Waypoint.SOURCE_HIDEYHO)
                // Pings are the same arrangement with no area term at all: one exists exactly while
                // the player has pressed the bind in the last few seconds, anywhere in the game.
                || WaypointStore.hasSource(Waypoint.SOURCE_PING)
                // The temple cheese spot exists only while its setting is on, an anchor was taken
                // from a guardian the player could see, and the run still needs the Amethyst.
                || WaypointStore.hasSource(Waypoint.SOURCE_TEMPLE_CHEESE)
                // The Crystal Hollows map's target: published exactly while the player has one
                // picked, and only on the Hollows.
                || WaypointStore.hasSource(Waypoint.SOURCE_CH_MAP)
                // The Recipe Viewer's NPC marker: published only while the player asked for the path
                // to that NPC and is on its island, cleared on arrival or when the setting goes off.
                || WaypointStore.hasSource(Waypoint.SOURCE_NPC)
                // Shared Crystal Hollows structures: only while sharing is on (setting, licence,
                // consent) and the player is in the Hollows; leaving the lobby clears them.
                || WaypointStore.hasSource(Waypoint.SOURCE_HOLLOWS)
                // Diana's three sets: published only while the toolkit is on, in the Hub, with the
                // ritual awake - and cleared the moment any of that stops being true.
                || WaypointStore.hasSource(Waypoint.SOURCE_DIANA_BURROW)
                || WaypointStore.hasSource(Waypoint.SOURCE_DIANA_GUESS)
                || WaypointStore.hasSource(Waypoint.SOURCE_DIANA_CREATURE)
                // The appearance preview's samples: only while it is on and a settings screen is open.
                || WaypointStore.hasSource(Waypoint.SOURCE_DIANA_PREVIEW);
    }

    /**
     * Whether {@code waypoint} comes from a publisher-owned set that is drawn outside dev mode with no
     * further condition - publishing IS the gate (see {@link #publishedMarking()}).
     */
    static boolean publisherOwned(Waypoint waypoint) {
        return waypoint.isPreset() || waypoint.isGemzie() || waypoint.isPing()
                || waypoint.isHideyho() || waypoint.isTempleCheese()
                || waypoint.isHollowsTarget() || waypoint.isDiana() || waypoint.isNpc()
                || waypoint.isHollowsStructure();
    }

    /** Whether the NPC module wants its objective marker shown. */
    private static boolean objectiveMarking() {
        var npc = cfg().npc;
        return npc.objectiveRouting && npc.waypoint;
    }

    /** Whether the NPC module wants to be routed to its objective marker. */
    private static boolean objectiveRouting() {
        var npc = cfg().npc;
        return npc.objectiveRouting && npc.waypoint && npc.pathfinding;
    }

    /** Whether the SkyBlock map wants the place you clicked marked. */
    private static boolean mapMarking() {
        return cfg().map.enabled;
    }

    /** Whether the SkyBlock map wants a route drawn to the place you clicked. */
    private static boolean mapRouting() {
        var map = cfg().map;
        return map.enabled && map.showRoute;
    }

    /** Whether the Fairy Souls module wants its markers drawn. */
    private static boolean fairySoulMarking() {
        return cfg().fairySouls.enabled;
    }

    /** Whether the Fairy Souls module wants a route to the soul it has picked. */
    private static boolean fairySoulRouting() {
        var souls = cfg().fairySouls;
        return souls.enabled && souls.pathfind;
    }

    /** Whether the Secret Routes module wants a route to the room's next uncollected secret. */
    private static boolean secretRouting() {
        var secrets = cfg().secretRoutes;
        return secrets.enabled && secrets.pathfind;
    }

    /**
     * The waypoints that should be visible.
     *
     * <p>Outside dev mode the personal waypoints stay hidden (they are part of the dev module); what
     * shows is the allowlist below - quest, objective and map markers behind their settings, the
     * publisher-owned sets ({@link #publisherOwned}) and commissions.
     */
    public static List<Waypoint> visibleWaypoints() {
        List<Waypoint> inDimension = WaypointStore.inCurrentDimension();
        boolean commissions = commissionRouting();
        // With "mark all" off only the commission actually being routed to is drawn. The whole set is
        // still published - the multi-goal search needs every candidate to be able to say which is
        // nearest by route - so this is a drawing decision made here rather than a smaller set.
        Waypoint routedCommission = commissions && !cfg().miningHelpers.commissionRouteMarkAll
                ? PathfindingManager.getInstance().target(RouteSource.COMMISSIONS) : null;
        boolean onlyRouted = commissions && !cfg().miningHelpers.commissionRouteMarkAll;
        // DEV-ONLY: personal dev waypoints/routes; player sources have their own terms
        if (DevMode.ACTIVE && cfg().pathfinding.renderWaypoints) {
            // Fairy Souls and dungeon secrets are drawn by their own renderers, with styling this one
            // has no concept of; letting the dev list draw them too would double every marker.
            // Commissions are the exception among the candidate sources: they have no renderer of
            // their own and this is the one that gives them their marker, so they stay in the list.
            List<Waypoint> shown = new ArrayList<>(inDimension.size());
            for (Waypoint waypoint : inDimension) {
                // Hideyho spots join commissions as the candidate sets with no renderer of their
                // own: this list is what gives them their marker, so dropping them here would leave
                // a dev-mode player with a route to something invisible.
                if (!waypoint.isRouteCandidate() || waypoint.isHideyho()
                        || showCommission(waypoint, commissions, onlyRouted, routedCommission)) {
                    shown.add(waypoint);
                }
            }
            return shown;
        }
        List<Waypoint> shown = new ArrayList<>();
        boolean quests = questRouting();
        boolean objectives = objectiveMarking();
        boolean map = mapMarking();
        for (Waypoint waypoint : inDimension) {
            if ((quests && waypoint.isQuest()) || (objectives && waypoint.isObjective())
                    || (map && waypoint.isMap())
                    // Publisher-owned points carry no condition here on purpose: publishing IS the
                    // gate. Each publisher only ever emits while its own toggle is on AND its own
                    // area - island, and zone where the group names one - is underfoot, so a point
                    // that exists is one that was asked for.
                    // Re-testing a toggle here would be a second gate that can disagree with the
                    // first - and this list being an allowlist by source is exactly why they were
                    // published and then silently dropped before.
                    || publisherOwned(waypoint)
                    || showCommission(waypoint, commissions, onlyRouted, routedCommission)) {
                shown.add(waypoint);
            }
        }
        return shown;
    }

    /** Whether {@code source} is switched on - its route exists only while this is true. */
    public static boolean routing(RouteSource source) {
        return switch (source) {
            case MAP -> mapRouting();
            case QUEST -> questRouting();
            case OBJECTIVE -> objectiveRouting();
            case NPC -> npcRouting();
            case SECRETS -> secretRouting();
            case HIDEYHO -> hideyhoRouting();
            case FAIRY_SOULS -> fairySoulRouting();
            case COMMISSIONS -> commissionRouting();
            case DEV -> devRouting();
        };
    }

    /**
     * Every switched-on source with its goal set - what the pathfinder searches, one route each.
     *
     * <p>This used to pick <b>one</b> source by priority and hand only that to the one search, so a
     * higher source whose target dropped out for a frame (a flickering objective line, arrival)
     * gave the path to the next source and took it back a moment later - the snapping between a fairy
     * soul and the "Bartender" objective. Now every source gets its own route and priority only
     * decides the primary; see {@link RouteSource}.
     *
     * <p>A switched-on source with nothing to route maps to an empty list rather than being left out,
     * because the two mean different things to the manager: absent is "turned off, drop the route
     * now", empty is "lost its target, keep the route for the grace period".
     */
    public static java.util.EnumMap<RouteSource, List<Waypoint>> goals(BlockPos from) {
        java.util.EnumMap<RouteSource, List<Waypoint>> out = new java.util.EnumMap<>(RouteSource.class);
        List<Waypoint> inDimension = WaypointStore.inCurrentDimension();
        for (RouteSource source : RouteSource.values()) {
            if (routing(source)) {
                out.put(source, goalsOf(source, inDimension, from));
            }
        }
        return out;
    }

    /**
     * The goal set of one source. Pure over the waypoint list, so the per-source sets are tested.
     *
     * <ul>
     *   <li>Map, Quest, Objective name one destination. An objective only counts when a walking
     *       route exists; one on another island of the same map is shown, not routed (you warp
     *       there, see Waypoint).</li>
     *   <li>Secrets, Hideyho, Fairy Souls, Commissions are candidate sets: every candidate goes to
     *       one multi-goal search, which decides which is nearest <i>by route</i> - a soul twenty
     *       blocks away through a wall is further than one sixty blocks down a corridor. Unroutable
     *       Hideyho spots (already checked) and commissions (area not loaded) keep their markers but
     *       are not searched for.</li>
     *   <li>Dev routes to the nearest personal waypoint, skipping quest markers, the candidate sets
     *       (picking the straight-line nearest would defeat the multi-goal search), pings (they
     *       move and expire, which would restart the search forever) and Crystal Hollows structure
     *       markers (landmarks, not a place the player asked to go).</li>
     * </ul>
     */
    static List<Waypoint> goalsOf(RouteSource source, List<Waypoint> inDimension, BlockPos from) {
        return switch (source) {
            case MAP -> firstOf(inDimension, Waypoint::isMap);
            case QUEST -> firstOf(inDimension, Waypoint::isQuest);
            case OBJECTIVE -> firstOf(inDimension, waypoint -> waypoint.isObjective() && waypoint.routable);
            case NPC -> firstOf(inDimension, Waypoint::isNpc);
            case SECRETS -> filter(inDimension, Waypoint::isDungeonSecret);
            case HIDEYHO -> filter(inDimension, waypoint -> waypoint.isHideyho() && waypoint.routable);
            case FAIRY_SOULS -> filter(inDimension, Waypoint::isFairySoul);
            case COMMISSIONS -> filter(inDimension, waypoint -> waypoint.isCommission() && waypoint.routable);
            case DEV -> {
                Waypoint nearest = WaypointStore.nearestTo(from, filter(inDimension, waypoint ->
                        !waypoint.isQuest() && !waypoint.isRouteCandidate() && !waypoint.isPing()
                                && !waypoint.isMap() && !waypoint.isObjective() && !waypoint.isNpc()
                                && !waypoint.isHollowsStructure() && !waypoint.isTempleCheese()));
                yield nearest == null ? List.of() : List.of(nearest);
            }
        };
    }

    private static List<Waypoint> firstOf(List<Waypoint> inDimension,
                                          java.util.function.Predicate<Waypoint> accept) {
        for (Waypoint waypoint : inDimension) {
            if (accept.test(waypoint)) {
                return List.of(waypoint);
            }
        }
        return List.of();
    }

    private static List<Waypoint> filter(List<Waypoint> inDimension,
                                         java.util.function.Predicate<Waypoint> accept) {
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint waypoint : inDimension) {
            if (accept.test(waypoint)) {
                out.add(waypoint);
            }
        }
        return out;
    }

    /**
     * Whether a commission marker should be drawn: all of them, or only the one being routed to.
     *
     * <p>Until the first search finishes there is no routed target yet, and drawing nothing at all
     * would read as the feature being broken - so the whole set shows until one has been picked.
     */
    private static boolean showCommission(Waypoint waypoint, boolean commissions, boolean onlyRouted,
                                          Waypoint routed) {
        if (!commissions || !waypoint.isCommission()) {
            return false;
        }
        return !onlyRouted || routed == null || routed == waypoint;
    }

    /**
     * The highest-priority single destination among map, quest and objective - the primary's answer
     * when only single sources are running. Kept pure so the order stays tested.
     */
    static Waypoint pickSingle(List<Waypoint> inDimension, boolean map, boolean quest, boolean objective) {
        boolean[] on = {map, quest, objective};
        RouteSource[] singles = {RouteSource.MAP, RouteSource.QUEST, RouteSource.OBJECTIVE};
        for (int i = 0; i < singles.length; i++) {
            if (on[i]) {
                List<Waypoint> goal = goalsOf(singles[i], inDimension, BlockPos.ZERO);
                if (!goal.isEmpty()) {
                    return goal.getFirst();
                }
            }
        }
        return null;
    }
}
