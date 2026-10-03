/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.casing.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import sbs.modid.client.dungeons.casing.model.CaseItem;

/**
 * The reel of a CS:GO-style case opening: a horizontal strip of items that scrolls fast and eases to
 * a stop <b>exactly</b> on the winning item.
 *
 * <p>This class is pure maths – no Minecraft, no rendering – so the one property that matters can be
 * proven in a test: at the end of the animation, the cell under the centre marker is the winning
 * item, for any strip length, cell size and marker position. The screen just draws what this
 * computes.
 *
 * <p>It never touches drop odds. The winning item is passed in (the server already decided it); the
 * only randomness here is which <i>filler</i> items fill the rest of the strip, weighted so common
 * items show up more often than rare ones – a believable-looking reel, not a real roll.
 */
public final class CaseOpeningReel {

    /** How many cells the strip holds. Long enough to whizz past a lot before it lands. */
    private static final int STRIP_LENGTH = 60;
    /** The winning cell sits a few from the end, so there is always run-out past the marker. */
    private static final int WIN_INDEX = STRIP_LENGTH - 6;

    private final List<CaseItem> strip;
    private final int winIndex;
    private final long durationMs;
    private final long startMs;

    private CaseOpeningReel(List<CaseItem> strip, int winIndex, long durationMs, long startMs) {
        this.strip = strip;
        this.winIndex = winIndex;
        this.durationMs = durationMs;
        this.startMs = startMs;
    }

    /**
     * Builds a reel that lands on {@code winner}, filled from {@code pool} weighted by rarity.
     *
     * @param pool       the items that can appear as filler (typically the chest's own loot pool)
     * @param winner     the item the reel must stop on – the reward the server already granted
     * @param durationMs total animation length, clamped to the 2–5 s the feature promises
     * @param seed       randomness source, so a test can make the strip deterministic
     */
    public static CaseOpeningReel build(List<CaseItem> pool, CaseItem winner, long durationMs,
                                        long seed, long nowMs) {
        long clamped = Math.max(2000, Math.min(5000, durationMs));
        List<CaseItem> effectivePool = (pool == null || pool.isEmpty()) ? List.of(winner) : pool;
        Random random = new Random(seed);
        List<CaseItem> strip = new ArrayList<>(STRIP_LENGTH);
        for (int i = 0; i < STRIP_LENGTH; i++) {
            strip.add(i == WIN_INDEX ? winner : weightedPick(effectivePool, random));
        }
        return new CaseOpeningReel(strip, WIN_INDEX, clamped, nowMs);
    }

    /** A rarity-weighted pick: common items land far more often than mythics. */
    private static CaseItem weightedPick(List<CaseItem> pool, Random random) {
        int total = 0;
        for (CaseItem item : pool) {
            total += item.reelWeight();
        }
        if (total <= 0) {
            return pool.get(random.nextInt(pool.size()));
        }
        int roll = random.nextInt(total);
        for (CaseItem item : pool) {
            roll -= item.reelWeight();
            if (roll < 0) {
                return item;
            }
        }
        return pool.get(pool.size() - 1);
    }

    public List<CaseItem> strip() {
        return strip;
    }

    public CaseItem winner() {
        return strip.get(winIndex);
    }

    /** Animation progress in {@code [0,1]} at {@code nowMs}. */
    public double progress(long nowMs) {
        if (durationMs <= 0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, (nowMs - startMs) / (double) durationMs));
    }

    public boolean finished(long nowMs) {
        return nowMs - startMs >= durationMs;
    }

    /**
     * Cubic ease-out: derivative 3 at t=0 (starts fast) and 0 at t=1 (glides to a dead stop). The
     * shape CS:GO uses – quick blur, then a slow, tense settle.
     */
    public static double easeOut(double t) {
        double inv = 1.0 - t;
        return 1.0 - inv * inv * inv;
    }

    /**
     * The strip's scroll offset in pixels at time {@code t} (0..1), for a given {@code cellWidth}.
     *
     * <p>Defined so that at {@code t == 1} the winning cell's centre is exactly {@code markerX}: the
     * offset is the eased fraction of the final offset, and the final offset places the win under the
     * marker. That identity is what the test checks, and what guarantees a pixel-perfect landing.
     */
    public double offsetAt(double t, int cellWidth, int markerX) {
        double finalOffset = winIndex * (double) cellWidth + cellWidth / 2.0 - markerX;
        return easeOut(Math.max(0.0, Math.min(1.0, t))) * finalOffset;
    }

    /**
     * The strip index whose cell sits under {@code markerX} for a given scroll {@code offset}.
     * The screen uses this only for effects; the test uses it to assert the landing is exact.
     */
    public int indexUnderMarker(double offset, int cellWidth, int markerX) {
        return (int) Math.floor((offset + markerX) / cellWidth);
    }

    public int winIndex() {
        return winIndex;
    }
}
