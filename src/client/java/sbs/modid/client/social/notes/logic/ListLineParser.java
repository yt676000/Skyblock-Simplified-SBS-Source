/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.logic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's {@code /friend list} and {@code /ignore list} answers, one plain (colour-stripped)
 * line at a time. Pure: no game classes, unit-tested in {@code ListLineParserTest}.
 *
 * <p>A player line is only recognised <b>inside a block</b> - after a header this class recognised -
 * because "Steve" on its own is not a list line. The header decides the kind, and the kind decides
 * which line shapes count. A language whose header is not known here is not a block, so nothing is
 * read and nothing is annotated.
 *
 * <p>Shapes: see {@code docs/features/player-notes.md}, <i>Friend and ignore lists</i>. Until the
 * probe captures are recorded there, the shapes are the expected ones, not confirmed ones; every
 * pattern is anchored so a near miss is ignored rather than mis-read.
 */
public final class ListLineParser {

    /** Which list a block belongs to. */
    public enum Kind {
        FRIENDS,
        IGNORED
    }

    private static final String RANK = "(?:\\[[^\\]]{1,20}] )?";
    private static final String NAME = "([A-Za-z0-9_]{1,16})";

    /** {@code << Friends (Page 1 of 3) >>} / {@code << Freunde (Seite 1 von 3) >>}. */
    private static final Pattern FRIENDS_HEADER = Pattern.compile(
            "^(?:<< )?(?:Friends|Freunde) \\((?:Page|Seite) \\d+ (?:of|von) \\d+\\)(?: >>)?$");
    /** {@code Ignored Players (Page 1 of 1)} / {@code Ignorierte Spieler (Seite 1 von 1)}. */
    private static final Pattern IGNORED_HEADER = Pattern.compile(
            "^(?:<< )?(?:Ignored(?: Players| Users)?|Ignorierte(?: Spieler| Nutzer)?|Ignoriert)"
                    + " \\((?:Page|Seite) \\d+ (?:of|von) \\d+\\)(?: >>)?$");

    /** {@code [MVP+] Steve is in SkyBlock}, {@code Steve is currently offline}. */
    private static final Pattern FRIEND_EN = Pattern.compile(
            "^" + RANK + NAME + " is (?:currently offline|offline|in .+|currently .+|idle .+)$");
    /** {@code Steve ist offline}, {@code Steve ist in SkyBlock}, {@code Steve ist gerade offline}. */
    private static final Pattern FRIEND_DE = Pattern.compile(
            "^" + RANK + NAME + " ist (?:gerade |derzeit |momentan )?(?:offline|in .+|auf .+|im .+)$");
    /** An ignore list line is a name, optionally numbered and ranked: {@code 1. [VIP] Steve}. */
    private static final Pattern IGNORED_LINE = Pattern.compile("^(?:\\d+\\. )?" + RANK + NAME + "$");

    private ListLineParser() {
    }

    /** The block a header line opens, or {@code null} when the line is no list header. */
    public static Kind header(String plain) {
        String line = plain.trim();
        if (FRIENDS_HEADER.matcher(line).matches()) {
            return Kind.FRIENDS;
        }
        if (IGNORED_HEADER.matcher(line).matches()) {
            return Kind.IGNORED;
        }
        return null;
    }

    /** Hypixel's box rule above and below the list: a line of nothing but dashes. */
    public static boolean isSeparator(String plain) {
        String line = plain.trim();
        if (line.length() < 10) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != '-' && c != '▬' && c != '━') {
                return false;
            }
        }
        return true;
    }

    /** The player a line of a {@code kind} block names, or {@code null}. */
    public static String playerName(Kind kind, String plain) {
        if (kind == null) {
            return null;
        }
        String line = plain.trim();
        if (kind == Kind.FRIENDS) {
            String name = group(FRIEND_EN, line);
            return name != null ? name : group(FRIEND_DE, line);
        }
        return group(IGNORED_LINE, line);
    }

    private static String group(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        return m.matches() ? m.group(1) : null;
    }
}
