/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.sacrifice.logic;

import sbs.modid.client.core.util.PlainText;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Sacrifice Altar lines in The End. Pure: no Minecraft, so every confirmed line is a test.
 *
 * <p>Confirmed shapes (maintainer's 1.8.9 logs):
 * <ul>
 *   <li>{@code SACRIFICE! You turned Ender Necklace into 30 Dragon Essence!}</li>
 *   <li>{@code BONUS LOOT! You also received 2x Enchanted Ender Pearl from your sacrifice!}</li>
 *   <li>{@code BONUS LOOT! You also received Dragon Claw from your sacrifice!}</li>
 *   <li>{@code BONUS LOOT! They also received ... from their sacrifice!} - another player's, never counted</li>
 *   <li>{@code A sacrifice is already in progress!} / {@code This item cannot be sacrificed!} - nothing happened</li>
 * </ul>
 */
public final class SacrificeParser {

    public enum Type { SACRIFICE, BONUS, REFUSED }

    /**
     * One recognised line. {@code item} is the display name as written; {@code count} is the essence
     * for a sacrifice and the stack size for bonus loot; {@code own} is false for "They also received".
     */
    public record Event(Type type, String item, int count, boolean own) {
    }

    private static final Pattern SACRIFICE = Pattern.compile(
            "^SACRIFICE! You turned (.+?) into ([\\d,]+) Dragon Essence!$");
    private static final Pattern BONUS = Pattern.compile(
            "^BONUS LOOT! (You|They) also received (?:([\\d,]+)x )?(.+?) from (?:your|their) sacrifice!$");
    private static final Pattern REFUSED = Pattern.compile(
            "^(?:A sacrifice is already in progress|This item cannot be sacrificed)!$");
    /** The client's stacking counter, " (N)" at the very end. */
    private static final Pattern STACK = Pattern.compile("\\s*\\(\\d+\\)$");
    /** "Old Dragon Fragment" is keyed {@code OLD_FRAGMENT}, not {@code OLD_DRAGON_FRAGMENT}. */
    private static final Pattern FRAGMENT = Pattern.compile("^(\\w+) Dragon Fragment$");
    private static final Pattern NON_KEY = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern EDGE = Pattern.compile("^_+|_+$");

    private SacrificeParser() {
    }

    /** What the probe looks at: anything altar-shaped, read or not. */
    public static boolean candidate(String line) {
        return line != null && (line.contains("SACRIFICE!") || line.contains("BONUS LOOT!")
                || line.contains("sacrifice"));
    }

    /** The recognised event, or {@code null} for anything else. */
    public static Event parse(String line) {
        String text = clean(line);
        if (text.isEmpty()) {
            return null;
        }
        Matcher sacrifice = SACRIFICE.matcher(text);
        if (sacrifice.matches()) {
            return new Event(Type.SACRIFICE, sacrifice.group(1).trim(), number(sacrifice.group(2)), true);
        }
        Matcher bonus = BONUS.matcher(text);
        if (bonus.matches()) {
            int count = bonus.group(2) == null ? 1 : number(bonus.group(2));
            return new Event(Type.BONUS, bonus.group(3).trim(), count, bonus.group(1).equals("You"));
        }
        if (REFUSED.matcher(text).matches()) {
            return new Event(Type.REFUSED, "", 0, true);
        }
        return null;
    }

    /** A bonus-loot display name as a price id: {@code "Enchanted Eye of Ender"} → {@code ENCHANTED_EYE_OF_ENDER}. */
    public static String itemId(String name) {
        Matcher fragment = FRAGMENT.matcher(name.trim());
        String base = fragment.matches() ? fragment.group(1) + " Fragment" : name;
        return key(base);
    }

    /**
     * A display name reduced to id convention, for matching a chat name against the removed stack's
     * name. The stack's name may carry a reforge or stars the chat line leaves out, so callers test
     * containment, not equality.
     */
    public static String key(String name) {
        if (name == null) {
            return "";
        }
        String upper = NON_KEY.matcher(PlainText.strip(name).toUpperCase(Locale.ROOT)).replaceAll("_");
        return EDGE.matcher(upper).replaceAll("");
    }

    /** Colour codes, surrounding space and the stacking counter removed. */
    static String clean(String line) {
        if (line == null) {
            return "";
        }
        return STACK.matcher(PlainText.strip(line).trim()).replaceFirst("");
    }

    private static int number(String digits) {
        return Integer.parseInt(digits.replace(",", ""));
    }
}
