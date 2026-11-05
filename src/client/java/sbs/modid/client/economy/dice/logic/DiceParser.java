/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.dice.logic;

import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.dice.model.DiceKind;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the player's own Archfiend dice lines. Pure: no Minecraft, so every confirmed line is a test.
 *
 * <p>Only the roll line is read for the numbers. A 6's coins and a 7's dye follow from the rolled
 * value, so the payout and dye lines (not captured yet) are not needed to count them - and when they
 * are captured they must not count a second time.
 */
public final class DiceParser {

    public enum Type { ROLL, DROP }

    /** One recognised line. {@code value} and {@code bonus} are 0 for a drop; {@code bonus} 0 if absent. */
    public record Event(Type type, DiceKind kind, int value, int bonus) {
    }

    private static final Pattern ROLL = Pattern.compile(
            "^Your (High Class )?Archfiend Dice rolled an? ([1-7])!(.*)$");
    private static final Pattern BONUS = Pattern.compile("Bonus: ([+-]?\\d+)❤");
    private static final Pattern DROP = Pattern.compile(
            "^(?:VERY|CRAZY) RARE DROP!\\s+\\(?(High Class )?Archfiend Dice\\)?");
    /** The client's stacking counter, " (N)" at the very end. */
    private static final Pattern STACK = Pattern.compile("\\s*\\(\\d+\\)$");
    /** "[MVP+] Name: ..." - somebody talking, never a game line. */
    private static final Pattern SPEAKER = Pattern.compile("^\\[[^\\]]+\\] ?\\S+: ");

    private DiceParser() {
    }

    /** What the probe looks at: anything dice-shaped, read or not. */
    public static boolean candidate(String line) {
        return line != null && (line.contains("Archfiend") || line.contains("Dice rolled"));
    }

    /** The recognised event, or {@code null} for anything else, including other players' chat. */
    public static Event parse(String line) {
        String text = clean(line);
        if (text.isEmpty() || !ownLine(text)) {
            return null;
        }
        Matcher roll = ROLL.matcher(text);
        if (roll.matches()) {
            DiceKind kind = roll.group(1) != null ? DiceKind.HIGH_CLASS : DiceKind.ARCHFIEND;
            Matcher bonus = BONUS.matcher(roll.group(3));
            int value = roll.group(2).charAt(0) - '0';
            return new Event(Type.ROLL, kind, value, bonus.find() ? Integer.parseInt(bonus.group(1)) : 0);
        }
        Matcher drop = DROP.matcher(text);
        if (drop.find()) {
            return new Event(Type.DROP, drop.group(1) != null ? DiceKind.HIGH_CLASS : DiceKind.ARCHFIEND, 0, 0);
        }
        return null;
    }

    /** Colour codes, surrounding space and the stacking counter removed. */
    static String clean(String line) {
        if (line == null) {
            return "";
        }
        return STACK.matcher(PlainText.strip(line).trim()).replaceFirst("");
    }

    /** False for relayed and private chat and for any "[rank] name:" speaker. */
    static boolean ownLine(String text) {
        return !(text.startsWith("Party >") || text.startsWith("Guild >") || text.startsWith("Co-op >")
                || text.startsWith("Officer >") || text.startsWith("From ") || text.startsWith("To ")
                || SPEAKER.matcher(text).find());
    }
}
