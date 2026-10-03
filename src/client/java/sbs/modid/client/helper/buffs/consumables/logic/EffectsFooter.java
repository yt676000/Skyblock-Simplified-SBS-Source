/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.logic;

import sbs.modid.client.helper.buffs.BuffDuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one parser of the tab footer's Active Effects and Cookie Buff blocks. Pure, tested on footers
 * copied from the play logs. {@code BuffTracker} takes its God Potion / cookie text from here, the
 * consumable timers take everything.
 *
 * <p>The footer as {@code TabWidgets.footerLines()} hands it over (one entry per line):
 * <pre>
 * Active Effects
 * You have a God Potion active! 16 hours            | "1h 29m" | "0s"
 * Use "/effects" to see the effects!
 * Cookie Buff
 * 2 days, 5 hours                                   | "3 years, 3 months" | "Not active! Obtain ..."
 * </pre>
 * or, without a God Potion, {@code You have 2 active effects. Use "/effects" to see them!} followed by
 * single effects ({@code Haste III}, {@code Smoldering Polarization I 12m}); with nothing active,
 * {@code No effects active. Drink Potions or splash them on the} / {@code ground to buff yourself!}.
 * The single-effect list is not complete (two effects counted, one listed), so only "No effects
 * active" is ever read as a full listing.
 */
public final class EffectsFooter {

    /** One single-effect line. {@code remainingMs} is -1 when the line had no time. */
    public record Effect(String name, long remainingMs, long precisionMs) {
    }

    /**
     * What the footer said. {@code blockSeen} is whether an Active Effects header was there at all;
     * without it nothing here says anything about potions.
     */
    public record Reading(boolean blockSeen, String godPotionText, BuffDuration.Parsed godPotion,
                          boolean noEffects, int effectCount, List<Effect> effects,
                          String cookieText, BuffDuration.Parsed cookie, boolean cookieInactive) {

        /** Whether this footer says the God Potion is <i>not</i> active. */
        public boolean godPotionAbsent() {
            return blockSeen && godPotionText == null;
        }
    }

    private static final String EFFECTS_HEADER = "active effects";

    /** "You have a God Potion active! 4 hours" - the remainder is the duration. */
    private static final Pattern GOD_POTION =
            Pattern.compile("(?i)god\\s*potion\\s*active\\s*!?\\s*(.*)$");
    /** The "Cookie Buff" header; the duration is the line after it. */
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)^cookie\\s*buff\\s*:?\\s*(.*)$");
    private static final Pattern EFFECT_COUNT = Pattern.compile("(?i)^you have (\\d+) (?:[a-z-]+ )?effects?\\b");
    /** Headers that start another footer section, ending the effects block. */
    private static final Pattern NEXT_SECTION = Pattern.compile(
            "(?i)^(?:cookie buff\\b.*|.*\\bbuffs\\s*$|.*\\bupgrades?\\s*$)");
    /** Lines inside the block that are instructions, not effects. */
    private static final Pattern NOT_AN_EFFECT = Pattern.compile(
            "(?i)^(?:use\\b.*|you have\\b.*|no effects active\\b.*|ground to buff yourself.*|.*/effects.*)$");
    /** An effect name: starts with a letter, letters/spaces/apostrophes/hyphens, optional level. */
    private static final Pattern EFFECT_NAME = Pattern.compile("^[A-Za-z][A-Za-z' -]*?(?: [IVXLC]+)?$");

    private EffectsFooter() {
    }

    /**
     * Reads {@code lines} - the footer, optionally followed by the tab widget lines, which is how
     * {@code BuffTracker} has always scanned for the two buffs in case Hypixel moves them.
     */
    public static Reading parse(List<String> lines) {
        String god = null;
        String cookie = null;
        boolean cookieInactive = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);

            Matcher m = GOD_POTION.matcher(line);
            if (m.find()) {
                String rest = m.group(1).trim();
                // "...active!" with the duration on the next line is just as plausible a layout.
                god = rest.isEmpty() ? nextValue(lines, i) : rest;
                continue;
            }
            Matcher c = COOKIE_HEADER.matcher(line);
            if (c.matches()) {
                String inline = c.group(1).trim();
                String value = inline.isEmpty() ? nextValue(lines, i) : inline;
                if (value != null && value.toLowerCase(Locale.ROOT).contains("not active")) {
                    cookieInactive = true;
                }
                cookie = value;
                continue;
            }
            if (lower.contains("cookie") && (lower.contains("not active") || lower.contains("no cookie"))) {
                cookie = null;
                cookieInactive = true;
            }
        }
        god = clean(god);
        cookie = clean(cookie);

        List<String> block = block(lines);
        boolean noEffects = false;
        int count = -1;
        List<Effect> effects = new ArrayList<>();
        for (int i = 1; i < block.size(); i++) {
            String line = block.get(i);
            if (line.toLowerCase(Locale.ROOT).startsWith("no effects active")) {
                noEffects = true;
                continue;
            }
            Matcher n = EFFECT_COUNT.matcher(line);
            if (n.find()) {
                count = Integer.parseInt(n.group(1));
                continue;
            }
            if (GOD_POTION.matcher(line).find() || NOT_AN_EFFECT.matcher(line).matches()) {
                continue;
            }
            Effect effect = effect(line);
            if (effect != null) {
                effects.add(effect);
            }
        }
        BuffDuration.Parsed godParsed = BuffDuration.parse(god);
        if (godParsed != null && godParsed.millis() <= 0) {
            godParsed = null;   // "0s" has been seen with a God Potion active: not a reading
        }
        return new Reading(!block.isEmpty(), god, godParsed, noEffects, count, List.copyOf(effects),
                cookie, BuffDuration.parse(cookie), cookieInactive);
    }

    /**
     * The footer's Active Effects block: the header line and every line after it, up to (not
     * including) a blank line or the next section's header. Empty when there is no header.
     */
    public static List<String> block(List<String> footer) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String raw : footer) {
            String line = raw == null ? "" : raw.trim();
            if (!in) {
                if (line.equalsIgnoreCase(EFFECTS_HEADER)) {
                    in = true;
                    out.add(line);
                }
                continue;
            }
            if (line.isEmpty() || NEXT_SECTION.matcher(line).matches()) {
                break;
            }
            out.add(line);
        }
        return out;
    }

    /**
     * One single-effect line: the longest trailing run of up to three words that reads as a
     * duration is the time, the rest the name. {@code null} when what is left is not a name.
     */
    static Effect effect(String line) {
        String[] words = line.trim().split("\\s+");
        for (int take = Math.min(3, words.length - 1); take >= 1; take--) {
            String tail = String.join(" ", Arrays.copyOfRange(words, words.length - take, words.length));
            BuffDuration.Parsed d = BuffDuration.parse(tail);
            if (d != null) {
                String name = String.join(" ", Arrays.copyOfRange(words, 0, words.length - take));
                return EFFECT_NAME.matcher(name).matches() ? new Effect(name, d.millis(), d.precisionMs()) : null;
            }
        }
        String name = line.trim();
        return EFFECT_NAME.matcher(name).matches() ? new Effect(name, -1, 0) : null;
    }

    /** The next non-empty line after {@code i}, or {@code null}. */
    private static String nextValue(List<String> lines, int i) {
        for (int j = i + 1; j < lines.size(); j++) {
            String next = lines.get(j) == null ? "" : lines.get(j).trim();
            if (!next.isEmpty()) {
                return next;
            }
        }
        return null;
    }

    /**
     * Drops the instructional tail Hypixel appends ("Use "/effects" to see the effects!") and any
     * value that is not really a duration.
     */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || lower.startsWith("use ") || lower.contains("/effects")
                || lower.contains("not active") || lower.equals("none")) {
            return null;
        }
        return trimmed;
    }
}
