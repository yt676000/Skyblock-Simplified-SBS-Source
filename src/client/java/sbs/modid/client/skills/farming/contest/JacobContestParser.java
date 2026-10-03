/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.contest;

import sbs.modid.client.skills.farming.model.CropType;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every Jacob's Contest string the overlay reads, in one place, each marked with how sure it is.
 *
 * <p><b>VERIFIED</b> against the play instance's logs (2026-09-25): the start line (30 sightings) and
 * the Anita's Artifact line (Wheat, Potato, Nether Wart, Melon Slice, Wild Rose).
 *
 * <p><b>ESTIMATED</b> - never captured, because scoreboard contents are not logged: every sidebar
 * pattern below. They are the shapes the feature request expects. The {@code [SBS][Jacob]} probe in
 * {@link JacobContestTracker} logs the real sidebar every 10 s during a contest; when that log
 * exists these patterns are replaced by the real lines and the tests by those lines.
 */
public final class JacobContestParser {

    /** Your bracket, weakest first. */
    public enum Bracket {
        BRONZE, SILVER, GOLD, PLATINUM, DIAMOND;

        public String label() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }

        static Bracket byWord(String word) {
            try {
                return valueOf(word.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException notABracket) {
                return null;
            }
        }
    }

    /**
     * What the sidebar said about the running contest. Any field may be missing: {@code crop} null,
     * {@code secondsLeft}/{@code collected} -1, {@code bracket} null.
     */
    public record Standing(boolean contest, CropType crop, int secondsLeft, long collected,
                           Bracket bracket) {

        static final Standing NONE = new Standing(false, null, -1, -1, null);
    }

    // ---------------------------------------------------------------- VERIFIED chat lines

    private static final String START = "[NPC] Jacob: My contest has started!";
    private static final Pattern ANITA = Pattern.compile(
            "^\\[NPC] Jacob: Your Anita's Artifact is giving you \\+(\\d+) (.+?) Fortune during the contest!$");

    // ---------------------------------------------------------------- ESTIMATED sidebar lines

    /** ESTIMATED: the sidebar's contest header. */
    private static final Pattern HEADER = Pattern.compile("(?i)^jacob's contest$");
    /** ESTIMATED: "<icon> Wheat 12m 30s" - the contest crop and the time left. */
    private static final Pattern CROP_TIME = Pattern.compile(
            "^[^A-Za-z]*([A-Za-z][A-Za-z ']*?)\\s+(?:(\\d+)m\\s*)?(\\d+)s$");
    /** ESTIMATED: "Collected: 12,345" before you place. */
    private static final Pattern COLLECTED = Pattern.compile("(?i)^collected:?\\s*([\\d,]+)$");
    /** ESTIMATED: "Gold with 12,345" once you place. */
    private static final Pattern BRACKET = Pattern.compile(
            "(?i)^(bronze|silver|gold|platinum|diamond)\\s+with\\s+([\\d,]+)$");

    private JacobContestParser() {
    }

    /** VERIFIED: the contest-start line (plain text). */
    public static boolean isStart(String plain) {
        return plain != null && plain.trim().equals(START);
    }

    /** VERIFIED: the crop the Anita's Artifact line names, or {@code null}. "Melon Slice" is Melon. */
    public static CropType anitaCrop(String plain) {
        if (plain == null) {
            return null;
        }
        Matcher m = ANITA.matcher(plain.trim());
        return m.matches() ? CropType.forText(m.group(2)) : null;
    }

    /** VERIFIED: the fortune bonus on the Anita line, or -1. */
    public static int anitaFortune(String plain) {
        if (plain == null) {
            return -1;
        }
        Matcher m = ANITA.matcher(plain.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** ESTIMATED: the contest as the sidebar shows it, from plain sidebar lines. */
    public static Standing standing(List<String> sidebar) {
        if (sidebar == null) {
            return Standing.NONE;
        }
        boolean header = false;
        CropType crop = null;
        int secondsLeft = -1;
        long collected = -1;
        Bracket bracket = null;
        for (String raw : sidebar) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (HEADER.matcher(line).matches()) {
                header = true;
                continue;
            }
            Matcher b = BRACKET.matcher(line);
            if (b.matches()) {
                bracket = Bracket.byWord(b.group(1));
                collected = number(b.group(2));
                continue;
            }
            Matcher c = COLLECTED.matcher(line);
            if (c.matches()) {
                collected = number(c.group(1));
                continue;
            }
            Matcher t = CROP_TIME.matcher(line);
            if (header && crop == null && t.matches()) {
                CropType named = CropType.forText(t.group(1));
                if (named != null) {
                    crop = named;
                    int minutes = t.group(2) == null ? 0 : Integer.parseInt(t.group(2));
                    secondsLeft = minutes * 60 + Integer.parseInt(t.group(3));
                }
            }
        }
        return header ? new Standing(true, crop, secondsLeft, collected, bracket) : Standing.NONE;
    }

    private static long number(String digits) {
        try {
            return Long.parseLong(digits.replace(",", ""));
        } catch (NumberFormatException bad) {
            return -1;
        }
    }
}
