/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.ui;

import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.bazaar.ui.FlipFormat;
import sbs.modid.client.economy.forge.model.LocalForgeFlip;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared wording for locally computed forge flips, so the in-Forge window and the Forge Flips screen
 * describe the same numbers the same way.
 *
 * <p>The same two rules as {@link FlipFormat}, which this deliberately leans on rather than
 * duplicates: estimates are <b>labelled</b> as estimates, and every result carries its <b>inputs</b>
 * — here, the actual ingredient list with the price each line was costed at. A player who can see
 * that a recipe's headline rests on one ingredient being unusually cheap can decide the ranking is
 * wrong, which is the only defence a projected figure has.
 */
public final class ForgeFormat {

    private ForgeFormat() {
    }

    /** The headline figure, always marked as an estimate rather than stated as a rate. */
    public static String perHour(double profitPerHour) {
        return "~" + NumberDisplay.format(profitPerHour) + "/h";
    }

    /** "craft 4.2M → 6.1M · +1.9M (45%)" — the run, end to end. */
    public static String runLine(LocalForgeFlip flip) {
        return String.format(Locale.ROOT, "craft %s -> %s  ·  +%s (%.0f%%)",
                NumberDisplay.format(flip.cost()), NumberDisplay.format(flip.revenue()),
                NumberDisplay.format(flip.profit()), flip.marginPct());
    }

    /** "8h · 12.4 runs/wk demand · 31.0 supply · [book]" — the shape of the opportunity. */
    public static String marketLine(LocalForgeFlip flip) {
        return String.format(Locale.ROOT, "%s  ·  %.1f runs/wk sold  ·  %.1f supplied  ·  [%s]",
                duration(flip.durationSeconds()), flip.demandRunsWeek(), flip.supplyRunsWeek(),
                flip.confidence().badge());
    }

    /** "2x Refined Mithril, 1x Enchanted Diamond" — the recipe at a glance, trimmed by the caller. */
    public static String ingredientLine(LocalForgeFlip flip) {
        StringBuilder out = new StringBuilder();
        for (LocalForgeFlip.Ingredient input : flip.inputs()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(amount(input.count())).append("x ").append(displayName(input.itemId()));
        }
        return out.toString();
    }

    /**
     * Every input behind one result, for a tooltip that has room for all of it.
     *
     * <p>The two routes are both here on purpose. The ranking sorts by the patient one — buy orders
     * in, sell offer out — and that is the better number, but it is only reachable if you are willing
     * to wait for the orders. The instant figure is what the same recipe pays if you start it now.
     * Showing only the first would be quoting a price nobody is obliged to be able to get.
     */
    public static List<String> inputLines(LocalForgeFlip flip) {
        List<String> out = new ArrayList<>();
        out.add("§7Forge time §f" + duration(flip.durationSeconds())
                + " §8· yields " + amount(flip.outputCount()) + "x");
        out.add("");
        for (LocalForgeFlip.Ingredient input : flip.inputs()) {
            out.add("§8· §7" + amount(input.count()) + "x " + displayName(input.itemId())
                    + " §8@ §7" + NumberDisplay.format(input.unitPrice())
                    + " §8= §7" + NumberDisplay.format(input.lineCost()));
        }
        out.add("§7Ingredients §6" + NumberDisplay.format(flip.cost())
                + " §8(buy orders, one tick above the best bid)");
        out.add("§7Sells for §6" + NumberDisplay.format(flip.revenue())
                + " §8(sell offer, after " + String.format(Locale.ROOT, "%.3f%%", flip.taxRate() * 100)
                + " tax)");
        out.add("§7Profit §a" + NumberDisplay.format(flip.profit())
                + String.format(Locale.ROOT, " §8(%.0f%%)", flip.marginPct()));
        out.add("§7Per forge hour §a" + NumberDisplay.format(flip.profitPerHour()));
        out.add("");
        out.add("§7If you do not want to wait for the orders:");
        out.add("§8· instant buy §7" + NumberDisplay.format(flip.instantCost())
                + " §8-> instant sell §7" + NumberDisplay.format(flip.instantRevenue())
                + " §8= §7" + NumberDisplay.format(flip.instantProfitPerHour()) + "/h");
        out.add("");
        out.add(String.format(Locale.ROOT, "§7Result sold §f%.1f runs' worth per week", flip.demandRunsWeek()));
        out.add(String.format(Locale.ROOT, "§7Tightest ingredient §f%s §7at %.1f runs' worth",
                flip.tightestInput() == null ? "-" : displayName(flip.tightestInput()),
                flip.supplyRunsWeek()));
        out.add("§8" + flip.confidence().explanation());
        if (flip.requirement() != null && !flip.requirement().isBlank()) {
            out.add("");
            out.add("§c" + flip.requirement());
        }
        return out;
    }

    /** The catalogue's display name for an id, falling back to a readable form of the id itself. */
    public static String displayName(String itemId) {
        if (itemId == null) {
            return "";
        }
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        if (entry != null && entry.name != null && !entry.name.isBlank()) {
            return entry.name;
        }
        StringBuilder pretty = new StringBuilder();
        for (String word : itemId.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (pretty.length() > 0) {
                pretty.append(' ');
            }
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return pretty.toString();
    }

    /** Counts are fractional in the source data; whole ones are shown without a pointless ".0". */
    public static String amount(double count) {
        return count == Math.rint(count) ? String.valueOf((long) count)
                : String.format(Locale.ROOT, "%.2f", count);
    }

    /** 30 -> "30s", 1800 -> "30m", 28800 -> "8h" – the forge time at a glance. */
    public static String duration(int seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        long hours = seconds / 3600;
        long rest = (seconds % 3600) / 60;
        return rest == 0 ? hours + "h" : hours + "h" + rest + "m";
    }
}
