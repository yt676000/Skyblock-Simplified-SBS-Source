/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.model;

/**
 * Live competitive status of a tracked Bazaar order, derived by comparing the player's
 * order price against the Hypixel Bazaar API.
 *
 * <ul>
 *   <li>{@link #BEST_OFFER} – the player's order is the single best available order.</li>
 *   <li>{@link #MATCHED} – the player's price is tied with the best available order.</li>
 *   <li>{@link #OUTDATED} – a better competing order exists.</li>
 *   <li>{@link #FILLED} – the order is 100% filled (sold / bought out); nothing left to compete.</li>
 *   <li>{@link #UNKNOWN} – not yet evaluated (e.g. just scanned, or missing from the API).</li>
 * </ul>
 */
public enum BazaarStatus {

    UNKNOWN("Unknown"),
    BEST_OFFER("Best Offer"),
    MATCHED("Matched"),
    OUTDATED("Outdated"),
    FILLED("Filled");

    private final String displayName;

    BazaarStatus(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable label, e.g. {@code "Best Offer"} – used in chat messages. */
    public String displayName() {
        return displayName;
    }
}
