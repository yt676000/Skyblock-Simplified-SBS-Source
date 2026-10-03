/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.model;

import sbs.modid.client.economy.bazaar.logic.BazaarSyncService;
/**
 * One Bazaar order read from the "Your Bazaar Orders" / "Co-op Bazaar Orders" menu.
 *
 * <p>A plain POJO so {@link sbs.modid.client.core.config.ConfigManager}'s Gson can persist the
 * cache across restarts. The competitive {@link #status} is filled in by the background
 * {@link BazaarSyncService}; {@link #lastNotifiedStatus} is in-memory only (transient) and
 * is used to send a chat message exactly once per status change.
 *
 * <p>{@link #status} is {@code volatile}: it is written by the API thread and read by the
 * render thread (the highlight overlay), so a plain field would not be safely visible.
 */
public final class BazaarOrder {

    /** Hypixel SkyBlock product id (matches the Bazaar API key), e.g. {@code ENCHANTED_SUGAR_CANE}. */
    private String itemId = "";

    /** Display name of the traded item, e.g. {@code Enchanted Sugar Cane}. */
    private String itemName = "";

    /** Whether this is a buy order or a sell offer. */
    private BazaarOrderType type = BazaarOrderType.BUY;

    /** The player's price per unit for this order. */
    private double price;

    /** Order amount (units), parsed from the tooltip's "Order/Offer amount" line. */
    private int amount;

    /**
     * Units of {@link #amount} already bought / sold, parsed from the order lore's "Filled" line.
     *
     * <p>Distinct from the {@link BazaarStatus#FILLED} status, which is the all-or-nothing verdict
     * the highlight overlay needs. This is the partial figure, and it exists for one job: a cancelled
     * buy order refunds coins for the units that did <i>not</i> fill, so {@link #remaining()} is what
     * the player has to re-order and the one number Hypixel never states.
     *
     * <p>0 when the lore carries no fill line, which reads as "nothing filled" - the safe direction,
     * since it makes the remainder the whole order rather than silently shrinking it.
     */
    private int filled;

    /** Slot index of this order within the orders menu (only valid while that menu is open). */
    private int slot = -1;

    /** Latest evaluated status (written by the API sync, read by the highlight overlay). */
    private volatile BazaarStatus status = BazaarStatus.UNKNOWN;

    /** The status last announced in chat – never persisted, resets each launch. */
    private transient BazaarStatus lastNotifiedStatus;

    /**
     * When this order first compared as beating the <i>entire</i> order book, or 0 when it does not.
     * Legitimately true only for the ~20s until the snapshot includes the freshly placed order, so a
     * long run of it means the stored price is wrong or the order no longer exists – see
     * {@link BazaarSyncService#evaluate}. Transient: a fresh launch re-measures it.
     */
    private transient long strictlyBetterSince;

    public BazaarOrder() {
    }

    public BazaarOrder(String itemId, String itemName, BazaarOrderType type, double price, int amount, int slot) {
        this.itemId = itemId == null ? "" : itemId;
        this.itemName = itemName == null ? "" : itemName;
        this.type = type == null ? BazaarOrderType.BUY : type;
        this.price = snapToTick(price);
        this.amount = amount;
        this.slot = slot;
    }

    /**
     * Every Bazaar price sits on a <b>0.1-coin grid</b>, at any price level — measured 2026-07-25
     * against the live order book, where consecutive levels on a ~105k item were
     * {@code 105790.9 / 105791.1 / 105791.2}. The grid does not widen with the price, so an undercut
     * is always a whole number of 0.1 steps whether the item costs 3 coins or 16 million.
     *
     * <p>Prices are therefore stored snapped to that grid, because the sources feed in off-grid
     * values: a chat-derived order computes its unit price as {@code total / amount}
     * (1.1000000000000001), and menu lore parsing carries its own rounding. Off-grid noise is fatal
     * here — the whole comparison turns on a single 0.1 difference — and it also broke {@link #key()},
     * which embeds the price, so the same order could hash two ways across re-scans and lose its
     * tracked status.
     */
    public static double snapToTick(double price) {
        return price <= 0 ? price : Math.round(price * 10.0) / 10.0;
    }

    /**
     * A price as a whole number of 0.1-coin ticks. Lets the status comparison be exact integer
     * arithmetic instead of an epsilon guess — see {@link BazaarSyncService#evaluate}.
     */
    public static long ticks(double price) {
        return Math.round(price * 10.0);
    }

    /** Re-applies {@link #snapToTick} to this order's price (for objects Gson built field-by-field). */
    public void snapPriceToTick() {
        this.price = snapToTick(this.price);
    }

    public String itemId() {
        return itemId == null ? "" : itemId;
    }

    public String itemName() {
        return itemName == null ? "" : itemName;
    }

    public BazaarOrderType type() {
        return type == null ? BazaarOrderType.BUY : type;
    }

    public double price() {
        return price;
    }

    public int amount() {
        return amount;
    }

    public int filled() {
        return filled;
    }

    public void setFilled(int filled) {
        this.filled = Math.clamp(filled, 0, Math.max(0, amount));
    }

    /** Units still outstanding: what a cancellation would refund, and what re-placing it must order. */
    public int remaining() {
        return Math.max(0, amount - filled);
    }

    public int slot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public BazaarStatus status() {
        return status == null ? BazaarStatus.UNKNOWN : status;
    }

    public void setStatus(BazaarStatus status) {
        this.status = status == null ? BazaarStatus.UNKNOWN : status;
    }

    public long strictlyBetterSince() {
        return strictlyBetterSince;
    }

    public void setStrictlyBetterSince(long strictlyBetterSince) {
        this.strictlyBetterSince = strictlyBetterSince;
    }

    public BazaarStatus lastNotifiedStatus() {
        return lastNotifiedStatus;
    }

    public void setLastNotifiedStatus(BazaarStatus lastNotifiedStatus) {
        this.lastNotifiedStatus = lastNotifiedStatus;
    }

    /** Identity key for matching the same order across re-scans (id + side + price). */
    public String key() {
        return itemId() + '|' + type() + '|' + price;
    }
}
