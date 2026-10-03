/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules that decide when a head's skin becomes "the soul skin". Pure - no Minecraft types - so
 * the rules are unit-tested rather than trusted.
 *
 * <p><b>Why two lessons, not one (2026-09-26).</b> The first version learned the skin from the one
 * head within reach of a single collection line. On the play instance that taught the skin of a
 * pair of <i>decorative</i> heads, and the scanner then boxed every copy of that decoration as a
 * "Fairy Soul". One collection proves only that <i>some</i> head nearby was near a soul; the same
 * skin turning up at two collections in two different places is what makes it the soul's own.
 *
 * <ul>
 *   <li>A collection with zero or 2+ candidate heads teaches nothing (ambiguous).</li>
 *   <li>A collection with exactly one candidate records a <i>lesson</i>: that skin at that spot.</li>
 *   <li>A skin is adopted once it has lessons at {@link #REQUIRED_LESSONS} positions at least
 *       {@link #MIN_SEPARATION} blocks apart. Re-touching the same soul twice is one lesson.</li>
 * </ul>
 */
public final class SoulSkinLessons {

    /** Distinct collection sites a skin must be seen at before it is adopted. */
    public static final int REQUIRED_LESSONS = 2;

    /** Two lessons closer than this are the same soul, collected (or re-touched) twice. */
    public static final double MIN_SEPARATION = 8.0;

    /** A head seen beside a collection: its skin texture value and block position. */
    public record Candidate(String texture, int x, int y, int z) {
    }

    /** What one collection did. */
    public enum Outcome {
        /** No head within reach - nothing to learn from. */
        NONE,
        /** Two or more heads within reach: no way to tell which is the soul. */
        AMBIGUOUS,
        /** One head, recorded - but its skin has not been seen at enough separate sites yet. */
        LESSON_RECORDED,
        /** This lesson was the one that made the skin the soul skin. */
        ADOPTED
    }

    /**
     * Pending lessons: texture to the sites it was seen at. Deliberately NOT split per island - the
     * bad file held the same two positions under four island names (the island read lagged the
     * world), and counting those as four sites is exactly the mistake this class exists to stop.
     * Persisted by {@link FairySoulLearned}.
     */
    private final Map<String, List<int[]>> pending;

    public SoulSkinLessons(Map<String, List<int[]>> pending) {
        this.pending = pending == null ? new HashMap<>() : pending;
    }

    public Map<String, List<int[]>> pending() {
        return pending;
    }

    /**
     * Folds one collection's candidates in. {@code candidates} should already be restricted to heads
     * that could be this soul (see {@code FairySoulLearned.plausible}).
     */
    public Outcome observe(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Outcome.NONE;
        }
        if (candidates.size() != 1) {
            return Outcome.AMBIGUOUS;
        }
        Candidate c = candidates.getFirst();
        if (c.texture() == null || c.texture().isEmpty()) {
            return Outcome.NONE;
        }
        List<int[]> sites = pending.computeIfAbsent(c.texture(), k -> new ArrayList<>());
        boolean fresh = true;
        for (int[] s : sites) {
            if (distance(s, c) < MIN_SEPARATION) {
                fresh = false;
                break;
            }
        }
        if (fresh) {
            sites.add(new int[]{c.x(), c.y(), c.z()});
        }
        return siteCount(c.texture()) >= REQUIRED_LESSONS ? Outcome.ADOPTED
                : Outcome.LESSON_RECORDED;
    }

    /** Distinct sites the skin has been seen at. */
    public int siteCount(String texture) {
        List<int[]> sites = pending.get(texture);
        return sites == null ? 0 : sites.size();
    }

    private static double distance(int[] s, Candidate c) {
        double dx = s[0] - c.x();
        double dy = s[1] - c.y();
        double dz = s[2] - c.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
