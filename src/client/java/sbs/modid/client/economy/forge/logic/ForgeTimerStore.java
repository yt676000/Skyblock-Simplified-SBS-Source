/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.economy.forge.model.ForgeSlotTimer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The forge slots of <b>one Minecraft account + one SkyBlock profile</b>, as the forge menu last
 * showed them.
 *
 * <p>On disk because the forge runs while the player is offline: the whole point is a card that
 * is right after a relog, and an alert on join for a slot that finished in the meantime. Entries
 * hold wall-clock end times, so a restored file is correct rather than merely present.
 *
 * <p>Registered from its own constructor ({@code AGENTS.md}, per-profile state).
 */
public final class ForgeTimerStore implements ProfileScopedStore {

    private static final ForgeTimerStore INSTANCE = new ForgeTimerStore();

    private static final String FILE = "forge_timers.json";

    /** More than the forge has slots; a ceiling so a misread menu cannot grow the file. */
    private static final int MAX_SLOTS = 16;

    private static final class Persisted {
        /** Keyed by menu slot index; sorted so the file reads in slot order. */
        Map<Integer, ForgeSlotTimer> slots = new TreeMap<>();
    }

    private volatile Persisted state = new Persisted();
    private volatile boolean loaded;

    private ForgeTimerStore() {
        ProfileContext.getInstance().register(this);
    }

    public static ForgeTimerStore getInstance() {
        return INSTANCE;
    }

    /** Whether the profile is known, so "no slots" means no slots and not "not loaded yet". */
    public boolean ready() {
        return ProfileContext.getInstance().known();
    }

    public List<ForgeSlotTimer> all() {
        ensureLoaded();
        synchronized (this) {
            return new ArrayList<>(state.slots.values());
        }
    }

    public ForgeSlotTimer get(int slot) {
        ensureLoaded();
        synchronized (this) {
            return state.slots.get(slot);
        }
    }

    /**
     * Replaces everything with one reading of the menu. The menu is the authority: a slot it no
     * longer shows as running or ready has been claimed or emptied, so it goes.
     */
    public void replaceAll(List<ForgeSlotTimer> read) {
        ensureLoaded();
        synchronized (this) {
            state.slots.clear();
            for (ForgeSlotTimer timer : read) {
                if (timer != null && timer.valid() && state.slots.size() < MAX_SLOTS) {
                    state.slots.put(timer.slot, timer);
                }
            }
        }
        save();
    }

    public int clear() {
        ensureLoaded();
        int removed;
        synchronized (this) {
            removed = state.slots.size();
            state.slots.clear();
        }
        if (removed > 0) {
            save();
        }
        return removed;
    }

    /** Persists a change made in place (the alert flag). */
    public void touch() {
        save();
    }

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
                    if (parsed != null && parsed.slots != null) {
                        for (ForgeSlotTimer timer : parsed.slots.values()) {
                            if (timer != null && timer.valid()) {
                                read.slots.put(timer.slot, timer);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file is logged, not fatal; it stays on disk and memory starts empty.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][ForgeTimer] could not read {} ({})",
                    FILE, e.toString());
        }
        state = read;
        loaded = true;
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        if (!loaded || !ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][ForgeTimer] could not write {} ({})",
                    FILE, e.toString());
        }
    }
}
