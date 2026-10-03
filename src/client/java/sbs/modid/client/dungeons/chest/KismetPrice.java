/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.chest;

import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;

/**
 * What a Kismet Feather costs right now - the one number a reroll decision can be given honestly.
 *
 * <p><b>Buy side, where the chest itself is valued sell side.</b> That is not an inconsistency, it is
 * the same rule applied to the other direction of trade: a chest's loot is what you are going to
 * sell, so it is worth what a buyer pays; a feather is something you have to acquire, so it costs
 * what a seller asks. Pricing it sell side would quietly understate every reroll by the Bazaar
 * spread, and understating a cost is the direction that loses money.
 *
 * <p><b>{@code KISMET_FEATHER} is {@code CONFIRMED}</b>, from the bundled
 * {@code sbs-skyblock-items.json} - it is a real SkyBlock id in the data this build ships with, not
 * a name taken from the request. Which market carries it is <i>not</i> asserted anywhere: the Bazaar
 * is asked first and the auction crawl second, and a feather that is on neither simply has no price,
 * which is reported as nothing rather than as zero.
 *
 * <p><b>This is a price and not an expectation.</b> What a reroll is worth would need the per-floor
 * loot tables and the reroll's own odds; neither is in this repository and neither can be measured
 * from inside the client, so no such number is produced here - see
 * {@code docs/features/croesus-overlay.md}.
 */
public final class KismetPrice {

    /** The SkyBlock id, confirmed against the bundled item data. */
    public static final String ID = "KISMET_FEATHER";

    private KismetPrice() {
    }

    /**
     * What one feather costs, or {@code null} when no warm cache can answer.
     *
     * <p>Cache reads only. This runs from a tooltip, and a tooltip that fires a request is a tooltip
     * that stutters the first time it is shown.
     */
    public static Long cost() {
        Long bazaar = BazaarPriceCache.getInstance().getBuy(ID);
        if (bazaar != null && bazaar > 0) {
            return bazaar;
        }
        Long lbin = LbinCache.getInstance().getLbin(ID);
        return lbin != null && lbin > 0 ? lbin : null;
    }
}
