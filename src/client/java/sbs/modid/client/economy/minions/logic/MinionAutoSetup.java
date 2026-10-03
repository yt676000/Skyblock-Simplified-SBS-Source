/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.logic;

import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.minions.model.MinionData;
import sbs.modid.client.economy.minions.model.MinionModifierData;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits the best FUEL and the best pair of upgrades to one minion - the "Best setup" mode.
 *
 * <p><b>Why the fuel has to be part of it.</b> With one fuel applied to every minion, the Inferno
 * Minion ranks around 40th: its base speed is dreadful and an ordinary fuel does nothing for it.
 * Given its own Hypergolic fuel it is first by a factor of five, because that fuel is a 21x speed
 * multiplier no other minion can use. A ranking that fixes one fuel for everything is therefore
 * not ranking minions, it is ranking how well each one happens to suit that fuel.
 *
 * <p>Every applicable fuel is paired with every valid upgrade combination and projected; the best
 * net coins per day wins, subject to the upgrade budget. Fuels and upgrades the minion cannot take
 * never enter the search, so an Inferno fuel is only ever considered for the Inferno.
 *
 * <p>Scored on coins even for the XP tab: an upgrade or fuel that changes the output ITEM changes
 * XP too, and choosing on XP alone would fit a compactor that multiplies XP per item while gutting
 * the coin value. Coins is the stable objective; the XP that setup happens to make is reported as
 * it falls out.
 */
public final class MinionAutoSetup {

    /** The only upgrade the wiki documents as stacking with a second copy of itself. */
    private static final String STACKS_WITH_ITSELF = "MINION_EXPANDER";

    /** What was fitted, and what it costs to run. */
    public record Choice(MinionModifierData.Fuel fuel, List<MinionModifierData.Upgrade> upgrades,
                         double fuelCostPerDay, long upgradeCost) {
    }

    private MinionAutoSetup() {
    }

    /**
     * The best fuel + upgrade pair for this minion.
     *
     * @param upgradeBudget max coins for the two upgrade items together; {@code 0} = unlimited
     */
    public static Choice best(MinionData.Minion minion, MinionData.Tier tier, MinionData data,
                              MinionModifierData mods, MinionMath.Prices prices,
                              SBSConfig.MinionCalcSettings cfg, double taxRate,
                              double extraSpeedPct, Double hopperCut, int sameTypePlaced) {

        long upgradeBudget = Math.max(0, cfg.upgradeBudget);
        List<List<MinionModifierData.Upgrade>> pairs = pairs(mods, minion);

        // Fuels this minion accepts, plus "none" - which wins whenever every fuel costs more per
        // day than the extra output it buys, and that is a real answer, not a gap in the search.
        List<MinionModifierData.Fuel> fuels = new ArrayList<>();
        fuels.add(null);
        for (MinionModifierData.Fuel fuel : mods.fuels) {
            if (fuel.appliesTo(minion)) {
                fuels.add(fuel);
            }
        }

        Choice best = new Choice(null, List.of(), 0, 0);
        double bestValue = Double.NEGATIVE_INFINITY;
        for (MinionModifierData.Fuel fuel : fuels) {
            double drain = fuel == null ? 0
                    : MinionMath.fuelCostPerDay(fuel, prices.acquisitionCost(fuel.id));
            double speed = extraSpeedPct + (fuel == null ? 0 : fuel.speedPct);
            double outputMult = fuel == null ? 1.0 : fuel.outputMult;
            for (List<MinionModifierData.Upgrade> pair : pairs) {
                Long cost = costOf(pair, prices);
                if (cost == null || (upgradeBudget > 0 && cost > upgradeBudget)) {
                    continue;
                }
                MinionMath.Assumptions assumptions = new MinionMath.Assumptions(
                        Math.max(1, cfg.intervalHours), taxRate, speed, outputMult, hopperCut,
                        pair, 1.0 + cfg.xpBoostPct / 100.0, fuel, sameTypePlaced);
                MinionMath.Projection projection = MinionMath.project(minion, tier, data, mods,
                        prices, assumptions, drain);
                if (projection.netCoinsPerDay() > bestValue) {
                    bestValue = projection.netCoinsPerDay();
                    best = new Choice(fuel, pair, drain, cost);
                }
            }
        }
        return best;
    }

    /**
     * Nothing, every single, and every unordered pair of upgrades the minion accepts.
     *
     * <p>Upgrades limited to one per ISLAND (Sleepy Hollow, Potato Spreading, the Krampus Helmet)
     * are left out. They are not per-minion equipment, and offering one to every row quietly
     * credited the same single item to sixty minions at once - which put nine of them in the top
     * ten on the strength of an upgrade the player can only ever own one of. Anyone who has one
     * can still fit it by hand in the slot pickers.
     */
    private static List<List<MinionModifierData.Upgrade>> pairs(MinionModifierData mods,
                                                                MinionData.Minion minion) {
        List<MinionModifierData.Upgrade> usable = new ArrayList<>();
        for (MinionModifierData.Upgrade upgrade : mods.upgrades) {
            if (upgrade.appliesTo(minion) && upgrade.islandLimit == null) {
                usable.add(upgrade);
            }
        }
        List<List<MinionModifierData.Upgrade>> out = new ArrayList<>();
        out.add(List.of());
        for (int i = 0; i < usable.size(); i++) {
            out.add(List.of(usable.get(i)));
            if (STACKS_WITH_ITSELF.equals(usable.get(i).id)) {
                out.add(List.of(usable.get(i), usable.get(i)));
            }
            for (int j = i + 1; j < usable.size(); j++) {
                out.add(List.of(usable.get(i), usable.get(j)));
            }
        }
        return out;
    }

    /** What the pair costs to buy, or {@code null} when any of it is unpriceable. */
    public static Long costOf(List<MinionModifierData.Upgrade> pair, MinionMath.Prices prices) {
        long total = 0;
        for (MinionModifierData.Upgrade upgrade : pair) {
            Long cost = prices.acquisitionCost(upgrade.id);
            if (cost == null) {
                return null;
            }
            total += cost;
        }
        return total;
    }
}
