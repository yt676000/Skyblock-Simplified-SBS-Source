/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.rooms;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory dungeon room database, loaded once at startup from the mod's bundled resources.
 *
 * <p>The JSON ships inside the jar at {@code assets/skyblock-simplified-sbs/dungeons/rooms.json} and is
 * read through the classloader ({@link Class#getResourceAsStream}) – pure Java, no Fabric API, works in
 * dev ({@code runClient}) and in the built jar alike. Room names map to {@link DungeonRoom}.
 */
public final class DungeonRoomDatabase {

    private static final String RESOURCE = "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/dungeons/rooms.json";
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, DungeonRoom>>() {
    }.getType();

    private static Map<String, DungeonRoom> rooms = Map.of();

    private DungeonRoomDatabase() {
    }

    /** Loads (or reloads) the bundled database into memory. Safe to call on client init. */
    public static void load() {
        try (InputStream in = DungeonRoomDatabase.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Dungeon] Room database resource not found: {}", RESOURCE);
                return;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Map<String, DungeonRoom> parsed = SBSFiles.GSON.fromJson(reader, MAP_TYPE);
                if (parsed != null) {
                    rooms = parsed;
                }
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dungeon] Loaded {} room(s) from the database.", rooms.size());
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Dungeon] Failed to load the room database", e);
        }
    }

    /** Read-only view of every known room, keyed by name. */
    public static Map<String, DungeonRoom> rooms() {
        return rooms;
    }
}
