/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.card;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The sections of a tracker card - what each one is, whether it is on, and the order the player put
 * them in - so any card can be made reorderable without new UI.
 *
 * <p><b>Adopting it.</b> A card describes itself as a {@link Card}: its sections in default order
 * (stable ids, never display text - a renamed id is a lost order), where its order is stored, and
 * whether a section is switched on. It then draws its sections in {@link #resolve} order, and its
 * settings page opens {@link CardSectionOrderScreen} on it. The enabled state stays with the card's
 * own toggles; this class only orders. The fishing tracker is the first card on it; the Diana,
 * dungeon profit and slayer cards are meant to follow.
 *
 * <p><b>Stored order.</b> A list of ids, which may be missing, partial or hold ids a later build
 * removed: {@link #resolve} keeps the stored ids it still knows in their stored order, then appends
 * every section the list does not name in default order. A section added in an update therefore
 * shows up at the end of an existing layout instead of being lost or reshuffling it.
 */
public final class CardSections {

    /** One section of a card: a stable id and the label the order editor shows. */
    public record Section(String id, String label) {
    }

    /** What a tracker card has to say about itself to be reorderable. */
    public interface Card {

        /** The editor's title, e.g. "Fishing Tracker Sections". */
        String title();

        /** Every section, in the default order. */
        List<Section> sections();

        /** The stored order, by id; null when never edited. */
        List<String> storedOrder();

        /** Stores a new order; the caller saves the config. */
        void storeOrder(List<String> ids);

        /** Whether the section is switched on - the editor marks the ones that are not. */
        boolean enabled(String id);
    }

    private CardSections() {
    }

    /**
     * The full section order: stored ids that still exist, in stored order, then every other section
     * in default order. Never contains an id twice or one the catalogue does not know.
     */
    public static List<String> resolve(List<String> stored, List<Section> catalogue) {
        Set<String> known = new LinkedHashSet<>();
        for (Section section : catalogue) {
            known.add(section.id());
        }
        Set<String> out = new LinkedHashSet<>();
        if (stored != null) {
            for (String id : stored) {
                if (id != null && known.contains(id)) {
                    out.add(id);
                }
            }
        }
        out.addAll(known);
        return new ArrayList<>(out);
    }

    /** {@link #resolve} for a card. */
    public static List<String> order(Card card) {
        return resolve(card.storedOrder(), card.sections());
    }

    /** The resolved order as sections, for an editor's rows. */
    public static List<Section> orderedSections(Card card) {
        List<Section> out = new ArrayList<>();
        for (String id : order(card)) {
            for (Section section : card.sections()) {
                if (section.id().equals(id)) {
                    out.add(section);
                    break;
                }
            }
        }
        return out;
    }

    /** The default order's ids - what Reset goes back to. */
    public static List<String> defaults(List<Section> catalogue) {
        return resolve(null, catalogue);
    }
}
