/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.floorseven.model.RelicColour;
import sbs.modid.client.dungeons.floorseven.model.RelicPointData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * M7 relic markers (phase 5). Display only: it marks where a relic lies and where the one you carry
 * goes, and never moves, clicks or picks anything.
 *
 * <ul>
 *   <li><b>Spawn markers</b> - one per relic still at its spawn, labelled with its colour word
 *       ("Red Relic") so it reads without colour vision, narrowed by the colour filter.</li>
 *   <li><b>Target marker</b> - after <i>your</i> pickup (the CONFIRMED chat line), the target of that
 *       colour, plus the optional guide line drawn by {@code RelicGuideLine}. Cleared once the relic
 *       leaves your inventory (see {@link RelicProbe#holding}; whether a relic is an inventory item is
 *       UNVERIFIED, so a target that is never seen as an item stays until the run ends).</li>
 * </ul>
 *
 * <p>Positions come only from {@link RelicPointData}, which ships empty until our own
 * {@code [SBS][Relics]} captures fill it: a missing point draws nothing. Markers are published to
 * {@link WaypointStore} under {@link Waypoint#SOURCE_M7_RELICS} when the run state changes, not per
 * tick.
 *
 * <p><b>The phase gate.</b> The chat-driven {@link DragonTracker#inDragonPhase()} can be stranded
 * below phase 5 by one missed line, so the first relic pickup line opens the gate too.
 */
public final class RelicTracker {

    private static final RelicTracker INSTANCE = new RelicTracker();

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/dungeons/m7_relics.json";
    private static final int SUPPORTED_SCHEMA = 1;
    private static final int HOLD_CHECK_TICKS = 10;

    private final VersionedDataStore<RelicPointData> store = new VersionedDataStore<>(
            "M7Relics", RESOURCE, SBSFiles.root().resolve("data").resolve("m7_relics.json"),
            null, RelicPointData.class, SUPPORTED_SCHEMA);
    private boolean loaded;
    private RelicPointData indexed;
    private final Map<RelicColour, RelicPointData.Point> spawns = new EnumMap<>(RelicColour.class);
    private final Map<RelicColour, RelicPointData.Point> targets = new EnumMap<>(RelicColour.class);

    private final RelicRun run = new RelicRun();

    /** What was last published, so a republish happens only on change and nothing is built per tick. */
    private boolean published;
    private int publishedVersion = -1;
    private String publishedFilter;
    private RelicPointData publishedDocument;
    private boolean seenHeld;
    private int tick;

    /** The carried relic's target for the guide line, or null. Written on the client thread only. */
    private volatile Vec3 guideTarget;
    private volatile int guideColour;

    private RelicTracker() {
        ChatPatternRegistry.getInstance().register(Pattern.compile(RelicPickup.REGEX),
                (matcher, raw) -> onPickup(RelicPickup.from(matcher, RelicProbe.selfName())),
                "m7 relics: pickup");
    }

    public static RelicTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    // ---- read by the renderer -----------------------------------------------------------------

    /** The carried relic's target, or {@code null} when nothing is carried or its target is unknown. */
    public Vec3 guideTarget() {
        return guideTarget;
    }

    /** ARGB of the carried relic. */
    public int guideColour() {
        return guideColour;
    }

    // ---- events -------------------------------------------------------------------------------

    void onPickup(RelicPickup pickup) {
        if (pickup == null || !DragonTracker.onMasterSeven()) {
            return;
        }
        if (run.picked(pickup) && pickup.self()) {
            seenHeld = false;
            SkyblockSimplifiedSBS.LOGGER.info(RelicProbe.TAG + "carrying {} (target {})", pickup.colour(),
                    targetOf(pickup.colour()) == null ? "not captured yet" : "known");
        }
    }

    /** World change: the run's state is entity-free, but a reconnect mid-phase is not worth resuming. */
    public void onWorldChange() {
        clear();
    }

    private void clear() {
        if (run.anyPicked()) {
            run.reset();
        }
        seenHeld = false;
        guideTarget = null;
        unpublish();
    }

    // ---- tick ---------------------------------------------------------------------------------

    public void onClientTick() {
        if (!DragonTracker.onMasterSeven()) {
            clear();
            return;
        }
        SBSConfig.DungeonsSettings cfg = cfg();
        boolean active = cfg.m7RelicWaypoints && DragonTracker.inBossRoom()
                && (DragonTracker.inDragonPhase() || run.anyPicked());
        if (!active) {
            unpublish();
            guideTarget = null;
            return;
        }
        ensureLoaded();
        if (run.carrying() != null && ++tick % HOLD_CHECK_TICKS == 0) {
            watchPlacement();
        }
        if (!published || publishedVersion != run.version() || publishedFilter != cfg.m7RelicFilter
                || publishedDocument != indexed) {
            published = true;
            publishedVersion = run.version();
            publishedFilter = cfg.m7RelicFilter;
            publishedDocument = indexed;
            publish(cfg.m7RelicFilter);
        }
    }

    private void unpublish() {
        if (published) {
            published = false;
            WaypointStore.clearTransient(Waypoint.SOURCE_M7_RELICS);
        }
    }

    private void watchPlacement() {
        LocalPlayer player = Minecraft.getInstance().player;
        RelicColour carrying = run.carrying();
        if (player == null || carrying == null) {
            return;
        }
        if (RelicProbe.holding(player, carrying)) {
            seenHeld = true;
        } else if (seenHeld && run.placed(carrying)) {
            SkyblockSimplifiedSBS.LOGGER.info(RelicProbe.TAG + "{} left the inventory - target cleared", carrying);
        }
    }

    private void publish(String filter) {
        String dimension = WaypointStore.currentDimension();
        List<Waypoint> out = new ArrayList<>(6);
        for (RelicColour colour : RelicColour.values()) {
            RelicPointData.Point spawn = spawns.get(colour);
            if (spawn != null && run.showSpawn(colour, filter)) {
                out.add(marker(colour.label() + " Relic", spawn, colour, dimension));
            }
        }
        RelicColour carrying = run.carrying();
        RelicPointData.Point target = carrying == null ? null : targetOf(carrying);
        if (target != null) {
            Waypoint marker = marker(carrying.label() + " Relic: place here", target, carrying, dimension);
            out.add(marker);
            guideColour = carrying.argb();
            guideTarget = new Vec3(Math.floor(target.x) + 0.5, Math.floor(target.y) + 0.5, Math.floor(target.z) + 0.5);
        } else {
            guideTarget = null;
        }
        WaypointStore.setTransient(Waypoint.SOURCE_M7_RELICS, out);
    }

    private static Waypoint marker(String name, RelicPointData.Point point, RelicColour colour, String dimension) {
        Waypoint waypoint = new Waypoint(name, BlockPos.containing(point.x, point.y, point.z), dimension,
                Waypoint.SOURCE_M7_RELICS);
        waypoint.colorHex = colour.hex();
        waypoint.routable = false;
        if (!point.certainty.trusted()) {
            waypoint.subLabel = point.certainty.displayName();
        }
        return waypoint;
    }

    // ---- data ---------------------------------------------------------------------------------

    private RelicPointData.Point targetOf(RelicColour colour) {
        ensureLoaded();
        return targets.get(colour);
    }

    private void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            store.load();
        }
        RelicPointData document = store.get();
        if (document == indexed) {
            return;
        }
        indexed = document;
        spawns.clear();
        targets.clear();
        if (document == null || document.relics == null) {
            return;
        }
        for (RelicPointData.Entry entry : document.relics) {
            RelicColour colour = entry == null ? null : parseColour(entry.colour);
            if (colour == null) {
                continue;
            }
            if (usable(entry.spawn)) {
                spawns.put(colour, entry.spawn);
            }
            if (usable(entry.target)) {
                targets.put(colour, entry.target);
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info(RelicProbe.TAG + "points loaded: {} spawns, {} targets ({})",
                spawns.size(), targets.size(), store.source());
    }

    private static boolean usable(RelicPointData.Point point) {
        return point != null && point.certainty != null
                && point.certainty != sbs.modid.client.helper.rift.model.Certainty.UNKNOWN;
    }

    private static RelicColour parseColour(String id) {
        for (RelicColour colour : RelicColour.values()) {
            if (colour.name().equalsIgnoreCase(id)) {
                return colour;
            }
        }
        return null;
    }
}
