/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.net;

/** How the Farming Net card prices crops. Stored by name in the config - never rename a constant. */
public enum CropPriceSource {
    /** Listed as a Bazaar sell order: about the lowest current sell offer (the instant-buy price). */
    SELL_ORDER("Sell Order"),
    /** Sold instantly to the Bazaar's highest buy order. */
    INSTANT_SELL("Instant Sell"),
    /** Sold to an NPC at the catalogue price. */
    NPC("NPC");

    private final String label;

    CropPriceSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
