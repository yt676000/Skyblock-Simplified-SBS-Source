/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.model;

import sbs.modid.client.core.util.NumberDisplay;

import java.util.Locale;

/**
 * One locally computed bazaar flip estimate, together with <b>every input that produced it</b>.
 *
 * <p>The inputs are not debug material, they are the point. A projected coins-per-hour figure is a
 * chain of assumptions — a snapshot's spread, a weekly volume divided by 168, a share of that volume
 * the player is guessed to capture — and a user who can see the chain can overrule it where they know
 * the market better than the heuristic does. A user who is shown only the output has to take it on
 * faith, and this estimate is not good enough to be taken on faith.
 *
 * @param itemId          bazaar product id
 * @param buyOrderPrice   what you would have to bid per unit to sit alone at the top of the buy book
 * @param sellOfferPrice  what you would have to ask per unit to be the cheapest sell offer
 * @param taxRate         the sell tax applied to {@code sellOfferPrice} (0.0125 = 1.25%)
 * @param unitMargin      net profit per unit: {@code sellOfferPrice * (1 - taxRate) - buyOrderPrice}
 * @param spreadPct       gross spread as a percentage of {@code buyOrderPrice} (pre-tax)
 * @param demandWeek      units bought out of sell offers over 7 days — fills YOUR sell offer
 * @param supplyWeek      units sold into buy orders over 7 days — fills YOUR buy order
 * @param flowPerHour     {@code min(demandWeek, supplyWeek) / 168} — the two-sided hourly throughput
 * @param unitsPerHour    {@code flowPerHour} after the share factor and the order-size cap
 * @param profitPerHour   {@code unitsPerHour * unitMargin} — the headline estimate
 * @param unitsPerOrder   order size the estimate assumes, capped by the in-game cap and the budget
 * @param capital         coins tied up in one buy order of {@code unitsPerOrder}
 * @param buyOrderCount   distinct buy orders on the book (quick_status)
 * @param sellOrderCount  distinct sell offers on the book (quick_status)
 * @param buyConcentration share of the visible buy book's units sitting at its single top level
 * @param sellConcentration share of the visible sell book's units sitting at its single top level
 * @param confidence      whether the order-book top or only the summary averages were available
 * @param filtered        why this entry was excluded, or {@code null} when it passed every filter
 */
public record LocalFlip(
        String itemId,
        double buyOrderPrice,
        double sellOfferPrice,
        double taxRate,
        double unitMargin,
        double spreadPct,
        long demandWeek,
        long supplyWeek,
        double flowPerHour,
        double unitsPerHour,
        double profitPerHour,
        long unitsPerOrder,
        double capital,
        int buyOrderCount,
        int sellOrderCount,
        double buyConcentration,
        double sellConcentration,
        LocalFlip.Confidence confidence,
        LocalFlip.Filter filtered) {

    /**
     * How the two order prices were obtained.
     *
     * <p>Only {@link #BOOK} is a real answer. The summary averages are weighted across a slice of the
     * book, so an estimate built on them is answering a different question than "what does it cost to
     * be first in the queue" — which is the only question a flip cares about.
     */
    public enum Confidence {
        /** Top of the live order book on both sides — what the estimate is designed around. */
        BOOK("book", "Order-book top on both sides."),
        /** One side had no book this snapshot; its weighted average stood in. Treat as indicative. */
        SUMMARY("avg", "One side had no order book — a weighted average stood in for the queue price. "
                + "The real price to be first may be well off this.");

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
     * Why an entry was excluded. Each one is a separate signal rather than a single rule, because a
     * wide spread is a <i>symptom</i>: filtering on it alone throws away the rare genuine opportunity
     * while leaving a manipulated item that happens to sit at 20% right where it was.
     */
    public enum Filter {
        SPREAD("spread"),
        DEMAND("demand"),
        SUPPLY("supply"),
        BUY_ORDERS("buy orders"),
        SELL_ORDERS("sell orders"),
        CONCENTRATION("book concentration");

        private final String label;

        Filter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** True when this entry passed every anomaly filter. */
    public boolean kept() {
        return filtered == null;
    }

    /**
     * The concrete reason this entry was hidden, naming the measured value and the threshold it
     * failed — "spread 71% over the 25% limit", never just "filtered". A user overruling the
     * heuristic needs to see what the heuristic actually objected to.
     */
    public String filterReason(int spreadLimit, long volumeFloor, int orderFloor, int concentrationLimit) {
        if (filtered == null) {
            return "";
        }
        return switch (filtered) {
            case SPREAD -> String.format(Locale.ROOT,
                    "spread %.0f%% is over the %d%% limit — an unusually wide gap is more often a thin "
                            + "or manipulated book than free money", spreadPct, spreadLimit);
            case DEMAND -> "only " + NumberDisplay.shorten(demandWeek) + " bought per week (floor "
                    + NumberDisplay.shorten(volumeFloor) + ") — too few buyers to sell into";
            case SUPPLY -> "only " + NumberDisplay.shorten(supplyWeek) + " sold per week (floor "
                    + NumberDisplay.shorten(volumeFloor) + ") — too little supply to fill your buy order";
            case BUY_ORDERS -> buyOrderCount + " buy orders (floor " + orderFloor
                    + ") — a book this narrow is one person's to move";
            case SELL_ORDERS -> sellOrderCount + " sell offers (floor " + orderFloor
                    + ") — a book this narrow is one person's to move";
            case CONCENTRATION -> String.format(Locale.ROOT,
                    "%.0f%% of the visible book sits at one price (limit %d%%) — a single wall, not a market",
                    Math.max(buyConcentration, sellConcentration) * 100, concentrationLimit);
        };
    }
}
