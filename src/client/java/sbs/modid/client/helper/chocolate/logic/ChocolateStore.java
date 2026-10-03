/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.helper.chocolate.model.ChocolateSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The last look at the Chocolate Factory, kept per account and SkyBlock profile.
 *
 * <p>The factory is per profile - a different profile is a different factory with its own
 * chocolate, employees and barn - so this goes through {@link ProfileScopedStore} like every other
 * witnessed-progress store, and registers in its own constructor rather than from the client
 * initializer.
 *
 * <p>It exists so the Time Tower and barn cards have anything to say once the menu is shut. What it
 * holds is history, and every reader of it prints the age alongside.
 */
public final class ChocolateStore implements ProfileScopedStore {

    private static final ChocolateStore INSTANCE = new ChocolateStore();

    private static final String FILE = "chocolate.json";

    private volatile ChocolateSnapshot snapshot = new ChocolateSnapshot();
    private volatile boolean loaded;

    private ChocolateStore() {
        ProfileContext.getInstance().register(this);
    }

    public static ChocolateStore getInstance() {
        return INSTANCE;
    }

    /** The last capture for this profile. Never null; {@code empty()} while nothing was read. */
    public ChocolateSnapshot snapshot() {
        ensureLoaded();
        return snapshot;
    }

    /** Replaces the capture wholesale - a menu read describes the factory as it is, not as a delta. */
    public void record(ChocolateSnapshot fresh) {
        ensureLoaded();
        if (fresh == null) {
            return;
        }
        fresh.capturedAt = System.currentTimeMillis();
        snapshot = fresh;
        save();
    }

    /** Forgets this profile's capture. */
    public void reset() {
        ensureLoaded();
        snapshot = new ChocolateSnapshot();
        save();
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
        ChocolateSnapshot read = new ChocolateSnapshot();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    ChocolateSnapshot parsed = SBSFiles.GSON.fromJson(reader, ChocolateSnapshot.class);
                    if (parsed != null) {
                        read = parsed;
                        if (read.upgrades == null) {
                            read.upgrades = new java.util.ArrayList<>();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file is expected and logged rather than thrown: the feature degrades to
            // "nothing captured yet", which is a state it already knows how to draw.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Chocolate] could not read {} ({})",
                    FILE, e.toString());
        }
        snapshot = read;
        loaded = true;
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        // "default" is the placeholder before the profile context knows where it is; writing there
        // files this profile's capture under a name that belongs to nobody.
        if (ProfileContext.getInstance().profile().equals("default")) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(snapshot), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Chocolate] could not write {}", FILE, e);
        }
    }
}
