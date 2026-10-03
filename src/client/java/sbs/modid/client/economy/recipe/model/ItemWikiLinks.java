/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.model;

import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.Locale;

/**
 * Builds Hypixel SkyBlock wiki page URLs from item display names – for the official wiki
 * ({@code wiki.hypixel.net}) and the community SkyBlock wiki
 * ({@code hypixelskyblock.minecraft.wiki}, a community wiki.gg wiki).
 *
 * <p>Reforges are <b>not</b> part of the item name, so decorated live-stack names
 * ("Sharp Aspect of the End ✪✪", "[Lvl 80] Bat") must be cleaned before they become a page title.
 * {@link #cleanName} resolves the canonical catalogue name where possible – an exact-name hit is
 * preferred so real items whose first word happens to be a reforge word ("Superior Dragon Helmet",
 * "Refined Diamond", "Ancient Claw") are never mutilated; only then is the normalized (reforge- and
 * star-stripped) lookup tried, and finally a manual reforge strip for items the catalogue misses.
 */
public final class ItemWikiLinks {

    /** The official Hypixel wiki; item pages are {@code /<Item_Name>}. */
    public static final String OFFICIAL_BASE = "https://wiki.hypixel.net/";

    /** The community SkyBlock wiki (wiki.gg); item pages are {@code /w/<Item_Name>}. */
    public static final String SKYBLOCK_WIKI_BASE = "https://hypixelskyblock.minecraft.wiki/w/";

    private ItemWikiLinks() {
    }

    /** Official-wiki URL for the (possibly decorated) display name. */
    public static String officialUrl(String displayName) {
        return OFFICIAL_BASE + pageTitle(displayName);
    }

    /** Community SkyBlock-wiki ({@code hypixelskyblock.minecraft.wiki}) URL for the display name. */
    public static String skyblockWikiUrl(String displayName) {
        return SKYBLOCK_WIKI_BASE + pageTitle(displayName);
    }

    /**
     * The clean item name a wiki page is titled after: formatting codes, the pet-level prefix and
     * upgrade-star glyphs are removed, then the name is resolved against the item catalogue
     * (exact first, normalized second) so reforge prefixes disappear without breaking items whose
     * real name starts with a reforge word.
     */
    public static String cleanName(String displayName) {
        if (displayName == null) {
            return "";
        }
        String clean = displayName.replaceAll(String.valueOf((char) 0x00A7) + ".", "").trim();
        clean = clean.replaceAll("(?i)^\\[Lvl\\s*[0-9]+]\\s*", "");
        clean = clean.replaceAll("[✪✦⚚➊➋➌➍➎]+$", "").trim();

        SkyBlockItemCatalog catalog = SkyBlockItemCatalog.getInstance();
        SkyBlockItemCatalog.Entry exact = catalog.byName(clean);
        if (exact != null) {
            return stripTypeTags(exact.name); // canonical casing; protects "Superior Dragon Helmet" etc.
        }
        SkyBlockItemCatalog.Entry fuzzy = catalog.byNormalizedName(clean);
        if (fuzzy != null) {
            return stripTypeTags(fuzzy.name);
        }
        // Unknown item – a leading reforge word is still not part of the name.
        return stripTypeTags(stripReforge(clean));
    }

    /**
     * Removes trailing entry-type tags that are not part of the wiki page title: any bracketed
     * suffix ("Alda [NPC]") and the known parenthesized kinds ("Alda (NPC)", "(Rift NPC)",
     * "(Monster)", ...). Real item names in parentheses ("God Potion (Legacy)") stay untouched.
     */
    private static String stripTypeTags(String name) {
        String result = name;
        String previous;
        do {
            previous = result;
            result = result.replaceAll("\\s*\\[[^\\]]*]$", "").trim();
            result = result.replaceAll("(?i)\\s*\\((?:rift )?(?:npc|monster|miniboss|boss|mayor|sea creature|animal|pet)\\)$", "").trim();
        } while (!result.equals(previous));
        return result.isEmpty() ? name : result;
    }

    /** Removes one leading reforge word (two-word reforges like "Green Thumb" included). */
    private static String stripReforge(String name) {
        String[] words = name.split(" ");
        if (words.length > 2
                && SkyblockItem.isReforgeWord((words[0] + "_" + words[1]).toUpperCase(Locale.ROOT))) {
            return name.substring(words[0].length() + words[1].length() + 2).trim();
        }
        if (words.length > 1 && SkyblockItem.isReforgeWord(words[0].toUpperCase(Locale.ROOT))) {
            return name.substring(words[0].length() + 1).trim();
        }
        return name;
    }

    private static String pageTitle(String displayName) {
        return cleanName(displayName).replace(' ', '_');
    }
}
