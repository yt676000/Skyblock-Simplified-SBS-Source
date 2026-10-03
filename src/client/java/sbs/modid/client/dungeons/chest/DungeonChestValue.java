/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.chest;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Values a dungeon reward chest: what its contents are worth against what it costs to open.
 *
 * <p>Everything is priced from the <b>live</b> Hypixel APIs the mod already keeps warm - the auction
 * crawl behind {@link LbinCache} ({@code api.hypixel.net/v2/skyblock/auctions}) for anything sold on
 * the Auction House, and {@link BazaarPriceCache} for the Bazaar. Nothing here is a stored table or
 * an estimate.
 *
 * <p><b>Sell side, not buy side.</b> A chest is loot you are going to sell, so a Bazaar item is worth
 * its instant-<i>sell</i> price and an auctionable is worth the lowest BIN. Using buy prices would
 * quietly inflate every chest by the Bazaar spread.
 *
 * <p><b>Unpriced items are never counted as zero.</b> They are reported separately, because "this
 * chest is worth 2M" and "this chest is worth 2M plus a Spirit Sword I could not price" are very
 * different claims, and only the second one is true when a lookup misses.
 */
public final class DungeonChestValue {

    /** The chest tooltip lists its loot under this header, one item per line. */
    private static final String CONTENTS = "Contents";

    /** "Cost" header, followed by a line like "3,000,000 Coins". */
    private static final String COST = "Cost";

    /** "3,000,000 Coins" - the amount the chest charges. */
    private static final Pattern COINS = Pattern.compile("^([0-9][0-9,.]*)\\s*Coins?$");

    /** A content line's trailing "x39" stack size (absent means one). */
    private static final Pattern COUNT_SUFFIX = Pattern.compile("^(.*?)\\s+x\\s*([0-9,]+)$");

    /** "Enchanted Book (Rejuvenate II)" - a Bazaar enchantment, not an auctionable book. */
    private static final Pattern ENCHANTED_BOOK =
            Pattern.compile("(?i)^Enchanted Book\\s*\\((.+?)\\s+([IVXLC]+)\\)$");

    /** "Undead Essence" -> {@code ESSENCE_UNDEAD}. */
    private static final Pattern ESSENCE = Pattern.compile("(?i)^(\\w+)\\s+Essence$");

    private DungeonChestValue() {
    }

    /** One priced (or unpriced) line of the chest's contents. */
    public record Loot(String name, int count, Long unitPrice, String source) {
        public long total() {
            return unitPrice == null ? 0L : unitPrice * count;
        }

        public boolean priced() {
            return unitPrice != null;
        }
    }

    /** A valued chest: its loot, the cost to open, and what that leaves. */
    public record Chest(List<Loot> loot, long cost) {
        public long value() {
            long sum = 0;
            for (Loot item : loot) {
                sum += item.total();
            }
            return sum;
        }

        public long profit() {
            return value() - cost;
        }

        /**
         * Whether every line of the chest could be priced.
         *
         * <p>The same question {@link #unpriced()} answers, asked where only the yes/no is wanted.
         * That method builds a list, and the slot overlay asks this of every chest in the menu on
         * every frame - allocating a list per chest per frame to find out whether it is empty is the
         * hot-path mistake {@code overlay-perf-patterns} records.
         */
        public boolean complete() {
            for (Loot item : loot) {
                if (!item.priced()) {
                    return false;
                }
            }
            return true;
        }

        /** Items whose price could not be resolved - the reason a total may be understated. */
        public List<Loot> unpriced() {
            List<Loot> out = new ArrayList<>();
            for (Loot item : loot) {
                if (!item.priced()) {
                    out.add(item);
                }
            }
            return out;
        }
    }

    /**
     * Values {@code stack} when it is a dungeon reward chest, else returns {@code null}.
     *
     * <p>Detection is the tooltip's own shape - a "Contents" listing followed by a "Cost" in coins.
     * That is what makes a chest a chest, so it holds for every tier and needs no list of chest names
     * to fall out of date.
     */
    public static Chest of(ItemStack stack) {
        List<String> lore = lore(stack);
        int contents = lore.indexOf(CONTENTS);
        if (contents < 0) {
            return null;
        }
        Long cost = null;
        List<Loot> loot = new ArrayList<>();
        for (int i = contents + 1; i < lore.size(); i++) {
            String line = lore.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals(COST)) {
                cost = coinsAfter(lore, i);
                break;   // the listing ends where the cost begins
            }
            loot.add(price(line));
        }
        if (loot.isEmpty()) {
            return null;
        }
        return new Chest(List.copyOf(loot), cost == null ? 0L : cost);
    }

    /** The first "<n> Coins" line after {@code index}. */
    private static Long coinsAfter(List<String> lore, int index) {
        for (int i = index + 1; i < lore.size(); i++) {
            Matcher m = COINS.matcher(lore.get(i).trim());
            if (m.matches()) {
                return parseNumber(m.group(1));
            }
        }
        return null;
    }

    /** Resolves one content line to a unit price, remembering which market answered. */
    private static Loot price(String line) {
        String name = line;
        int count = 1;
        Matcher counted = COUNT_SUFFIX.matcher(line);
        if (counted.matches()) {
            name = counted.group(1).trim();
            count = (int) Math.max(1, parseNumber(counted.group(2)));
        }

        String bazaarId = bazaarIdFor(name);
        if (bazaarId != null) {
            var bz = BazaarPriceCache.getInstance().get(bazaarId);
            if (bz != null && bz.sell() > 0) {
                return new Loot(name, count, bz.sell(), "BZ");
            }
        }
        // Auctionables: the catalogue turns the display name into the SkyBlock id the auction crawl
        // is keyed by. A name the catalogue does not know simply stays unpriced.
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byName(name);
        if (entry != null) {
            Long lbin = LbinCache.getInstance().getLbin(entry.id);
            if (lbin != null) {
                return new Loot(name, count, lbin, "LBIN");
            }
            var bz = BazaarPriceCache.getInstance().get(entry.id);
            if (bz != null && bz.sell() > 0) {
                return new Loot(name, count, bz.sell(), "BZ");
            }
        }
        return new Loot(name, count, null, null);
    }

    /**
     * The Bazaar product id for the names that do not resolve through the item catalogue: essences
     * and enchanted books, which are the bulk of every chest. Public because the Crystal Nucleus
     * bundle names its books the same way ({@code NucleusItemIds}).
     *
     * @return the id, or {@code null} when the name is not one of those shapes
     */
    public static String bazaarIdFor(String name) {
        Matcher essence = ESSENCE.matcher(name);
        if (essence.matches()) {
            return "ESSENCE_" + essence.group(1).toUpperCase(Locale.ROOT);
        }
        Matcher book = ENCHANTED_BOOK.matcher(name);
        if (book.matches()) {
            int level = roman(book.group(2));
            if (level > 0) {
                return "ENCHANTMENT_" + book.group(1).trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_")
                        + "_" + level;
            }
        }
        return null;
    }

    /** Enchantment levels are roman on the tooltip and arabic in the Bazaar id. */
    private static int roman(String text) {
        int total = 0;
        int previous = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            int value = switch (Character.toUpperCase(text.charAt(i))) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            if (value == 0) {
                return 0;
            }
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }

    private static long parseNumber(String text) {
        try {
            return Long.parseLong(text.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException bad) {
            return 0L;
        }
    }

    /** The stack's lore as plain, colour-stripped lines. */
    private static List<String> lore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (var line : lore.lines()) {
            out.add(line.getString().replaceAll("§.", "").trim());
        }
        return out;
    }
}
