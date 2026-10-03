/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.prerender;

import net.minecraft.world.item.ItemStack;

/**
 * What a Bazaar view is reading from: either the real container, or a capture of one.
 *
 * <p>The interface exists so the two are interchangeable to whatever draws them, which is the seam
 * the brief asked to be left open. A renderer written against this cannot accidentally depend on
 * being live, and adding phase 2 does not mean rewriting the render path — it means implementing
 * {@link #acceptClick} on one of the two sides.
 *
 * <h2>Why input is refused here, and what would have to be true to allow it</h2>
 *
 * <p>{@link #acceptClick} exists and always refuses. That is the whole of phase 1's input handling,
 * and it is a design position rather than an unfinished edge.
 *
 * <p>A click on a container slot is sent as a slot index against a <b>window id</b>. A cached view
 * has no window id — the real container has not arrived, which is the entire reason the cached view
 * is on screen. So a click made against the cache can only be delivered by replaying it once the
 * real window exists, against whatever layout the server actually sent. If that layout differs from
 * the capture by even one slot, the replayed click lands on a different item than the one the player
 * aimed at. On a Bazaar, the items are orders and prices: the failure is not a wrong screen, it is a
 * wrong order at a wrong price, placed by the mod, from an input the player made against something
 * else.
 *
 * <p>Revisiting this needs all of the following, not any of them:
 *
 * <ul>
 *   <li><b>Static-layout screens only</b> — the category and navigation pages, never a product page
 *       or a confirm flow.</li>
 *   <li><b>The arriving container matches the capture exactly</b>, by the same strict comparison
 *       {@link CapturedScreen#diff} makes. Not "mostly", and not "structurally".</li>
 *   <li><b>Never on a slot that confirms a transaction.</b> No buy, no sell, no confirm, at any
 *       match rate.</li>
 *   <li><b>Any mismatch discards the buffered click.</b> Silently doing nothing is the correct
 *       outcome; re-aiming it at what looks like the same item is the thing that must never be
 *       written.</li>
 * </ul>
 *
 * <p>Phase 1's diagnostics exist to say whether the second condition is ever true in practice. Until
 * that number is in, none of this is safe to build, which is why the method refuses rather than
 * being absent — an absent method invites someone to add clicking at the call site instead.
 */
public interface PreviewSource {

    /** The container title, formatting intact. */
    String title();

    /** The menu's own slot count, excluding the player inventory. */
    int slotCount();

    /** The stack at a menu slot, or {@link ItemStack#EMPTY}. */
    ItemStack stackAt(int slot);

    /** Whether this is the real container. False for a capture, and the disclaimer keys off it. */
    boolean live();

    /** Age of the data in milliseconds; {@code 0} when {@link #live()}. */
    long ageMs();

    /**
     * Where input would enter. Always {@code false} in phase 1 — see the class documentation for the
     * conditions that would have to hold first.
     *
     * @return whether the click was consumed; never true while this phase is what ships
     */
    default boolean acceptClick(int slot, int button) {
        return false;
    }
}
