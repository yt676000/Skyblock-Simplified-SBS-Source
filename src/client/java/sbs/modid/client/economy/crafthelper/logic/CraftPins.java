/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.crafthelper.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Craft Helper's favourites: which items the player starred, how many of each they want, which
 * one the HUD panel shows, and which recipe they picked for an item that has several.
 *
 * <p><b>Per account and profile</b>, like every other "what am I working towards" list: a goal on one
 * profile is not a goal on another, and the counts it is measured against are per profile too.
 *
 * <p>Keyed by the item's lookup id, never its name. Writes happen on a click, go through
 * {@link SbsExecutors#io()}, and {@link #revision()} moves on every change so the model rebuilds.
 */
public final class CraftPins implements ProfileScopedStore {

    private static final CraftPins INSTANCE = new CraftPins();

    private static final String FILE = "craft_helper.json";

    /** Largest quantity the - / + control goes to. */
    public static final int MAX_QUANTITY = 9999;

    /** The on-disk shape. A class rather than a record so Gson can fill it field by field. */
    private static final class Persisted {
        /** Starred item id -> wanted quantity, in the order they were starred. */
        LinkedHashMap<String, Integer> pins = new LinkedHashMap<>();
        /** The pin the HUD panel shows, or {@code null} when the panel is closed. */
        String shown;
        /** Item id -> index of the recipe the player picked. */
        LinkedHashMap<String, Integer> recipeChoices = new LinkedHashMap<>();
    }

    private volatile Persisted state = new Persisted();
    private volatile boolean loaded;
    private volatile int revision;
    /** Mirror of {@code state.shown}, readable from a draw path without the lock. */
    private volatile String shownId;
    /** Immutable copy of the pins and their quantities, for the same reason. */
    private volatile Map<String, Integer> pinView = Map.of();

    private CraftPins() {
        ProfileContext.getInstance().register(this);
    }

    public static CraftPins getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ queries

    /** Changes on every edit and profile switch. */
    public int revision() {
        ensureLoaded();
        return revision;
    }

    public boolean isPinned(String id) {
        ensureLoaded();
        return id != null && pinView.containsKey(key(id));
    }

    /** The starred ids, oldest first. */
    public synchronized List<String> pins() {
        ensureLoaded();
        return List.copyOf(state.pins.keySet());
    }

    public int quantity(String id) {
        ensureLoaded();
        return id == null ? 1 : pinView.getOrDefault(key(id), 1);
    }

    /** The id on the HUD, or {@code null}. */
    public String shown() {
        ensureLoaded();
        return shownId;
    }

    /** A copy of the recipe picks. */
    public synchronized Map<String, Integer> recipeChoices() {
        ensureLoaded();
        return Map.copyOf(state.recipeChoices);
    }

    // ------------------------------------------------------------------ edits

    /** Stars or un-stars {@code id}. Un-starring the shown item closes the panel. */
    public synchronized void togglePin(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        ensureLoaded();
        String key = key(id);
        if (state.pins.remove(key) == null) {
            state.pins.put(key, 1);
        } else if (key.equals(state.shown)) {
            state.shown = null;
        }
        changed();
    }

    /** Shows {@code id} on the HUD, starring it first if needed; {@code null} closes the panel. */
    public synchronized void show(String id) {
        ensureLoaded();
        if (id == null) {
            state.shown = null;
        } else {
            String key = key(id);
            state.pins.putIfAbsent(key, 1);
            state.shown = key;
        }
        changed();
    }

    /** Adds {@code delta} to the wanted quantity, clamped to {@code 1..MAX_QUANTITY}. */
    public synchronized void addQuantity(String id, int delta) {
        ensureLoaded();
        String key = key(id);
        Integer current = state.pins.get(key);
        if (current == null) {
            return;
        }
        int next = Math.max(1, Math.min(MAX_QUANTITY, current + delta));
        if (next != current) {
            state.pins.put(key, next);
            changed();
        }
    }

    /** Picks recipe {@code index} for {@code id} wherever it appears in a tree. */
    public synchronized void chooseRecipe(String id, int index) {
        ensureLoaded();
        String key = key(id);
        Integer previous = index <= 0 ? state.recipeChoices.remove(key) : state.recipeChoices.put(key, index);
        if (previous == null ? index > 0 : previous != index) {
            changed();
        }
    }

    private void changed() {
        shownId = state.shown;
        pinView = Map.copyOf(state.pins);
        revision++;
        save();
    }

    private static String key(String id) {
        return id.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ persistence

    private Path file() {
        return ProfileContext.getInstance().file(FILE);
    }

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    @Override
    public synchronized void reloadProfile() {
        Persisted read = new Persisted();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    Persisted parsed = SBSFiles.GSON.fromJson(reader, Persisted.class);
                    if (parsed != null) {
                        if (parsed.pins != null) {
                            parsed.pins.forEach((id, qty) -> {
                                if (id != null && !id.isEmpty()) {
                                    read.pins.put(key(id), qty == null ? 1 : Math.max(1, Math.min(MAX_QUANTITY, qty)));
                                }
                            });
                        }
                        if (parsed.recipeChoices != null) {
                            parsed.recipeChoices.forEach((id, index) -> {
                                if (id != null && index != null && index > 0) {
                                    read.recipeChoices.put(key(id), index);
                                }
                            });
                        }
                        read.shown = parsed.shown != null && read.pins.containsKey(key(parsed.shown))
                                ? key(parsed.shown) : null;
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file starts empty in memory and is left on disk, so nothing recoverable is
            // overwritten until the player stars something again.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][CraftHelper] could not read {} ({})", FILE, e.toString());
        }
        state = read;
        shownId = read.shown;
        pinView = Map.copyOf(read.pins);
        loaded = true;
        revision++;
    }

    @Override
    public void flushProfile() {
        // Every edit is already queued on the io thread; nothing is held back.
    }

    private void save() {
        // Before the profile is known, writing would file this under a name belonging to nobody.
        if (!ProfileContext.getInstance().known()) {
            return;
        }
        Path path = file();
        String json = SBSFiles.GSON.toJson(state);
        SbsExecutors.io().execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, json, StandardCharsets.UTF_8);
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][CraftHelper] could not write {}", FILE, e);
            }
        });
    }
}
