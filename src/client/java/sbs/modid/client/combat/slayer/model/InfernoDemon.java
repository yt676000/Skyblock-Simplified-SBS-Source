/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two demons the Inferno Demonlord splits into: while they are up the boss itself is gone from
 * the world, so for that stretch they are the only thing worth hitting.
 *
 * <p>Captured in the Server Scanner session of 2026-11-22 (Smoldering Tomb, tier III and IV). Each
 * demon is a three-line stack exactly like the boss's own:
 * <pre>
 *   SPIRIT ♨8 04:07                     (or "IMMUNE 04:07" - the one you cannot hit right now)
 *   Steve's SLAYER BOSS
 *   [Lv999] ⓆⓊⒶⓏⒾⒾ 10M/10M❤             (wither skeleton; Typhoeus is a zombified piglin)
 * </pre>
 * The names are spelled in Unicode circled letters, which is why they are folded to plain letters
 * before matching - a plain "Quazii" never appears on a nametag. "Demon" alone is no demon: Flare,
 * Kindleheart and Burningsoul Demons are minibosses, so only the two names count.
 */
public enum InfernoDemon {

    QUAZII("Quazii"),
    TYPHOEUS("Typhoeus");

    /** Either name, matched whole, so neither turns up inside some other word. */
    private static final Pattern NAME = Pattern.compile("\\b(Quazii|Typhoeus)\\b", Pattern.CASE_INSENSITIVE);
    /**
     * The current health on a tag: the first number of "0/5M❤" or "2.3M/10M❤", or the only one of an
     * older "2.4M❤". Taking the last number before the heart would read the maximum instead.
     */
    private static final Pattern HEALTH =
            Pattern.compile("([\\d.,]+[kKmMbB]?)(?:\\s*/\\s*[\\d.,]+[kKmMbB]?)?\\s*❤");

    /** Any number as tags print them, unit suffix included: "2.4M", "12,000", "6". */
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d.,]*[kKmMbB]?");

    /** Ⓐ..Ⓩ and ⓐ..ⓩ, the circled Latin letters the demon names are written in. */
    private static final char CIRCLED_UPPER_A = 'Ⓐ';
    private static final char CIRCLED_LOWER_A = 'ⓐ';

    private final String displayName;

    InfernoDemon(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The demon a colour-stripped nametag names, or {@code null} when it names neither. */
    public static InfernoDemon inNametag(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        Matcher matcher = NAME.matcher(foldCircled(plain));
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).equalsIgnoreCase("quazii") ? QUAZII : TYPHOEUS;
    }

    /**
     * The text with circled letters turned into plain ones ("ⓆⓊⒶⓏⒾⒾ" → "QUAZII"). Returns the same
     * string when there is nothing to fold, so the common case allocates nothing.
     */
    public static String foldCircled(String text) {
        if (text == null) {
            return "";
        }
        int first = -1;
        for (int i = 0; i < text.length(); i++) {
            if (isCircled(text.charAt(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        out.append(text, 0, first);
        for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= CIRCLED_UPPER_A && c < CIRCLED_UPPER_A + 26) {
                out.append((char) ('A' + (c - CIRCLED_UPPER_A)));
            } else if (c >= CIRCLED_LOWER_A && c < CIRCLED_LOWER_A + 26) {
                out.append((char) ('a' + (c - CIRCLED_LOWER_A)));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isCircled(char c) {
        return c >= CIRCLED_UPPER_A && c < CIRCLED_LOWER_A + 26;
    }

    /**
     * The tag with every number taken out ("[Lv999] QUAZII 2.3M/10M❤" → "[Lv#] QUAZII #/#❤"): the same
     * wording with different health reads the same, which is what the probe dedups on.
     */
    public static String withoutNumbers(String plain) {
        return plain == null ? "" : NUMBER.matcher(plain).replaceAll("#");
    }

    /** The current health text off a demon tag ("2.3M" of "2.3M/10M❤"), or {@code null} when it carries none. */
    public static String healthIn(String plain) {
        if (plain == null) {
            return null;
        }
        Matcher matcher = HEALTH.matcher(plain);
        return matcher.find() ? matcher.group(1) : null;
    }
}
