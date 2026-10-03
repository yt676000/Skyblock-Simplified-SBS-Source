/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import net.minecraft.core.BlockPos;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.hollows.HollowsDetector;
import sbs.modid.client.core.location.hollows.HollowsGeometry;
import sbs.modid.client.core.location.hollows.HollowsStructure;
import sbs.modid.client.core.location.hollows.StructureFix;
import sbs.modid.client.helper.map.model.IslandMap;
import sbs.modid.client.helper.map.model.MapLocation;
import sbs.modid.client.social.chat.logic.ChatWaypoints;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Crystal Hollows' layer on the general SkyBlock Map, which cannot be a data file: the Hollows
 * are regenerated every few hours, so nothing in them has a fixed coordinate to write down.
 *
 * <p>{@code map/crystal_hollows.json} says exactly that and ships an empty {@code locations} list.
 * The places come from {@link HollowsDetector} instead - what you have walked into during this lobby
 * - plus structures other SBS players shared (Structure Sharing) and coordinates posted in chat.
 * The dedicated schematic map is {@code HollowsMapScreen}; this class only adapts the same
 * knowledge to the island map's {@link MapLocation} list.
 *
 * <h2>Geometry</h2>
 * <b>ESTIMATED</b>, and taken from {@link HollowsGeometry} like everything else that draws the
 * Hollows. The box is that estimate widened by every structure actually found this lobby, so a wrong
 * estimate frames the map badly rather than placing a marker outside it.
 */
public final class HollowsTracker {

    private static final HollowsTracker INSTANCE = new HollowsTracker();

    /** The island these zones belong to, as {@code SkyBlockLocation} names it. */
    public static final String ISLAND = HollowsGeometry.ISLAND;

    /** Upper bound on chat pins drawn, whatever the chat layer is holding. */
    private static final int MAX_CHAT_PINS = 8;

    private HollowsTracker() {
    }

    public static HollowsTracker getInstance() {
        return INSTANCE;
    }

    /**
     * The box the map should draw: the ESTIMATED extent, widened by the structures found this lobby.
     *
     * <p>Widened and never narrowed, so a structure found past the estimate is still on the map.
     */
    public IslandMap.Bounds bounds() {
        int min = HollowsGeometry.MIN;
        int max = HollowsGeometry.MAX;
        for (StructureFix fix : HollowsDetector.getInstance().snapshot()) {
            min = Math.min(min, Math.min(fix.minX(), fix.minZ()));
            max = Math.max(max, Math.max(fix.maxX(), fix.maxZ()));
        }
        return new IslandMap.Bounds(min, min, max, max);
    }

    /**
     * Whether a position could be somewhere in the Hollows at all - the gate on anything from chat.
     *
     * <p><b>Rests on the ESTIMATED extent</b> in {@link HollowsGeometry}: a real Hollows coordinate
     * outside it is dropped from chat.
     */
    public boolean inBounds(BlockPos pos) {
        IslandMap.Bounds box = bounds();
        return pos.getX() >= box.minX && pos.getX() <= box.maxX
                && pos.getZ() >= box.minZ && pos.getZ() <= box.maxZ;
    }

    /**
     * The structure whose name appears in {@code text}, or {@code null}.
     *
     * <p>Deliberately narrow: the whole zone name has to be in the message. A structure labelled
     * wrongly is worse than a pin with no label, because the map then states something false in the
     * same shape as everything true on it.
     */
    public static String structureNamed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (HollowsStructure structure : HollowsStructure.values()) {
            if (lower.contains(structure.zone().toLowerCase(Locale.ROOT))) {
                return structure.zone();
            }
        }
        return null;
    }

    /**
     * Everything this lobby knows, as ordinary {@link MapLocation}s.
     *
     * <p>They are the same type the island files produce on purpose: the map screen's hover,
     * tooltip, search and click-to-waypoint all work on {@code MapLocation}, so a runtime marker
     * that is one of those inherits the lot instead of needing a parallel drawing path.
     */
    public List<MapLocation> locations(IslandMap map) {
        List<MapLocation> out = new ArrayList<>();
        Set<HollowsStructure> own = EnumSet.noneOf(HollowsStructure.class);
        if (ConfigManager.getInstance().get().map.hollowsDiscover) {
            for (StructureFix fix : HollowsDetector.getInstance().snapshot()) {
                if (fix.samples() == 0) {
                    continue;
                }
                own.add(fix.structure());
                MapLocation location = new MapLocation();
                location.name = fix.structure().displayName();
                location.category = "Point of Interest";
                location.x = fix.x();
                location.y = fix.y();
                location.z = fix.z();
                location.area = fix.structure().zone();
                location.note = "Found here this lobby";
                location.map = map;
                out.add(location);
            }
        }
        addShared(out, map, own);
        if (ConfigManager.getInstance().get().map.hollowsChatPins) {
            addChatPins(out, map);
        }
        return out;
    }

    /**
     * Structures other SBS players shared for this lobby, for the ones not already on the map
     * because you walked into them. Marked as shared, with how many players confirm them.
     */
    private void addShared(List<MapLocation> out, IslandMap map, Set<HollowsStructure> own) {
        for (StructureSharing.MapEntry entry : StructureSharing.getInstance().sharedForMap()) {
            if (own.contains(entry.structure())) {
                continue;
            }
            String name = entry.structure().zone();
            MapLocation location = new MapLocation();
            location.name = name;
            location.category = "Point of Interest";
            location.x = entry.x();
            location.y = entry.y();
            location.z = entry.z();
            location.area = name;
            location.note = entry.confirmations() >= 2
                    ? "Shared by SBS players, confirmed by " + entry.confirmations()
                    : "Shared by one SBS player, unconfirmed";
            location.map = map;
            out.add(location);
        }
    }

    /** The chat layer's own list, filtered to what could be a place in these Hollows. */
    private void addChatPins(List<MapLocation> out, IslandMap map) {
        int added = 0;
        for (ChatWaypoints.Waypoint waypoint : ChatWaypoints.getInstance().active()) {
            if (added >= MAX_CHAT_PINS) {
                break;
            }
            if (!inBounds(waypoint.pos())) {
                continue;   // a coordinate from somewhere else entirely; not this map's business
            }
            MapLocation location = new MapLocation();
            String named = structureNamed(waypoint.sender());
            location.name = named != null ? named : waypoint.sender();
            location.category = "Event";
            location.x = waypoint.pos().getX();
            location.y = waypoint.pos().getY();
            location.z = waypoint.pos().getZ();
            location.note = "Posted by " + waypoint.sender() + ", "
                    + Math.max(0, (System.currentTimeMillis() - waypoint.createdAt()) / 60_000)
                    + " min ago";
            location.map = map;
            out.add(location);
            added++;
        }
    }
}
