/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

import java.util.Locale;

/**
 * The one spelling of "which shard is this": {@code ATTRIBUTE_SHARD_<NAME>;<tier>}.
 *
 * <p><b>Why a canonical form is the whole fix.</b> Six screens name the same shard six ways - the
 * Attribute Menu writes the <i>attribute</i> and a roman tier ("Berry Eater IX"), the Hunting Box
 * writes the shard's display name, the Confirm Fusion dialog writes it into a lore line, and a real
 * item carries NBT and no readable name at all. Every one of those readings is turned into this
 * form before anything else touches it, so a shard read in one screen is the same shard read in
 * another and every cross-reference between them lands.
 *
 * <p><b>Identity is the name, never the tier.</b> {@link #key(String)} drops the {@code ;<tier>}
 * suffix, and it is what every map in this feature is keyed on. A player who levels an attribute
 * from III to IV has not acquired a second shard, and an id that carried the tier would say they
 * had - one row per tier, per shard, growing as they play. The tier is a <i>reading</i> about a
 * shard and travels beside the id, not inside it.
 *
 * <p>Pure string work with no game types, so it is unit-tested directly ({@code ShardIdTest}).
 */
public final class ShardId {

    /** The prefix every canonical id carries; also Hypixel's own shared item id for these. */
    public static final String PREFIX = "ATTRIBUTE_SHARD_";

    /** Separates the name from the tier. */
    private static final char TIER_SEPARATOR = ';';

    /** The tier a reading that did not state one is written with. */
    public static final int DEFAULT_TIER = 1;

    private ShardId() {
    }

    /**
     * The canonical id for a shard name at {@link #DEFAULT_TIER}, or {@code null} when the name is
     * not usable. The form the request names: {@code ATTRIBUTE_SHARD_<NAME>;1}.
     */
    public static String of(String name) {
        return of(name, DEFAULT_TIER);
    }

    /** The canonical id for a shard name at a stated tier, or {@code null} when the name is empty. */
    public static String of(String name, int tier) {
        String bare = normaliseName(name);
        return bare == null ? null : PREFIX + bare + TIER_SEPARATOR + Math.max(1, tier);
    }

    /**
     * A shard name normalised to the id alphabet: upper case, runs of anything else collapsed to a
     * single underscore, no leading or trailing underscore. {@code null} when nothing is left.
     *
     * <p>Deliberately <b>not</b> {@code SkyblockItem.normalizeName}, which strips a leading reforge
     * word - a shard whose display name begins "Fine", "Clean", "Heavy" or any of forty others
     * would silently lose it. See {@code docs/issues/skills.md}.
     */
    public static String normaliseName(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return key.isEmpty() ? null : key;
    }

    /**
     * The identity key of a canonical id - the id with any {@code ;<tier>} removed.
     *
     * <p>{@code null} in, {@code null} out. An id that is not canonical is returned upper-cased
     * rather than rejected: a caller that has already resolved something is not helped by having it
     * taken away here, and the catalogue lookup that follows will simply not find it.
     */
    public static String key(String canonicalId) {
        if (canonicalId == null) {
            return null;
        }
        String upper = canonicalId.toUpperCase(Locale.ROOT).trim();
        int at = upper.indexOf(TIER_SEPARATOR);
        return at < 0 ? upper : upper.substring(0, at);
    }

    /** The bare shard name inside a canonical id ({@code TOXIC}), or {@code null}. */
    public static String nameOf(String canonicalId) {
        String key = key(canonicalId);
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        String bare = key.substring(PREFIX.length());
        return bare.isEmpty() ? null : bare;
    }

    /** The tier written into a canonical id, or {@link #DEFAULT_TIER} when it states none. */
    public static int tierOf(String canonicalId) {
        if (canonicalId == null) {
            return DEFAULT_TIER;
        }
        int at = canonicalId.indexOf(TIER_SEPARATOR);
        if (at < 0 || at + 1 >= canonicalId.length()) {
            return DEFAULT_TIER;
        }
        try {
            return Math.max(1, Integer.parseInt(canonicalId.substring(at + 1).trim()));
        } catch (NumberFormatException notATier) {
            return DEFAULT_TIER;
        }
    }

    /** Whether an id is in canonical form at all. */
    public static boolean isCanonical(String id) {
        return id != null && id.toUpperCase(Locale.ROOT).startsWith(PREFIX);
    }

    /**
     * The Bazaar's own product id for a shard name ({@code SHARD_<NAME>}).
     *
     * <p>The market keys these differently from the game, and both spellings have to exist
     * somewhere. They meet here rather than in six callers: {@code ShardBazaar} is the only thing
     * that needs the market's spelling, and it converts at the moment it asks.
     */
    public static String bazaarId(String canonicalId) {
        String name = nameOf(canonicalId);
        return name == null ? null : "SHARD_" + name;
    }

}
