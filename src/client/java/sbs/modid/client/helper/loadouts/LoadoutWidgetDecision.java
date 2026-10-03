/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.loadouts;

import java.util.function.IntPredicate;

/**
 * Which loadout the Equipped Loadout widget shows, and how a visit to the Loadouts menu changes
 * that - pure functions, so the whole rule is unit-tested instead of living inline in a 400 ms
 * refresh.
 *
 * <p><b>The menu is the authority.</b> The answer is the slot the Loadouts menu last showed as
 * equipped (or the slot last equipped through the overlay), and it stays the answer until the menu
 * is opened again: across restarts, island changes and armour swaps, whatever the body wears.
 *
 * <p><b>Why not the body.</b> This used to be re-derived from the armour every refresh - proven by
 * the helmet, then by the listing, then by a loose resemblance - and each of those fails in
 * ordinary play: a hand-swapped piece, components that differ after a restart, loadouts that
 * differ only by pet or equipment, armour the cache never captured. Every failure flipped the card
 * to the bare body card or to the wrong slot. The body now only decides the "changed since last
 * Loadouts visit" hint, never the slot.
 *
 * <p><b>A slot only counts if it has a card.</b> A slot whose cache entry has no loadout item
 * cannot draw its name, equipment or power; it falls to the body card rather than being chosen and
 * then drawn bare with a log that names the slot.
 */
final class LoadoutWidgetDecision {

    enum Source {
        MENU, BODY
    }

    /**
     * The widget's answer.
     *
     * @param slot   the loadout drawn, or -1 for the body card
     * @param source which rule produced it
     */
    record Choice(int slot, Source source) {
    }

    private LoadoutWidgetDecision() {
    }

    /**
     * @param storedSlot the slot the Loadouts menu last said was equipped (persisted), or -1
     * @param hasCard    whether a slot has a cache entry that can draw its card ({@link #cardable})
     */
    static Choice decide(int storedSlot, IntPredicate hasCard) {
        if (storedSlot > 0 && hasCard.test(storedSlot)) {
            return new Choice(storedSlot, Source.MENU);
        }
        return new Choice(-1, Source.BODY);
    }

    /**
     * Whether a cached loadout can draw its own card. The loadout item is the richest source, but
     * not the only one: the number is always known, and a rename, armour or a pet is enough for a
     * card that is clearly that loadout. A loadout item still in the file but not decoded yet
     * counts too - it is retried, and drawing the body card meanwhile would be the wrong answer.
     */
    static boolean cardable(boolean loadoutItem, int armourPieces, boolean undecodedItem,
                            boolean customName, boolean pet) {
        return loadoutItem || undecodedItem || armourPieces > 0 || customName || pet;
    }

    /**
     * The stored slot after one read of an open Loadouts page.
     *
     * <p>Only a page that <i>could</i> show the stored slot may clear it: the menu has several
     * pages, and page 1 marking nothing says nothing about loadout 14 on page 2. So "none equipped"
     * clears the answer only when the stored slot was read on this very page and was not marked.
     *
     * @param storedSlot    the current answer, or -1
     * @param markedOnPage  the slot this page marks as equipped, or -1
     * @param storedOnPage  whether the stored slot was among the loadouts read on this page
     * @return the new answer, or -1 for the body card
     */
    static int afterMenuRead(int storedSlot, int markedOnPage, boolean storedOnPage) {
        if (markedOnPage > 0) {
            return markedOnPage;
        }
        return storedOnPage ? -1 : storedSlot;
    }
}
