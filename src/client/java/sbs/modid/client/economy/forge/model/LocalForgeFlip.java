/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.model;

import sbs.modid.client.core.util.NumberDisplay;

import java.util.List;
import java.util.Locale;

/**
 * One locally computed forge-flip estimate, together with <b>every input that produced it</b>.
 *
 * <p>Same contract as {@link sbs.modid.client.economy.bazaar.model.LocalFlip}: the headline is a
 * projection built on a chain of assumptions, so the chain travels with it. A player who can see the
 * ingredient prices, the tax and the two routes can overrule the ranking where they know the market
 * better than it does; a player shown only "3.2M/h" has to take it on faith, and this number is not
 * good enough for that.
 *
 * <p><b>Two routes, because a forge run is a decision about time.</b> The patient route buys the
 * ingredients with buy orders and sells the result with a sell offer — the best prices, and what the
 * ranking sorts by, matching what the backend computes. The instant route takes the cheapest sell
 * offers and hits the highest buy order — worse prices, available now. The gap between them is
 * exactly the question "is it worth waiting for the orders to fill", which the ranking itself cannot
 * answer for the player.
 *
 * @param outputId             SkyBlock id the forge produces
 * @param displayName          its name, § codes already stripped
 * @param outputCount          units produced per run
 * @param durationSeconds      forge time of one run — the denominator of the whole ranking
 * @param requirement          the archive's unlock note (a HotM tier, usually), or empty
 * @param inputs               what one run consumes, each with the price it was costed at
 * @param cost                 patient route: total ingredient cost of one run
 * @param revenue              patient route: sale proceeds of one run, after sell tax
 * @param profit               {@code revenue - cost}
 * @param profitPerHour        {@code profit / (durationSeconds / 3600)} — the headline
 * @param marginPct            {@code profit / cost * 100}
 * @param taxRate              the sell tax applied to the output (0.0125 = 1.25%)
 * @param instantCost          instant route: ingredient cost taking the cheapest sell offers
 * @param instantRevenue       instant route: proceeds of hitting the best buy order, after tax
 * @param instantProfitPerHour the same figure for the instant route
 * @param demandRunsWeek       runs per week the output's buyers could absorb
 * @param supplyRunsWeek       runs per week the tightest ingredient's sellers could supply
 * @param tightestInput        which ingredient that is
 * @param confidence           whether every leg came off the order book or a summary stood in
 * @param filtered             why this entry was excluded, or {@code null} when it passed
 */
public record LocalForgeFlip(
        String outputId,
        String displayName,
        double outputCount,
        int durationSeconds,
        String requirement,
        List<Ingredient> inputs,
        double cost,
        double revenue,
        double profit,
        double profitPerHour,
        double marginPct,
        double taxRate,
        double instantCost,
        double instantRevenue,
        double instantProfitPerHour,
        double demandRunsWeek,
        double supplyRunsWeek,
        String tightestInput,
        Confidence confidence,
        Filter filtered) {

    /**
     * One ingredient line, priced.
     *
     * @param unitPrice what one unit was costed at on the patient route
     * @param lineCost  {@code unitPrice * count}
     */
    public record Ingredient(String itemId, double count, double unitPrice, double lineCost) {
    }

    /**
     * How the prices behind this entry were obtained.
     *
     * <p>Only {@link #BOOK} is a real answer. A weighted average is a different question than "what
     * does it cost to be first in the queue", which is the only question an order price cares about.
     */
    public enum Confidence {
        /** Every leg came off the live order book. */
        BOOK("book", "Order-book top on every ingredient and on the result."),
        /** At least one leg had no book this snapshot and its weighted average stood in. */
        SUMMARY("avg", "At least one leg had no order book — a weighted average stood in for the "
                + "queue price, so the real cost may be well off this.");

        private final String badge;
        private final String explanation;

        Confidence(String badge, String explanation) {
            this.badge = badge;
            this.explanation = explanation;
        }

        public String badge() {
            return badge;
        }

        public String explanation() {
            return explanation;
        }
    }

    /**
     * Why an entry was excluded. Liquidity comes before size: a recipe nobody buys the result of is a
     * worse prospect than a profitable one that clears less than the threshold, so it is the reason
     * reported when both apply.
     */
    public enum Filter {
        /** Too few buyers for the result — the sell offer would sit there. */
        DEMAND("demand"),
        /** Too little supply of an ingredient — the buy order would not fill. */
        SUPPLY("supply"),
        /** Profitable, but under the minimum the player asked to see. */
        PROFIT("profit");

        private final String label;

        Filter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** True when this entry passed every filter. */
    public boolean kept() {
        return filtered == null;
    }

    /** The name to show, never blank. */
    public String label() {
        return displayName == null || displayName.isBlank() ? outputId : displayName;
    }

    /**
     * The concrete reason this entry was hidden, naming the measured value and the threshold it
     * failed. A player overruling a heuristic needs to see what the heuristic actually objected to,
     * not that one objected.
     */
    public String filterReason(long minProfit, int runsFloor) {
        if (filtered == null) {
            return "";
        }
        return switch (filtered) {
            case DEMAND -> String.format(Locale.ROOT,
                    "only %.1f runs' worth is bought per week (floor %d) — your sell offer would sit",
                    demandRunsWeek, runsFloor);
            case SUPPLY -> String.format(Locale.ROOT,
                    "%s supplies only %.1f runs' worth per week (floor %d) — your buy order would not fill",
                    tightestInput == null ? "an ingredient" : tightestInput, supplyRunsWeek, runsFloor);
            case PROFIT -> "clears " + NumberDisplay.format(profit) + " per run, under your "
                    + NumberDisplay.shorten(minProfit) + " minimum";
        };
    }
}
