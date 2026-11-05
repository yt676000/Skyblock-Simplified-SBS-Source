/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import java.util.List;

/**
 * Which price the Diana Profit card values a drop at. A drop the chosen market does not list falls
 * back to the other one: Crown of Greed and the Shelmet are auction items, feathers and Chimera are
 * Bazaar products, and a total that silently valued half the drops at zero would be worse than one
 * that mixes markets.
 */
public enum LootPriceSource {

    /** The lowest sell offer - what listing it as a sell order would fetch. */
    SELL_ORDER("Sell order"),

    /** The highest buy order - what selling it this second fetches. */
    INSTANT_SELL("Instant sell"),

    /** The lowest BIN on the auction house; the Bazaar sell offer for Bazaar-only items. */
    LOWEST_BIN("Lowest BIN");

    private final String displayName;

    LootPriceSource(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The labels in declaration order, for a segmented switch. */
    public static List<String> labels() {
        return List.of(SELL_ORDER.displayName(), INSTANT_SELL.displayName(), LOWEST_BIN.displayName());
    }

    /** The persisted name, tolerating anything an older or newer build wrote. */
    public static LootPriceSource byName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException bad) {
            return SELL_ORDER;
        }
    }
}
