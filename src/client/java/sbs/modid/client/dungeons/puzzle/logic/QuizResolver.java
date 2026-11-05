/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import sbs.modid.client.dungeons.puzzle.model.QuizData;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Question plus its three options in, the one right option out - or {@code null}. No Minecraft types
 * and no static stores, so the whole answer table is unit-tested against real frames.
 *
 * <p><b>{@code null} is the important return value.</b> One wrong click fails Oruo's room for the
 * whole party, so every rule must pick out <b>exactly one</b> of the three options: none matching
 * (Hypixel changed the answer, or our number is stale) and two matching are both "show nothing".
 * Checking against the offered options, rather than printing the stored answer, is what turns a stale
 * row into silence instead of a confident wrong hint.
 */
public final class QuizResolver {

    /** The numbers a dynamic row needs, from the mod's own data. A seam so tests use the bundled files. */
    public interface Facts {
        /** Fairy souls on an island; {@code 0} when unknown. */
        int fairySoulsOn(String island);

        /** Fairy souls in the game; {@code 0} when unknown. */
        int fairySoulsTotal();

        /** Minion types in the catalogue; {@code 0} when unknown. */
        int minionCount();

        /** The SkyBlock year now. */
        long skyblockYear();
    }

    /** The resolved option, its index among the three, and how far the row behind it is trusted. */
    public record Resolution(String answer, int index, Certainty certainty, String source) {

        /** Whether the solver may show it. ESTIMATED and UNKNOWN rows resolve but are never shown. */
        public boolean shown() {
            return certainty == Certainty.CONFIRMED || certainty == Certainty.WIKI;
        }
    }

    private final Map<String, QuizData.Entry> byQuestion;

    public QuizResolver(QuizData data) {
        Map<String, QuizData.Entry> built = new HashMap<>();
        if (data != null && data.questions != null) {
            for (QuizData.Entry entry : data.questions) {
                if (entry != null && entry.question != null && !entry.question.isBlank()) {
                    built.put(key(entry.question), entry);
                }
            }
        }
        this.byQuestion = Map.copyOf(built);
    }

    /** How many questions the table holds. */
    public int size() {
        return byQuestion.size();
    }

    /** Whether the table has a row for this question at all - unknown and unresolved log differently. */
    public boolean knows(String question) {
        return question != null && byQuestion.containsKey(key(question));
    }

    /**
     * The one option that answers {@code question}, or {@code null} when the table has no row for it
     * or its rule does not single out exactly one option.
     */
    public Resolution resolve(String question, List<String> options, Facts facts) {
        if (question == null || options == null || options.isEmpty()) {
            return null;
        }
        QuizData.Entry entry = byQuestion.get(key(question));
        if (entry == null) {
            return null;
        }
        Set<String> accepted = new HashSet<>();
        boolean invert = false;
        if (entry.dynamic != null) {
            String value = dynamicAnswer(entry, facts);
            if (value == null) {
                return null;
            }
            accepted.add(key(value));
        } else if (entry.notAmong != null && !entry.notAmong.isEmpty()) {
            for (String wrong : entry.notAmong) {
                accepted.add(key(wrong));
            }
            invert = true;
        } else if (entry.answers != null) {
            for (String answer : entry.answers) {
                accepted.add(key(answer));
            }
        }
        accepted.remove("");
        if (accepted.isEmpty()) {
            return null;
        }
        int found = -1;
        for (int i = 0; i < options.size(); i++) {
            boolean hit = accepted.contains(key(options.get(i))) != invert;
            if (hit) {
                if (found >= 0) {
                    return null;
                }
                found = i;
            }
        }
        if (found < 0) {
            return null;
        }
        Certainty certainty = entry.certainty == null ? Certainty.UNKNOWN : entry.certainty;
        return new Resolution(options.get(found), found, certainty, entry.source);
    }

    /** The option text a dynamic row expects, as Oruo prints it, or {@code null} when the fact is unknown. */
    static String dynamicAnswer(QuizData.Entry entry, Facts facts) {
        if (facts == null) {
            return null;
        }
        return switch (entry.dynamic) {
            case SKYBLOCK_YEAR -> {
                long year = facts.skyblockYear();
                yield year > 0 ? "Year " + year : null;
            }
            case FAIRY_SOULS_ON_ISLAND -> souls(facts.fairySoulsOn(entry.island));
            case FAIRY_SOULS_TOTAL -> souls(facts.fairySoulsTotal());
            case MINION_COUNT -> {
                int count = facts.minionCount();
                yield count > 0 ? count + " Minions" : null;
            }
        };
    }

    private static String souls(int count) {
        if (count <= 0) {
            return null;
        }
        return count == 1 ? "1 Fairy Soul" : count + " Fairy Souls";
    }

    /**
     * Lower-cased with everything that is not a letter or digit removed, so a curly apostrophe, a
     * trailing question mark or Hypixel's spacing cannot turn a known question into an unknown one.
     * Deliberately not fuzzy beyond that: a near-match resolving to the wrong question is a confident
     * wrong highlight.
     */
    public static String key(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
