/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.skills.hunting.model.ShardData;
import sbs.modid.client.skills.hunting.model.ShardDefinition;
import sbs.modid.client.skills.hunting.model.ShardId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which shards exist - the bundled catalogue, loaded through the shared {@link VersionedDataStore}.
 *
 * <p><b>This used to be learned at runtime, and that was the bug behind the bug.</b> The list was
 * assembled from the live Bazaar product map plus whatever menus the player had opened, which made
 * "what shards exist" depend on what the GUI had shown and on whether a network pull had landed. A
 * shard the menu never displayed could not be reported missing; a slow or rate-limited pull shrank
 * the catalogue silently; and the first seconds of every session had no catalogue at all. The set of
 * shards that exist is static data, so it is now a versioned file like every other curated dataset
 * here - present before the first frame, identical on every client, and never a function of what
 * happens to be on screen.
 *
 * <p><b>Nothing in this class knows what anybody owns</b>; that is {@link ShardOwnership}. The split
 * is what lets a newer catalogue arrive mid-session without disturbing one player record.
 *
 * <p><b>Three indexes, because three different things arrive from Hypixel.</b> The identity key
 * ({@code ATTRIBUTE_SHARD_<NAME>}) is what everything is stored under; a display name is what the
 * Hunting Box and the fusion menus hand over; and Hypixel's own short id ({@code C12}) is what the
 * Attribute Menu's {@code Source:} line carries. All three land on the same {@link ShardDefinition}.
 */
public final class ShardCatalog {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/hunting/shards.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 1;

    /**
     * The store, built on first use rather than at class-load.
     *
     * <p>{@link SBSFiles#root()} needs a game directory, so constructing it in a static initialiser
     * makes merely <i>mentioning</i> this class fail outside a running client - which took the
     * levelling table's own unit test down with it. Lazily is also the rule this tree already
     * follows for anything touching files or the item registry.
     */
    private static VersionedDataStore<ShardData> store;

    /** The document the tables below were built from, so they rebuild once per new copy. */
    private static ShardData linked;

    private static Map<String, ShardDefinition> byKey = Map.of();
    private static Map<String, ShardDefinition> byName = Map.of();
    private static Map<String, ShardDefinition> byShortId = Map.of();
    private static Set<String> unconsumable = Set.of();

    private ShardCatalog() {
    }

    /**
     * The store, created on the first call rather than at class-load. Synchronised because the
     * first call may come from the client initializer or from whichever thread mentions the class
     * first.
     */
    private static synchronized VersionedDataStore<ShardData> store() {
        if (store == null) {
            store = new VersionedDataStore<>(
                    "Shards", RESOURCE,
                    SBSFiles.root().resolve("data").resolve("shards.json"),
                    "/api/shards", ShardData.class, SUPPORTED_SCHEMA);
        }
        return store;
    }

    /** Loads bundled + cached copies and starts the background refresh. Call on client init. */
    public static void load() {
        store().load();
        link();
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Shards] catalogue: {} shard(s), {} unconsumable, source {} v{}",
                byKey.size(), unconsumable.size(), source(), version());
    }

    /**
     * Rebuilds the lookup tables from the live document.
     *
     * <p>Done here rather than in the store because the store is generic - it knows nothing about
     * shards. The identity check makes every call after the first free.
     */
    private static synchronized void link() {
        ShardData document = loaded();
        if (document == null || document == linked) {
            return;
        }
        document.link();

        Map<String, ShardDefinition> keys = new LinkedHashMap<>();
        Map<String, ShardDefinition> names = new LinkedHashMap<>();
        Map<String, ShardDefinition> shortIds = new LinkedHashMap<>();
        for (ShardDefinition shard : document.shards) {
            String key = shard.key();
            if (key == null || keys.putIfAbsent(key, shard) != null) {
                continue;   // a duplicate name in the file; the first wins and the rest are noise
            }
            names.putIfAbsent(nameKey(shard.display()), shard);
            names.putIfAbsent(nameKey(shard.name), shard);
            String shortId = shard.shortId();
            if (!shortId.isEmpty()) {
                shortIds.putIfAbsent(shortId, shard);
            }
        }

        Set<String> excluded = new LinkedHashSet<>();
        for (String name : document.unconsumable) {
            String key = ShardId.key(ShardId.of(name));
            if (key != null) {
                excluded.add(key);
            }
        }

        byKey = Map.copyOf(keys);
        byName = Map.copyOf(names);
        byShortId = Map.copyOf(shortIds);
        unconsumable = Set.copyOf(excluded);
        linked = document;
    }

    /**
     * The loaded document, or {@code null} when nothing has loaded.
     *
     * <p>Never creates the store: a lookup made before {@link #load()} - or in a unit test, which
     * has no game directory to build one in - answers "nothing yet" rather than failing.
     */
    private static ShardData loaded() {
        return store == null ? null : store.get();
    }

    /** The live document, linking it first when a newer copy has arrived. */
    private static ShardData data() {
        ShardData document = loaded();
        if (document != null && document != linked) {
            link();
        }
        return document;
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    /** Every shard in the catalogue, keyed by {@code ATTRIBUTE_SHARD_<NAME>}, in file order. */
    public static Map<String, ShardDefinition> all() {
        data();
        return byKey;
    }

    /**
     * Every shard that counts towards the total - the catalogue minus the unconsumable ones.
     *
     * <p>What "missing" is the complement of. An unconsumable shard is not missing when absent and
     * not owned when held, so including it would put a row on the shopping list that can never be
     * closed.
     */
    public static Collection<ShardDefinition> consumable() {
        data();
        if (unconsumable.isEmpty()) {
            return byKey.values();
        }
        List<ShardDefinition> out = new java.util.ArrayList<>(byKey.size());
        for (Map.Entry<String, ShardDefinition> entry : byKey.entrySet()) {
            if (!unconsumable.contains(entry.getKey())) {
                out.add(entry.getValue());
            }
        }
        return out;
    }

    /** Whether a shard is one of the ones that cannot be consumed, and so counts towards nothing. */
    public static boolean isUnconsumable(String canonicalId) {
        data();
        String key = ShardId.key(canonicalId);
        return key != null && unconsumable.contains(key);
    }

    /** The shard a canonical id names, or {@code null} when the catalogue does not carry it. */
    public static ShardDefinition byId(String canonicalId) {
        data();
        String key = ShardId.key(canonicalId);
        return key == null ? null : byKey.get(key);
    }

    /**
     * The shard a display name names, or {@code null}.
     *
     * <p>Punctuation- and case-insensitive, and it consults {@link ShardNameOverrides} first, so a
     * name Hypixel spells differently from its own id still lands.
     */
    public static ShardDefinition byDisplayName(String displayName) {
        data();
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String override = ShardNameOverrides.canonicalNameFor(displayName);
        if (override != null) {
            ShardDefinition byOverride = byKey.get(ShardId.key(ShardId.of(override)));
            if (byOverride != null) {
                return byOverride;
            }
        }
        return byName.get(nameKey(displayName));
    }

    /** The shard Hypixel's own short id names ({@code C12}), or {@code null}. */
    public static ShardDefinition byShortId(String shortId) {
        data();
        if (shortId == null || shortId.isBlank()) {
            return null;
        }
        return byShortId.get(shortId.trim().toUpperCase(Locale.ROOT));
    }

    /** Shards per attribute tier for a rarity, index 0 being tier 1; empty when the file has none. */
    public static List<Integer> levelling(String rarityName) {
        ShardData document = data();
        if (document == null || rarityName == null) {
            return List.of();
        }
        List<Integer> table = document.levelling.get(rarityName.toUpperCase(Locale.ROOT));
        return table == null ? List.of() : table;
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /** How many shards the catalogue holds, or {@code 0} when nothing loaded. */
    public static int size() {
        data();
        return byKey.size();
    }

    /** How many of them are excluded from the total. */
    public static int unconsumableCount() {
        data();
        return unconsumable.size();
    }

    /** Where the live copy came from - bundled, cache or backend - for a status line. */
    public static String source() {
        return store == null ? "none" : store.source();
    }

    /** The loaded content version, or {@code -1} when nothing loaded. */
    public static int version() {
        return store == null ? -1 : store.version();
    }

    // ------------------------------------------------------------------

    /** Letters and digits only, lower case, so punctuation and colour artefacts cannot fail a match. */
    private static String nameKey(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
