/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.secretroutes.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.pathfinding.PathfindingManager;
import sbs.modid.client.core.pathfinding.RouteSource;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.dungeons.secretroutes.model.SecretWaypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Decides which secret of the current dungeon room the pathfinder is routing to.
 *
 * <p><b>Modelled on {@code FairySoulRouting}, deliberately.</b> The problem is the same one: several
 * live candidates, any of which would do, and the right answer is whichever is cheapest to actually
 * <i>walk</i> to. So every uncollected secret in the room is offered to the router at once and a
 * single multi-goal search picks between them. Straight-line "nearest" gets this wrong constantly in
 * dungeons, and worse than it does outdoors - a chest four blocks through the wall behind you is a
 * thirty-block walk around, and every room is built out of exactly that shape.
 *
 * <p><b>Retargeting is free.</b> Collecting the routed secret takes it out of the candidate set, the
 * set stops matching what the current path was searched over, and the pathfinder recomputes on its
 * own next tick. There is no "advance to the next secret" code to get wrong, because there is no such
 * step - the same property that makes the Fairy Souls version short.
 *
 * <p><b>Only SECRET points are goals.</b> A route also carries standing, pearl and etherwarp points,
 * and none of those is a destination: they are instructions about <i>how</i> to travel, and two of
 * them are reached by a throw the pathfinder cannot plan and must not pretend to. Routing to the
 * secrets themselves and leaving the movement points to the route renderer keeps each honest.
 *
 * <p>Candidates are published as <b>transient</b> waypoints ({@link WaypointStore#setTransient}), so a
 * dungeon's worth of markers never reaches the config file.
 */
public final class SecretRouting {

    private static final SecretRouting INSTANCE = new SecretRouting();

    /**
     * Signatures of the waypoints the published candidates were built from.
     *
     * <p>Both the room and the position go in, not just the count. A room change swaps the whole set
     * while the count can easily stay the same, and the same route drawn in a differently rotated
     * copy of the room resolves to entirely different world coordinates - two changes that a count
     * or an identity check would both miss, leaving the route pointing at the previous room.
     */
    private List<String> publishedKeys = List.of();

    /** How many candidates were last published, for the status line. */
    private int publishedCount;

    private SecretRouting() {
    }

    public static SecretRouting getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.SecretRoutesSettings cfg() {
        return ConfigManager.getInstance().get().secretRoutes;
    }

    // ------------------------------------------------------------------ state

    /** Whether the module wants secrets routed at all. */
    public static boolean routing() {
        SBSConfig.SecretRoutesSettings cfg = cfg();
        return cfg.enabled && cfg.pathfind;
    }

    /** The secret currently being routed to, or {@code null} when nothing is. */
    public SecretWaypoint currentTarget() {
        Waypoint routed = PathfindingManager.getInstance().target(RouteSource.SECRETS);
        if (routed == null || !routed.isDungeonSecret()) {
            return null;
        }
        for (SecretRoutesManager.RenderWaypoint candidate : candidates()) {
            BlockPos pos = blockOf(candidate);
            if (routed.x == pos.getX() && routed.y == pos.getY() && routed.z == pos.getZ()) {
                return candidate.source();
            }
        }
        return null;
    }

    /** One line for the settings page. */
    public String statusLine() {
        if (!cfg().enabled) {
            return "§7Off";
        }
        if (!cfg().pathfind) {
            return "§7Route points only - routing off";
        }
        SecretWaypoint target = currentTarget();
        if (target != null) {
            return "§aRouting to the nearest " + target.typeLabel().toLowerCase(java.util.Locale.ROOT);
        }
        if (!SecretRoutesManager.getInstance().hasRoom()) {
            return "§7No room detected";
        }
        return publishedCount == 0
                ? "§7No uncollected secrets on this room's route"
                : "§7Choosing from " + publishedCount + " uncollected";
    }

    // ------------------------------------------------------------------ tick

    /**
     * Called every client tick; republishes only when the candidate set would actually differ.
     *
     * <p>Ticked from the client hook rather than from {@link SecretRoutesManager#tick}, which returns
     * early while the module is switched off - and "switched off" is precisely the moment the
     * published candidates have to be taken back, not the moment to stop looking at them.
     */
    public void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || !routing()) {
            if (publishedCount > 0) {
                reset();
            }
            return;
        }
        List<SecretRoutesManager.RenderWaypoint> candidates = candidates();
        if (!samePublished(candidates)) {
            republish(candidates);
        }
    }

    /** World unload, dungeon left, module switched off: drop everything. */
    public void reset() {
        publishedCount = 0;
        publishedKeys = List.of();
        WaypointStore.clearTransient(Waypoint.SOURCE_DUNGEON_SECRET);
    }

    // ------------------------------------------------------------------ candidates

    /**
     * The secrets to offer the router right now.
     *
     * <p>Read straight off {@link SecretRoutesManager#renderWaypoints()} rather than re-derived, so
     * this cannot disagree with what is drawn: that method already applies the enabled flag, hides
     * the secrets collected this visit, and resolves canonical room coordinates onto the rotation the
     * room actually generated with this run. A second implementation of any one of those three would
     * eventually route to a secret the player can see is already taken.
     */
    private List<SecretRoutesManager.RenderWaypoint> candidates() {
        List<SecretRoutesManager.RenderWaypoint> out = new ArrayList<>();
        for (SecretRoutesManager.RenderWaypoint waypoint
                : SecretRoutesManager.getInstance().renderWaypoints()) {
            if (waypoint.source().type == SecretWaypoint.Type.SECRET) {
                out.add(waypoint);
            }
        }
        return leversFirst(out, waypoint -> waypoint.source().subtype);
    }

    /**
     * Levers before everything else: while any uncollected lever is left, only the levers are goals.
     *
     * <p>A lever usually opens the way to a room's chest or item, and routing to that secret first
     * walks the player into a closed wall. The route data does not say <i>which</i> secret a lever
     * unlocks, so the rule is the coarse one - every lever in the room before any other secret.
     * Collecting the last lever empties this filter and the full set comes back, so the order among
     * the other secrets is exactly what it was before.
     */
    static <T> List<T> leversFirst(List<T> candidates, Function<T, SecretWaypoint.Secret> subtype) {
        List<T> levers = new ArrayList<>();
        for (T candidate : candidates) {
            if (subtype.apply(candidate) == SecretWaypoint.Secret.LEVER) {
                levers.add(candidate);
            }
        }
        return levers.isEmpty() ? candidates : levers;
    }

    /** Whether {@code candidates} is exactly what {@link #publishedKeys} was built from, in order. */
    private boolean samePublished(List<SecretRoutesManager.RenderWaypoint> candidates) {
        if (candidates.size() != publishedKeys.size()) {
            return false;
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (!keyOf(candidates.get(i)).equals(publishedKeys.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Rebuilds the transient candidate waypoints. Cheap and idempotent. */
    private void republish(List<SecretRoutesManager.RenderWaypoint> candidates) {
        List<Waypoint> waypoints = new ArrayList<>(candidates.size());
        List<String> keys = new ArrayList<>(candidates.size());
        String dimension = WaypointStore.currentDimension();
        for (SecretRoutesManager.RenderWaypoint candidate : candidates) {
            SecretWaypoint secret = candidate.source();
            Waypoint waypoint = new Waypoint(secret.typeLabel(), blockOf(candidate), dimension,
                    Waypoint.SOURCE_DUNGEON_SECRET);
            // The route renderer already draws these points, with their number, type and note. This
            // waypoint exists to be a goal, so it carries no second label of its own.
            waypoint.showDistance = false;
            waypoints.add(waypoint);
            keys.add(keyOf(candidate));
        }
        WaypointStore.setTransient(Waypoint.SOURCE_DUNGEON_SECRET, waypoints);
        publishedCount = waypoints.size();
        publishedKeys = List.copyOf(keys);
    }

    /** The world block a candidate sits on. */
    private static BlockPos blockOf(SecretRoutesManager.RenderWaypoint candidate) {
        return BlockPos.containing(candidate.world());
    }

    /** Room + order + resolved world position - see {@link #publishedKeys}. */
    private static String keyOf(SecretRoutesManager.RenderWaypoint candidate) {
        BlockPos pos = blockOf(candidate);
        return SecretRoutesManager.getInstance().boundRoomName() + "#" + candidate.source().index
                + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
