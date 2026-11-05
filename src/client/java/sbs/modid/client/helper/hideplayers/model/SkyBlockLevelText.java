/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.hideplayers.model;

/**
 * The SkyBlock level as Hypixel writes it in front of a player's name - pure text, no Minecraft
 * types, so it is unit-tested.
 *
 * <p>Public chat, colour-stripped (confirmed shapes from the maintainer's logs):
 * <pre>
 * [212] [MVP+] Name: msg
 * [212] ✦ [MVP+] Name: msg      (an emblem glyph before the rank)
 * [22] Name: msg                (no rank)
 * [212] [ ] [MVP+] Name: msg
 * </pre>
 * Party, guild, private and co-op lines start with their channel ("Party &gt;", "Guild &gt;",
 * "From", "To", "Co-op &gt;"), never with the level, so the scan below - anchored at the first
 * character - cannot match them.
 *
 * <p>Tab list: a real player's entry has no display name; the level is the scoreboard team prefix
 * ("[212] [MVP+] "), which {@link #prefixLevel} reads.
 */
public final class SkyBlockLevelText {

    /** A public-chat speaker: their level and their account name. */
    public record Speaker(int level, String name) {
    }

    /** Longest text inside a rank bracket ("[MVP++]", "[YOUTUBE]", "[ ]"). */
    private static final int MAX_BRACKET = 12;
    private static final int MAX_NAME = 16;

    private SkyBlockLevelText() {
    }

    /**
     * The speaker of a public-chat line, or {@code null} for any other line. Colour codes are removed
     * here. Shape: "[level] ", an optional one- or two-character emblem and a space, any number of
     * "[...] " brackets, then the account name followed by ": ". A hand-written scan, no regex.
     */
    public static Speaker publicSpeaker(String line) {
        if (line == null) {
            return null;
        }
        String text = stripColours(line);
        int length = text.length();
        int close = levelClose(text, 0);
        if (close < 0 || close + 1 >= length || text.charAt(close + 1) != ' ') {
            return null;
        }
        int level = digits(text, 1, close);
        int i = close + 2;

        // Optional emblem glyph before the rank: one or two characters that are not a name or a bracket.
        int space = text.indexOf(' ', i);
        if (space > i && space - i <= 2 && isEmblem(text, i, space)) {
            i = space + 1;
        }
        // Rank brackets: "[MVP+] ", "[ ] ".
        while (i < length && text.charAt(i) == '[') {
            int end = text.indexOf(']', i);
            if (end < 0 || end - i - 1 > MAX_BRACKET || end + 1 >= length || text.charAt(end + 1) != ' ') {
                return null;
            }
            i = end + 2;
        }
        // The name, then ": ".
        int start = i;
        while (i < length && i - start < MAX_NAME && isNameChar(text.charAt(i))) {
            i++;
        }
        if (i == start || i + 1 >= length || text.charAt(i) != ':' || text.charAt(i + 1) != ' ') {
            return null;
        }
        return new Speaker(level, text.substring(start, i));
    }

    /**
     * The player of a tab-list text row - "[445] ⚡ [MVP++] XJU" or "[557] rt606 ➶ (Archer L)" - or
     * {@code null}. SkyBlock lists players as widget text, not as player entries (the 2026-10-17 hub
     * read 50 entries, none with a level prefix), so the row's own text is where level and name are.
     * Same shape as {@link #publicSpeaker} up to the name, which here ends at a space or the end.
     */
    public static Speaker tabPlayer(String row) {
        if (row == null) {
            return null;
        }
        String text = stripColours(row).trim();
        int length = text.length();
        int close = levelClose(text, 0);
        if (close < 0 || close + 1 >= length || text.charAt(close + 1) != ' ') {
            return null;
        }
        int level = digits(text, 1, close);
        int i = close + 2;
        int space = text.indexOf(' ', i);
        if (space > i && space - i <= 2 && isEmblem(text, i, space)) {
            i = space + 1;
        }
        while (i < length && text.charAt(i) == '[') {
            int end = text.indexOf(']', i);
            if (end < 0 || end - i - 1 > MAX_BRACKET || end + 1 >= length || text.charAt(end + 1) != ' ') {
                return null;
            }
            i = end + 2;
        }
        int start = i;
        while (i < length && i - start < MAX_NAME && isNameChar(text.charAt(i))) {
            i++;
        }
        if (i == start || (i < length && text.charAt(i) != ' ')) {
            return null;
        }
        return new Speaker(level, text.substring(start, i));
    }

    /** The level at the start of a tab-list team prefix, or -1 when there is none. */
    public static int prefixLevel(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return -1;
        }
        String text = stripColours(prefix);
        int from = 0;
        while (from < text.length() && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        int close = levelClose(text, from);
        return close < 0 ? -1 : digits(text, from + 1, close);
    }

    /** Index of the ']' closing a "[1-3 digits]" that starts at {@code at}, or -1. */
    private static int levelClose(String text, int at) {
        if (at >= text.length() || text.charAt(at) != '[') {
            return -1;
        }
        int i = at + 1;
        while (i < text.length() && i - at <= 3 && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            i++;
        }
        int count = i - at - 1;
        return count >= 1 && count <= 3 && i < text.length() && text.charAt(i) == ']' ? i : -1;
    }

    private static int digits(String text, int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            value = value * 10 + (text.charAt(i) - '0');
        }
        return value;
    }

    private static boolean isEmblem(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '[' || isNameChar(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** Removes legacy section-sign colour codes, which survive in some server-built strings. */
    public static String stripColours(String text) {
        if (text.indexOf('§') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
