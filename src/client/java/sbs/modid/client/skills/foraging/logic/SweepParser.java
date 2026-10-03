/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import sbs.modid.client.skills.foraging.model.SweepChop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's per-chop foraging detail message into a {@link SweepChop}.
 *
 * <p><b>Why this scans fields instead of matching a line.</b> The exact wording of the message is
 * not established - see {@code docs/SWEEP-HUD-DESIGN.md} §1, which explains why it was not written
 * down from a guess. A whole-line regex written against a guessed sentence matches nothing, and a
 * feature that matches nothing looks exactly like a feature that is switched off. So this finds
 * <i>labelled numbers</i> anywhere in the line and classifies each by its label: fields may be
 * reordered, fields we do not know may sit between them, and fields we do know may be missing.
 * Whatever is recognised is kept, and anything else is ignored rather than fatal.
 *
 * <p><b>No Minecraft imports</b>, so the rules are exercised in {@code src/test} rather than only on
 * Galatea - the same reason {@code ChatChannel} has none. It also does no logging: a parser that
 * returns {@code null} says everything the caller needs, and the near-miss report belongs to the
 * caller that knows whether the player was even chopping.
 *
 * <p><b>The reject path allocates nothing.</b> Every chat line in the game passes through here, so a
 * line that cannot be ours is turned away by a manual case-insensitive scan - no {@code
 * toLowerCase}, which would copy every chat line in the game, and no {@link Matcher} until a
 * candidate is in hand.
 */
public final class SweepParser {

    /** The mathematical glyph the stat is written with, when it is a real codepoint. */
    public static final char SWEEP_GLYPH = '∮';

    /**
     * A labelled number, in either order Hypixel writes them.
     *
     * <p>Alternative 1 is {@code Label: 421} (groups 1 and 2), alternative 2 is {@code 421 Label} or
     * {@code ∮ 421 Sweep} (groups 3 and 4). Numbers keep Hypixel's thousands separators and an
     * optional decimal. Labels are bounded so a whole sentence cannot become one.
     */
    private static final Pattern FIELD = Pattern.compile(
            "([A-Za-z][A-Za-z ]{0,24}?)\\s*:\\s*([\\d,]+(?:\\.\\d+)?)"
                    + "|([\\d,]+(?:\\.\\d+)?)\\s*([A-Za-z][A-Za-z ]{0,24})");

    /** Label keywords, matched as substrings so "Effective Sweep" and "Sweep" both land. */
    private static final String SWEEP = "sweep";
    private static final String TOUGHNESS = "tough";
    private static final String BLOCKS = "block";

    /** Words that would tell a thrown axe from a swung one, if the message carries either. */
    private static final String THROWN = "thrown";
    private static final String THROW = "throw";
    private static final String MELEE = "melee";

    private SweepParser() {
    }

    /**
     * Whether {@code raw} could be a sweep message at all - the cheap gate every chat line pays.
     *
     * <p>Deliberately broader than the parser: a line naming the stat or carrying its glyph is a
     * candidate even if no field is readable, because that is exactly the line worth reporting when
     * the wording has moved.
     *
     * <p>A Century Cake line ({@code Yum! You gain +5 Sweep for 48 hours!}, and {@code Big Yum!} on
     * a refresh) names the stat but is a buff, not a chop, so it is never a candidate.
     */
    public static boolean isCandidate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (raw.contains("Yum!")) {
            String plain = raw.replaceAll("(?i)§.", "").trim();
            if (plain.startsWith("Yum!") || plain.startsWith("Big Yum!")) {
                return false;
            }
        }
        if (raw.indexOf(SWEEP_GLYPH) >= 0) {
            return true;
        }
        return containsIgnoreCase(raw, SWEEP);
    }

    /**
     * Parses a <b>formatting-stripped</b> line.
     *
     * @return the chop, or {@code null} when no field at all was recognised - which is the silent
     *         failure the design asks for, not an error
     */
    public static SweepChop parse(String stripped, long now) {
        if (stripped == null || stripped.isEmpty()) {
            return null;
        }
        double sweep = SweepChop.ABSENT;
        double toughness = SweepChop.ABSENT;
        int blocks = (int) SweepChop.ABSENT;

        Matcher matcher = FIELD.matcher(stripped);
        while (matcher.find()) {
            String label = matcher.group(1) != null ? matcher.group(1) : matcher.group(4);
            String number = matcher.group(1) != null ? matcher.group(2) : matcher.group(3);
            if (label == null || number == null) {
                continue;
            }
            double value = number(number);
            if (value < 0) {
                continue;
            }
            // First writer wins per field: a message repeating a label is more likely to be a
            // summary line following the real one than a correction of it.
            if (containsIgnoreCase(label, SWEEP)) {
                if (sweep < 0) {
                    sweep = value;
                }
            } else if (containsIgnoreCase(label, TOUGHNESS)) {
                if (toughness < 0) {
                    toughness = value;
                }
            } else if (containsIgnoreCase(label, BLOCKS)) {
                if (blocks < 0) {
                    blocks = (int) value;
                }
            }
        }

        if (sweep < 0 && toughness < 0 && blocks < 0) {
            return null;
        }
        return new SweepChop(sweep, toughness, blocks, delivery(stripped), now);
    }

    /** Melee or thrown, when the line says so at all. */
    private static SweepChop.Delivery delivery(String stripped) {
        if (containsIgnoreCase(stripped, THROWN) || containsIgnoreCase(stripped, THROW)) {
            return SweepChop.Delivery.THROWN;
        }
        if (containsIgnoreCase(stripped, MELEE)) {
            return SweepChop.Delivery.MELEE;
        }
        return SweepChop.Delivery.UNKNOWN;
    }

    /** Hypixel's number format, or {@code -1} when it is not one. */
    private static double number(String text) {
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * {@code haystack.toLowerCase().contains(needle)} without the copy.
     *
     * @param needle must already be lower case
     */
    private static boolean containsIgnoreCase(String haystack, String needle) {
        int limit = haystack.length() - needle.length();
        for (int i = 0; i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }
}
