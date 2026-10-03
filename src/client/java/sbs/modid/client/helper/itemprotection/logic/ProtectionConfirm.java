/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.logic;

import sbs.modid.client.helper.itemprotection.model.ProtectionCategory;

/**
 * The "click again to confirm" window: one pending intent at a time, cleared by anything else.
 *
 * <p><b>Keyed by item and category together.</b> Confirming a drop must not also confirm a sale of
 * the same item, and confirming one item must not confirm the next - so a different item, or the
 * same item towards a different destination, starts a fresh window rather than walking into an
 * already-open one. That is the whole reason this is not a plain "last press was recent" timer.
 *
 * <p>Not thread-safe, and does not need to be: every caller is on the client thread, reached from
 * screen input or the container funnel.
 */
public final class ProtectionConfirm {

    private static String pending = "";
    private static long pendingAt;

    /**
     * The last category a confirmation was actually granted for, and when.
     *
     * <p>Separate from {@link #pending} because it must survive {@link #clear}: a Hypixel sale is a
     * button press on one screen followed by a confirmation on the next, and the screen change
     * between them clears the pending window by design. Without this, one sale would ask twice, and
     * a prompt people learn to click through is a prompt that protects nothing.
     *
     * <p>Kept on the category rather than the item, because a confirmation screen names no item -
     * it is the sale the player already agreed to one screen ago. It expires on the same window the
     * player configured, so a second, unrelated sale inside those few seconds is waved through once.
     * That is the cost of asking once instead of twice, and it is bounded.
     */
    private static String granted = "";
    private static long grantedAt;

    private ProtectionConfirm() {
    }

    /**
     * Offers an attempt to the window.
     *
     * @param identity the protected item's identity key (uuid or id), never its display name
     * @param category what the action would do
     * @param windowMs how long a confirmation stays open
     * @return {@code true} when this attempt confirms an earlier one and must be let through;
     *         {@code false} when it is the first attempt, which the caller cancels
     */
    public static boolean offer(String identity, ProtectionCategory category, long windowMs) {
        String key = identity + "|" + category.name();
        long now = System.currentTimeMillis();
        if (key.equals(pending) && now - pendingAt <= windowMs) {
            clear();   // spent: the next attempt asks again
            granted = category.name();
            grantedAt = now;
            return true;
        }
        pending = key;
        pendingAt = now;
        return false;
    }

    /**
     * Whether a confirmation for this category was granted within {@code windowMs} - the question a
     * follow-up screen in the same flow asks so it does not re-ask what was just agreed to.
     */
    public static boolean grantedRecently(ProtectionCategory category, long windowMs) {
        return category.name().equals(granted) && System.currentTimeMillis() - grantedAt <= windowMs;
    }

    /**
     * Forgets any open window. Called when the screen changes, so an offer cannot survive a menu.
     *
     * <p>Deliberately does <b>not</b> forget a granted confirmation: that one exists precisely to
     * cross a screen change - see {@link #granted}. It expires on its own window instead.
     */
    public static void clear() {
        pending = "";
        pendingAt = 0L;
    }
}
