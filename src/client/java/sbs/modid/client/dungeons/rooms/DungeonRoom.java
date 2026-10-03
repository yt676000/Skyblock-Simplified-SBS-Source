/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.rooms;

import java.util.List;
import java.util.Map;

/**
 * One curated dungeon room from the bundled database ({@code assets/.../dungeons/rooms.json}).
 *
 * <p>Deliberately relative-only: the absolute world coordinates the dev scanner captured are stripped,
 * so a room is defined purely by its signature blocks and waypoints in the NORTH-normalised, door-centred
 * frame (see {@code RoomRotation}). Underscore field names mirror the JSON keys 1:1 for Gson.
 */
public final class DungeonRoom {

    public String map_color;
    public String room_size;
    public int scanned_blocks_count;
    public String door_facing_direction;

    /**
     * Every entry door of this room, each as its canonical position (relative to the reference door /
     * NORTH-normalised frame). The reference door itself is {@code (0,0,0)}. When empty/absent the room
     * is treated as single-door (reference only). Lets the player enter a room from any of its doors.
     */
    public List<Coord> doors;

    public List<RoomBlock> blocks;
    public Map<String, RoomWaypoint> waypoints;

    /** A door-relative coordinate triple. */
    public static final class Coord {
        public int relative_x;
        public int relative_y;
        public int relative_z;

        public Coord() {
        }

        public Coord(int x, int y, int z) {
            this.relative_x = x;
            this.relative_y = y;
            this.relative_z = z;
        }
    }

    /** A signature block at a door-relative position. */
    public static final class RoomBlock {
        public String id;
        public int relative_x;
        public int relative_y;
        public int relative_z;
    }

    /** A named waypoint at a door-relative position. */
    public static final class RoomWaypoint {
        public String type;
        public int relative_x;
        public int relative_y;
        public int relative_z;
    }
}
