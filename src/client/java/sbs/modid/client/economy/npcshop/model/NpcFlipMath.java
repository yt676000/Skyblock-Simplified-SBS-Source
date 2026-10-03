/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.npcshop.model;

/**
 * The money of one NPC flip, with no Minecraft types. Buy from the NPC at its coin price, sell on the
 * Bazaar; the Bazaar takes its tax on the sale only - the buy side is untaxed, and the NPC charges
 * no tax of its own.
 */
public final class NpcFlipMath {

    private NpcFlipMath() {
    }

    /**
     * Profit per unit: what one unit brings on the Bazaar after tax, minus what one unit costs.
     *
     * @param npcCostPerStack the learned coin price for {@code stackSize} units
     * @param stackSize       how many units that price buys (at least 1)
     * @param bazaarUnitPrice the Bazaar price per unit on the chosen side (insta-sell or sell offer)
     * @param taxRate         the Bazaar sell tax as a fraction ({@code LocalFlipEngine.taxRate})
     */
    public static double profitPerUnit(long npcCostPerStack, int stackSize, double bazaarUnitPrice, double taxRate) {
        double costPerUnit = npcCostPerStack / (double) Math.max(1, stackSize);
        double netSale = bazaarUnitPrice * (1.0 - taxRate);
        return netSale - costPerUnit;
    }

    /**
     * Whether an offer may be ranked at all: coins only (an extra item cost priced at nothing would
     * make the flip look better than it is), a real Bazaar price, and a positive cost.
     */
    public static boolean rankable(ShopCost.Cost cost, Double bazaarUnitPrice) {
        return cost != null && cost.coinsOnly() && cost.coins() > 0
                && bazaarUnitPrice != null && bazaarUnitPrice > 0;
    }
}
