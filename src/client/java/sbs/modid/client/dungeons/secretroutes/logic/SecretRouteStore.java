/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.secretroutes.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.dungeons.secretroutes.model.SecretRoute;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence for the Secret Routes module: every recorded route, grouped by <b>room name</b> (the
 * key returned by the room recognition), stored in {@code config/sbs/secretroutes.txt}.
 *
 * <p>The file is a {@code .txt} on purpose (the user asked for one) but holds pretty-printed,
 * structured JSON via the shared {@link SBSFiles#GSON}, so it stays human-readable and re-parseable
 * without a bespoke format. Positions inside are room-relative and canonical, exactly like the
 * scanned-room database, so routes rotate back onto the current run's room orientation on load.
 *
 * <pre>
 * {
 *   "Cage": [
 *     { "name": "Fast", "waypoints": [ ... ], "trail": [ [x,y,z], ... ], "breakerBlocks": [ ... ] }
 *   ]
 * }
 * </pre>
 */
public final class SecretRouteStore {

    private static final Type FILE_TYPE =
            new TypeToken<LinkedHashMap<String, List<SecretRoute>>>() {
            }.getType();

    private static Map<String, List<SecretRoute>> routes = new LinkedHashMap<>();

    private SecretRouteStore() {
    }

    /** {@code config/sbs/secretroutes.txt}. */
    private static Path file() {
        return SBSFiles.root().resolve("secretroutes.txt");
    }

    /** Loads the file into memory (safe to call on client init; missing file = empty). */
    public static void load() {
        Path path = file();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Map<String, List<SecretRoute>> parsed = SBSFiles.GSON.fromJson(reader, FILE_TYPE);
                    if (parsed != null) {
                        routes = parsed;
                    }
                }
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][SecretRoutes] Loaded routes for {} room(s).",
                        routes.size());
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][SecretRoutes] Failed to load secretroutes.txt", e);
        }
    }

    /** All routes stored for a room (never null; a live, mutable list once the room has any). */
    public static List<SecretRoute> routesFor(String roomName) {
        if (roomName == null) {
            return List.of();
        }
        List<SecretRoute> list = routes.get(roomName);
        return list != null ? list : List.of();
    }

    /** The room names that have at least one saved route. */
    public static java.util.Set<String> rooms() {
        return routes.keySet();
    }

    /** Creates a new empty route under the room and persists; returns it (for immediate selection). */
    public static SecretRoute createRoute(String roomName, String routeName) {
        SecretRoute route = new SecretRoute(routeName);
        routes.computeIfAbsent(roomName, k -> new ArrayList<>()).add(route);
        save();
        return route;
    }

    /** Adds/keeps a fully-built route under the room and persists (used by import). */
    public static void putRoute(String roomName, SecretRoute route) {
        routes.computeIfAbsent(roomName, k -> new ArrayList<>()).add(route);
        save();
    }

    /** Removes a route (by identity) from a room and persists. */
    public static void removeRoute(String roomName, SecretRoute route) {
        List<SecretRoute> list = routes.get(roomName);
        if (list != null && list.remove(route)) {
            if (list.isEmpty()) {
                routes.remove(roomName);
            }
            save();
        }
    }

    /** Persists the in-memory routes to {@code secretroutes.txt} (call after any mutation). */
    public static void save() {
        Path path = file();
        try {
            SBSFiles.ensureParent(path);
            try (Writer writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(routes, FILE_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][SecretRoutes] Failed to write secretroutes.txt", e);
        }
    }

    // ------------------------------------------------------------------
    // Import / export (clipboard-friendly JSON)
    // ------------------------------------------------------------------

    /** Exports one room's routes as a JSON string ({@code null} when the room has none). */
    public static String exportRoom(String roomName) {
        List<SecretRoute> list = routes.get(roomName);
        if (list == null || list.isEmpty()) {
            return null;
        }
        Map<String, List<SecretRoute>> one = new LinkedHashMap<>();
        one.put(roomName, list);
        return SBSFiles.GSON.toJson(one, FILE_TYPE);
    }

    /** Exports every room's routes as one JSON string. */
    public static String exportAll() {
        return SBSFiles.GSON.toJson(routes, FILE_TYPE);
    }

    /**
     * Imports a JSON string produced by {@link #exportRoom}/{@link #exportAll}: every room's routes
     * are appended to what is already stored. Returns the number of routes imported, or -1 on a parse
     * failure (the caller shows the outcome).
     */
    public static int importJson(String json) {
        if (json == null || json.isBlank()) {
            return -1;
        }
        try {
            Map<String, List<SecretRoute>> parsed = SBSFiles.GSON.fromJson(json.trim(), FILE_TYPE);
            if (parsed == null) {
                return -1;
            }
            int count = 0;
            for (Map.Entry<String, List<SecretRoute>> entry : parsed.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                List<SecretRoute> target = routes.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
                for (SecretRoute route : entry.getValue()) {
                    if (route != null) {
                        target.add(route);
                        count++;
                    }
                }
            }
            save();
            return count;
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][SecretRoutes] Import failed: {}", e.toString());
            return -1;
        }
    }
}
