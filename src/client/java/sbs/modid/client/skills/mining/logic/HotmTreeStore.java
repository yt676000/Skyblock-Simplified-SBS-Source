/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import com.google.gson.JsonSyntaxException;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Heart of the Mountain tree as last read from the menu, cached per account + SkyBlock profile.
 *
 * <p><b>Why cached rather than read on demand.</b> The tree only changes when the player spends
 * powder, so making them reopen the menu every session to see a ranking would be a tax with nothing
 * behind it. Reading it once and remembering is the correct interaction cost.
 *
 * <p><b>Why profile-scoped.</b> A perk tree belongs to one profile. Serving another profile's tree
 * would rank against perks the player does not have, which is worse than having no ranking - it is a
 * plan they cannot follow.
 *
 * <p><b>Staleness is visible, not silent.</b> {@link #readAt()} dates the reading and
 * {@link #invalidate} is called when a spend is detected, so the UI can say the tree may be out of
 * date and ask for a re-read. A ranking quietly computed against a tree from three spends ago is the
 * failure this exists to prevent - it looks current and is not.
 *
 * <p><b>Also the HotM Upgrade Reminder's state</b>: the perks the player watches, the last powder
 * totals seen (the tab forgets them on every world change), and the stale flag - persisted, because
 * the reminder trusts the cached costs across a restart and a tree that went stale before quitting
 * is still stale after.
 */
public final class HotmTreeStore implements ProfileScopedStore {

    private static final HotmTreeStore INSTANCE = new HotmTreeStore();

    /** Live powder changes every few blocks; the file only needs it now and then. */
    private static final long POWDER_SAVE_INTERVAL_MS = 30_000L;

    /** What the file holds: the levels, when they were read, and the powder totals at that moment. */
    private static final class Snapshot {
        Map<String, Integer> levels = new LinkedHashMap<>();
        long readAt;
        /** Powder totals when the tree was read, so a later drop is detectable as a spend. */
        Map<String, Long> powderAtRead = new LinkedHashMap<>();
        Map<String, NodeState> nodes = new LinkedHashMap<>();
        Map<String, Long> menuPowder = new LinkedHashMap<>();
        int tokens = -1;
        int unlockedTier;
        Set<String> watched = new LinkedHashSet<>();
        /** Last powder totals seen anywhere (tab or menu header), upper-case keys. */
        Map<String, Long> lastPowder = new LinkedHashMap<>();
    }

    /**
     * What the menu said about one perk beyond its level: the next step's price, what blocks an
     * unlock, and the max level it printed. Persisted, because the menu only ever quotes the next
     * step and the advisor needs it after the menu is closed.
     */
    public static final class NodeState {
        public int level;
        public int maxLevel;
        public long nextCost;
        public String nextPowder = "";
        /** The perk a locked node's {@code Requires} line names, or null when it is reachable. */
        public String requires;
        public int tier;
        public int column = -1;
    }

    private Snapshot snapshot = new Snapshot();
    private boolean loaded;
    private boolean dirty;
    /** Set when a spend or tier-up was noticed; cleared by the next read. Persisted with the tree. */
    private volatile boolean stale;
    private long lastPowderSaveAt;

    private HotmTreeStore() {
        // Registered from the constructor, per the profile-scoped-store contract: doing it from the
        // client initializer would force this class to load before the item registry is bound.
        ProfileContext.getInstance().register(this);
    }

    public static HotmTreeStore getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ read model

    /** Perk id -> level, as last read. Empty until the menu has been opened once. */
    public Map<String, Integer> levels() {
        load();
        return snapshot.levels;
    }

    /** The level of one perk, or {@code 0} when the tree has not been read or does not carry it. */
    public int level(String perkId) {
        Integer level = levels().get(perkId);
        return level == null ? 0 : level;
    }

    /** When the tree was read, or {@code 0} if never. */
    public long readAt() {
        load();
        return snapshot.readAt;
    }

    /** Whether anything has been read at all. */
    public boolean known() {
        return readAt() > 0 && !levels().isEmpty();
    }

    /** How long ago it was read, or {@code -1} if never. */
    public long ageMs() {
        long at = readAt();
        return at == 0 ? -1L : System.currentTimeMillis() - at;
    }

    /**
     * Whether a spend has been noticed since the reading, so the ranking may be out of date.
     *
     * <p>Distinct from mere age on purpose. An hour-old tree that nobody spent against is perfectly
     * current, and nagging about it would teach the player to ignore the prompt for the one case that
     * matters. This is only true when something actually happened.
     */
    public boolean stale() {
        load();
        return stale;
    }

    /** Perk ids the HotM Upgrade Reminder watches on this profile. */
    public Set<String> watched() {
        load();
        return snapshot.watched;
    }

    /** Watches {@code perkId} if it was not watched, and stops if it was. Returns the new state. */
    public boolean toggleWatched(String perkId) {
        load();
        boolean now = !snapshot.watched.remove(perkId);
        if (now) {
            snapshot.watched.add(perkId);
        }
        dirty = true;
        save();
        return now;
    }

    /**
     * The last powder totals seen on this profile, upper-case keys ({@code MITHRIL}, ...). Survives
     * world changes and restarts, unlike the tab reading.
     */
    public Map<String, Long> lastPowder() {
        load();
        return snapshot.lastPowder;
    }

    /** Records live powder totals (any key case). Written to disk at most every 30 seconds. */
    public void notePowder(Map<String, Long> powder) {
        load();
        if (powder == null || powder.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<String, Long> entry : powder.entrySet()) {
            String key = entry.getKey().toUpperCase(Locale.ROOT);
            if (entry.getValue() != null && entry.getValue() >= 0
                    && !entry.getValue().equals(snapshot.lastPowder.get(key))) {
                snapshot.lastPowder.put(key, entry.getValue());
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastPowderSaveAt >= POWDER_SAVE_INTERVAL_MS) {
            lastPowderSaveAt = now;
            save();
        }
    }

    /** Per-perk menu facts, by perk id. Empty until read. */
    public Map<String, NodeState> nodes() {
        load();
        return snapshot.nodes;
    }

    /** Spendable powder as the menu header stated it (MITHRIL / GEMSTONE / GLACITE). */
    public Map<String, Long> menuPowder() {
        load();
        return snapshot.menuPowder;
    }

    /** Token of the Mountain on hand at the reading, or -1 when never read. */
    public int tokens() {
        load();
        return snapshot.tokens;
    }

    /** The highest tier the menu showed as unlocked. */
    public int unlockedTier() {
        load();
        return snapshot.unlockedTier;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Merges one menu page into the tree. Merged rather than replaced, because the menu scrolls: the
     * top tiers and the bottom tiers are different pages, and replacing would forget whichever page
     * the player is not looking at.
     */
    public void recordPage(Map<String, NodeState> pageNodes, Map<String, Long> headerPowder, int tokens,
                           int unlockedTier, Map<String, Long> powderNow) {
        load();
        if (pageNodes == null || pageNodes.isEmpty()) {
            return;
        }
        for (Map.Entry<String, NodeState> entry : pageNodes.entrySet()) {
            snapshot.nodes.put(entry.getKey(), entry.getValue());
            snapshot.levels.put(entry.getKey(), entry.getValue().level);
        }
        if (headerPowder != null && !headerPowder.isEmpty()) {
            snapshot.menuPowder = new LinkedHashMap<>(headerPowder);
            snapshot.lastPowder.putAll(headerPowder);
        }
        if (tokens >= 0) {
            snapshot.tokens = tokens;
        }
        snapshot.unlockedTier = Math.max(snapshot.unlockedTier, unlockedTier);
        snapshot.readAt = System.currentTimeMillis();
        snapshot.powderAtRead = powderNow == null ? new LinkedHashMap<>() : new LinkedHashMap<>(powderNow);
        stale = false;
        dirty = true;
        save();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] tree page recorded: {} node(s), {} known in total",
                pageNodes.size(), snapshot.nodes.size());
    }

    /**
     * Marks the cached tree as possibly out of date. Called when a spend is detected - either from a
     * chat confirmation or from a powder total that dropped below what it was at the reading.
     */
    public void invalidate(String reason) {
        load();
        if (!known() || stale) {
            return;
        }
        stale = true;
        dirty = true;
        save();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] tree marked stale: {}", reason);
    }

    /** The powder totals captured at the reading, for the spend check. */
    public Map<String, Long> powderAtRead() {
        load();
        return snapshot.powderAtRead;
    }

    // ------------------------------------------------------------------ persistence

    private Path file() {
        return ProfileContext.getInstance().file("hotm-tree.json");
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!ProfileContext.getInstance().known()) {
            // No profile yet: keep the empty snapshot and try again after the context resolves.
            loaded = false;
            return;
        }
        try {
            Path path = file();
            if (!Files.isRegularFile(path)) {
                return;
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Stored stored = SBSFiles.GSON.fromJson(json, Stored.class);
            if (stored != null && stored.levels != null) {
                snapshot.levels = new LinkedHashMap<>(stored.levels);
                snapshot.readAt = stored.readAt;
                snapshot.powderAtRead = stored.powderAtRead == null
                        ? new LinkedHashMap<>() : new LinkedHashMap<>(stored.powderAtRead);
                // Absent in files written before the advisor: empty, never null.
                snapshot.nodes = stored.nodes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stored.nodes);
                snapshot.menuPowder = stored.menuPowder == null
                        ? new LinkedHashMap<>() : new LinkedHashMap<>(stored.menuPowder);
                snapshot.tokens = stored.tokens == null ? -1 : stored.tokens;
                snapshot.unlockedTier = stored.unlockedTier;
                // Absent in files written before the upgrade reminder: empty / not stale.
                snapshot.watched = stored.watched == null
                        ? new LinkedHashSet<>() : new LinkedHashSet<>(stored.watched);
                snapshot.lastPowder = stored.lastPowder == null
                        ? new LinkedHashMap<>() : new LinkedHashMap<>(stored.lastPowder);
                stale = stored.stale;
            }
        } catch (IOException | JsonSyntaxException e) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Hotm] cached tree unreadable, will re-read from the menu: {}", e.toString());
        }
    }

    /** The on-disk shape. Kept separate from {@link Snapshot} so the file format is explicit. */
    private static final class Stored {
        Map<String, Integer> levels = new LinkedHashMap<>();
        long readAt;
        Map<String, Long> powderAtRead = new LinkedHashMap<>();
        Map<String, NodeState> nodes = new LinkedHashMap<>();
        Map<String, Long> menuPowder = new LinkedHashMap<>();
        /** Boxed so a file written before this field reads as "unknown", not as zero tokens. */
        Integer tokens;
        int unlockedTier;
        Set<String> watched = new LinkedHashSet<>();
        Map<String, Long> lastPowder = new LinkedHashMap<>();
        boolean stale;
    }

    /**
     * Writes the tree on {@link SbsExecutors#io()}. The JSON is built here, on the caller's thread,
     * so the writer never touches the live maps; the path is resolved here too, so a profile switch
     * queued behind the write cannot redirect it to the next profile's file.
     */
    private void save() {
        if (!dirty || !ProfileContext.getInstance().known()) {
            return;
        }
        dirty = false;
        Stored stored = new Stored();
        stored.levels = snapshot.levels;
        stored.readAt = snapshot.readAt;
        stored.powderAtRead = snapshot.powderAtRead;
        stored.nodes = snapshot.nodes;
        stored.menuPowder = snapshot.menuPowder;
        stored.tokens = snapshot.tokens;
        stored.unlockedTier = snapshot.unlockedTier;
        stored.watched = snapshot.watched;
        stored.lastPowder = snapshot.lastPowder;
        stored.stale = stale;
        String json = SBSFiles.GSON.toJson(stored);
        Path path = file();
        SbsExecutors.io().execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Hotm] could not write the cached tree", e);
            }
        });
    }

    @Override
    public void flushProfile() {
        save();
    }

    @Override
    public void reloadProfile() {
        snapshot = new Snapshot();
        stale = false;
        loaded = false;
        dirty = false;
        lastPowderSaveAt = 0L;
        load();
    }
}
