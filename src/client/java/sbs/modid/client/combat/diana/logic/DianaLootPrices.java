/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import sbs.modid.client.combat.diana.model.LootPriceSource;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;

/**
 * Coins each for a Diana drop, read from the shared price caches. No cache of its own: the card that
 * asks rebuilds only when the tracker or {@code PriceVersion} moves, so a lookup here is per rebuild,
 * not per frame.
 */
public final class DianaLootPrices {

    private DianaLootPrices() {
    }

    /** Coins each for {@code id} under {@code source}, falling back to the other market; or {@code null}. */
    public static Long price(String id, LootPriceSource source) {
        if (id == null) {
            return null;
        }
        Long bazaar = bazaar(id, source);
        Long lbin = LbinCache.getInstance().getLbin(id);
        if (source == LootPriceSource.LOWEST_BIN) {
            return positive(lbin) != null ? lbin : bazaar;
        }
        return bazaar != null ? bazaar : positive(lbin);
    }

    private static Long bazaar(String id, LootPriceSource source) {
        BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().priceOf(id);
        if (price == null) {
            return null;
        }
        long each = source == LootPriceSource.INSTANT_SELL ? price.sell() : price.buy();
        return each > 0 ? each : null;
    }

    private static Long positive(Long value) {
        return value != null && value > 0 ? value : null;
    }
}
