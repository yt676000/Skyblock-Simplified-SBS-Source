/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tab;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player's own SkyBlock stats, read off the tab list's stats widget.
 *
 * <p>The client is never sent its SkyBlock Strength or Defense as numbers - they are server-side
 * concepts that only ever arrive as <i>text</i>. The tab widget is the one place that text is
 * available continuously and without opening anything: a menu has to be opened to be read, the
 * action bar carries only Health / Mana / Defense, but the widget updates itself while you fight.
 * That is what makes a stat <i>change</i> observable at all.
 *
 * <p>Lives in {@code core/tab} next to {@link TabWidgets} rather than inside the one feature reading
 * it today: it is the same "read the tab list" machinery, and the Damage Overlay's menu-scraped
 * snapshot is the obvious second caller.
 *
 * <p><b>Matching rules</b>, the same ones the Damage Overlay learned the hard way:
 * <ul>
 *   <li>Colour codes are already gone - {@link TabWidgets#lines()} strips them.</li>
 *   <li>Hypixel prefixes every stat with a custom-font glyph that changes between menu generations,
 *       so everything before the first letter is dropped and matching is by stat <i>name</i>.</li>
 *   <li>Names are matched against a fixed list. A permissive "any {@code Name: number} line" reader
 *       would happily take "Pests: 3" or "Bank: 12" out of the neighbouring widgets.</li>
 *   <li>A {@code current/max} value ("Health: 1,340/1,340") reads as the <b>max</b>. The current
 *       half moves every time something hits you, and a reader watching for stat changes would see
 *       nothing else.</li>
 * </ul>
 *
 * <p>Nothing is cached here - {@link TabWidgets} does not cache either, and callers are throttled.
 */
public final class TabStats {

    /**
     * The SkyBlock stats worth reading off the widget, in the order they are reported in.
     *
     * <p>Ids are the enum constants and are never renumbered - a stat's identity outlives whatever
     * Hypixel calls it this year, which is what the alias list is for.
     */
    public enum Stat {
        HEALTH("Health", 0xFFFF5555, false),
        DEFENSE("Defense", 0xFF55FF55, false),
        STRENGTH("Strength", 0xFFFF5555, false),
        INTELLIGENCE("Intelligence", 0xFF55FFFF, false),
        CRIT_CHANCE("Crit Chance", 0xFF5555FF, true),
        CRIT_DAMAGE("Crit Damage", 0xFF5555FF, true),
        ATTACK_SPEED("Attack Speed", 0xFFFFFF55, true, "Bonus Attack Speed"),
        ABILITY_DAMAGE("Ability Damage", 0xFFFF5555, true),
        FEROCITY("Ferocity", 0xFFFF5555, false),
        SPEED("Speed", 0xFFFFFFFF, false, "Walk Speed"),
        TRUE_DEFENSE("True Defense", 0xFFFFFFFF, false),
        HEALTH_REGEN("Health Regen", 0xFFFF5555, false),
        VITALITY("Vitality", 0xFFAA0000, false),
        MENDING("Mending", 0xFFFF5555, false),
        SWING_RANGE("Swing Range", 0xFFFFAA00, false),
        MAGIC_FIND("Magic Find", 0xFF55FFFF, false),
        PET_LUCK("Pet Luck", 0xFFFF55FF, false),
        SEA_CREATURE_CHANCE("Sea Creature Chance", 0xFF00AAAA, true),
        FISHING_SPEED("Fishing Speed", 0xFF55FFFF, false),
        MINING_SPEED("Mining Speed", 0xFFFFAA00, false),
        MINING_FORTUNE("Mining Fortune", 0xFFFFAA00, false),
        FARMING_FORTUNE("Farming Fortune", 0xFFFFAA00, false),
        FORAGING_FORTUNE("Foraging Fortune", 0xFFFFAA00, false),
        BREAKING_POWER("Breaking Power", 0xFF00AAAA, false),
        PRISTINE("Pristine", 0xFFFF55FF, false),
        // Read off a live widget on Galatea. Whether Respiration and Pressure Resistance are
        // percentages is a display question only and is not yet confirmed against the game.
        SWEEP("Sweep", 0xFF55FF55, false),
        RESPIRATION("Respiration", 0xFF55FFFF, true),
        PRESSURE_RESISTANCE("Pressure Resistance", 0xFF00AAAA, true);

        private final String displayName;
        private final int color;
        private final boolean percent;
        private final String[] aliases;

        Stat(String displayName, int color, boolean percent, String... aliases) {
            this.displayName = displayName;
            this.color = color;
            this.percent = percent;
            this.aliases = aliases;
        }

        /** The stat's name as it is shown to the player. */
        public String displayName() {
            return displayName;
        }

        /** Hypixel's colour for this stat, for a chat line that should look like the game's. */
        public int color() {
            return color;
        }

        /** Whether the value is a percentage (Crit Damage 380 means 380%). */
        public boolean percent() {
            return percent;
        }
    }

    /** Every accepted spelling, lower-cased, to the stat it means. */
    private static final Map<String, Stat> BY_NAME = new HashMap<>(64);

    static {
        for (Stat stat : Stat.values()) {
            BY_NAME.put(stat.displayName().toLowerCase(Locale.ROOT), stat);
            for (String alias : stat.aliases) {
                BY_NAME.put(alias.toLowerCase(Locale.ROOT), stat);
            }
        }
    }

    /**
     * One stat row: a name, an optional colon, then the number - optionally {@code current/max},
     * optionally a trailing percent sign, and optionally something after it that is not part of the
     * value ("Strength: 512 (+100)" while a buff is running).
     *
     * <p><b>The glyph sits between the colon and the number</b>, not at the start of the line:
     * a live widget reads {@code Sweep: <glyph>100}. An earlier version of this pattern allowed only
     * spaces there and therefore matched <i>no</i> row the game actually sends - the feature went
     * quiet rather than wrong, which is exactly the failure this class was written to avoid. The
     * separator is now "any run of characters that is neither a digit nor a letter", which is correct
     * whatever Hypixel's icon font uses this year and does not require knowing the codepoint.
     *
     * <p>Signs are excluded from that run so a genuine {@code -12} keeps its sign instead of being
     * swallowed and reported as {@code +12}. Letting the separator be this permissive is safe only
     * because the name is then checked against a fixed list: "Plot 4: 2" parses as name {@code Plot},
     * and {@code Plot} is not a stat, so it is dropped.
     */
    private static final Pattern ROW = Pattern.compile(
            "^([A-Za-z][A-Za-z ]*?)\\s*:?\\s*[^\\dA-Za-z+-]*([+-]?[\\d,]+(?:\\.\\d+)?)"
                    + "(?:\\s*/\\s*[+-]?([\\d,]+(?:\\.\\d+)?))?\\s*%?\\b.*$");

    private TabStats() {
    }

    /** A set of stat values read at one moment. Absent stats are simply not in the map. */
    public record Snapshot(Map<Stat, Double> values, long readAt) {

        /** Whether the widget was there at all - an empty snapshot means "cannot see the stats". */
        public boolean available() {
            return !values.isEmpty();
        }

        /** The value of one stat, or {@code null} when the widget did not list it. */
        public Double get(Stat stat) {
            return values.get(stat);
        }
    }

    /** An empty snapshot - what every caller gets when there is no tab list to read. */
    public static Snapshot empty() {
        return new Snapshot(new EnumMap<>(Stat.class), System.currentTimeMillis());
    }

    /** Reads the stats out of the tab list right now. */
    public static Snapshot read() {
        return parse(TabWidgets.lines());
    }

    /**
     * The parse itself, over lines already read and colour-stripped. Split out from {@link #read()}
     * because it is pure text work with no Minecraft in it - which is what makes the line shapes
     * testable without a running game, and they are the part most likely to be wrong.
     */
    public static Snapshot parse(List<String> lines) {
        Map<Stat, Double> values = new EnumMap<>(Stat.class);
        for (String line : lines) {
            parseInto(values, line);
        }
        return new Snapshot(values, System.currentTimeMillis());
    }

    /**
     * The tab lines that look like they might be stat rows but did not parse - the tuning aid for
     * when Hypixel rewords the widget and the reader goes quiet. Never used on the hot path.
     */
    public static List<String> unparsedCandidates() {
        List<String> out = new ArrayList<>(8);
        Map<Stat, Double> scratch = new EnumMap<>(Stat.class);
        for (String line : TabWidgets.lines()) {
            String cleaned = deglyph(line);
            if (cleaned.isEmpty() || !cleaned.matches(".*\\d.*")) {
                continue;
            }
            scratch.clear();
            if (!parseInto(scratch, line)) {
                out.add(line);
            }
        }
        return out;
    }

    /** Parses one tab line into {@code values}; returns whether it was a stat row. */
    private static boolean parseInto(Map<Stat, Double> values, String line) {
        Matcher matcher = ROW.matcher(deglyph(line));
        if (!matcher.matches()) {
            return false;
        }
        Stat stat = BY_NAME.get(matcher.group(1).trim().toLowerCase(Locale.ROOT));
        if (stat == null) {
            return false;
        }
        // "1,340/1,340" - the max is the stat, the current half is just how hurt you are.
        String number = matcher.group(3) != null ? matcher.group(3) : matcher.group(2);
        try {
            values.put(stat, Double.parseDouble(number.replace(",", "")));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Drops the leading icon glyph: whatever Hypixel uses this year, it is not a letter. */
    private static String deglyph(String line) {
        return line == null ? "" : line.replaceFirst("^[^A-Za-z]+", "").trim();
    }
}
