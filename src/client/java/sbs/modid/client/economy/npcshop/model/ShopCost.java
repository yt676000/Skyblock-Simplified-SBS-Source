/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.npcshop.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The price written on a Hypixel shop offer, read from its colour-stripped lore. No Minecraft types,
 * so it is unit-tested.
 *
 * <p><b>The shape is {@code ESTIMATED}</b> - no shop tooltip has been captured (see
 * {@code docs/features/npc-flips.md}). Expected:
 * <pre>
 * Cost
 * 1,234 Coins
 * Enchanted Coal x2
 *
 * Click to trade!
 * </pre>
 * A {@code ... Bits} line is read too, so the Bits Shop can share this once the shape is confirmed.
 * Any line mentioning a limit is kept verbatim: its wording is unknown, so it is shown, never used.
 */
public final class ShopCost {

    /** One non-currency part of a price: "Enchanted Coal x2". */
    public record ItemCost(String name, int amount) {
    }

    /**
     * @param coins     the coin part, or {@code null} when the price has none
     * @param bits      the bits part, or {@code null}
     * @param items     every other part, in lore order
     * @param limitLine a lore line that mentions a limit, verbatim, or {@code null}
     */
    public record Cost(Long coins, Long bits, List<ItemCost> items, String limitLine) {

        /** Priced in coins alone - the only kind the flip ranking uses. */
        public boolean coinsOnly() {
            return coins != null && bits == null && items.isEmpty();
        }
    }

    private static final Pattern COINS = Pattern.compile("^([\\d,]+(?:\\.\\d+)?)\\s*coins?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BITS = Pattern.compile("^([\\d,]+)\\s*bits?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM = Pattern.compile("^(.*?[A-Za-z].*?)(?:\\s+x(\\d[\\d,]*))?$");

    private ShopCost() {
    }

    /** The price on an offer, or {@code null} when its lore has no {@code Cost} block. */
    public static Cost parse(List<String> lore) {
        Long coins = null;
        Long bits = null;
        List<ItemCost> items = new ArrayList<>();
        String limit = null;
        boolean inBlock = false;
        boolean found = false;
        for (String raw : lore) {
            String line = raw == null ? "" : raw.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("limit") && limit == null) {
                limit = line;
            }
            if (!inBlock) {
                if (lower.equals("cost") || lower.equals("cost:")) {
                    inBlock = true;
                    found = true;
                }
                continue;
            }
            if (line.isEmpty() || lower.startsWith("click") || lower.startsWith("right-click")) {
                inBlock = false;   // the block ends; keep scanning for a limit line below it
                continue;
            }
            Matcher coin = COINS.matcher(line);
            if (coin.matches()) {
                coins = amount(coin.group(1));
                continue;
            }
            Matcher bit = BITS.matcher(line);
            if (bit.matches()) {
                bits = amount(bit.group(1));
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (item.matches()) {
                Long n = item.group(2) == null ? Long.valueOf(1) : amount(item.group(2));
                if (n != null && n > 0 && n <= Integer.MAX_VALUE) {
                    items.add(new ItemCost(item.group(1).trim(), n.intValue()));
                }
            }
        }
        if (!found || (coins == null && bits == null && items.isEmpty())) {
            return null;
        }
        return new Cost(coins, bits, List.copyOf(items), limit);
    }

    /** "1,234" or "1,234.5" - fractional coins are rounded up: you pay the next whole coin. */
    private static Long amount(String digits) {
        try {
            double value = Double.parseDouble(digits.replace(",", ""));
            return value < 0 || value > Long.MAX_VALUE / 2 ? null : (long) Math.ceil(value);
        } catch (NumberFormatException bad) {
            return null;
        }
    }
}
