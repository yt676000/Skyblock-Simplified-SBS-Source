/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.model;

import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;


/**
 * What a quest costs to buy your way through, priced live.
 *
 * <p>No price history is involved and none is needed: "what would this cost me right now" is exactly
 * the Bazaar instant-buy or the lowest BIN, both of which the mod already keeps as live caches for
 * the tooltip price lines. This class only looks items up and adds them together.
 *
 * <p>Bazaar first, then auction: a bazaarable item is always cheaper and instant there, so that is
 * the number a player would actually pay. An item in neither (a quest reward, a mob drop) has no
 * price – reported as {@link #UNKNOWN} rather than silently counted as zero, because a total that
 * quietly omits items is worse than one that admits what it does not know.
 */
public final class QuestCost {

    /** Returned when an item has no live price on either market. */
    public static final long UNKNOWN = -1L;

    private QuestCost() {
    }

    /** The unit price of one item right now, or {@link #UNKNOWN}. */
    public static long unitPrice(String skyblockId) {
        if (skyblockId == null || skyblockId.isBlank()) {
            return UNKNOWN;
        }
        Long bazaar = BazaarPriceCache.getInstance().getBuy(skyblockId);
        if (bazaar != null && bazaar > 0) {
            return bazaar;
        }
        Long lbin = LbinCache.getInstance().getLbin(skyblockId);
        return lbin != null && lbin > 0 ? lbin : UNKNOWN;
    }

    /** What one step's item costs in total, or {@link #UNKNOWN}. Stepless steps cost nothing. */
    public static long stepCost(Quest.QuestStep step) {
        if (step == null || step.item == null) {
            return 0;
        }
        long unit = unitPrice(step.item.id);
        return unit == UNKNOWN ? UNKNOWN : unit * Math.max(1, step.item.amount);
    }

    /** A quest's total cost, and how much of it could actually be priced. */
    public record Total(long coins, int priced, int unpriced) {

        /** Whether every required item had a live price. */
        public boolean complete() {
            return unpriced == 0;
        }

        /** "12.4M" or "12.4M+" when some items could not be priced. */
        public String display() {
            if (priced == 0) {
                return "?";
            }
            return format(coins) + (complete() ? "" : "+");
        }
    }

    /**
     * The whole quest's cost from the current step onward – what is still ahead, not what was
     * already spent, which is the number a player actually wants while running it.
     */
    public static Total remaining(Quest quest, int fromStep) {
        long total = 0;
        int priced = 0;
        int unpriced = 0;
        if (quest == null || quest.steps == null) {
            return new Total(0, 0, 0);
        }
        for (int i = Math.max(0, fromStep); i < quest.steps.size(); i++) {
            Quest.QuestStep step = quest.steps.get(i);
            if (step == null || step.item == null) {
                continue;
            }
            long cost = stepCost(step);
            if (cost == UNKNOWN) {
                unpriced++;
            } else {
                total += cost;
                priced++;
            }
        }
        return new Total(total, priced, unpriced);
    }

    /** The whole quest, start to finish. */
    public static Total full(Quest quest) {
        return remaining(quest, 0);
    }

    /** 1234567 -> "1.2M" – quest costs are millions, so the exact coin count is noise. */
    public static String format(long coins) {
        return sbs.modid.client.core.util.NumberDisplay.format(coins);
    }
}
