/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.PathfindingManager;
import sbs.modid.client.core.pathfinding.RouteSource;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.helper.fairysouls.model.FairySoul;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which Fairy Soul the pathfinder is routing to.
 *
 * <p><b>Auto-nearest is a mode, not a rule.</b> In {@link Mode#AUTO} every uncollected soul on the
 * island is offered to the router at once and a single multi-goal search picks the cheapest by
 * <i>route</i> - which is the entire point: a soul twenty blocks away through a wall is further than
 * one sixty blocks down a corridor, and straight-line "nearest" gets that backwards constantly. In
 * {@link Mode#MANUAL} exactly one chosen soul is offered and nothing re-picks behind the player.
 *
 * <p><b>Retargeting is free.</b> Collecting the routed soul removes it from the candidate set, the
 * set no longer matches what the current path was searched over, and the pathfinder recomputes on
 * its own next tick. There is no special "advance to the next one" path to get wrong.
 *
 * <p>Candidates are published as <b>transient</b> waypoints - see
 * {@link WaypointStore#setTransient} - so an island's worth of markers never reaches the config file.
 */
public final class FairySoulRouting {

    private static final FairySoulRouting INSTANCE = new FairySoulRouting();

    /** How the target is chosen. */
    public enum Mode {
        /** Whichever uncollected soul is cheapest to walk to, re-picked as they are collected. */
        AUTO,
        /** One soul the player chose; never re-picked automatically. */
        MANUAL
    }

    /** The soul chosen in {@link Mode#MANUAL}, or {@code null}. */
    private FairySoul manualTarget;

    /** The island the published candidate set belongs to, so a change is noticed exactly once. */
    private String publishedIsland = "";

    /** How many candidates were last published, for the status line. */
    private int publishedCount;

    /**
     * The soul ids the published waypoints were actually built from.
     *
     * <p>The change detector {@link #tick} needs: souls can stop being candidates without any
     * per-soul event firing at all – see there.
     */
    private List<String> publishedIds = List.of();

    /** The island an "other islands" notice was last shown for, so it is said once per arrival. */
    private String announcedFor = "";

    private FairySoulRouting() {
    }

    public static FairySoulRouting getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FairySoulSettings cfg() {
        return ConfigManager.getInstance().get().fairySouls;
    }

    // ------------------------------------------------------------------ state

    public Mode mode() {
        return manualTarget != null ? Mode.MANUAL : Mode.AUTO;
    }

    /** The soul currently being routed to, or {@code null} when nothing is. */
    public FairySoul currentTarget() {
        if (manualTarget != null) {
            return manualTarget;
        }
        Waypoint routed = PathfindingManager.getInstance().target(RouteSource.FAIRY_SOULS);
        if (routed == null || !routed.isFairySoul()) {
            return null;
        }
        for (FairySoul soul : uncollectedHere()) {
            if (soul.x == routed.x && soul.y == routed.y && soul.z == routed.z) {
                return soul;
            }
        }
        return null;
    }

    /** One line for the settings page and the module card. */
    public String statusLine() {
        if (!cfg().enabled) {
            return "§7Off";
        }
        if (!cfg().pathfind) {
            return "§7Markers only - routing off";
        }
        FairySoul target = currentTarget();
        if (target != null) {
            return (mode() == Mode.MANUAL ? "§bRouting to chosen soul " : "§aRouting to nearest ")
                    + target.id;
        }
        return publishedCount == 0
                ? "§7No uncollected souls known on this island"
                : "§7Choosing from " + publishedCount + " uncollected";
    }

    // ------------------------------------------------------------------ selection

    /** Picks one soul by hand and stops auto-retargeting. */
    public void selectManual(FairySoul soul) {
        manualTarget = soul;
        republish();
        if (soul != null) {
            SBSChat.send(Component.literal(" Routing to Fairy Soul " + soul.summary())
                    .withColor(SBSChat.PREFIX_COLOR));
        }
    }

    /** Returns to auto-nearest. */
    public void clearManual() {
        if (manualTarget != null) {
            manualTarget = null;
            republish();
            SBSChat.send(Component.literal(" Back to the nearest uncollected Fairy Soul.")
                    .withColor(SBSChat.PREFIX_COLOR));
        }
    }

    // ------------------------------------------------------------------ events

    /**
     * A soul was collected. The candidate set is rebuilt, which is all that "auto-retarget" needs -
     * the pathfinder notices the set changed and re-searches by itself.
     */
    public void onSoulCollected(FairySoul soul) {
        if (manualTarget != null && soul != null && manualTarget.id.equals(soul.id)) {
            manualTarget = null;   // the chosen one is done; auto takes over rather than dead-ending
        }
        republish();
    }

    /** The island changed: the old island's coordinates mean nothing here. */
    public void onIslandChanged() {
        manualTarget = null;
        publishedIsland = "";
        republish();
    }

    /** World unload / disconnect: drop everything so nothing survives into the next session. */
    public void reset() {
        manualTarget = null;
        publishedIsland = "";
        publishedCount = 0;
        publishedIds = List.of();
        WaypointStore.clearTransient(Waypoint.SOURCE_FAIRY_SOUL);
    }

    // ------------------------------------------------------------------ tick

    /**
     * Called every client tick; republishes only when the candidate set would actually differ.
     *
     * <p><b>Compared against the souls, not just the island.</b> Watching the island name alone was
     * not the same claim and quietly failed the one case that matters most: reading the Quest Log's
     * Fairy Souls Guide retires <i>whole islands at once</i>
     * ({@link FairySoulStore#markIslandDone}), and it does so without collecting anything, so no
     * per-soul event ever fires. Standing on the island you just learned you had finished, the
     * candidates published minutes ago stayed live and the route kept leading to souls now on record
     * as collected. Diffing the ids ends that for every future source of the same shape - a store
     * edit, a profile switch, a data file gaining souls - none of which have to know this class
     * exists.
     */
    public void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            if (publishedCount > 0) {
                reset();
            }
            return;
        }
        if (!cfg().enabled || !cfg().pathfind) {
            if (publishedCount > 0) {
                reset();
            }
            return;
        }
        // The candidate set is "every soul NOT collected", which on an unloaded record is every soul
        // there is - so routing before the profile is known picks a target from souls the player may
        // long since have found, and publishes a screenful of waypoints to take back a moment later.
        if (!FairySoulStore.getInstance().ready()) {
            if (publishedCount > 0) {
                reset();
            }
            return;
        }
        String island = SkyBlockLocation.island();
        if (!island.equals(publishedIsland)) {
            publishedIsland = island;
            republish();
            return;
        }
        // A hand-picked soul is retired the same way and just as silently, and routing to a soul the
        // player is on record as having collected is exactly as wrong when they chose it themselves.
        if (manualTarget != null
                && FairySoulStore.getInstance().isCollected(manualTarget.id, island)) {
            manualTarget = null;
        }
        if (!samePublished(candidates())) {
            republish();
        }
    }

    /** Whether {@code candidates} is exactly what {@link #publishedIds} was built from, in order. */
    private boolean samePublished(List<FairySoul> candidates) {
        if (candidates.size() != publishedIds.size()) {
            return false;
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (!candidates.get(i).id.equals(publishedIds.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The souls to offer the router right now: the hand-picked one, or every uncollected soul on
     * this island. The single definition, so the change detector and {@link #republish} can never
     * disagree about what is supposed to be published.
     */
    private List<FairySoul> candidates() {
        return manualTarget != null ? List.of(manualTarget) : uncollectedHere();
    }

    /**
     * Rebuilds the transient candidate waypoints.
     *
     * <p>Cheap and idempotent, so every event that could change the answer simply calls it rather
     * than trying to work out a minimal edit.
     */
    private void republish() {
        if (!cfg().enabled || !cfg().pathfind) {
            WaypointStore.clearTransient(Waypoint.SOURCE_FAIRY_SOUL);
            publishedCount = 0;
            publishedIds = List.of();
            return;
        }
        List<FairySoul> candidates = candidates();

        List<Waypoint> waypoints = new ArrayList<>(candidates.size());
        List<String> ids = new ArrayList<>(candidates.size());
        String dimension = WaypointStore.currentDimension();
        for (FairySoul soul : candidates) {
            Waypoint waypoint = new Waypoint("Fairy Soul", soul.pos(), dimension,
                    Waypoint.SOURCE_FAIRY_SOUL);
            waypoint.throughWalls = cfg().throughWalls;
            waypoint.showDistance = cfg().showDistance;
            waypoints.add(waypoint);
            ids.add(soul.id);
        }
        WaypointStore.setTransient(Waypoint.SOURCE_FAIRY_SOUL, waypoints);
        publishedCount = waypoints.size();
        publishedIds = List.copyOf(ids);

        if (waypoints.isEmpty() && !cfg().currentIslandOnly) {
            announceOtherIslands();
        }
    }

    /**
     * With the island restriction off and nothing left here, says where souls actually remain.
     *
     * <p>Routing there needs the inter-island travel edges, which do not exist yet - so this
     * <b>tells</b> rather than routes, once per island change. That is the honest half of the
     * setting: it does something real today and becomes a route when the edges land.
     */
    private void announceOtherIslands() {
        if (announcedFor.equals(publishedIsland)) {
            return;
        }
        announcedFor = publishedIsland;

        FairySoulStore store = FairySoulStore.getInstance();
        List<String> islands = new ArrayList<>();
        for (var soul : FairySoulDatabase.all()) {
            if (soul.island.equals(publishedIsland) || islands.contains(soul.island)
                    || store.isCollected(soul.id, soul.island)) {
                continue;
            }
            islands.add(soul.island);
        }
        if (islands.isEmpty()) {
            return;
        }
        SBSChat.send(Component.literal(" No Fairy Souls left here. Still uncollected on: "
                + String.join(", ", islands)).withColor(SBSChat.WHITE));
    }

    /** Every uncollected soul on the island the player is standing on. */
    public List<FairySoul> uncollectedHere() {
        String island = SkyBlockLocation.island();
        if (island.isEmpty()) {
            return List.of();
        }
        FairySoulStore store = FairySoulStore.getInstance();
        List<FairySoul> out = new ArrayList<>();
        for (FairySoul soul : FairySoulDatabase.forIsland(island)) {
            if (!store.isCollected(soul.id, island)) {
                out.add(soul);
            }
        }
        return out;
    }
}
