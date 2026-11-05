/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.dragonprofit.logic;

import sbs.modid.client.combat.dragonprofit.model.DragonType;
import sbs.modid.client.core.util.PlainText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the End dragon fight lines. Pure: no Minecraft, so every confirmed line is a test.
 * Line certainty is in {@code docs/features/dragon-profit-tracker.md}.
 */
public final class DragonParser {

    public enum Type { EYE_PLACED, EGG, SPAWN, DOWN, FINAL_BLOW, DAMAGER, OWN_DAMAGE, EYE_DROP }

    /**
     * One recognised line. {@code name} is the player on an eye / final-blow / damager line;
     * {@code count} the eye count (1..8) or the damager place (1..3) or your position;
     * {@code damage} the damage on a damager or "Your Damage" line, else 0.
     */
    public record Event(Type type, DragonType dragon, String name, int count, long damage) {
    }

    /** "[MVP+] " - optional, so both ranked and unranked names match. */
    private static final String RANK = "(?:\\[[^\\]]+\\] )?";
    private static final Pattern EYE = Pattern.compile(
            "^☬ " + RANK + "(\\S+) placed a Summoning Eye!(?: Brace yourselves!)? \\(([1-8])/8\\)$");
    private static final Pattern EGG = Pattern.compile("^☬ The Dragon Egg has spawned!$");
    private static final Pattern SPAWN = Pattern.compile("^☬ The (\\w+) Dragon has spawned!$");
    private static final Pattern DOWN = Pattern.compile("^(\\w+) DRAGON DOWN!$");
    private static final Pattern FINAL_BLOW = Pattern.compile("^" + RANK + "(\\S+) dealt the final blow\\.$");
    private static final Pattern DAMAGER = Pattern.compile(
            "^([123])(?:st|nd|rd) Damager - " + RANK + "(\\S+) - ([\\d,.]+)$");
    private static final Pattern OWN_DAMAGE = Pattern.compile(
            "^Your Damage: ([\\d,.]+) \\(Position #(\\d+)\\)$");
    private static final Pattern EYE_DROP = Pattern.compile("^RARE DROP! \\(?Summoning Eye\\)?(?: .*)?$");
    /** The client's stacking counter, " (N)" at the very end. */
    private static final Pattern STACK = Pattern.compile("\\s*\\(\\d+\\)$");
    /** "[MVP+] Name: ..." - somebody talking, never a game line. */
    private static final Pattern SPEAKER = Pattern.compile("^\\[[^\\]]+\\] ?\\S+: ");

    private DragonParser() {
    }

    /** What the probe and the chat prefilter look at: anything dragon-shaped, read or not. */
    public static boolean candidate(String line) {
        return line != null && (line.contains("Summoning Eye") || line.contains("Dragon")
                || line.contains("DRAGON DOWN") || line.contains("Your Damage:") || line.contains("Damager -")
                || line.contains("final blow"));
    }

    /** The recognised event, or {@code null} for anything else, including relayed chat. */
    public static Event parse(String line) {
        String text = clean(line);
        if (text.isEmpty() || !ownLine(text)) {
            return null;
        }
        Matcher m = EYE.matcher(text);
        if (m.matches()) {
            return new Event(Type.EYE_PLACED, null, m.group(1), m.group(2).charAt(0) - '0', 0L);
        }
        if (EGG.matcher(text).matches()) {
            return new Event(Type.EGG, null, null, 0, 0L);
        }
        m = SPAWN.matcher(text);
        if (m.matches()) {
            DragonType type = DragonType.of(m.group(1));
            return type == null ? null : new Event(Type.SPAWN, type, null, 0, 0L);
        }
        m = DOWN.matcher(text);
        if (m.matches()) {
            DragonType type = DragonType.of(m.group(1));
            return type == null ? null : new Event(Type.DOWN, type, null, 0, 0L);
        }
        m = OWN_DAMAGE.matcher(text);
        if (m.matches()) {
            return new Event(Type.OWN_DAMAGE, null, null, Integer.parseInt(m.group(2)), damage(m.group(1)));
        }
        m = DAMAGER.matcher(text);
        if (m.matches()) {
            return new Event(Type.DAMAGER, null, m.group(2), m.group(1).charAt(0) - '0', damage(m.group(3)));
        }
        m = FINAL_BLOW.matcher(text);
        if (m.matches()) {
            return new Event(Type.FINAL_BLOW, null, m.group(1), 0, 0L);
        }
        if (EYE_DROP.matcher(text).matches()) {
            return new Event(Type.EYE_DROP, null, null, 0, 0L);
        }
        return null;
    }

    /** "1,234,567" or "1,234,567.8" to a whole number; -1 when it does not read. */
    static long damage(String digits) {
        try {
            return Math.round(Double.parseDouble(digits.replace(",", "")));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** Colour codes, surrounding space and the stacking counter removed. */
    public static String clean(String line) {
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
