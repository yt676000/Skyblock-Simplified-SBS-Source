/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.croesus.logic;

import java.util.List;
import java.util.Locale;

/**
 * Reading Croesus's run list: is this his menu, and does this run still have a chest waiting.
 *
 * <p><b>Every answer here is a pure function of strings</b>, so the one part of this feature that
 * cannot be checked in game is the part that can be checked without a game at all. The scan, the
 * drawing and the reminder all route through these three methods and hold no wording of their own.
 *
 * <p><b>Nothing in this class is a literal, and that is the rule rather than a preference.</b> Not
 * one line of the Croesus menu is recorded anywhere in this repository - the root {@code AGENTS.md}
 * is explicit that a name arriving with a request is a hypothesis, that it becomes a config field
 * with a tolerant match rather than a constant, and that the feature logs what it really sees and
 * ships off until somebody has looked. All three phrase sets are therefore parameters, and the
 * defaults live in {@code SBSConfig} where a player can correct them without an update.
 *
 * <p><b>Three states, not two.</b> A run that matches neither phrase set is {@link RunState#UNKNOWN}
 * and is drawn as nothing at all. That is the whole safety property: a wrong phrase list makes the
 * feature quiet rather than confidently wrong, and a page that reports "14 items, 0 recognised" has
 * named its own fix. Two states would have forced every filler pane and every scenery head in the
 * menu into one bucket or the other.
 */
public final class CroesusMenu {

    /** What a run entry's lore says about its chests. */
    public enum RunState {
        /** A phrase from the unopened list matched: there is something left to collect. */
        UNOPENED,
        /** A phrase from the finished list matched. Checked first - see {@link #stateOf}. */
        DONE,
        /** Neither matched. Not a run entry, or the phrases are wrong; either way, say nothing. */
        UNKNOWN
    }

    private CroesusMenu() {
    }

    /**
     * Whether an open menu's title says this is Croesus's.
     *
     * <p>Containment rather than equality, because a paged menu carries its page counter in the
     * title and a menu Hypixel later renames around the same word would otherwise stop matching. An
     * empty setting answers {@code true} for every menu - the escape hatch for a title that does not
     * carry the NPC's name at all, at the price of having the phrase lists decide alone.
     *
     * @param normalisedTitle the title already colour-stripped and lowercased, as {@code MenuFrame}
     *                        hands it out
     * @param configured      the fragment to look for; matched case-insensitively
     */
    public static boolean isCroesusTitle(String normalisedTitle, String configured) {
        if (configured == null || configured.isBlank()) {
            return true;
        }
        if (normalisedTitle == null) {
            return false;
        }
        return normalisedTitle.contains(configured.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * What one entry's lore says about its chests.
     *
     * <p><b>Finished is tested first, and the order is load-bearing.</b> "No more chests" contains
     * the word "chests", and any phrase list broad enough to catch a real "chests left" wording is
     * broad enough to catch that too. Testing the more specific claim first means a run that matches
     * both is reported finished, which is the safe direction: a missed highlight costs a trip to the
     * NPC, while a highlight on an empty run costs the trust in every other highlight.
     *
     * @param lore      the entry's lore, colour-stripped
     * @param opened    comma-separated phrases meaning "finished"
     * @param unopened  comma-separated phrases meaning "something is left"
     */
    public static RunState stateOf(List<String> lore, String opened, String unopened) {
        if (lore == null || lore.isEmpty()) {
            return RunState.UNKNOWN;
        }
        if (matchIndex(lore, opened) >= 0) {
            return RunState.DONE;
        }
        return matchIndex(lore, unopened) >= 0 ? RunState.UNOPENED : RunState.UNKNOWN;
    }

    /**
     * How many chests the entry says are waiting, or {@code 0} when it does not say.
     *
     * <p><b>The number nearest the phrase wins</b>, not the first on the line. A run entry names a
     * floor and a date as well, so "Floor 7 - 2 unopened" holds two integers and the first of them is
     * the wrong one; measuring from where the phrase actually matched picks the 2 whichever side of
     * it the wording puts the count on. When the line carries no digits at all this returns 0 and the
     * caller draws no count - the highlight is the feature, the count is a detail on top of it, and
     * inventing one would be worse than leaving it off.
     */
    public static int countIn(List<String> lore, String unopened) {
        int line = matchIndex(lore, unopened);
        if (line < 0) {
            return 0;
        }
        String text = lore.get(line).toLowerCase(Locale.ROOT);
        int at = phraseIndex(text, unopened);
        if (at < 0) {
            return 0;
        }
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < text.length(); ) {
            if (!Character.isDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            long value = 0;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                value = value * 10 + (text.charAt(i) - '0');
                if (value > 999) {
                    // Not a chest count. A year or a timestamp would otherwise win on distance.
                    value = 0;
                    break;
                }
                i++;
            }
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                i++;
            }
            if (value <= 0) {
                continue;
            }
            int distance = Math.min(Math.abs(start - at), Math.abs(i - at));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (int) value;
            }
        }
        return best;
    }

    /** The index of the first lore line containing any of {@code phrases}, or {@code -1}. */
    private static int matchIndex(List<String> lore, String phrases) {
        if (lore == null || phrases == null || phrases.isBlank()) {
            return -1;
        }
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.get(i);
            if (line != null && phraseIndex(line.toLowerCase(Locale.ROOT), phrases) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Where the earliest of {@code phrases} occurs in an already-lowercased line, or {@code -1}. */
    private static int phraseIndex(String lowerLine, String phrases) {
        int best = -1;
        for (String part : phrases.split(",")) {
            String want = part.trim().toLowerCase(Locale.ROOT);
            if (want.isEmpty()) {
                continue;
            }
            int at = lowerLine.indexOf(want);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }
}
