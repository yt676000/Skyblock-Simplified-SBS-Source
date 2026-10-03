/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.skills.foraging.model.HoneyTimer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The running honey tree cooldowns for <b>one Minecraft account + one SkyBlock profile</b>.
 *
 * <p><b>Why per profile.</b> A cooldown belongs to the profile that smeared the tree; an Ironman's
 * timers must not show up on the main's Galatea. {@link ProfileScopedStore} is the shape that
 * already solves that, and it flushes and reloads across a switch on its own.
 *
 * <p><b>Why on disk at all.</b> The whole point of the feature is that the timer outlives the
 * session - a relog, a server hop, an island change or a crash in the middle of a 15-minute
 * cooldown. In memory it would be a feature that only works if you never leave, which is the case
 * it is least needed in.
 *
 * <p><b>Nothing here counts.</b> The store holds start timestamps and durations; the remaining time
 * is computed from the wall clock every time it is asked for. That is what makes a restored file
 * correct rather than merely present.
 *
 * <p><b>Registered from its own constructor</b>, not from the client initializer - registering
 * early forces classes to load before the item registry is bound ({@code AGENTS.md}, per-profile
 * state).
 */
public final class HoneyTimerStore implements ProfileScopedStore {

    private static final HoneyTimerStore INSTANCE = new HoneyTimerStore();

    private static final String FILE = "honey_timers.json";

    /**
     * A ceiling on stored entries, so a bug in the arming path cannot grow the file without bound.
     * Well above the ten trees the shipped data knows about, and the oldest go first.
     */
    private static final int MAX_ENTRIES = 200;

    /** The on-disk shape. A class rather than a record so Gson can build it field by field. */
    private static final class Persisted {
        Map<String, HoneyTimer> timers = new LinkedHashMap<>();
    }

    private volatile Persisted state = new Persisted();
    private volatile boolean loaded;

    private HoneyTimerStore() {
        ProfileContext.getInstance().register(this);
    }

    public static HoneyTimerStore getInstance() {
        return INSTANCE;
    }

    /**
     * Whether these records can be believed yet.
     *
     * <p>Before the profile is known every query answers from an empty map, and "no timers" is
     * indistinguishable from a genuine fresh start. Callers that would draw on that answer wait -
     * the same window {@code FairySoulStore.ready()} exists for, and the same reason: a field of
     * markers flashing up wrong for a moment on every join.
     */
    public boolean ready() {
        return ProfileContext.getInstance().known();
    }

    /** Every stored timer, in no particular order. A copy - callers sort and filter their own. */
    public List<HoneyTimer> all() {
        ensureLoaded();
        synchronized (this) {
            return new ArrayList<>(state.timers.values());
        }
    }

    /** The timer for this key, or {@code null}. */
    public HoneyTimer get(String key) {
        ensureLoaded();
        if (key == null) {
            return null;
        }
        synchronized (this) {
            return state.timers.get(key);
        }
    }

    /**
     * Stores a timer, replacing any entry under the same key.
     *
     * <p>Replacement <i>is</i> the "smeared a tree that already has a running timer" case: same
     * tree, same key, new start. There is deliberately no merge and no refusal - a second smear is
     * the player telling us the cooldown restarted.
     */
    public void put(HoneyTimer timer) {
        ensureLoaded();
        if (timer == null || !timer.valid()) {
            return;
        }
        synchronized (this) {
            state.timers.put(timer.key, timer);
            trim();
        }
        save();
    }

    /** Drops one timer. Returns whether anything was there. */
    public boolean remove(String key) {
        ensureLoaded();
        if (key == null) {
            return false;
        }
        boolean removed;
        synchronized (this) {
            removed = state.timers.remove(key) != null;
        }
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * Drops every entry that has been ready for longer than {@code afterMs}.
     *
     * @return how many were dropped, so the caller can decide whether a save is worth it
     */
    public int prune(long now, long afterMs) {
        ensureLoaded();
        int removed;
        synchronized (this) {
            int before = state.timers.size();
            state.timers.values().removeIf(timer ->
                    !timer.valid() || timer.expiredForMs(now) > afterMs);
            removed = before - state.timers.size();
        }
        if (removed > 0) {
            save();
        }
        return removed;
    }

    /** Clears every timer for the current profile - the settings page's reset. */
    public int clear() {
        ensureLoaded();
        int removed;
        synchronized (this) {
            removed = state.timers.size();
            state.timers.clear();
        }
        if (removed > 0) {
            save();
        }
        return removed;
    }

    /** Persists a change made in place on an entry this store already holds. */
    public void touch() {
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

    /** Oldest first, so the ceiling drops the least interesting entries. */
    private void trim() {
        while (state.timers.size() > MAX_ENTRIES) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, HoneyTimer> entry : state.timers.entrySet()) {
                if (entry.getValue().startedAt < oldestAt) {
                    oldestAt = entry.getValue().startedAt;
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) {
                return;
            }
            state.timers.remove(oldest);
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
                    if (parsed != null && parsed.timers != null) {
                        for (Map.Entry<String, HoneyTimer> entry : parsed.timers.entrySet()) {
                            HoneyTimer timer = entry.getValue();
                            if (timer == null) {
                                continue;
                            }
                            // A file written by hand, or by a build with a different key shape,
                            // must not produce an entry whose key does not match its own map slot -
                            // every later lookup would miss it and it could never be replaced.
                            if (timer.key == null || timer.key.isBlank()) {
                                timer.key = entry.getKey();
                            }
                            if (timer.valid()) {
                                read.timers.put(timer.key, timer);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file is expected and logged, not fatal: the file stays on disk so it is
            // still recoverable, and memory starts empty so the next save does not overwrite it
            // with nothing.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Honey] could not read {} ({})",
                    FILE, e.toString());
            loaded = true;
            state = read;
            return;
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
        // Before the profile is known, writing would file these timers under a name that belongs to
        // nobody. Same window ready() makes readers wait for.
        if (!loaded || !ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Honey] could not write {} ({})",
                    FILE, e.toString());
        }
    }
}
