/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.enchants.counter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import sbs.modid.client.helper.enchants.EnchantNames;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleFunction;

/**
 * The progress of a counter enchant on one item: which enchant, its level, the running total, and
 * where that total stands against the level table. Pure: a custom-data compound and the table in,
 * a value out, so every case is unit-tested without a game.
 */
public final class CounterEnchantXp {

    /**
     * One item's counter enchant.
     *
     * @param counterKey the custom-data key the total was read from - the table's, or the tolerant
     *                   fallback's when the table's key was absent
     */
    public record Progress(CounterEnchantData.Entry entry, int level, double value, String counterKey) {

        /** At or past the last level the table knows. */
        public boolean maxed() {
            return level >= entry.maxLevel();
        }

        /** The cumulative total the next level needs; only meaningful below max. */
        public long next() {
            return entry.thresholds[Math.max(0, Math.min(level, entry.maxLevel() - 1))];
        }

        /** How far the total is past the last level's threshold; never negative. */
        public double over() {
            return Math.max(0d, value - entry.thresholds[entry.maxLevel() - 1]);
        }

        /** The total as a fraction of {@link #next()}, clamped to 0..1. */
        public double fraction() {
            long next = next();
            return next <= 0 ? 1d : Math.max(0d, Math.min(1d, value / next));
        }

        /** Whether the key came from the table rather than the tolerant fallback. */
        public boolean tableKey() {
            return counterKey.equals(entry.counterKey);
        }
    }

    private CounterEnchantXp() {
    }

    /**
     * The first counter enchant on the item that the table knows and whose total can be read, or
     * {@code null}. An item without the enchant, or with the enchant but no readable total, has none -
     * a missing total is never shown as zero.
     *
     * @param extra the item's custom data ({@code SkyblockItem.extraAttributes})
     */
    public static Progress find(CompoundTag extra, List<CounterEnchantData.Entry> table) {
        if (extra == null || extra.isEmpty() || table.isEmpty()
                || !(extra.get("enchantments") instanceof CompoundTag enchants) || enchants.isEmpty()) {
            return null;
        }
        for (CounterEnchantData.Entry entry : table) {
            int level = enchants.getIntOr(entry.enchant, 0);
            if (level <= 0) {
                continue;
            }
            String key = counterKey(extra, entry);
            if (key == null) {
                continue;
            }
            return new Progress(entry, level, number(extra.get(key)), key);
        }
        return null;
    }

    /**
     * The custom-data key holding the entry's total: the table's key when it is present and numeric,
     * else the first numeric key starting with {@code <enchant>_}, else {@code null}.
     */
    static String counterKey(CompoundTag extra, CounterEnchantData.Entry entry) {
        if (entry.counterKey != null && !entry.counterKey.isEmpty()
                && !Double.isNaN(number(extra.get(entry.counterKey)))) {
            return entry.counterKey;
        }
        String prefix = entry.enchant.toLowerCase(Locale.ROOT) + "_";
        for (String key : extra.keySet()) {
            if (key.toLowerCase(Locale.ROOT).startsWith(prefix) && !Double.isNaN(number(extra.get(key)))) {
                return key;
            }
        }
        return null;
    }

    /** A numeric tag's value, {@code NaN} for anything else (a string, a compound, nothing). */
    static double number(Tag tag) {
        if (tag == null) {
            return Double.NaN;
        }
        Optional<Number> number = tag.asNumber();
        return number.isPresent() ? number.get().doubleValue() : Double.NaN;
    }

    /**
     * The tooltip text: {@code Champion VII: 1.7M / 2M XP (86.7%)} below max,
     * {@code Champion X: 3.4M XP (max, +413K over)} at or past it.
     *
     * @param number how a number is written (the player's setting, or the full form with Shift)
     */
    public static String text(Progress progress, DoubleFunction<String> number) {
        StringBuilder out = new StringBuilder(64);
        out.append(EnchantNames.displayName(progress.entry().enchant)).append(' ')
                .append(roman(progress.level())).append(": ")
                .append(number.apply(progress.value()));
        String unit = progress.entry().unit == null ? "" : progress.entry().unit;
        if (progress.maxed()) {
            appendUnit(out, unit);
            out.append(" (max");
            double over = progress.over();
            if (over > 0d) {
                out.append(", +").append(number.apply(over)).append(" over");
            }
            out.append(')');
        } else {
            out.append(" / ").append(number.apply(progress.next()));
            appendUnit(out, unit);
            out.append(String.format(Locale.ROOT, " (%.1f%%)", progress.fraction() * 100d));
        }
        return out.toString();
    }

    private static void appendUnit(StringBuilder out, String unit) {
        if (!unit.isEmpty()) {
            out.append(' ').append(unit);
        }
    }

    /** The row's certainty; anything unreadable counts as {@link Certainty#WIKI}. */
    public static Certainty certainty(String value) {
        if (value != null) {
            for (Certainty candidate : Certainty.values()) {
                if (candidate.name().equalsIgnoreCase(value.trim())) {
                    return candidate;
                }
            }
        }
        return Certainty.WIKI;
    }

    /** 1..39 as Roman numerals, the way Hypixel writes enchant levels; plain digits beyond. */
    public static String roman(int value) {
        if (value <= 0 || value >= 40) {
            return String.valueOf(value);
        }
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        return "X".repeat(value / 10) + ones[value % 10];
    }
}
