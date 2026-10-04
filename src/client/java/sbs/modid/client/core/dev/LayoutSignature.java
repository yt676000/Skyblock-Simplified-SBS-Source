/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one text normaliser for "same layout, different values". Two uses, one rule set, so they
 * cannot drift: the custom scoreboard keys hidden/reordered lines on {@link #numbers}, and the Layout
 * Recorder decides whether a screen is new on {@link #normalise}.
 *
 * <p><b>Order matters</b> - each rule runs before the ones that would eat its parts: players before
 * numbers (a name can contain digits), dates and durations before bare numbers (a date is a value as
 * a whole, not three numbers), bars before numbers.
 *
 * <p><b>Kept:</b> roman numerals (a "Tier V" item is a different item, not a value), item wording,
 * currency words ("coins", "bits"), punctuation. Pure - no Minecraft types - and unit-tested with
 * real lines from the play instance's logs.
 */
public final class LayoutSignature {

    public static final String PLAYER = "<player>";
    public static final String TIME = "<time>";
    public static final String DATE = "<date>";
    public static final String SERVER = "<server>";
    public static final String BAR = "<bar>";

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** The scoreboard's historical rule: any run of digits, commas and dots. */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[0-9][0-9,.]*");

    /** "[MVP+] Name", "[VIP] Name", "[YOUTUBE] Name", "[ADMIN] Name". */
    private static final Pattern RANKED_NAME = Pattern.compile(
            "\\[(?:VIP|VIP\\+|MVP|MVP\\+|MVP\\+\\+|YOUTUBE|ADMIN|MOD|HELPER|GM|OWNER|PIG\\+\\+\\+)]\\s+[A-Za-z0-9_]{2,16}");
    /** Tab player rows: "[442] Name ⚔" - a level in brackets, then a name. */
    private static final Pattern LEVELLED_NAME = Pattern.compile("^\\[\\d{1,3}]\\s+[A-Za-z0-9_]{2,16}\\b.*$");

    private static final Pattern SERVER_ID = Pattern.compile("\\b(?:mini|mega|m|M)\\d{1,4}[A-Za-z]{1,3}\\b");

    private static final Pattern SB_DATE = Pattern.compile(
            "(?i)\\b(?:early |late )?(?:spring|summer|autumn|winter) \\d{1,2}(?:st|nd|rd|th)\\b");
    private static final Pattern YEAR = Pattern.compile("(?i)\\byear \\d+\\b");
    private static final Pattern ISO_DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern SLASH_DATE = Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");
    private static final Pattern MONTH_DATE = Pattern.compile(
            "(?i)\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* \\d{1,2}(?:, \\d{4})?\\b");

    private static final Pattern CLOCK = Pattern.compile("(?i)\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:am|pm)?(?![\\w])");
    /** "12m 30s", "1h 55m", "2d 6h", "45s", "01m 02s" - integer amounts with a d/h/m/s unit. */
    private static final Pattern DURATION = Pattern.compile("\\b(?:\\d+[dhms]\\b\\s*)+");
    /** "7 Hours", "5 minutes", "1 day". */
    private static final Pattern WORD_DURATION = Pattern.compile(
            "(?i)\\b\\d+\\s+(?:seconds?|secs?|minutes?|mins?|hours?|days?|weeks?)\\b");

    /** Three or more of one progress glyph: "||||||||||", "■■■□□", "-----". */
    private static final Pattern BAR_RUN = Pattern.compile("([|■□▌▍▎▏█▓▒░\\-=━─⬛⬜])\\1{2,}[|■□▌▍▎▏█▓▒░\\-=━─⬛⬜]*");

    /** Every number form: 1,234 · 1.2M · 5k · 12.5% · +50 · -3 · 100.8M. Exponent letter kept out. */
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d[\\d,]*(?:\\.\\d+)?[kKmMbBtT%]?(?![A-Za-z])");

    private LayoutSignature() {
    }

    /** {@code text} without {@code §} codes. */
    public static String stripCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf(SECTION_SIGN) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The scoreboard's signature, unchanged in behaviour: codes gone, every digit run one {@code #},
     * whitespace collapsed, lower case. Kept as its own rule because the custom scoreboard's saved
     * hide/reorder choices are keyed on it - changing it would re-key every player's layout.
     */
    public static String numbers(String text) {
        String normalised = PLAIN_NUMBER.matcher(stripCodes(text)).replaceAll("#");
        return normalised.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** {@link #normalise(String, Collection)} with no known players beyond the rank/level rules. */
    public static String normalise(String text) {
        return normalise(text, Set.of());
    }

    /**
     * The layout form of one line: values replaced by tokens, lower case, whitespace collapsed.
     *
     * @param players names known to be players (tab list, the client's own name); matched whole-word,
     *                case-insensitive
     */
    public static String normalise(String text, Collection<String> players) {
        String s = stripCodes(text).trim();
        if (s.isEmpty()) {
            return "";
        }
        s = replacePlayers(s, players);
        s = SERVER_ID.matcher(s).replaceAll(SERVER);
        s = SB_DATE.matcher(s).replaceAll(DATE);
        s = YEAR.matcher(s).replaceAll(DATE);
        s = ISO_DATE.matcher(s).replaceAll(DATE);
        s = SLASH_DATE.matcher(s).replaceAll(DATE);
        s = MONTH_DATE.matcher(s).replaceAll(DATE);
        s = CLOCK.matcher(s).replaceAll(TIME);
        s = WORD_DURATION.matcher(s).replaceAll(TIME);
        s = DURATION.matcher(s).replaceAll(TIME + " ");
        s = BAR_RUN.matcher(s).replaceAll(BAR);
        s = NUMBER.matcher(s).replaceAll("#");
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Names only, for a stored raw example: other players (and you) become {@link #PLAYER}, nothing
     * else changes. The example exists to show wording, not whose screen it was.
     */
    public static String redactPlayers(String text, Collection<String> players) {
        return replacePlayers(stripCodes(text), players);
    }

    /**
     * {@link #redactPlayers} for text that keeps its {@code §} codes: the same names become
     * {@link #PLAYER}, the codes around them stay. Names are the given players plus every name the
     * rank rule finds in the plain text. A whole tab player row becomes {@link #PLAYER}, as there.
     *
     * <p>A name can be split by a code ({@code §bNa§cme}) and then survives a plain replace. When any
     * collected name is still in the result once its codes are stripped, the plain redacted text is
     * returned instead: the codes are lost, the name never leaks.
     */
    public static String redactPlayersKeepCodes(String text, Collection<String> players) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String plain = stripCodes(text);
        if (LEVELLED_NAME.matcher(plain.trim()).matches()) {
            return PLAYER;
        }
        Set<String> names = new java.util.LinkedHashSet<>();
        if (players != null) {
            for (String name : players) {
                if (name != null && name.length() >= 2) {
                    names.add(name);
                }
            }
        }
        Matcher ranked = RANKED_NAME.matcher(plain);
        while (ranked.find()) {
            String match = ranked.group();
            names.add(match.substring(match.lastIndexOf(' ') + 1));
        }
        String out = text;
        for (String name : names) {
            // The letter of a code right before a name ("§aName") is not part of a longer word.
            out = out.replaceAll("(?i)(?<!(?<!§)[A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])",
                    Matcher.quoteReplacement(PLAYER));
        }
        String check = stripCodes(out);
        for (String name : names) {
            if (Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])")
                    .matcher(check).find()) {
                return redactPlayers(text, players);
            }
        }
        return out;
    }

    private static String replacePlayers(String s, Collection<String> players) {
        if (LEVELLED_NAME.matcher(s).matches()) {
            return PLAYER;   // a whole tab player row: level, name, emblems - all of it is the player
        }
        s = RANKED_NAME.matcher(s).replaceAll(PLAYER);
        if (players != null) {
            for (String name : players) {
                if (name == null || name.length() < 2) {
                    continue;
                }
                s = s.replaceAll("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])",
                        Matcher.quoteReplacement(PLAYER));
            }
        }
        return s;
    }

    /** SHA-1 of a canonical signature string, lower-case hex - the file name. */
    public static String hash(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(canonical.hashCode());
        }
    }

    /** A file-system-safe slug: lower case, letters/digits/dashes, at most 60 chars, never empty. */
    public static String slug(String text) {
        String slug = normalise(text).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "untitled" : slug;
    }
}
