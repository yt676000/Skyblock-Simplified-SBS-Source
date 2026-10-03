/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSConfig.MiningRoute;
import sbs.modid.client.core.config.SBSFiles;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for the Mining Routes module in its own {@code config/sbs/miningroutes.txt} instead of
 * the main config, matching how Secret Routes stores {@code secretroutes.txt}. The {@code .txt} holds
 * pretty-printed, structured JSON via the shared {@link SBSFiles#GSON} - a plain array of
 * {@link MiningRoute}. The module's small settings (toggles, selected index, line width, keybinds)
 * stay in the main config; only the route data moved out.
 *
 * <p><b>One-time migration:</b> when the file does not exist yet but the old
 * {@code miningRoutes.routes} list is still present in {@code config.json} (from before this split),
 * those routes are copied into the file and cleared from the config, so nobody loses their routes.
 */
public final class MiningRouteStore {

    private static final Type LIST_TYPE = new TypeToken<ArrayList<MiningRoute>>() {
    }.getType();

    private static List<MiningRoute> routes = new ArrayList<>();

    private MiningRouteStore() {
    }

    /** {@code config/sbs/miningroutes.txt}. */
    private static Path file() {
        return SBSFiles.root().resolve("miningroutes.txt");
    }

    /** The live, mutable route list (source of truth once loaded). */
    public static List<MiningRoute> routes() {
        return routes;
    }

    /** Loads the file (or migrates legacy config routes). Call on client init after the config loads. */
    public static void load() {
        Path path = file();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    List<MiningRoute> parsed = SBSFiles.GSON.fromJson(reader, LIST_TYPE);
                    if (parsed != null) {
                        routes = parsed;
                    }
                }
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningRoutes] Loaded {} route(s).", routes.size());
            } else {
                migrateFromConfig();
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][MiningRoutes] Failed to load miningroutes.txt", e);
        }
    }

    /** Moves any routes still stored in the main config into the new file (once), then clears them. */
    private static void migrateFromConfig() {
        SBSConfig.MiningRoutesSettings cfg = ConfigManager.getInstance().get().miningRoutes;
        if (cfg.routes != null && !cfg.routes.isEmpty()) {
            routes = new ArrayList<>(cfg.routes);
            save();
            cfg.routes = new ArrayList<>();       // keep them out of config.json from now on
            ConfigManager.getInstance().save();
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][MiningRoutes] Migrated {} route(s) from config to miningroutes.txt.", routes.size());
        }
    }

    /** Persists the route list to {@code miningroutes.txt} (call after any route mutation). */
    public static void save() {
        Path path = file();
        try {
            SBSFiles.ensureParent(path);
            try (Writer writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(routes, LIST_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][MiningRoutes] Failed to write miningroutes.txt", e);
        }
    }
}
