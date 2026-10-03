/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which Enigma Souls this account+profile has collected, and what Hypixel says the total is.
 *
 * <p><b>Better off than the Fairy Souls, for one reason.</b> Hypixel's collection message carries
 * both halves - "You have found 31/52 Souls in the Rift Dimension!" - so the client gets an
 * authoritative count for free, every time a soul is picked up, without opening a menu. That is what
 * makes the three-state model work: the count is the truth, the local set is what the client
 * witnessed, and the difference between them is the honestly-unknown part rather than something to
 * be reconciled away.
 *
 * <p><b>Per account and per SkyBlock profile</b> via {@link ProfileScopedStore}, because souls are
 * collected per profile and an Ironman's progress must never leak into the main's.
 *
 * <p><b>Ids, not coordinates.</b> Records key off {@link
 * sbs.modid.client.helper.rift.model.EnigmaSoul#id}, so the coordinate file can be corrected or
 * replaced wholesale without disturbing anybody's progress - and an id the current data file no
 * longer contains is kept rather than pruned, because a soul missing from a partial file has not
 * been un-collected.
 */
public final class EnigmaSoulStore implements ProfileScopedStore {

    private static final EnigmaSoulStore INSTANCE = new EnigmaSoulStore();

    private static final String FILE = "enigma_souls.json";

    /** The on-disk shape. A class rather than a record so Gson can build it field by field. */
    private static final class Persisted {
        /** Souls this client witnessed being collected, by id. */
        Set<String> collected = new LinkedHashSet<>();

        /**
         * Souls named by Hypixel's own API, by id. Kept apart from {@link #collected} so a stale or
         * wrong id mapping can be dropped wholesale without touching what the client actually saw.
         */
        Set<String> fromApi = new LinkedHashSet<>();

        /** The last count Hypixel stated, and its denominator; {@code -1} means never seen. */
        int foundCount = -1;
        int totalCount = -1;

        /** When the count was last seen, epoch millis; {@code 0} means never. */
        long countSeenAt;
    }

    private volatile Persisted state = new Persisted();
    private volatile boolean loaded;

    private EnigmaSoulStore() {
        ProfileContext.getInstance().register(this);
    }

    public static EnigmaSoulStore getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ queries

    /** Whether this soul is on record as collected, from either source. */
    /**
     * Whether this record can be believed yet.
     *
     * <p>Until the profile is known every query here answers from an empty record. That is not a
     * harmless "not loaded": with no count seen, {@link #recordComplete()} is false and
     * {@link #isCollected} false for everything, so every soul in the Rift resolves to
     * {@code UNKNOWN} and the whole field is drawn as maybes for the first seconds of a session.
     * Callers that draw must wait for this rather than show a picture they will retract.
     */
    public boolean ready() {
        return ProfileContext.getInstance().known();
    }

    public boolean isCollected(String soulId) {
        ensureLoaded();
        return soulId != null
                && (state.collected.contains(soulId) || state.fromApi.contains(soulId));
    }

    /** How many souls are individually on record. */
    public int trackedCount() {
        ensureLoaded();
        Set<String> union = new LinkedHashSet<>(state.collected);
        union.addAll(state.fromApi);
        return union.size();
    }

    /** What Hypixel last said had been found, or {@code -1} when the line has never been seen. */
    public int reportedFound() {
        ensureLoaded();
        return state.foundCount;
    }

    /** The denominator from the same line - the real number of souls in the game, or {@code -1}. */
    public int reportedTotal() {
        ensureLoaded();
        return state.totalCount;
    }

    /** When the count was last read, epoch millis; {@code 0} means never. */
    public long countSeenAt() {
        ensureLoaded();
        return state.countSeenAt;
    }

    /**
     * How many found souls the record cannot name - the size of the honestly-unknown set.
     *
     * <p>Clamped at zero: the record can legitimately exceed the count for a moment (the client
     * witnesses the pickup on the same tick the line arrives), and a negative gap would flip every
     * unknown to missing for that frame.
     */
    public int unaccounted() {
        ensureLoaded();
        if (state.foundCount < 0) {
            return -1;
        }
        return Math.max(0, state.foundCount - trackedCount());
    }

    /**
     * Whether the record accounts for every soul Hypixel says has been found - i.e. whether an
     * un-recorded soul can be called missing rather than unknown.
     *
     * <p>False when the count has never been seen: with no count there is nothing to be complete
     * against, and assuming completeness on an established profile is exactly the wrong guess.
     */
    public boolean recordComplete() {
        return state.foundCount >= 0 && unaccounted() == 0;
    }

    // ------------------------------------------------------------------ mutations

    /**
     * Records a soul as collected by witness. Returns whether this changed anything.
     *
     * <p>Callers must be <b>certain</b>: a false positive permanently hides a soul the player still
     * needs. Unlike the Fairy Souls this is recoverable - the count will disagree and the settings
     * page says so - but it is still the failure worth being strict about.
     */
    public boolean markCollected(String soulId) {
        ensureLoaded();
        if (soulId == null || soulId.isBlank() || !state.collected.add(soulId)) {
            return false;
        }
        save();
        return true;
    }

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
     * Replaces the API-sourced set wholesale.
     *
     * <p>Wholesale rather than merged because the API is authoritative about its own answer: a soul
     * it no longer names is one whose id mapping was wrong, and keeping it would make a bad mapping
     * permanent. What the client witnessed is untouched.
     */
    public void setApiCollected(Set<String> ids) {
        ensureLoaded();
        Set<String> incoming = ids == null ? new LinkedHashSet<>() : new LinkedHashSet<>(ids);
        if (incoming.equals(state.fromApi)) {
            return;
        }
        state.fromApi = incoming;
        save();
    }

    /**
     * Records the count Hypixel stated. {@code total} is its denominator, which is how the real
     * number of souls in the game gets known without shipping it as a constant.
     */
    public void recordCount(int found, int total) {
        ensureLoaded();
        if (found < 0) {
            return;
        }
        boolean changed = state.foundCount != found || state.totalCount != total;
        state.foundCount = found;
        if (total > 0) {
            state.totalCount = total;
        }
        state.countSeenAt = System.currentTimeMillis();
        if (changed) {
            save();
        }
    }

    /** Clears everything for the current profile. */
    public void reset() {
        ensureLoaded();
        state.collected.clear();
        state.fromApi.clear();
        state.foundCount = -1;
        state.totalCount = -1;
        state.countSeenAt = 0L;
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
                        read.fromApi = parsed.fromApi == null
                                ? new LinkedHashSet<>() : parsed.fromApi;
                        read.foundCount = parsed.foundCount;
                        read.totalCount = parsed.totalCount;
                        read.countSeenAt = parsed.countSeenAt;
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file must not wipe progress silently - keep it on disk and start empty in
            // memory so the next save does not overwrite what might be recoverable.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Rift] could not read {} ({})", FILE, e.toString());
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
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Rift] could not write {}", FILE, e);
        }
    }
}
