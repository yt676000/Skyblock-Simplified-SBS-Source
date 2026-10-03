/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.sacks.model;

/**
 * Which price the Sack Overlay puts on everything: what you would get, by the route you would take.
 *
 * <p>The three are genuinely different amounts of money for the same item, which is the whole reason
 * the switch exists - a sack of cobblestone is worth one thing to an NPC, another to whoever has a
 * buy order up, and a third to somebody who will wait for your sell offer to fill.
 */
public enum SackPriceMode {

    /**
     * Top sell offer (the ask) minus sell tax: what you get by undercutting the cheapest offer and
     * waiting. The best of the three, and the only one that is not immediate.
     */
    SELL_OFFER("Sell Offer"),

    /**
     * Top buy order (the bid) minus sell tax: what you get right now by selling into the book.
     * Always at or below {@link #SELL_OFFER} - the spread is what you pay for not waiting.
     */
    INSTA_SELL("Insta-Sell"),

    /**
     * What an NPC merchant pays. Untaxed - the Bazaar sell tax has nothing to do with a shop - and
     * absent entirely for most items, which is a dash rather than a zero.
     */
    NPC("NPC");

    private final String displayName;

    SackPriceMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether the Bazaar sell tax applies. Never to an NPC sale. */
    public boolean taxed() {
        return this != NPC;
    }

    /** The one-line explanation shown under the switch, so the difference is on screen. */
    public String describe() {
        return switch (this) {
            case SELL_OFFER -> "Top sell offer, after tax. Not instant - it has to fill.";
            case INSTA_SELL -> "Top buy order, after tax. What you get right now.";
            case NPC -> "What a shop pays. No tax, and most items have none.";
        };
    }

    /** Display names in declaration order, for the segmented switch. */
    public static java.util.List<String> labels() {
        java.util.List<String> out = new java.util.ArrayList<>(values().length);
        for (SackPriceMode mode : values()) {
            out.add(mode.displayName());
        }
        return out;
    }
}
