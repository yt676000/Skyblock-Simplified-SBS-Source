/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.model;

import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import java.util.Locale;
import java.util.Set;

/**
 * The Recipe Viewer's category filter – the button row above the result list, which narrows the
 * catalogue to weapons, armor, equipment, ... or to the non-item lookups (mobs and NPCs).
 *
 * <p>Each constant owns the raw SkyBlock categories it covers. The primary source is the official
 * API's {@code category} field ({@link SkyBlockItemCatalog.Entry#category}); the supplemental
 * entries registered by {@link CatalogExtraEntries} carry the synthetic {@code MOB} / {@code NPC}
 * categories. Everything else – plain materials, enchanted items, most repo-only entries – has no
 * category at all and therefore only shows up under {@link #ALL}, except where
 * {@link #inferCategory} can read the answer straight off the vanilla material
 * ({@code DIAMOND_SWORD} is a sword no matter who catalogued it).
 */
public enum ItemFilter {

    ALL("All"),
    WEAPONS("Weapons", "SWORD", "LONGSWORD", "BOW", "WAND", "GAUNTLET", "FISHING_WEAPON"),
    ARMOR("Armor", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"),
    EQUIPMENT("Equipment", "NECKLACE", "CLOAK", "BELT", "BRACELET", "GLOVES"),
    ACCESSORIES("Accessories", "ACCESSORY", "MEMENTO"),
    TOOLS("Tools", "PICKAXE", "AXE", "SHOVEL", "SPADE", "HOE", "SHEARS", "DRILL", "FISHING_ROD",
            "VACUUM", "CHISEL"),
    CONSUMABLES("Consumables", "CONSUMABLE", "ARROW", "ARROW_POISON", "BAIT", "PET_ITEM",
            "TRAVEL_SCROLL", "DEPLOYABLE", "PORTAL", "REFORGE_STONE"),
    MOBS_NPCS("Mobs & NPCs", "MOB", "NPC");

    private final String displayName;
    private final Set<String> categories;

    ItemFilter(String displayName, String... categories) {
        this.displayName = displayName;
        this.categories = Set.of(categories);
    }

    public String displayName() {
        return displayName;
    }

    /** Whether the entry belongs to this filter ({@link #ALL} takes everything). */
    public boolean matches(SkyBlockItemCatalog.Entry entry) {
        if (this == ALL) {
            return true;
        }
        if (entry == null) {
            return false;
        }
        String category = entry.category != null && !entry.category.isBlank()
                ? entry.category.toUpperCase(Locale.ROOT)
                : inferCategory(entry.material);
        return category != null && categories.contains(category);
    }

    /**
     * The category a vanilla material gives away by its own name, used only for entries the API did
     * not categorise. Deliberately narrow: it recognises the gear suffixes and nothing else, because
     * a wrong guess would file an item under a category the user then can't find it in.
     */
    private static String inferCategory(String material) {
        if (material == null || material.isEmpty()) {
            return null;
        }
        String name = material.toUpperCase(Locale.ROOT);
        int variant = name.indexOf(':'); // legacy data-variant suffix (WOOD_STEP:4)
        if (variant > 0) {
            name = name.substring(0, variant);
        }
        if (name.endsWith("_HELMET")) {
            return "HELMET";
        }
        if (name.endsWith("_CHESTPLATE")) {
            return "CHESTPLATE";
        }
        if (name.endsWith("_LEGGINGS")) {
            return "LEGGINGS";
        }
        if (name.endsWith("_BOOTS")) {
            return "BOOTS";
        }
        if (name.endsWith("_SWORD")) {
            return "SWORD";
        }
        if (name.endsWith("_PICKAXE")) {
            return "PICKAXE";
        }
        if (name.endsWith("_AXE")) {
            return "AXE";
        }
        if (name.endsWith("_SHOVEL") || name.endsWith("_SPADE")) {
            return "SHOVEL";
        }
        if (name.endsWith("_HOE")) {
            return "HOE";
        }
        return switch (name) {
            case "BOW" -> "BOW";
            case "SHEARS" -> "SHEARS";
            case "FISHING_ROD" -> "FISHING_ROD";
            default -> null;
        };
    }
}
