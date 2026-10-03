/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.model;

import sbs.modid.client.economy.essenceshop.logic.PerkLevelReader;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The blessings a dungeon run has collected, summed per type, and the chat lines they come from.
 * Pure - no Minecraft types - so the parse is tested against the real strings.
 *
 * <p><b>Chat is the only source.</b> 607 tab-list dumps taken inside runs carry no blessings
 * section, so a blessing found before the mod was watching is invisible. That is what
 * {@link #complete()} records: only a run whose start was seen can say "none" rather than
 * "unknown".
 *
 * <p>Every pattern here is {@code CONFIRMED} against the play instance's logs (236 buff lines,
 * 2026-07-08 onwards) and matches colour-stripped text, as {@code ChatPatternRegistry} delivers it.
 * See {@code docs/features/dungeon-blessings.md} for the probe.
 */
public final class DungeonBlessings {

    /** The five blessings, in the order the HUD shows them. */
    public enum Type {
        POWER("Power"), LIFE("Life"), WISDOM("Wisdom"), STONE("Stone"), TIME("Time");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** "Power" → POWER; null for a name this build does not know. */
        public static Type of(String name) {
            if (name == null) {
                return null;
            }
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    /** One blessing found: its type and the level the buff line states. */
    public record Found(Type type, int level) {
    }

    /**
     * One line per blessing, shown to the whole team, in three shapes:
     * <pre>
     * DUNGEON BUFF! You found a Blessing of Power V! (19s)
     * DUNGEON BUFF! Somebody found a Blessing of Stone V! (01m 36s)
     * DUNGEON BUFF! A Blessing of Wisdom V was found! (45s)
     * </pre>
     * The run-time suffix is optional (absent on 113 of 217 "found a" lines), so nothing is anchored
     * after the {@code !}. The companion lines - {@code [MVP+] X has obtained Blessing of Power!} and
     * {@code A Blessing of Wisdom was picked up!} - carry no level and arrive with a buff line;
     * matching them would count the blessing twice, which is why the {@code DUNGEON BUFF!} banner is
     * required.
     */
    public static final Pattern BUFF = Pattern.compile(
            "^DUNGEON BUFF! (?:\\S+ found a Blessing of (\\w+) ([IVXL]+)!"
                    + "|A Blessing of (\\w+) ([IVXL]+) was found!)");

    /** Mort's map line: exactly once per run, at its start, on every floor seen (56 of 56 runs). */
    public static final Pattern RUN_START = Pattern.compile(
            "^\\[NPC\\] Mort: Here, I found this map when I first entered the dungeon\\.");

    private final Map<Type, Integer> levels = new EnumMap<>(Type.class);
    private boolean complete;

    /** The blessing a {@link #BUFF} match names, or null when its type or level is unreadable. */
    public static Found parse(Matcher buff) {
        boolean named = buff.group(1) != null;
        Type type = Type.of(named ? buff.group(1) : buff.group(3));
        int level = PerkLevelReader.parseLevelToken(named ? buff.group(2) : buff.group(4));
        return type == null || level <= 0 ? null : new Found(type, level);
    }

    /** {@link #parse(Matcher)} for a whole colour-stripped line; null when it is not a buff line. */
    public static Found parse(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = BUFF.matcher(line);
        return matcher.find() ? parse(matcher) : null;
    }

    /** A run has started with the mod watching: from here on, an absent type really is zero. */
    public void startRun() {
        levels.clear();
        complete = true;
    }

    /** Forgets the run - left the Catacombs, or never saw it start. */
    public void clear() {
        levels.clear();
        complete = false;
    }

    public void add(Found found) {
        if (found != null) {
            levels.merge(found.type(), found.level(), Integer::sum);
        }
    }

    /** Summed level of one type counted so far (0 when none was counted). */
    public int level(Type type) {
        return levels.getOrDefault(type, 0);
    }

    /** Whether the run's start was seen, so every count is the whole run's. */
    public boolean complete() {
        return complete;
    }

    /**
     * What the HUD prints for one type: the level when the run was watched from its start; after a
     * mid-run join, {@code 3+} ("at least") for a counted type and {@code –} for one with nothing
     * counted, because an unseen blessing is not a zero.
     */
    public String display(Type type) {
        int level = level(type);
        if (complete) {
            return String.valueOf(level);
        }
        return level > 0 ? level + "+" : "–";
    }
}
