/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The small text chores every farming / Garden feature repeats: stripping Hypixel's colour codes,
 * turning "1.2m" or "1,234" into a number, and formatting counts, coins and durations back out.
 *
 * <p>These lived as private copies in each overlay class before there were several of them. One copy
 * means the milestone card and the pest timer can never disagree about what "1.2k" means, and the
 * lore readers all see the same de-coloured text.
 */
public final class FarmingText {

    private FarmingText() {
    }

    /** Removes every {@code §x} colour / format code. */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 0x00A7 && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The stack's display name, colour-stripped. */
    public static String name(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : strip(stack.getHoverName().getString()).trim();
    }

    /** The stack's lore lines, colour-stripped. Never {@code null}. */
    public static List<String> lore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            out.add(strip(line.getString()));
        }
        return out;
    }

    /**
     * Parses a Hypixel-formatted number: {@code 1,234}, {@code 1.2k}, {@code 45.7%}, {@code 3.4m}.
     * Returns {@code -1} when the text holds no number at all, so callers can tell "zero" from
     * "unreadable" – a milestone at 0 % is real data, an unparsed line is not.
     */
    public static double parseNumber(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        StringBuilder digits = new StringBuilder();
        char suffix = 0;
        boolean seen = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c) || (c == '.' && seen)) {
                digits.append(c);
                seen = true;
            } else if (c == ',' && seen) {
                // thousands separator - skip
            } else if (seen) {
                char lower = Character.toLowerCase(c);
                if (lower == 'k' || lower == 'm' || lower == 'b') {
                    suffix = lower;
                }
                break;
            }
        }
        if (!seen) {
            return -1;
        }
        double value;
        try {
            value = Double.parseDouble(digits.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
        return switch (suffix) {
            case 'k' -> value * 1_000;
            case 'm' -> value * 1_000_000;
            case 'b' -> value * 1_000_000_000;
            default -> value;
        };
    }

    /**
     * Short count format: 1.2K / 3.4M / 5.6B, honouring "Shorten Numbers".
     *
     * <p>Below ten thousand the exact figure is kept whatever the setting says: this also draws
     * farming fortune, and a fortune of 1,234 must not read "1.2K" - that is a stat, not a haul.
     */
    public static String shortNumber(double value) {
        return value >= 10_000
                ? sbs.modid.client.core.util.NumberDisplay.format(value)
                : String.format(Locale.ROOT, "%,.0f", value);
    }

    /** Short coin format: 1.2K / 3.4M / 5.6B, honouring "Shorten Numbers". */
    public static String coins(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }

    /**
     * Compact duration: "12s", "4m 20s", "3h 5m", "2d 4h". Anything beyond a month reads as
     * "&gt;30d" – a precise number there is false precision, the answer is "never at this rate".
     */
    public static String duration(long millis) {
        if (millis < 0) {
            return "-";
        }
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m " + (seconds % 60) + "s";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + (minutes % 60) + "m";
        }
        long days = hours / 24;
        if (days > 30) {
            return ">30d";
        }
        return days + "d " + (hours % 24) + "h";
    }
}
