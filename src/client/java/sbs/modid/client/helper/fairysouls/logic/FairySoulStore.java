/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which Fairy Souls this account+profile has collected, as far as the client has been able to tell.
 *
 * <p><b>Why this is local at all.</b> Hypixel publishes a <i>count</i> and nothing else - the API's
 * {@code fairy_soul.total_collected} says 180, never which 180, and the Quest Log's Fairy Souls Guide
 * breaks that down per island but still never names a soul. So per-soul state can only be built from
 * what the client itself witnesses, and it necessarily starts empty for an existing player. That gap
 * is not hidden: see {@link FairySoulTracker#reconciliation()}.
 *
 * <p><b>Two kinds of record, kept apart.</b> {@code collected} holds souls witnessed individually;
 * {@code islandsDone} holds islands known finished as a whole; {@code menu} holds the Quest Log's
 * per-island counts, which are evidence rather than progress and are what {@link FairySoulMenu} sets
 * the island flags from.
 *
 * <p><b>Per account and per SkyBlock profile</b>, via {@link ProfileScopedStore} - souls are collected
 * per profile, so an Ironman's progress must never leak into the main's. The context flushes and
 * reloads this on every profile switch.
 *
 * <p><b>Ids, not coordinates.</b> Records key off {@link sbs.modid.client.helper.fairysouls.model.FairySoul#id},
 * so the coordinate file can be corrected or replaced wholesale without disturbing anybody's
 * progress - and an id the current data file no longer contains is kept rather than pruned, because
 * a soul missing from a partial data file has not been un-collected.
 */
public final class FairySoulStore implements ProfileScopedStore {

    private static final FairySoulStore INSTANCE = new FairySoulStore();

    private static final String FILE = "fairy_souls.json";

    /** One island's numbers exactly as the Quest Log's Fairy Souls Guide stated them. */
    public static final class Progress {
        public int found;
        public int total;

        /** Gson needs a no-arg constructor. */
        public Progress() {
        }

        public boolean complete() {
            return total > 0 && found >= total;
        }
    }

    /** The on-disk shape. A class rather than a record so Gson can build it field by field. */
    private static final class Persisted {
        Set<String> collected = new LinkedHashSet<>();
        Set<String> islandsDone = new LinkedHashSet<>();
        /** Island -> what the Quest Log said, kept so the numbers survive between menu opens. */
        Map<String, Progress> menu = new LinkedHashMap<>();
        /** When the guide was last read, epoch millis; {@code 0} means never. */
        long menuReadAt;
    }

    private volatile Persisted state = new Persisted();
    private volatile boolean loaded;

    private FairySoulStore() {
        ProfileContext.getInstance().register(this);
    }

    public static FairySoulStore getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ queries

    /**
     * Whether this record can be believed yet.
     *
     * <p>Until the profile is known every query here answers from an empty record, and "nothing is
     * collected" is indistinguishable from a genuine fresh start. Callers that would draw or route
     * on that answer must wait: on a profile with souls already found it is not merely unhelpful,
     * it is wrong, and it is what made a field of pink markers flash up for a moment on every join.
     */
    public boolean ready() {
        return ProfileContext.getInstance().known();
    }

    /** Whether this soul is on record as collected - either individually, or by its island. */
    public boolean isCollected(String soulId, String island) {
        ensureLoaded();
        if (soulId == null) {
            return false;
        }
        return state.collected.contains(soulId) || isIslandDone(island);
    }

    /** Whether the player declared this whole island finished. */
    public boolean isIslandDone(String island) {
        ensureLoaded();
        return island != null && state.islandsDone.contains(island);
    }

    /** How many individual souls are on record (excluding whole-island declarations). */
    public int trackedCount() {
        ensureLoaded();
        return state.collected.size();
    }

    /** The islands the player has marked finished. */
    public Set<String> islandsDone() {
        ensureLoaded();
        return Set.copyOf(state.islandsDone);
    }

    /** What the Quest Log last said about this island, or {@code null} if it has not been read. */
    public Progress menuProgress(String island) {
        ensureLoaded();
        return island == null ? null : state.menu.get(island);
    }

    /** When the Fairy Souls Guide was last read, epoch millis; {@code 0} means never. */
    public long menuReadAt() {
        ensureLoaded();
        return state.menuReadAt;
    }

    /**
     * The account-wide souls-found total across every island the guide listed.
     *
     * <p>Only the islands, so this is deliberately <i>not</i> the number Hypixel shows as the grand
     * total: the guide's "Miscellaneous" tile has no island and is skipped on the way in, and
     * pretending otherwise would make the reconciliation line compare two different quantities.
     */
    public int menuFoundOnIslands() {
        ensureLoaded();
        int sum = 0;
        for (Progress progress : state.menu.values()) {
            sum += progress.found;
        }
        return sum;
    }

    /** The matching denominator for {@link #menuFoundOnIslands()}. */
    public int menuTotalOnIslands() {
        ensureLoaded();
        int sum = 0;
        for (Progress progress : state.menu.values()) {
            sum += progress.total;
        }
        return sum;
    }

    // ------------------------------------------------------------------ mutations

    /**
     * Records a soul as collected. Returns whether this changed anything.
     *
     * <p>Callers must be <b>certain</b> before calling: a false positive permanently hides a soul the
     * player still needs, which is the one failure mode this feature cannot recover from on its own.
     * {@link FairySoulTracker} therefore only calls this on an unambiguous match.
     */
    public synchronized boolean markCollected(String soulId) {
        ensureLoaded();
        if (soulId == null || soulId.isBlank() || !state.collected.add(soulId)) {
            return false;
        }
        if (!ProfileContext.getInstance().known()) {
            // save() cannot write yet, and the reload that the real profile triggers replaces this
            // in-memory record - so a soul collected in that window used to be lost. Hold it and
            // replay it into the real profile's record (2026-09-26).
            pendingCollected.add(soulId);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] {} held until the profile is "
                    + "known", soulId);
        }
        save();
        return true;
    }

    /** Souls collected before the profile resolved, replayed by {@link #reloadProfile()}. */
    private final java.util.Set<String> pendingCollected = new LinkedHashSet<>();

    /** Un-records a soul - the undo for a mistaken mark. */
    public boolean unmarkCollected(String soulId) {
        ensureLoaded();
        if (soulId == null || !state.collected.remove(soulId)) {
            return false;
        }
        save();
        return true;
    }

    /**
     * Declares every soul on an island collected, without inventing per-soul records.
     *
     * <p>Stored as a separate island flag rather than by expanding it into ids on purpose: the data
     * file grows, and an island "finished" in a build that knew 22 souls should still read finished
     * when a later file adds the 23rd - the player said the island is done, not "these 22 are done".
     */
    public boolean markIslandDone(String island, boolean done) {
        ensureLoaded();
        if (island == null || island.isBlank()) {
            return false;
        }
        boolean changed = done ? state.islandsDone.add(island) : state.islandsDone.remove(island);
        if (changed) {
            save();
        }
        return changed;
    }

    /**
     * Records what the Quest Log's Fairy Souls Guide reported for one island.
     *
     * <p>Stored rather than merely acted on: the menu is only readable while it is open, and every
     * status line the module shows would otherwise go blank the moment the player closes it.
     */
    public void recordMenuProgress(String island, int found, int total) {
        ensureLoaded();
        if (island == null || island.isBlank() || total <= 0) {
            return;
        }
        Progress progress = state.menu.computeIfAbsent(island, key -> new Progress());
        boolean changed = progress.found != found || progress.total != total;
        progress.found = found;
        progress.total = total;
        state.menuReadAt = System.currentTimeMillis();
        if (changed) {
            save();
        }
    }

    /** Clears everything for the current profile. */
    public void reset() {
        ensureLoaded();
        state.collected.clear();
        state.islandsDone.clear();
        state.menu.clear();
        state.menuReadAt = 0L;
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
        Persisted read = new Persisted();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    Persisted parsed = SBSFiles.GSON.fromJson(reader, Persisted.class);
                    if (parsed != null) {
                        read.collected = parsed.collected == null
                                ? new LinkedHashSet<>() : parsed.collected;
                        read.islandsDone = parsed.islandsDone == null
                                ? new LinkedHashSet<>() : parsed.islandsDone;
                        // Absent in files written before the Quest Log read existed.
                        read.menu = parsed.menu == null ? new LinkedHashMap<>() : parsed.menu;
                        read.menuReadAt = parsed.menuReadAt;
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file must not wipe the player's progress silently - keep it on disk and
            // start empty in memory so the next save does not overwrite what might be recoverable.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][FairySouls] could not read {} ({})",
                    FILE, e.toString());
            loaded = true;
            state = read;
            return;
        }
        state = read;
        loaded = true;
        if (!pendingCollected.isEmpty() && ProfileContext.getInstance().known()) {
            state.collected.addAll(pendingCollected);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] replayed {} soul(s) collected "
                    + "before the profile was known", pendingCollected.size());
            pendingCollected.clear();
            save();
        }
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        // Before the profile is known, writing would file this progress under a name that belongs
        // to nobody. Same window {@link #ready()} makes readers wait for.
        if (!ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][FairySouls] could not write {}", FILE, e);
        }
    }
}
