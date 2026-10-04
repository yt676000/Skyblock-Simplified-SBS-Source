/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes the deep search found, kept on disk so a hidden target (a fairy soul behind a hidden
 * entrance) is solved once, not on every visit. Keyed by island + waypoint source + position - the
 * waypoint's identity, never its label. {@code config/sbs/data/deep_routes.json}.
 *
 * <p>An entry is dropped when the route fails on the ground: the player followed it and got stuck
 * ({@link Route#checkBlocked}). Client thread only; written on every change, which is rare.
 */
final class DeepRouteCache {

    private static final DeepRouteCache INSTANCE = new DeepRouteCache();

    private final Map<String, List<BlockPos>> routes = new HashMap<>();
    private boolean loaded;

    private DeepRouteCache() {
    }

    static DeepRouteCache getInstance() {
        return INSTANCE;
    }

    /** The key for one target. Package-private for the tests. */
    static String key(String island, Waypoint target) {
        return (island == null ? "?" : island) + "|" + (target.source == null ? "" : target.source)
                + "|" + target.x + "," + target.y + "," + target.z;
    }

    List<BlockPos> get(String key) {
        load();
        return routes.get(key);
    }

    void put(String key, List<BlockPos> path) {
        load();
        routes.put(key, List.copyOf(path));
        save();
    }

    void invalidate(String key) {
        load();
        if (routes.remove(key) != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Path] deep route {} dropped - blocked on the ground", key);
            save();
        }
    }

    private static Path file() {
        return SBSFiles.dataDir().resolve("deep_routes.json");
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                List<BlockPos> path = new ArrayList<>();
                for (JsonElement node : entry.getValue().getAsJsonArray()) {
                    JsonArray xyz = node.getAsJsonArray();
                    path.add(new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
                }
                if (path.size() >= 2) {
                    routes.put(entry.getKey(), List.copyOf(path));
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Path] could not read {}", file, e);
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, List<BlockPos>> entry : routes.entrySet()) {
            JsonArray path = new JsonArray();
            for (BlockPos pos : entry.getValue()) {
                JsonArray xyz = new JsonArray();
                xyz.add(pos.getX());
                xyz.add(pos.getY());
                xyz.add(pos.getZ());
                path.add(xyz);
            }
            root.add(entry.getKey(), path);
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Path] could not write {}", file(), e);
        }
    }
}
