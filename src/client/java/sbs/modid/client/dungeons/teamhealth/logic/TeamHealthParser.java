/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.teamhealth.logic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one teammate row of the dungeon sidebar.
 *
 * <h2>The source, and how sure we are of it</h2>
 * The dungeon scoreboard lists every teammate as a row starting with their class initial in
 * brackets. Written 2026-09-24, <b>before any capture</b>, so every shape below is
 * {@code ESTIMATED}:
 * <ul>
 *   <li>{@code [M] Steve 1,234❤} - alive, <b>current health as an absolute number</b>. No maximum
 *       is printed, which is why the percentage is taken against the highest value seen this run
 *       (see {@link LowHealthMonitor}). A {@code ❤ 1,234} order and a {@code 12.3k} suffix are
 *       accepted too.</li>
 *   <li>{@code [M] Steve DEAD} - dead.</li>
 *   <li>{@code [M] Steve (Mage V)} - the pre-start row, which is the one example in this tree
 *       ({@code scoreboard/elements.json}); it carries no health, so it reads as unknown.</li>
 * </ul>
 * The rows arrive colour-stripped through {@code SkyBlockLocation.sidebarLines()}. The other
 * readable places were ruled out: the tab list's dungeon rows carry class and level only, and another
 * player's entity health on this client is a placeholder rather than the SkyBlock value - the same
 * trap {@code docs/issues/dungeons.md} records for the M7 dragons.
 *
 * <p>Pure - a string in, a reading out - so the parse is tested without a game.
 */
public final class TeamHealthParser {

    /** What a row says about its teammate. */
    public enum State { ALIVE, DEAD, UNKNOWN }

    /** One parsed row. {@code health} is meaningful only for {@link State#ALIVE}. */
    public record Reading(char dungeonClass, String name, State state, long health) {
    }

    private static final Pattern ROW =
            Pattern.compile("^\\[([ABTHM])\\]\\s+([A-Za-z0-9_]{1,16})\\b\\s*(.*)$");
    private static final Pattern DEAD = Pattern.compile("(?i)\\bDEAD\\b");
    private static final Pattern HEALTH_AFTER =
            Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kKmM])?\\s*[❤♥]");
    private static final Pattern HEALTH_BEFORE =
            Pattern.compile("[❤♥]\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kKmM])?");

    private TeamHealthParser() {
    }

    /** The row's reading, or {@code null} when the line is not a teammate row at all. */
    public static Reading parse(String line) {
        if (line == null) {
            return null;
        }
        Matcher row = ROW.matcher(line.trim());
        if (!row.matches()) {
            return null;
        }
        char dungeonClass = row.group(1).charAt(0);
        String name = row.group(2);
        String rest = row.group(3);
        if (DEAD.matcher(rest).find()) {
            return new Reading(dungeonClass, name, State.DEAD, 0L);
        }
        Matcher health = HEALTH_AFTER.matcher(rest);
        if (!health.find()) {
            health = HEALTH_BEFORE.matcher(rest);
            if (!health.find()) {
                return new Reading(dungeonClass, name, State.UNKNOWN, 0L);
            }
        }
        long value = amount(health.group(1), health.group(2));
        return value < 0
                ? new Reading(dungeonClass, name, State.UNKNOWN, 0L)
                : new Reading(dungeonClass, name, State.ALIVE, value);
    }

    private static long amount(String digits, String suffix) {
        try {
            double value = Double.parseDouble(digits.replace(",", ""));
            if (suffix != null) {
                value *= switch (suffix.toLowerCase(Locale.ROOT)) {
                    case "k" -> 1_000;
                    case "m" -> 1_000_000;
                    default -> 1;
                };
            }
            return Math.round(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** "Tank" for {@code 'T'} - how the warning names a teammate's class. */
    public static String className(char initial) {
        return switch (initial) {
            case 'A' -> "Archer";
            case 'B' -> "Berserk";
            case 'T' -> "Tank";
            case 'H' -> "Healer";
            case 'M' -> "Mage";
            default -> "?";
        };
    }
}
