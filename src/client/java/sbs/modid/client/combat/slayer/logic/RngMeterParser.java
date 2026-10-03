/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.logic;

import sbs.modid.client.combat.carry.model.SlayerBoss;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the slayer RNG meter out of Hypixel's text. Pure: plain strings in, values out, so every
 * rule is unit-tested on captured lines. Wording: {@code docs/skyblock-ui/menus.md}, <i>Slayer</i>.
 *
 * <p>Two sources, and they say different things:
 * <ul>
 *   <li><b>The menu</b> ({@code <Boss> RNG Meter} in the {@code Slayer} hub and on each boss page):
 *       selected drop, current XP as a full number, and the goal - but the goal only abbreviated
 *       ({@code 3.5M}, {@code 885.6k}). The goal exists nowhere else.</li>
 *   <li><b>Chat</b> after each boss: {@code RNG Meter - 26,791 Stored XP}. That is the meter's
 *       <i>total</i>, not the gain - consecutive kills read 26,241 then 26,791, and the Blaze line
 *       read 45,650, the same figure the menu showed. It can be abbreviated too ({@code 27.3K}).
 *       Which slayer it belongs to comes from the {@code <Type> Slayer LVL} line just before it.</li>
 * </ul>
 */
public final class RngMeterParser {

    /** What the menu slot says. {@code goal} is -1 when the line was missing. */
    public record MenuReading(SlayerBoss boss, String selectedDrop, long current, long goal,
                              double percent) {
    }

    private static final String NAME_SUFFIX = " RNG Meter";
    private static final Pattern PROGRESS = Pattern.compile("^Progress: ([0-9]+(?:\\.[0-9]+)?)%$");
    private static final Pattern AMOUNTS =
            Pattern.compile("^([0-9][0-9,]*(?:\\.[0-9]+)?[kKmMbB]?)/([0-9][0-9,]*(?:\\.[0-9]+)?[kKmMbB]?)$");
    /** "RNG Meter - 26,791 Stored XP" / "RNG Meter - 27.3K Stored XP" (colour codes stripped). */
    private static final Pattern CHAT =
            Pattern.compile("^RNG Meter - ([0-9][0-9,]*(?:\\.[0-9]+)?[kKmMbB]?) Stored XP$");
    /** "Enderman Slayer LVL 9 - LVL MAXED OUT!" - the slayer of the RNG line that follows. */
    private static final Pattern LEVEL_LINE = Pattern.compile("^([A-Za-z]+) Slayer LVL \\d+\\b.*$");
    private static final Pattern CODES = Pattern.compile("(?i)§.");

    private RngMeterParser() {
    }

    /**
     * The slayer RNG meter in a menu slot, or {@code null} when this slot is not one. Only a slot
     * named after a slayer boss counts: the Crystal Nucleus and Catacombs meters share the shape.
     */
    public static MenuReading parseMenu(String name, List<String> lore) {
        if (name == null || lore == null) {
            return null;
        }
        String plainName = strip(name);
        if (!plainName.endsWith(NAME_SUFFIX)) {
            return null;
        }
        SlayerBoss boss = bossByDisplayName(plainName.substring(0, plainName.length() - NAME_SUFFIX.length()));
        if (boss == null) {
            return null;
        }
        String drop = null;
        double percent = -1;
        long current = -1;
        long goal = -1;
        for (int i = 0; i < lore.size(); i++) {
            String line = strip(lore.get(i));
            if (line.equals("Selected Drop") && i + 1 < lore.size()) {
                String next = strip(lore.get(i + 1));
                drop = next.isEmpty() ? null : next;
                continue;
            }
            Matcher progress = PROGRESS.matcher(line);
            if (progress.matches()) {
                percent = Double.parseDouble(progress.group(1));
                continue;
            }
            Matcher amounts = AMOUNTS.matcher(line);
            if (amounts.matches()) {
                current = number(amounts.group(1));
                goal = number(amounts.group(2));
            }
        }
        if (current < 0) {
            return null;   // no progress figures: nothing worth recording
        }
        return new MenuReading(boss, drop, current, goal, percent);
    }

    /** The stored XP of a slayer RNG chat line, or -1 when the line is not one. */
    public static long parseChatStoredXp(String line) {
        if (line == null) {
            return -1;
        }
        Matcher matcher = CHAT.matcher(strip(line));
        return matcher.matches() ? number(matcher.group(1)) : -1;
    }

    /** Whether a slayer RNG chat line printed its total abbreviated ({@code 27.3K}) rather than in full. */
    public static boolean chatTotalIsAbbreviated(String line) {
        Matcher matcher = CHAT.matcher(strip(line == null ? "" : line));
        return matcher.matches() && Character.isLetter(matcher.group(1).charAt(matcher.group(1).length() - 1));
    }

    /** The slayer a {@code <Type> Slayer LVL} line names, or {@code null}. */
    public static SlayerBoss parseLevelLine(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = LEVEL_LINE.matcher(strip(line));
        if (!matcher.matches()) {
            return null;
        }
        return switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "zombie" -> SlayerBoss.REVENANT;
            case "spider" -> SlayerBoss.TARANTULA;
            case "wolf" -> SlayerBoss.SVEN;
            case "enderman" -> SlayerBoss.VOIDGLOOM;
            case "blaze" -> SlayerBoss.INFERNO;
            case "vampire" -> SlayerBoss.BLOODFIEND;
            default -> null;
        };
    }

    /** "1,045,050" / "3.5M" / "885.6k" / "27.3K" → a whole number. */
    static long number(String text) {
        String clean = text.replace(",", "");
        char last = Character.toLowerCase(clean.charAt(clean.length() - 1));
        long scale = switch (last) {
            case 'k' -> 1_000L;
            case 'm' -> 1_000_000L;
            case 'b' -> 1_000_000_000L;
            default -> 1L;
        };
        String digits = scale == 1L ? clean : clean.substring(0, clean.length() - 1);
        return Math.round(Double.parseDouble(digits) * scale);
    }

    private static SlayerBoss bossByDisplayName(String name) {
        for (SlayerBoss boss : SlayerBoss.values()) {
            if (boss.displayName().equals(name)) {
                return boss;
            }
        }
        return null;
    }

    private static String strip(String text) {
        return CODES.matcher(text).replaceAll("").trim();
    }
}
