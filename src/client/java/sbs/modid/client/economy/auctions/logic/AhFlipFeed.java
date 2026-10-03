/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The shared store of AH flips received by {@link AhFlipClient}: one place all three outputs read
 * from - the popup cards, the Flips window and the chat line - so they always agree on what a flip
 * is and when it expired.
 *
 * <p>A flip leaves the feed when its auction end passes, when it grows older than {@link #TTL_MS}
 * (matching the server's {@code feed_ttl_s} - a stale flip is almost certainly bought), or when the
 * user dismisses it. Dismissing hides it from every output at once: a card closed as "seen it" must
 * not linger in the window list.
 *
 * <p>All access is synchronized on the instance; callers get defensive copies. Values arrive
 * pre-validated and pre-sanitized by {@link AhFlipClient} - nothing here touches raw server data.
 */
public final class AhFlipFeed {

    private static final AhFlipFeed INSTANCE = new AhFlipFeed();

    /** Keep at most this many flips (the server sends ≤25 per scan). */
    private static final int CAP = 50;

    /** A flip is stale after this long, mirroring the server's feed TTL. */
    private static final long TTL_MS = 15 * 60_000L;

    /**
     * One validated, display-ready flip. {@code auctionId} is guaranteed to be exactly 32 hex
     * chars (checked in {@link AhFlipClient}), {@code displayName} is whitelist-sanitized.
     */
    public record Flip(String auctionId, String displayName, long price, long target, long profit,
                       double discountPct, long weekSales, long endAtMs, double rating,
                       long receivedMs, boolean local, int liveListings) {

        public boolean expired(long nowMs) {
            return nowMs - receivedMs > TTL_MS || (endAtMs > 0 && nowMs > endAtMs);
        }

        /**
         * The one-word market figure each output prints after the discount, worded for whichever
         * engine produced the flip.
         *
         * <p>A locally found flip has <b>no</b> weekly sales figure and must never print one: the
         * local engine sees a single snapshot of what is listed and knows nothing about what sells.
         * Printing "0/wk" there would be a confident wrong number in the field players use to judge
         * whether a flip will actually move, which is worse than printing nothing. It reports what it
         * does know instead - how many are on sale right now.
         */
        public String volumeLabel() {
            return local ? liveListings + " listed" : weekSales + "/wk";
        }
    }

    private final Deque<Flip> flips = new ArrayDeque<>();
    private final java.util.Set<String> dismissed = new java.util.LinkedHashSet<>();

    /** The active SkyBlock mayor from the last poll ({@code null} = unknown / not sent), for the window header. */
    private volatile String mayorName;

    private AhFlipFeed() {
    }

    public static AhFlipFeed getInstance() {
        return INSTANCE;
    }

    /** Adds a freshly received flip (newest first) and prunes expired ones. */
    public synchronized void add(Flip flip) {
        prune(System.currentTimeMillis());
        flips.addFirst(flip);
        while (flips.size() > CAP) {
            flips.removeLast();
        }
    }

    /** The live flips, newest first (expired and dismissed ones already removed). */
    public synchronized List<Flip> active() {
        prune(System.currentTimeMillis());
        return new ArrayList<>(flips);
    }

    /** Hides a flip everywhere (popup ✕, or after it was opened). */
    public synchronized void dismiss(String auctionId) {
        dismissed.add(auctionId);
        flips.removeIf(flip -> flip.auctionId().equals(auctionId));
        while (dismissed.size() > 256) {
            var it = dismissed.iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized boolean isDismissed(String auctionId) {
        return dismissed.contains(auctionId);
    }

    /** Records the active mayor from a poll response ({@code null}/blank clears it). */
    public void setMayorName(String name) {
        mayorName = name != null && !name.isBlank() ? name : null;
    }

    /** The active mayor's name for the window header, or {@code null} when the server sent none. */
    public String mayorName() {
        return mayorName;
    }

    private void prune(long nowMs) {
        flips.removeIf(flip -> flip.expired(nowMs));
    }
}
