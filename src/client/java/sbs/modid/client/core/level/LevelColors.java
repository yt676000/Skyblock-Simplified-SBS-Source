/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one place a SkyBlock level is turned into a colour.
 *
 * <p><b>Why this exists at all, and why it starts switched off.</b> This mod has always reproduced
 * Hypixel's own level colour rather than picking one - the reasoning is written out in
 * {@code TabWidgets.components()} and {@code TabInfoTracker.component()}: a colour chosen here is a
 * guess that goes stale the moment Hypixel ships a tier nobody has seen. Recolouring gives that up
 * in exchange for control, which is a trade only the player can make, so the master toggle is
 * {@code false} by default and an untouched install looks exactly as it did before.
 *
 * <p><b>One resolver, every render site.</b> The requirement that the colour be identical wherever a
 * level is drawn is met by there being exactly one function to ask - not by keeping several
 * implementations in step.
 *
 * <p><b>Sorted once, not per lookup.</b> The lookup runs per drawn level, potentially per frame, so
 * the config list is normalised into two parallel arrays whenever it changes and the lookup itself
 * is a walk over at most a handful of bounds. {@link #invalidate()} is what a settings change calls;
 * nothing here polls.
 *
 * <p><b>Nothing here throws.</b> It sits in a render path, so a malformed entry is dropped and
 * logged, and the answer degrades to {@link LevelTier#PASSTHROUGH} - Hypixel's colour - rather than
 * to an exception or to a colour nobody asked for.
 */
public final class LevelColors {

    /** No level is expected past this; a threshold beyond it is a typo, not a plan. */
    public static final int MAX_LEVEL = 100_000;

    /**
     * The shipped table, and the starting point the player edits.
     *
     * <p>Deliberately a rough echo of how Hypixel bands the level today rather than an attempt to
     * reproduce it exactly: the point of the defaults is to be recognisable and adjustable, and a
     * table claiming to be the real thing would be the same stale guess this feature is opting into
     * with its eyes open. The bottom band is passthrough, so anything below the first tier keeps the
     * server's colour until the player says otherwise.
     */
    private static List<LevelTier> defaults() {
        List<LevelTier> tiers = new ArrayList<>(8);
        tiers.add(new LevelTier(0, LevelTier.PASSTHROUGH));
        tiers.add(new LevelTier(40, "55FF55"));
        tiers.add(new LevelTier(80, "55FFFF"));
        tiers.add(new LevelTier(120, "5555FF"));
        tiers.add(new LevelTier(160, "AA00AA"));
        tiers.add(new LevelTier(200, "FFAA00"));
        tiers.add(new LevelTier(280, "FF5555"));
        tiers.add(new LevelTier(360, "AA0000"));
        return tiers;
    }

    /** A fresh copy of the shipped table - what a new config and the reset button both get. */
    public static List<LevelTier> defaultTiers() {
        return defaults();
    }

    // ------------------------------------------------------------------ cache

    /** Ascending start bounds of the normalised table. */
    private static int[] bounds = new int[0];

    /** Colour per band, parallel to {@link #bounds}; {@code null} means passthrough. */
    private static Integer[] colors = new Integer[0];

    private static boolean built;

    private LevelColors() {
    }

    /** Called whenever the tier list or the toggle changes; the next lookup rebuilds. */
    public static synchronized void invalidate() {
        built = false;
    }

    // ------------------------------------------------------------------ lookup

    /**
     * The colour for {@code level} as {@code 0xRRGGBB}, or {@code null} to leave it to Hypixel.
     *
     * <p>{@code null} is returned for every case the feature has no opinion about: the toggle is
     * off, the level is unknown ({@code <= 0}), it falls below the lowest tier, or the tier that
     * covers it is a passthrough band. Callers draw the server's own colour then, which is what the
     * mod did everywhere before this existed.
     */
    public static synchronized Integer colorOf(int level) {
        if (level <= 0 || !enabled()) {
            return null;
        }
        if (!built) {
            build();
        }
        Integer answer = null;
        for (int i = 0; i < bounds.length && bounds[i] <= level; i++) {
            answer = colors[i];   // later bands win; the last one that starts at or below wins
        }
        return answer;
    }

    private static boolean enabled() {
        var cfg = sbs.modid.client.core.config.ConfigManager.getInstance().get().levelColors;
        return cfg != null && cfg.enabled;
    }

    /**
     * Rebuilds the lookup from the config: unreadable entries dropped, thresholds clamped, sorted
     * ascending, and a duplicate threshold resolved in favour of the later entry.
     *
     * <p>The config list itself is never rewritten. A player who hand-edits {@code config.json} into
     * something odd gets it read charitably and told about it in the log; silently reordering their
     * file underneath them would be a worse answer than either.
     */
    private static void build() {
        built = true;
        var cfg = sbs.modid.client.core.config.ConfigManager.getInstance().get().levelColors;
        List<LevelTier> source = cfg == null || cfg.tiers == null ? List.of() : cfg.tiers;
        List<LevelTier> usable = new ArrayList<>(source.size());
        int dropped = 0;
        for (LevelTier tier : source) {
            if (tier == null || tier.from < 0 || tier.from > MAX_LEVEL
                    || (!tier.isPassthrough() && parseHex(tier.hex) == null)) {
                dropped++;
                continue;
            }
            usable.add(tier.copy());
        }
        usable.sort((a, b) -> Integer.compare(a.from, b.from));

        int[] newBounds = new int[usable.size()];
        Integer[] newColors = new Integer[usable.size()];
        int size = 0;
        for (LevelTier tier : usable) {
            if (size > 0 && newBounds[size - 1] == tier.from) {
                newColors[size - 1] = tier.isPassthrough() ? null : parseHex(tier.hex);
                continue;   // same threshold twice: the later entry is the one the player edited last
            }
            newBounds[size] = tier.from;
            newColors[size] = tier.isPassthrough() ? null : parseHex(tier.hex);
            size++;
        }
        bounds = java.util.Arrays.copyOf(newBounds, size);
        colors = java.util.Arrays.copyOf(newColors, size);
        if (dropped > 0) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][LevelColor] {} tier(s) ignored - a threshold outside 0..{} or an "
                            + "unreadable colour; the rest of the table is in use", dropped, MAX_LEVEL);
        }
    }

    /** {@code "RRGGBB"} to an int, or {@code null} when it is not six hex digits. */
    static Integer parseHex(String hex) {
        if (hex == null) {
            return null;
        }
        String clean = hex.trim();
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (clean.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(clean, 16);
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /** An int back to the {@code RRGGBB} the config stores. */
    public static String toHex(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ testable core

    /**
     * The resolver with the table handed in - the same walk {@link #colorOf} does, without the
     * config or the cache.
     *
     * <p>Split out so the rules that are easy to get wrong (which band a level falls in, what a
     * duplicate or an out-of-order entry does, where passthrough wins) can be checked on a bench.
     * {@code LevelColorsTest} is what holds them.
     */
    public static Integer resolve(List<LevelTier> tiers, int level) {
        if (tiers == null || level <= 0) {
            return null;
        }
        List<LevelTier> sorted = new ArrayList<>(tiers.size());
        for (LevelTier tier : tiers) {
            if (tier != null && tier.from >= 0 && tier.from <= MAX_LEVEL
                    && (tier.isPassthrough() || parseHex(tier.hex) != null)) {
                sorted.add(tier);
            }
        }
        sorted.sort((a, b) -> Integer.compare(a.from, b.from));
        Integer answer = null;
        for (LevelTier tier : sorted) {
            if (tier.from > level) {
                break;
            }
            answer = tier.isPassthrough() ? null : parseHex(tier.hex);
        }
        return answer;
    }
}
