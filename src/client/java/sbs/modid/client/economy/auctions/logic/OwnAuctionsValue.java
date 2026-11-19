/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the player's own listings in "Manage Auctions" bring in if they all sell, read from each
 * listing's lore: a BIN its {@code Buy it now} price, an auction its {@code Top bid}, or its
 * {@code Starting bid} while nobody has bid. Pure: lines in, coins out.
 */
public final class OwnAuctionsValue {

    private static final Pattern BIN = Pattern.compile("^Buy it now: ([0-9,]+) coins");
    private static final Pattern TOP_BID = Pattern.compile("^Top bid: ([0-9,]+) coins");
    private static final Pattern STARTING_BID = Pattern.compile("^Starting bid: ([0-9,]+) coins");

    private OwnAuctionsValue() {
    }

    /** Whether a menu title is the player's own auction list. */
    public static boolean isManageAuctions(String title) {
        return title != null && title.trim().equals("Manage Auctions");
    }

    /**
     * The coins one listing brings if it sells, or -1 when the lore is not a listing (menu
     * buttons, the empty slots). A top bid wins over the starting bid.
     */
    public static long listingValue(List<String> lore) {
        long starting = -1;
        for (String raw : lore) {
            String line = raw.trim();
            long bin = amount(BIN, line);
            if (bin >= 0) {
                return bin;
            }
            long top = amount(TOP_BID, line);
            if (top >= 0) {
                return top;
            }
            long start = amount(STARTING_BID, line);
            if (start >= 0) {
                starting = start;
            }
        }
        return starting;
    }

    private static long amount(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        if (!m.find()) {
            return -1;
        }
        try {
            return Long.parseLong(m.group(1).replace(",", ""));
        } catch (NumberFormatException tooLong) {
            return -1;
        }
    }
}
