/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.dungeons.rooms.DungeonRoom;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The player's personal <b>route waypoints</b>: extra waypoints on top of the bundled room database,
 * created in-game with the two route hotkeys. Persisted to {@code config/sbs/Routes.json} – visible for
 * everyone (not dev-gated) and deliberately minimal: room name → waypoints, nothing else. All positions
 * are relative-only, in the room's canonical frame (same as the database), so they resolve through the
 * normal room match exactly like database waypoints.
 *
 * <pre>
 * {
 *   "Knight": {
 *     "My Spot": { "type": "standing", "relative_x": 12, "relative_y": 69, "relative_z": 20 }
 *   }
 * }
 * </pre>
 */
public final class DungeonRouteStore {

    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, LinkedHashMap<String, DungeonRoom.RoomWaypoint>>>() {
    }.getType();

    private static Map<String, LinkedHashMap<String, DungeonRoom.RoomWaypoint>> routes = new LinkedHashMap<>();

    private DungeonRouteStore() {
    }

    /** Loads {@code Routes.json} into memory (safe to call on client init; missing file = empty). */
    public static void load() {
        Path path = routesFile();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Map<String, LinkedHashMap<String, DungeonRoom.RoomWaypoint>> parsed =
                            SBSFiles.GSON.fromJson(reader, FILE_TYPE);
                    if (parsed != null) {
                        routes = parsed;
                    }
                }
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dungeon] Loaded route waypoints for {} room(s).", routes.size());
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Dungeon] Failed to load Routes.json", e);
        }
    }

    /** Adds (or replaces) one route waypoint under the room and persists the file immediately. */
    public static void add(String roomName, String waypointName, String type, int relX, int relY, int relZ) {
        DungeonRoom.RoomWaypoint waypoint = new DungeonRoom.RoomWaypoint();
        waypoint.type = type;
        waypoint.relative_x = relX;
        waypoint.relative_y = relY;
        waypoint.relative_z = relZ;
        routes.computeIfAbsent(roomName, k -> new LinkedHashMap<>()).put(waypointName, waypoint);
        save();
    }

    /** The route waypoints saved for a room (empty map when none). */
    public static Map<String, DungeonRoom.RoomWaypoint> waypointsFor(String roomName) {
        Map<String, DungeonRoom.RoomWaypoint> forRoom = routes.get(roomName);
        return forRoom == null ? Map.of() : forRoom;
    }

    private static void save() {
        Path path = routesFile();
        try {
            SBSFiles.ensureParent(path);
            try (Writer writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(routes, FILE_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Dungeon] Failed to write Routes.json", e);
        }
    }

    /** {@code config/sbs/Routes.json}. */
    private static Path routesFile() {
        return SBSFiles.root().resolve("Routes.json");
    }
}
