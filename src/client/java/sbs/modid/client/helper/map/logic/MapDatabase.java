/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.map.model.IslandMap;
import sbs.modid.client.helper.map.model.MapLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The island maps, loaded once at startup from the mod's bundled resources.
 *
 * <p>{@code assets/skyblock-simplified-sbs/map/index.json} is a plain array of file names; each names
 * an island file in the same folder. Adding an island is therefore <b>two data edits and no code</b>
 * – the file itself, and one line in the index – which is the point of the whole module.
 *
 * <p>Read through the classloader ({@link Class#getResourceAsStream}), like
 * {@link sbs.modid.client.dungeons.rooms.DungeonRoomDatabase}: pure Java, no Fabric API, and it works
 * the same in {@code runClient} and in the built jar.
 *
 * <p><b>One bad file does not take the map down.</b> Each island is parsed on its own and a failure is
 * logged and skipped, because a map that is missing the Rift is far more useful than no map at all.
 */
public final class MapDatabase {

    private static final String FOLDER = "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/map/";
    private static final String INDEX = FOLDER + "index.json";

    private static final Type INDEX_TYPE = new TypeToken<List<String>>() {
    }.getType();

    private static List<IslandMap> maps = List.of();

    private MapDatabase() {
    }

    /** Loads (or reloads) every island map into memory. Safe to call on client init. */
    public static void load() {
        List<String> files = readIndex();
        if (files.isEmpty()) {
            return;
        }
        List<IslandMap> loaded = new ArrayList<>(files.size());
        for (String file : files) {
            IslandMap map = readIsland(file);
            if (map != null) {
                loaded.add(map);
            }
        }
        maps = List.copyOf(loaded);

        int places = 0;
        int warps = 0;
        for (IslandMap map : maps) {
            places += map.locations.size();
            warps += map.warps.size();
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Map] Loaded {} island map(s): {} place(s), {} warp(s).",
                maps.size(), places, warps);
    }

    private static List<String> readIndex() {
        try (InputStream in = MapDatabase.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Map] Map index not found: {}", INDEX);
                return List.of();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                List<String> parsed = SBSFiles.GSON.fromJson(reader, INDEX_TYPE);
                return parsed == null ? List.of() : parsed;
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Map] Failed to read the map index", e);
            return List.of();
        }
    }

    private static IslandMap readIsland(String file) {
        String path = FOLDER + file;
        try (InputStream in = MapDatabase.class.getResourceAsStream(path)) {
            if (in == null) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Map] Island file listed but missing: {}", path);
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                IslandMap map = SBSFiles.GSON.fromJson(reader, IslandMap.class);
                if (map == null || map.island == null || map.island.isBlank()) {
                    SkyblockSimplifiedSBS.LOGGER.warn(
                            "[SBS][Map] Skipping {} - no \"island\" field, so nothing could be routed", path);
                    return null;
                }
                map.link();
                return map;
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Map] Failed to load island file {}", path, e);
            return null;
        }
    }

    /** Every loaded island map, in index order. */
    public static List<IslandMap> maps() {
        return maps;
    }

    /** The map with this id, or {@code null}. */
    public static IslandMap byId(String id) {
        if (id == null) {
            return null;
        }
        for (IslandMap map : maps) {
            if (id.equalsIgnoreCase(map.id)) {
                return map;
            }
        }
        return null;
    }

    /** The map for an island name as the location service spells it, or {@code null}. */
    public static IslandMap byIsland(String island) {
        if (island == null || island.isBlank()) {
            return null;
        }
        for (IslandMap map : maps) {
            if (island.equalsIgnoreCase(map.island)) {
                return map;
            }
        }
        return null;
    }

    /**
     * The map for where the player is standing right now, or {@code null} off the beaten track.
     *
     * <p>Matched island-level through {@link SkyBlockLocation#onIsland}, so the Hub map is still the
     * current one while the scoreboard says {@code ⏣ Coal Mine} - a zone is not an island, and every
     * zone of an island shares its coordinate space.
     */
    public static IslandMap current() {
        for (IslandMap map : maps) {
            if (SkyBlockLocation.onIsland(map.island)) {
                return map;
            }
        }
        return null;
    }

    /**
     * Every place whose name contains {@code query}, across all islands, best matches first.
     *
     * <p>Ordering rewards the match the player most likely meant: an exact name, then one that starts
     * with the query, then any containing it. Within a tier, places on the island you are standing on
     * come first - the Bank you want is almost always the one you could walk to.
     */
    public static List<MapLocation> search(String query) {
        List<MapLocation> out = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return out;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (IslandMap map : maps) {
            for (MapLocation location : map.locations) {
                if (location.name.toLowerCase(Locale.ROOT).contains(needle)) {
                    out.add(location);
                }
            }
        }
        out.sort((a, b) -> Integer.compare(rank(a, needle), rank(b, needle)));
        return out;
    }

    private static int rank(MapLocation location, String needle) {
        String name = location.name.toLowerCase(Locale.ROOT);
        int tier = name.equals(needle) ? 0 : name.startsWith(needle) ? 2 : 4;
        boolean here = location.map != null && SkyBlockLocation.onIsland(location.map.island);
        return here ? tier : tier + 1;
    }
}
