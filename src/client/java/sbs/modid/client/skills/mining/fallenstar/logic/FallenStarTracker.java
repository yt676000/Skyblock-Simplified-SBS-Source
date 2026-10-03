/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.fallenstar.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.FallenStarSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.helper.map.logic.MapDatabase;
import sbs.modid.client.helper.map.model.IslandMap;
import sbs.modid.client.helper.map.model.MapLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The live Fallen Star state for this lobby, plus the hook the event calendar feeds the Cult of the
 * Fallen Star through.
 *
 * <p>Two things, kept apart on purpose. The <b>Fallen Star</b> is a rare occurrence announced in chat
 * ({@link FallenStarLines}); this class raises it, alerts, points a waypoint at the named zone and
 * clears it on a time-out or a world change. The <b>Cult of the Fallen Star</b> is a scheduled event:
 * its schedule belongs to the event calendar, and this class only holds what that calendar reports
 * through {@link #onCultEvent} - it never computes a schedule of its own.
 *
 * <p>While a star is up, entities in the lobby whose name mentions a star are logged once each under
 * {@code [SBS][FallenStar]} - the probe for how the star appears in the world, which is unknown.
 */
public final class FallenStarTracker {

    private static final FallenStarTracker INSTANCE = new FallenStarTracker();

    public static final String ISLAND = "Dwarven Mines";
    private static final String WAYPOINT_SOURCE = "fallen_star";
    private static final long PROBE_MS = 2_000L;
    private static final int MAX_PROBE_LINES = 24;

    private volatile String zone;
    private volatile long crashedAt;
    private volatile boolean cultActive;
    private volatile String cultDetail = "";
    private long lastProbeAt;
    private final Set<String> probed = new HashSet<>();

    private FallenStarTracker() {
    }

    public static FallenStarTracker getInstance() {
        return INSTANCE;
    }

    private static FallenStarSettings cfg() {
        return ConfigManager.getInstance().get().fallenStar;
    }

    /** Every displayed chat line. */
    public void onChat(String text) {
        FallenStarSettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        String named = FallenStarLines.crashZone(text);
        if (named == null) {
            return;
        }
        long now = System.currentTimeMillis();
        zone = named;
        crashedAt = now;
        probed.clear();
        MapLocation centre = centreOf(named);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FallenStar] crashed at {} (map centre {})", named,
                centre == null ? "unknown" : centre.pos().toShortString());
        Alerts.send(Alerts.Alert.of(FallenStarLines.alertTitle(named),
                "Nearby ore and powder drops are amplified"
                        + (centre != null && cfg.waypoint ? " - waypoint to the zone (approximate)" : "")),
                cfg.alertChannels);
        if (cfg.waypoint && centre != null) {
            WaypointStore.setTransient(WAYPOINT_SOURCE, List.of(new Waypoint(
                    "Fallen Star (" + named + ", approximate)", centre.pos(),
                    WaypointStore.currentDimension(), WAYPOINT_SOURCE)));
        }
    }

    /** Every client tick: the time-out, the island gate, and the world probe. */
    public void onClientTick() {
        if (zone == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!cfg().enabled || now - crashedAt > FallenStarLines.ACTIVE_TIMEOUT_MS) {
            clear("timed out");
            return;
        }
        if (now - lastProbeAt >= PROBE_MS && SkyBlockLocation.onIsland(ISLAND)) {
            lastProbeAt = now;
            probe();
        }
    }

    public void onWorldChange() {
        if (zone != null) {
            clear("world change");
        }
    }

    /**
     * The event calendar's hook for the Cult of the Fallen Star. The calendar owns the schedule and
     * calls this when the gathering starts ({@code active = true}) or ends; nothing here guesses it.
     */
    public void onCultEvent(boolean active, String detail) {
        cultActive = active;
        cultDetail = detail == null ? "" : detail;
        if (active && cfg().enabled) {
            Alerts.send(Alerts.Alert.of("Cult of the Fallen Star",
                    cultDetail.isEmpty() ? "The gathering has started in the Dwarven Mines" : cultDetail),
                    cfg().alertChannels);
        }
    }

    /** The zone of the active star, or {@code null}. */
    public String zone() {
        return zone;
    }

    public long crashedAt() {
        return crashedAt;
    }

    public boolean cultActive() {
        return cultActive;
    }

    public String cultDetail() {
        return cultDetail;
    }

    private void clear(String why) {
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FallenStar] cleared ({}) after {}s", why,
                (System.currentTimeMillis() - crashedAt) / 1000);
        zone = null;
        WaypointStore.clearTransient(WAYPOINT_SOURCE);
    }

    private static MapLocation centreOf(String zone) {
        IslandMap map = MapDatabase.byId("dwarven_mines");
        return map == null ? null : FallenStarLines.zoneCentre(zone, map.locations);
    }

    /** Logs named entities mentioning a star, once each - how the star shows in the world is unknown. */
    private void probe() {
        var level = Minecraft.getInstance().level;
        if (level == null || probed.size() >= MAX_PROBE_LINES) {
            return;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == null || !entity.hasCustomName() || entity.getCustomName() == null) {
                continue;
            }
            String name = entity.getCustomName().getString();
            if (!name.toLowerCase(Locale.ROOT).contains("star")) {
                continue;
            }
            String key = name + "@" + entity.blockPosition().toShortString();
            if (probed.size() < MAX_PROBE_LINES && probed.add(key)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][FallenStar] entity '{}' ({}) at {} in zone {}",
                        name, entity.getType().getDescriptionId(), entity.blockPosition().toShortString(),
                        SkyBlockLocation.zone());
            }
        }
    }
}
