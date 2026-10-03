/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the chat line Hypixel prints when a Century Cake is eaten. Pure, so it is tested on the
 * captured lines.
 *
 * <p>The two shapes, seen 2026-10-01 for every cake (play-instance log):
 * <ul>
 *   <li>{@code Yum! You gain +5 Mining Fortune for 48 hours!}</li>
 *   <li>{@code Big Yum! You refresh +5 Sweep for 48 hours!} - the same cake again while its buff
 *       is active.</li>
 * </ul>
 * The word "cake" appears in neither, so the {@code Yum!} shape is the only handle. Both carry the
 * duration, so it is read from the line rather than assumed.
 *
 * <p><b>Every real line has the stat's icon glued to the number</b>: a private-use glyph from
 * Hypixel's font ({@code +5}, the glyph, then {@code  Mining Fortune}), invisible in most logs and
 * copies. It is skipped, not required, so a line without it parses too.
 */
public final class CakeBuffParser {

    /** One eaten cake: the stat, its amount, the duration Hypixel printed, and whether it refreshed. */
    public record Eat(String stat, int amount, int hours, boolean refresh) {
    }

    private static final Pattern EAT = Pattern.compile(
            "^(Big )?Yum! You (gain|refresh) \\+(\\d+)[^A-Za-z ]* ([A-Za-z][A-Za-z ]*?) for (\\d+) hours?!$");
    private static final Pattern CODES = Pattern.compile("(?i)§.");

    private CakeBuffParser() {
    }

    /** The eaten cake on this line, or {@code null} when it is not a recognised eat line. */
    public static Eat parse(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = EAT.matcher(strip(line));
        if (!m.matches()) {
            return null;
        }
        boolean big = m.group(1) != null;
        boolean refresh = m.group(2).equals("refresh");
        if (big != refresh) {
            return null;   // "Big Yum! You gain" / "Yum! You refresh" have not been seen: not guessed at
        }
        return new Eat(m.group(4).trim(), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(5)), refresh);
    }

    /**
     * Whether the line has the cake shape at all ({@code Yum!} / {@code Big Yum!} at the start).
     * A line that passes this but not {@link #parse} is a wording change, and is logged.
     */
    public static boolean looksLikeCake(String line) {
        if (line == null) {
            return false;
        }
        String plain = strip(line);
        return plain.startsWith("Yum!") || plain.startsWith("Big Yum!");
    }

    private static String strip(String text) {
        return CODES.matcher(text).replaceAll("").trim();
    }
}
