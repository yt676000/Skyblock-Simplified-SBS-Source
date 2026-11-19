/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tab list's "Bestiary" widget: the mobs nearby with their tier progress, which keeps the HUD
 * live between menu opens.
 *
 * <p><b>Shape not yet confirmed in game.</b> Expected, from the request: a {@code Bestiary:} header,
 * then one row per mob, {@code "Zombie 12: 1,234/1,500"} or {@code "Zombie 12: MAX"}. Tier as a
 * number or a roman numeral, counts with separators or a k/m suffix. Every row under the header is
 * logged by {@link BestiaryTracker} ({@code [SBS][Bestiary] tab: …}), so the pattern can be pinned
 * down from a capture; a row that does not match is skipped, never guessed at.
 */
public final class BestiaryTab {

    /** "Zombie 12: 1,234/1,500", "Zombie XII: 1.2k/1.5k", "Zombie 12: MAX". */
    private static final Pattern ROW = Pattern.compile(
            "^\\s*(.+?)\\s+([IVXLC]+|\\d+)\\s*:\\s*(?:([0-9][0-9,.]*[kKmM]?)\\s*/\\s*([0-9][0-9,.]*[kKmM]?)|(MAX(?:ED)?))\\s*$");

    /** One mob in the widget. {@code kills}/{@code needed} are -1 when the row says MAX. */
    public record Row(String name, int tier, long kills, long needed, boolean maxed) {
    }

    private BestiaryTab() {
    }

    /** The widget's rows - the lines under a "Bestiary" header up to the next header or blank line. */
    public static List<String> section(List<String> lines) {
        List<String> out = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (!inside) {
                inside = trimmed.toLowerCase(Locale.ROOT).startsWith("bestiary");
                continue;
            }
            if (trimmed.isEmpty() || isHeader(trimmed)) {
                break;
            }
            out.add(trimmed);
        }
        return out;
    }

    /** The parsed rows of the widget; empty when there is none. */
    public static List<Row> parse(List<String> lines) {
        List<Row> out = new ArrayList<>();
        for (String line : section(lines)) {
            Row row = parseRow(line);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    static Row parseRow(String line) {
        Matcher m = ROW.matcher(line);
        if (!m.matches()) {
            return null;
        }
        int tier = tier(m.group(2));
        if (m.group(5) != null) {
            return new Row(m.group(1).trim(), tier, -1, -1, true);
        }
        long kills = number(m.group(3));
        long needed = number(m.group(4));
        if (kills < 0 || needed <= 0) {
            return null;
        }
        return new Row(m.group(1).trim(), tier, kills, needed, false);
    }

    /** A widget header: ends in ':' with nothing after it ("Skills:", "Info:"). */
    private static boolean isHeader(String trimmed) {
        return trimmed.endsWith(":");
    }

    /** "12" or "XII" -> 12; 0 when neither. */
    static int tier(String text) {
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        int total = 0;
        int previous = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            int value = switch (text.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }

    /** "1,234" -> 1234, "1.5k" -> 1500, "2m" -> 2000000; -1 when unreadable. */
    static long number(String text) {
        String t = text.replace(",", "").toLowerCase(Locale.ROOT);
        long scale = 1;
        if (t.endsWith("k")) {
            scale = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            scale = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(t) * scale);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
