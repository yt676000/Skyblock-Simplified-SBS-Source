/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

import java.util.List;

/**
 * Which side of the Bazaar book a missing shard is priced from.
 *
 * <p>{@link #INSTANT_BUY} is the default and the honest one: it is what acquiring the shard costs
 * right now, and it is the same argument {@code EssenceBazaar.coinsFor} already makes - the sell side
 * reads lower and answers a question nobody asked. {@link #BUY_ORDER} is offered because a player
 * filling a shopping list over an evening really will pay the lower number, and hiding it would
 * overstate every total on the panel by the spread.
 */
public enum ShardPriceSource {

    /** {@code quick_status.buyPrice} - the lowest sell offer, i.e. buy it this second. */
    INSTANT_BUY("Instant buy"),

    /** {@code quick_status.sellPrice} - the highest standing buy order. Cheaper, not immediate. */
    BUY_ORDER("Buy order");

    private final String displayName;

    ShardPriceSource(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The labels in declaration order, for a segmented switch. */
    public static List<String> labels() {
        return List.of(INSTANT_BUY.displayName(), BUY_ORDER.displayName());
    }

    /** The persisted name, tolerating anything an older or newer build wrote. */
    public static ShardPriceSource byName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException bad) {
            return INSTANT_BUY;
        }
    }
}
