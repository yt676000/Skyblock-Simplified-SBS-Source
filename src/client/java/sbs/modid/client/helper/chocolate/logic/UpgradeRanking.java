/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.logic;

import sbs.modid.client.helper.chocolate.model.FactoryUpgrade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which Chocolate Factory upgrade is the best buy, by payback time.
 *
 * <p><b>Payback, not gain and not price.</b> Ranking by added chocolate per second buys the
 * biggest number rather than the best value; ranking by price buys the cheapest, which is a
 * different question again. {@code cost / gain} is the seconds an upgrade needs to produce its own
 * price back, it is comparable across upgrades of wildly different sizes, and - the part that
 * matters in practice - it still means something while the player cannot afford any of them.
 *
 * <p><b>Affordability never moves an entry.</b> What the player can buy right now and what they
 * should be saving for are different questions, and the second one is the one an upgrade panel is
 * for. The shortfall is a label on the slot, not a demotion.
 *
 * <p>Pure: numbers in, numbers out, no menu and no Minecraft. This is the half that is correct
 * whatever {@link ChocolateLore} managed to read, which is why it is the half with the tests.
 */
public final class UpgradeRanking {

    private UpgradeRanking() {
    }

    /**
     * Seconds until {@code upgrade} has produced its own cost back.
     *
     * @return the payback in seconds, or {@code -1} when the upgrade cannot be ranked
     */
    public static double paybackSeconds(FactoryUpgrade upgrade) {
        return upgrade != null && upgrade.rankable() ? upgrade.cost() / upgrade.gain() : -1;
    }

    /**
     * Every rankable upgrade, shortest payback first.
     *
     * <p>Unrankable entries are dropped rather than sorted to the end. A list that shows them last
     * reads as "these are the worst buys", and they are not - they are the ones nothing is known
     * about, which is a different statement and belongs on the slot, not in the order.
     *
     * <p>Ties break on cost, cheapest first: two upgrades that pay back equally fast are otherwise
     * ordered by whatever the menu happened to list first, and the cheaper one is reachable sooner.
     */
    public static List<FactoryUpgrade> ranked(List<FactoryUpgrade> upgrades) {
        List<FactoryUpgrade> out = new ArrayList<>();
        if (upgrades == null) {
            return out;
        }
        for (FactoryUpgrade upgrade : upgrades) {
            if (upgrade != null && upgrade.rankable()) {
                out.add(upgrade);
            }
        }
        out.sort(Comparator.comparingDouble(UpgradeRanking::paybackSeconds)
                .thenComparingLong(FactoryUpgrade::cost));
        return out;
    }

    /** The shortest-payback upgrade, or {@code null} when nothing could be ranked. */
    public static FactoryUpgrade best(List<FactoryUpgrade> upgrades) {
        List<FactoryUpgrade> ranked = ranked(upgrades);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * How much more chocolate is needed to afford {@code upgrade}.
     *
     * @return the shortfall, or {@code 0} when it is already affordable or the cost is unknown -
     *         an unknown cost must not be reported as an infinite shortfall
     */
    public static long shortfall(FactoryUpgrade upgrade, long balance) {
        if (upgrade == null || upgrade.cost() <= 0) {
            return 0;
        }
        return Math.max(0, upgrade.cost() - balance);
    }

    /**
     * Seconds of saving before {@code upgrade} can be bought, at the current production rate.
     *
     * @return the wait in seconds, {@code 0} when it is already affordable, or {@code -1} when the
     *         rate is unknown or zero - with no production there is no honest answer, and "never"
     *         dressed up as a big number is the wrong one
     */
    public static double secondsToAfford(FactoryUpgrade upgrade, long balance, double perSecond) {
        long missing = shortfall(upgrade, balance);
        if (missing == 0) {
            return 0;
        }
        return perSecond > 0 ? missing / perSecond : -1;
    }
}
