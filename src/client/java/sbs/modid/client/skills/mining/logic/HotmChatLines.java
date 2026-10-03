/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The Heart of the Mountain chat lines, matched over colour-stripped text. Pure, so the patterns are
 * tested without a game.
 *
 * <p><b>None of these wordings has been captured yet.</b> No log in this tree contains a HotM tier-up
 * or a perk purchase line, so both patterns are deliberately tolerant and every line that merely
 * looks related is logged by {@link #isCaptureCandidate} under {@code [SBS][Hotm] chat:}. One session
 * with a tier-up replaces the guess with the real line. A miss here costs one reminder and never a
 * wrong number: a tier-up only marks the cache stale, and a spend is also caught by the powder total
 * dropping ({@link HotmTreeReader}).
 */
public final class HotmChatLines {

    /**
     * A tier-up announcement: the HotM name and "level/tier up" in either order, or
     * "Tier N unlocked/reached" beside the HotM name. Requires the HotM name so a skill level-up
     * ("SKILL LEVEL UP Mining 45") never matches.
     */
    private static final Pattern TIER_UP = Pattern.compile(
            "(?:\\bHOTM\\b|heart of the mountain).{0,40}?(?:\\b(?:level(?:led)?|tier)\\s+up\\b"
                    + "|\\btier\\s+\\d{1,2}\\s+(?:unlocked|reached)\\b)"
                    + "|\\b(?:level|tier)\\s+up\\b.{0,40}?(?:\\bHOTM\\b|heart of the mountain)",
            Pattern.CASE_INSENSITIVE);

    /**
     * A perk purchase. Unchanged from the advisor's guess: both a purchase verb and the word powder,
     * because "unlocked" alone is every other SkyBlock message.
     */
    private static final Pattern SPEND = Pattern.compile(
            "(?:unlocked|purchased|upgraded|you\\s+bought).{0,60}?"
                    + "|(?:mithril|gemstone|glacite)\\s+powder.{0,20}(?:spent|used)",
            Pattern.CASE_INSENSITIVE);

    /** Lines worth logging verbatim until the real wordings are known. */
    private static final Pattern CAPTURE = Pattern.compile(
            "heart of the mountain|\\bhotm\\b|token of the mountain|level up|upgraded|\\bpowder\\b",
            Pattern.CASE_INSENSITIVE);

    private HotmChatLines() {
    }

    /** Whether {@code plain} announces a Heart of the Mountain tier-up. */
    public static boolean isTierUp(String plain) {
        return plain != null && TIER_UP.matcher(plain).find();
    }

    /** Whether {@code plain} reads like a perk purchase paid in powder. */
    public static boolean isSpend(String plain) {
        return plain != null && SPEND.matcher(plain).find()
                && plain.toLowerCase(Locale.ROOT).contains("powder");
    }

    /** Whether {@code plain} should be logged for the capture. */
    public static boolean isCaptureCandidate(String plain) {
        return plain != null && !plain.isBlank() && CAPTURE.matcher(plain).find();
    }
}
