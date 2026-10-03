/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.logic;

import sbs.modid.client.economy.minions.model.MinionStopReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the line written over a minion into a reason it has stopped - or into nothing at all.
 *
 * <p><b>Every wording here is {@code ESTIMATED}.</b> No session has stood on a Private Island with
 * a stopped minion, and nothing resembling Hypixel's hologram text exists anywhere in this
 * repository. Root {@code AGENTS.md} ("A name in a request is a hypothesis") is what shapes this
 * class: the words are the player's config rather than literals in code, everything seen is logged
 * whether it matched or not, and the feature ships off.
 *
 * <p><b>Silence is not a stop.</b> A hologram that matches none of the keyword lists is reported as
 * nothing by default, not as {@link MinionStopReason#OTHER} - a decorative nametag, a pet's label
 * or a minion skin's flourish would otherwise each become a minion the player is told is broken.
 * {@code flagUnknown} switches that round for anyone deliberately hunting the real wording; the log
 * line is the better tool for that and is always written.
 *
 * <p>Order matters: "full" is tested before "blocked", because a line could plausibly mention both
 * ("I can't place blocks, my storage is full") and the full case is the one the player acts on.
 */
public final class MinionStopRules {

    /**
     * Words that mean "the storage is full", comma-separated. ESTIMATED, from the shape the
     * feature was requested with ("/!\ My storage is full! :(").
     *
     * <p><b>Both defaults stay inside
     * {@link sbs.modid.client.core.config.share.ShareValues#MAX_TEXT} characters</b>, and that cap
     * is 64 whatever a {@code @Shareable(max = ...)} says - the annotation's {@code max} is only
     * read for numbers. A longer default is one that silently shortens the first time a config is
     * shared, which {@code ShareRoundTripTest} fails the build over rather than letting ship.
     */
    public static final String DEFAULT_FULL_WORDS = "storage is full, my storage, inventory is full";

    /**
     * Words that mean "it cannot work where it stands". ESTIMATED, same source ("I can't place
     * blocks here", "I need space to generate").
     */
    public static final String DEFAULT_BLOCKED_WORDS =
            "can't place, cannot place, need space, no space, blocked";

    private MinionStopRules() {
    }

    /**
     * Classifies one hologram line.
     *
     * @param line        the stand's custom name, colour codes already stripped
     * @param fullWords   comma-separated keywords meaning "full"
     * @param blockedWords comma-separated keywords meaning "cannot work here"
     * @param flagUnknown treat any other non-empty line as {@link MinionStopReason#OTHER}
     * @return the reason, or {@code null} when this line says nothing about a stoppage
     */
    public static MinionStopReason classify(String line, String fullWords, String blockedWords,
                                            boolean flagUnknown) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String text = line.toLowerCase(Locale.ROOT);
        if (containsAny(text, fullWords)) {
            return MinionStopReason.FULL;
        }
        if (containsAny(text, blockedWords)) {
            return MinionStopReason.BLOCKED;
        }
        return flagUnknown ? MinionStopReason.OTHER : null;
    }

    /** Whether {@code text} contains any of the comma-separated, already-lowercase {@code words}. */
    private static boolean containsAny(String text, String words) {
        for (String word : split(words)) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a comma-separated keyword setting, lowercased and trimmed, dropping empties.
     *
     * <p>Public because the settings page prints the parsed result back: a player who types
     * {@code ",,, "} should be able to see that it came to nothing.
     */
    public static List<String> split(String words) {
        List<String> parts = new ArrayList<>();
        if (words == null) {
            return parts;
        }
        for (String raw : words.split(",")) {
            String word = raw.trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty()) {
                parts.add(word);
            }
        }
        return parts;
    }

    /**
     * The one-line summary: {@code "3 minions stopped (2 full)"}.
     *
     * <p>Only the full count is broken out. It is the one the player can do something about in
     * thirty seconds, and a summary that lists every reason stops being a summary.
     *
     * @return the sentence, or {@code ""} when nothing is stopped - callers draw nothing for that
     */
    public static String summary(int stopped, int full) {
        if (stopped <= 0) {
            return "";
        }
        String head = stopped == 1 ? "1 minion stopped" : stopped + " minions stopped";
        return full > 0 ? head + " (" + full + " full)" : head;
    }
}
