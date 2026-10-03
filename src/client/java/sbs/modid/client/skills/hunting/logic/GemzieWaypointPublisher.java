/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.skills.hunting.model.GemzieSpot;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes the known Gemzie spawn spots as transient waypoints while the player is inside the
 * Critter Safari, and clears them the moment they are not.
 *
 * <p><b>Nothing is drawn here.</b> The points go into {@link WaypointStore} and the shared
 * {@code PathRenderer} draws them exactly as it draws the honey tree and beacon markers - box, beam,
 * label and distance. That is the whole reason this class is short: the feature is a publisher, and
 * the mod already owns a waypoint renderer.
 *
 * <p><b>The Safari gate is {@link SafariTracker#inSafariArea()} and nothing else.</b> One configurable
 * word covers the trip summary, the critter highlight and these markers, so a Hypixel rename stays a
 * one-field fix rather than three. Note which half of that class this uses: an unreadable location
 * answers "outside" there, which is the right answer for anything drawn - a marker that outlives the
 * instance it belongs to is a stale marker - and the opposite of what the trip tracker needs.
 *
 * <p><b>Transient, never persisted.</b> Three markers derived from a hard-coded table have no
 * business in {@code config.json}, and {@link WaypointStore#setTransient} swaps the whole set in one
 * call, so a stale marker cannot be left behind.
 *
 * <p><b>Recomputed only when something changed.</b> The tick runs every client tick, so the guard is
 * the point: while the player is inside and the set is already out, this is two field reads and a
 * return. A settings change calls {@link #refresh()} rather than being polled for, which is what
 * keeps the colour and opacity rows off the tick path entirely.
 */
public final class GemzieWaypointPublisher {

    private static final GemzieWaypointPublisher INSTANCE = new GemzieWaypointPublisher();

    /** Whether a set is currently out, and the dimension it was built for. */
    private boolean published;
    private String publishedDimension = "";

    private GemzieWaypointPublisher() {
    }

    public static GemzieWaypointPublisher getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.GemzieSettings cfg() {
        return ConfigManager.getInstance().get().critterFinder.gemzie;
    }

    /** Client tick. Cheap while nothing has changed; that is what the guard below is for. */
    public void onClientTick() {
        if (!cfg().enabled || !SafariTracker.inSafariArea()) {
            clear();
            return;
        }
        // A waypoint carries the dimension it belongs to and is filtered by it, so a set built for
        // one and left standing in another would simply stop being drawn with no way to tell why.
        String dimension = WaypointStore.currentDimension();
        if (dimension.isEmpty()) {
            return;   // between worlds; not a reason to drop a set that is about to be correct again
        }
        if (published && dimension.equals(publishedDimension)) {
            return;
        }
        publishedDimension = dimension;
        republish(dimension);
    }

    /** Forces a rebuild - the settings rows call this so the world follows a change at once. */
    public void refresh() {
        published = false;
        onClientTick();
    }

    /** Called on a world change or server hop: the Safari is its own instance. */
    public void onWorldChange() {
        publishedDimension = "";
        clear();
    }

    /** Drops everything this publisher owns. */
    public void clear() {
        if (published) {
            WaypointStore.clearTransient(Waypoint.SOURCE_GEMZIE);
            published = false;
        }
    }

    /**
     * Why nothing is on screen, in one line - because "you are not in the Safari" is otherwise a
     * silent no-op, and a feature that draws nothing without saying why is the one a player reports
     * as broken while it is working exactly as told.
     */
    public String status() {
        if (!cfg().enabled) {
            return "switched off - nothing is published";
        }
        if (!SafariTracker.inSafariArea()) {
            String where = SkyBlockLocation.describe();
            return "not in the Safari (" + where + ") - nothing is drawn";
        }
        return published
                ? "in the Safari - drawing " + GemzieSpot.values().length + " spot(s)"
                : "in the Safari - publishing on the next tick";
    }

    private void republish(String dimension) {
        SBSConfig.GemzieSettings cfg = cfg();
        List<Waypoint> out = new ArrayList<>(GemzieSpot.values().length);
        for (GemzieSpot spot : GemzieSpot.values()) {
            out.add(build(spot, cfg, dimension));
        }
        WaypointStore.setTransient(Waypoint.SOURCE_GEMZIE, out);
        published = true;
    }

    /** One spot as a waypoint, with the player's colour, opacity and fade applied. */
    private static Waypoint build(GemzieSpot spot, SBSConfig.GemzieSettings cfg, String dimension) {
        Waypoint waypoint = new Waypoint(spot.label(), spot.pos(), dimension, Waypoint.SOURCE_GEMZIE);
        waypoint.colorHex = cfg.colorHexOf(spot);
        waypoint.opacity = cfg.opacityOf(spot);
        waypoint.showDistance = cfg.showDistance;
        waypoint.fadeWithin = Math.max(0, cfg.fadeWithin);
        // Through walls on purpose: the whole point of a marker on a spawn spot is knowing where it
        // is before you can see it. This is also the renderer's default, so it costs no raycast.
        waypoint.throughWalls = true;
        // Coordinates nobody has stood on must not be handed to the pathfinder as a destination; the
        // marker and its distance are the honest amount to claim. The same call the preset publisher
        // makes, for the same reason.
        waypoint.routable = false;
        return waypoint;
    }
}
