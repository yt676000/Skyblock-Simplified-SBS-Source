/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.edit.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.ui.hud.edit.model.HudTransform;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns the HUD-editor layout and persists it to its own file, {@code config/sbs/gui/hud_layout.json}.
 *
 * <p>Previously these transforms lived inline inside the main config JSON; splitting them out keeps
 * {@code config.json} focused on real preferences. The map is loaded
 * lazily on first access and written back through {@link SBSFiles}, so nothing here duplicates the
 * path or Gson setup.
 *
 * <p>{@link #adoptLegacy(Map)} handles the one-time migration from the old inline layout – see
 * {@code ConfigManager.migrateHudLayout}.
 */
public final class HudLayoutStore {

    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, HudTransform>>() {
    }.getType();

    private static HudLayoutStore instance;

    private Map<String, HudTransform> layout;
    private boolean loaded;

    private HudLayoutStore() {
    }

    public static HudLayoutStore getInstance() {
        if (instance == null) {
            instance = new HudLayoutStore();
        }
        return instance;
    }

    /** The live, mutable layout map (loaded from disk on first access). */
    public Map<String, HudTransform> map() {
        if (!loaded) {
            load();
        }
        return layout;
    }

    private void load() {
        loaded = true;
        Path path = SBSFiles.hudLayoutFile();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Map<String, HudTransform> parsed = SBSFiles.GSON.fromJson(reader, MAP_TYPE);
                    if (parsed != null) {
                        // Resolve the pre-split single opacity + scope into the three per-part dials
                        // here, once, so nothing downstream has to know the old shape existed.
                        parsed.values().removeIf(java.util.Objects::isNull);
                        parsed.values().forEach(HudTransform::materialize);
                        layout = parsed;
                        return;
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to read HUD layout, using empty layout", e);
        }
        layout = new LinkedHashMap<>();
    }

    /**
     * Adopts a layout that used to live inside the main config (migration). Only applied when this
     * store is still empty, so it never clobbers an already-externalised {@code hud_layout.json}.
     */
    public void adoptLegacy(Map<String, HudTransform> legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        if (!loaded) {
            load();
        }
        if (layout.isEmpty()) {
            legacy.forEach((id, transform) -> {
                if (transform != null) {
                    transform.materialize();
                    layout.put(id, transform);
                }
            });
            save();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Migrated {} HUD element(s) to gui/hud_layout.json", legacy.size());
        }
    }

    /** Persists the current layout to {@code gui/hud_layout.json} (best effort – never throws). */
    public void save() {
        if (!loaded) {
            load();
        }
        Path path = SBSFiles.hudLayoutFile();
        try {
            SBSFiles.ensureParent(path);
            try (Writer writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(layout, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to write HUD layout", e);
        }
    }
}
