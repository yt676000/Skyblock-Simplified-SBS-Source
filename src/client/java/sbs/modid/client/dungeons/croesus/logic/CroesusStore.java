/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.croesus.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How many runs still owed you a chest the last time Croesus was open, and when that was.
 *
 * <p><b>Per account and profile</b>, because that is what the number belongs to: chests are earned
 * and claimed on one profile, and carrying the count across a profile switch would report another
 * character's leftovers.
 *
 * <p><b>The timestamp is not optional, and it is the whole reason this class stores two fields
 * instead of one.</b> This is a remembered number: it was true when the menu was open and it can
 * have been made false by anything since, including opening a chest in another session. Every reader
 * therefore gets {@link #ageMs()} beside the count and is expected to show it, so what the player
 * reads is "3, when you last looked" and never "3, now".
 *
 * <p><b>Writes only on a change.</b> The scan that feeds this runs four times a second while the
 * menu is open, and saving on each would be a file write per frame-ish for a number that changes
 * when a chest is claimed. Identical counts are dropped before they reach the disk.
 */
public final class CroesusStore implements ProfileScopedStore {

    private static final CroesusStore INSTANCE = new CroesusStore();

    private static final String FILE = "croesus.json";

    /** The on-disk shape. A class rather than a record so Gson can fill it field by field. */
    private static final class Persisted {
        /** Runs whose chests were not all opened, as of {@link #seenAt}. */
        int unopenedRuns;
        /** When Croesus was last read, epoch millis; {@code 0} means never. */
        long seenAt;
    }

    private volatile Persisted state = new Persisted();
    private volatile boolean loaded;

    private CroesusStore() {
        ProfileContext.getInstance().register(this);
    }

    public static CroesusStore getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ queries

    /** Runs that still owed a chest when Croesus was last open. {@code 0} when never read. */
    public int unopenedRuns() {
        ensureLoaded();
        return state.unopenedRuns;
    }

    /** Whether Croesus has ever been read on this profile. */
    public boolean known() {
        ensureLoaded();
        return state.seenAt > 0L;
    }

    /** How long ago that reading was, in millis. Meaningless unless {@link #known()}. */
    public long ageMs() {
        ensureLoaded();
        return state.seenAt <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - state.seenAt);
    }

    /** "just now" / "14m ago" / "3h ago" - the age in the words a reminder needs. */
    public String ageText() {
        long seconds = ageMs() / 1000L;
        if (seconds < 60L) {
            return "just now";
        }
        if (seconds < 3600L) {
            return (seconds / 60L) + "m ago";
        }
        return (seconds / 3600L) + "h ago";
    }

    // ------------------------------------------------------------------ writing

    /**
     * Records what the open menu showed.
     *
     * <p>The timestamp is refreshed even when the count has not changed - "still 3, and I checked a
     * minute ago" is a different claim from "still 3, and I checked yesterday" - but the file is only
     * rewritten when the count moves or the stored time is old enough to be worth correcting. That
     * keeps a menu left open from writing the same file four times a second.
     */
    public void record(int unopenedRuns) {
        ensureLoaded();
        long now = System.currentTimeMillis();
        boolean changed = state.unopenedRuns != unopenedRuns;
        state.unopenedRuns = unopenedRuns;
        long previous = state.seenAt;
        state.seenAt = now;
        if (changed || now - previous >= 60_000L) {
            save();
        }
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
                        read.unopenedRuns = Math.max(0, parsed.unopenedRuns);
                        read.seenAt = Math.max(0L, parsed.seenAt);
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file starts empty in memory and is left on disk: this is a convenience
            // number, and overwriting something recoverable to save it would be the worse trade.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Croesus] could not read {} ({})",
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
        // Before the profile is known, writing would file this under a name belonging to nobody.
        if (!ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Croesus] could not write {}", FILE, e);
        }
    }
}
