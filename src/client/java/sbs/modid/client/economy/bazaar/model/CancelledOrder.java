/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.model;

/**
 * The unfilled remainder of a cancelled BUY order — one row of the Bazaar order history.
 *
 * <p>A cancelled buy order is the one case where Hypixel gives the player nothing to work from: a
 * cancelled sell offer says its amount in chat ("Refunded 64x Cobblestone"), but a cancelled buy
 * order refunds <i>coins</i>, so the quantity still owed is the one number nobody states. Re-placing
 * it means remembering what was ordered, working out what filled, and subtracting. This record is
 * that subtraction, kept so the player never has to do it.
 *
 * <p>A plain POJO rather than a record so {@link sbs.modid.client.core.config.SBSFiles}' Gson can
 * rebuild it field-by-field from the history file, exactly as {@link BazaarOrder} is.
 *
 * <p><b>Identity is item + price</b> ({@link #key()}), never item alone. Two buy orders on the same
 * product at different prices are two different intentions — one at the price you actually want and
 * one parked below the book — and merging their remainders into a single row would produce an
 * amount the player never ordered.
 */
public final class CancelledOrder {

    /**
     * The largest single Bazaar order Hypixel accepts. Measured as 71,680 units — 640 stacks — and
     * the same at every price level. A remainder above this cannot be re-placed in one go, so the
     * panel says so rather than pre-filling an amount the sign will reject.
     */
    public static final int MAX_ORDER_UNITS = 71_680;

    /** Hypixel SkyBlock product id, matching the Bazaar API key (e.g. {@code ENCHANTED_SUGAR_CANE}). */
    private String itemId = "";

    /** Display name of the traded item, e.g. {@code Enchanted Sugar Cane}. */
    private String itemName = "";

    /** Units still owed when the order was cancelled: {@link #ordered} − {@link #filled}. */
    private int remaining;

    /** What the cancelled order was originally for, kept so the panel can draw the fill proportion. */
    private int ordered;

    /** How much of it had been bought by the time it was cancelled. */
    private int filled;

    /** The order's price per unit. Part of the identity — see {@link #key()}. */
    private double price;

    /** Epoch millis at which the cancellation was confirmed. Drives expiry and the sort order. */
    private long cancelledAt;

    public CancelledOrder() {
    }

    public CancelledOrder(String itemId, String itemName, int remaining, int ordered, int filled,
                          double price, long cancelledAt) {
        this.itemId = itemId == null ? "" : itemId;
        this.itemName = itemName == null ? "" : itemName;
        this.remaining = remaining;
        this.ordered = ordered;
        this.filled = filled;
        this.price = BazaarOrder.snapToTick(price);
        this.cancelledAt = cancelledAt;
    }

    public String itemId() {
        return itemId == null ? "" : itemId;
    }

    public String itemName() {
        return itemName == null || itemName.isBlank() ? itemId() : itemName;
    }

    public int remaining() {
        return remaining;
    }

    public int ordered() {
        return ordered;
    }

    public int filled() {
        return filled;
    }

    public double price() {
        return price;
    }

    public long cancelledAt() {
        return cancelledAt;
    }

    /** Re-applies the 0.1-coin snap to a price Gson wrote straight into the field. */
    public void snapPriceToTick() {
        this.price = BazaarOrder.snapToTick(this.price);
    }

    /**
     * The share of the original order that had filled, 0..1 — what the panel's bar draws instead of
     * printing the counts. Zero-width when the ordered amount is unknown, which reads as "all of it
     * is still owed" and is the honest picture in that case.
     */
    public float filledFraction() {
        if (ordered <= 0) {
            return 0f;
        }
        return Math.clamp(filled / (float) ordered, 0f, 1f);
    }

    /** Whether the remainder is a quantity the Bazaar would actually accept in one order. */
    public boolean withinOrderLimit() {
        return remaining > 0 && remaining <= MAX_ORDER_UNITS;
    }

    /**
     * Identity key: <b>item + price</b>, with the price in whole 0.1-coin ticks so two spellings of
     * the same figure cannot hash apart. Deliberately excludes the side — only buy orders are ever
     * recorded here, so a side component would be a constant.
     */
    public String key() {
        return itemId() + '|' + BazaarOrder.ticks(price);
    }
}
