/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.dungeons.rooms.DungeonRoom;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists scanned rooms and manually created waypoints to {@code config/sbs/Development_Stuff/Waypoints.json}.
 *
 * <p><b>Relative-only guarantee:</b> the JSON contains no absolute world coordinates whatsoever. Every
 * block and waypoint is stored as a door-relative, rotation-normalised delta (NORTH canonical, see
 * {@link RoomRotation}); {@code door_facing_direction} records the facing the room was scanned with, so
 * the file documents its own frame of reference. This makes the export directly usable as the bundled
 * {@code rooms.json} database schema.
 *
 * <p><b>Camouflage guarantee:</b> the {@code Development_Stuff/} folder and {@code Waypoints.json} are
 * created only inside {@link #writeAll(Map)}, which refuses to run unless {@link DevMode#ACTIVE} is
 * {@code true}. Nothing else in the mod ever touches this path, so it stays invisible until a developer
 * actually saves data.
 *
 * <p>Appends are non-destructive: existing rooms and waypoints are preserved. Re-scanning a room
 * refreshes its geometry but keeps its previously saved waypoints; saving a waypoint only inserts /
 * updates that one entry and never removes others. GSON pretty-prints the whole tree.
 *
 * <p>The nested model classes use underscore field names on purpose – Gson serialises them verbatim,
 * producing exactly the required JSON schema.
 */
public final class WaypointExporter {

    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, RoomEntry>>() {
    }.getType();

    private WaypointExporter() {
    }

    /** Waypoint kinds and the {@code type} string written for each. */
    public enum WaypointType {
        STANDING("standing"),
        LOOKING("looking"),
        // Auto-collected by the dev SecretScanner:
        CHEST("chest"),
        LEVER("lever"),
        BAT("bat"),
        ESSENCE("essence"),
        // A ground-item secret, saved at the block the player's legs occupy:
        ITEM("item");

        private final String id;

        WaypointType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    // ------------------------------------------------------------------
    // Public API (called from DevActions, always in dev mode)
    // ------------------------------------------------------------------

    /** Saves (or refreshes) a scanned room (blocks are already door-relative and rotation-normalised). */
    public static void saveRoom(String roomName, RoomMapReader.RoomInfo info, Direction facing,
                                String roomSize, List<RoomScanner.ScannedBlock> blocks) {
        if (roomName == null || roomName.isBlank()) {
            return;
        }
        Map<String, RoomEntry> file = loadAll();
        RoomEntry existing = file.get(roomName);

        RoomEntry entry = new RoomEntry();
        entry.map_color = info.colorName();
        entry.room_size = roomSize;
        entry.scanned_blocks_count = blocks.size();
        entry.door_facing_direction = facing.name();
        entry.blocks = new java.util.ArrayList<>();
        for (RoomScanner.ScannedBlock b : blocks) {
            entry.blocks.add(new BlockEntry(b));
        }
        // Preserve previously saved waypoints on a re-scan.
        entry.waypoints = existing != null && existing.waypoints != null
                ? existing.waypoints : new LinkedHashMap<>();

        file.put(roomName, entry);
        writeAll(file);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dev] Saved room '{}' ({} blocks, {}, {})",
                roomName, blocks.size(), entry.room_size, entry.map_color);
    }

    /**
     * Saves a waypoint under {@code roomName}. The position is already a door-relative, rotation-
     * normalised delta. If the room is not in the file yet, a minimal entry is created so the waypoint
     * still has a home.
     */
    public static void saveWaypoint(String roomName, RoomMapReader.RoomInfo roomInfo, Direction facing,
                                    String roomSize, String waypointName, WaypointType type, BlockPos relative) {
        if (roomName == null || roomName.isBlank() || waypointName == null || waypointName.isBlank()) {
            return;
        }
        Map<String, RoomEntry> file = loadAll();
        RoomEntry entry = file.get(roomName);
        if (entry == null) {
            entry = new RoomEntry();
            entry.map_color = roomInfo != null ? roomInfo.colorName() : "unknown";
            entry.room_size = roomSize;
            entry.scanned_blocks_count = 0;
            entry.door_facing_direction = facing.name();
            entry.blocks = new java.util.ArrayList<>();
            entry.waypoints = new LinkedHashMap<>();
            file.put(roomName, entry);
        }
        if (entry.waypoints == null) {
            entry.waypoints = new LinkedHashMap<>();
        }

        WaypointEntry wp = new WaypointEntry();
        wp.type = type.id();
        wp.relative_x = relative.getX();
        wp.relative_y = relative.getY();
        wp.relative_z = relative.getZ();
        entry.waypoints.put(waypointName, wp); // update-or-insert; never removes other waypoints

        writeAll(file);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dev] Saved {} waypoint '{}' in room '{}'",
                type.id(), waypointName, roomName);
    }

    /** The waypoint names already saved under a room (empty set when the room is unknown). */
    public static java.util.Set<String> waypointNames(String roomName) {
        RoomEntry entry = roomName == null ? null : loadAll().get(roomName);
        return entry == null || entry.waypoints == null
                ? java.util.Set.of() : new java.util.HashSet<>(entry.waypoints.keySet());
    }

    /**
     * The dev-scanned rooms converted into the matcher's {@link DungeonRoom} shape, so a room scanned
     * in an earlier session (and only present in {@code Waypoints.json}, not the bundled database) can
     * still be recognised from its signature blocks. Rooms without signature blocks are skipped – they
     * carry no match information.
     */
    public static Map<String, DungeonRoom> loadAsDatabase() {
        Map<String, DungeonRoom> database = new LinkedHashMap<>(loadAllRooms());
        database.values().removeIf(room -> room.blocks == null || room.blocks.isEmpty());
        return database;
    }

    /**
     * Every dev-scanned room exactly as stored – waypoints included, and <b>without</b> the block-less
     * filter {@link #loadAsDatabase()} applies. The read model for tooling that lists what has been
     * scanned ({@code ScannedRoomsScreen}): a 0-block entry is precisely what such a list must surface,
     * because it is the one that must never overwrite a good entry in {@code rooms.json}.
     */
    public static Map<String, DungeonRoom> loadAllRooms() {
        Map<String, DungeonRoom> rooms = new LinkedHashMap<>();
        for (Map.Entry<String, RoomEntry> e : loadAll().entrySet()) {
            RoomEntry entry = e.getValue();
            DungeonRoom room = new DungeonRoom();
            room.map_color = entry.map_color;
            room.room_size = entry.room_size;
            room.scanned_blocks_count = entry.scanned_blocks_count;
            room.door_facing_direction = entry.door_facing_direction;
            room.blocks = new java.util.ArrayList<>();
            if (entry.blocks != null) {
                for (BlockEntry b : entry.blocks) {
                    DungeonRoom.RoomBlock rb = new DungeonRoom.RoomBlock();
                    rb.id = b.id;
                    rb.relative_x = b.relative_x;
                    rb.relative_y = b.relative_y;
                    rb.relative_z = b.relative_z;
                    room.blocks.add(rb);
                }
            }
            room.waypoints = new LinkedHashMap<>();
            if (entry.waypoints != null) {
                for (Map.Entry<String, WaypointEntry> w : entry.waypoints.entrySet()) {
                    WaypointEntry we = w.getValue();
                    DungeonRoom.RoomWaypoint rw = new DungeonRoom.RoomWaypoint();
                    rw.type = we.type;
                    rw.relative_x = we.relative_x;
                    rw.relative_y = we.relative_y;
                    rw.relative_z = we.relative_z;
                    room.waypoints.put(w.getKey(), rw);
                }
            }
            rooms.put(e.getKey(), room);
        }
        return rooms;
    }

    /**
     * Creates the hidden {@code Development_Stuff/} folder and an empty {@code Waypoints.json} skeleton
     * if they do not exist yet. Called when dev mode is enabled so the structure is generated up front.
     * No-op (and never creates anything) unless {@link DevMode#ACTIVE}.
     */
    public static void ensureStructure() {
        if (!DevMode.ACTIVE) {
            return;
        }
        Path path = SBSFiles.waypointsFile();
        try {
            Files.createDirectories(SBSFiles.developmentDir());
            if (!Files.exists(path)) {
                try (Writer writer = Files.newBufferedWriter(path)) {
                    SBSFiles.GSON.toJson(new LinkedHashMap<String, RoomEntry>(), writer);
                }
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dev] Generated {}", path);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Dev] Failed to generate Development_Stuff structure", e);
        }
    }

    // ------------------------------------------------------------------
    // Disk I/O (the ONLY place Development_Stuff/ is created)
    // ------------------------------------------------------------------

    private static Map<String, RoomEntry> loadAll() {
        Path path = SBSFiles.waypointsFile();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Map<String, RoomEntry> parsed = SBSFiles.GSON.fromJson(reader, FILE_TYPE);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Dev] Failed to read Waypoints.json, starting fresh", e);
        }
        return new LinkedHashMap<>();
    }

    private static void writeAll(Map<String, RoomEntry> file) {
        if (!DevMode.ACTIVE) {
            return; // hard guard: never create the hidden folder outside dev mode
        }
        Path path = SBSFiles.waypointsFile();
        try {
            Files.createDirectories(SBSFiles.developmentDir()); // created here for the first time, on demand
            try (Writer writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(file, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Dev] Failed to write Waypoints.json", e);
        }
    }

    // ------------------------------------------------------------------
    // JSON model (underscore field names = exact output keys, relative-only)
    // ------------------------------------------------------------------

    static final class RoomEntry {
        String map_color;
        String room_size;
        int scanned_blocks_count;
        String door_facing_direction;
        List<BlockEntry> blocks;
        Map<String, WaypointEntry> waypoints;
    }

    static final class BlockEntry {
        String id;
        int relative_x;
        int relative_y;
        int relative_z;

        BlockEntry(RoomScanner.ScannedBlock b) {
            this.id = b.id();
            this.relative_x = b.relativeX();
            this.relative_y = b.relativeY();
            this.relative_z = b.relativeZ();
        }
    }

    static final class WaypointEntry {
        String type;
        int relative_x;
        int relative_y;
        int relative_z;
    }
}
