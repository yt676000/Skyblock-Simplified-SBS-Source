/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What a Recipe Viewer entry is, for the All / Items / Pets / NPCs chips. <b>The</b> classification -
 * one function, so the chip, the count and the tests cannot disagree.
 *
 * <p>The rules, each from the real data rather than from names:
 * <ul>
 *   <li><b>NPC</b>: {@code SkyblockNpcs.isNpcEntry(name)}, the test the viewer already used to route an
 *       NPC click to the locator.</li>
 *   <li><b>Pet</b>: a catalogue key the repo provider created from a {@code <TYPE>;<digits>} repo id
 *       ({@code SkyBlockRepoRecipeProvider.isPetKey}), e.g. {@code PET_GLACIAL_WISP}. Not "starts with
 *       {@code PET_}": {@code PET_SKIN_*}, {@code PET_ITEM_*} and {@code PET_ATTRIBUTE_SHARD_*} are items
 *       (checked against the cached repo, 2026-09-26).</li>
 *   <li><b>Mob</b>: the synthetic {@code MOB} category {@code CatalogExtraEntries} gives bestiary mobs.
 *       Neither an item, a pet nor an NPC, so only listed under All.</li>
 *   <li><b>Item</b>: everything else, materials included.</li>
 * </ul>
 * Pure: the two lookups come in as predicates, so the rules are unit-tested without the catalogue.
 */
public enum RecipeEntryKind {
    ITEM, PET, NPC, MOB;

    /**
     * @param id       the SkyBlock id ({@code null} allowed)
     * @param name     the display name ({@code null} allowed)
     * @param category the catalogue category, or {@code null}
     */
    public static RecipeEntryKind of(String id, String name, String category, Predicate<String> isPetKey,
                                     Predicate<String> isNpcName) {
        if (name != null && isNpcName.test(name)) {
            return NPC;
        }
        if (id != null && isPetKey.test(id)) {
            return PET;
        }
        if ("NPC".equals(category)) {
            return NPC;
        }
        if ("MOB".equals(category)) {
            return MOB;
        }
        return ITEM;
    }

    /** The chip row's choices, in display order. */
    public enum Filter {
        ALL("All", "entries", null),
        ITEMS("Items", "items", ITEM),
        PETS("Pets", "pets", PET),
        NPCS("NPCs", "NPCs", NPC);

        private final String label;
        private final String noun;
        private final RecipeEntryKind kind;

        Filter(String label, String noun, RecipeEntryKind kind) {
            this.label = label;
            this.noun = noun;
            this.kind = kind;
        }

        public String label() {
            return label;
        }

        /** "12 pets" - the count line's noun for this filter. */
        public String noun() {
            return noun;
        }

        public boolean accepts(RecipeEntryKind entry) {
            return kind == null || kind == entry;
        }

        /** The chips' labels, in order. */
        public static String[] labels() {
            Filter[] all = values();
            String[] out = new String[all.length];
            for (int i = 0; i < all.length; i++) {
                out[i] = all[i].label;
            }
            return out;
        }

        /** The stored name back to a filter; anything unknown (or an old config) is ALL. */
        public static Filter byName(String name) {
            for (Filter f : values()) {
                if (f.name().equals(name)) {
                    return f;
                }
            }
            return ALL;
        }
    }

    /** {@code items} narrowed to {@code filter}, order kept. ALL returns the list itself. */
    public static <T> List<T> filter(List<T> items, Filter filter, java.util.function.Function<T, RecipeEntryKind> kindOf) {
        if (filter == Filter.ALL) {
            return items;
        }
        List<T> out = new ArrayList<>();
        for (T item : items) {
            if (filter.accepts(kindOf.apply(item))) {
                out.add(item);
            }
        }
        return out;
    }
}
