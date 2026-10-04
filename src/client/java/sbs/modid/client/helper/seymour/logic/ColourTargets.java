/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.seymour.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider;
import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.helper.seymour.model.ColourTarget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The colours a piece is compared against, generated from data SBS already holds - never a list
 * written by hand and never a request of its own.
 *
 * <ul>
 *   <li><b>Armor</b>: every item of the official item catalogue carrying an API {@code color}
 *       ({@code "r,g,b"}, see {@link SkyBlockItemCatalog#parseColor}), named after the item.</li>
 *   <li><b>Dyes</b>: every item whose cached repo lore says it "changes the color of an armor piece
 *       to #rrggbb" - the item's own description. Animated dyes ("animates ... between") name two
 *       colours and are left out.</li>
 * </ul>
 *
 * Both are {@link Certainty#WIKI}: published by Hypixel, not yet matched against a piece in game.
 * The list is rebuilt whenever either source changes size, which is how it notices the catalogue
 * or the repo finishing a (re)load.
 */
public final class ColourTargets {

    /** One catalogue item as the armor generator needs it. */
    public record CatalogItem(String id, String name, int rgb) {
    }

    private static final Pattern DYE_LORE =
            Pattern.compile("changes the colou?r of an armou?r piece to #([0-9a-f]{6})\\b");

    private static final String ARMOR_SOURCE = "Hypixel item catalogue colour";
    private static final String DYE_SOURCE = "the dye's own item description";

    private static volatile List<ColourTarget> cached = List.of();
    private static volatile int cachedCatalogSize = -1;
    private static volatile int cachedLoreSize = -1;

    private ColourTargets() {
    }

    /**
     * Armor targets: every item with a colour ({@code rgb >= 0}), except the ids in {@code exclude}
     * (the Seymour pieces, which would otherwise match themselves).
     */
    public static List<ColourTarget> armor(Collection<CatalogItem> items, Set<String> exclude) {
        List<ColourTarget> out = new ArrayList<>();
        for (CatalogItem item : items) {
            if (item == null || item.rgb() < 0 || item.id() == null || item.name() == null
                    || exclude.contains(item.id().toUpperCase(Locale.ROOT))) {
                continue;
            }
            out.add(ColourTarget.of(item.name(), item.id(), item.rgb(), ColourTarget.Kind.ARMOR,
                    Certainty.WIKI, ARMOR_SOURCE));
        }
        return out;
    }

    /**
     * Dye targets from plain lore by item id. {@code nameOf} turns an id into its display name and may
     * return {@code null}, in which case the id is prettified.
     */
    public static List<ColourTarget> dyes(Map<String, String> loreById, Function<String, String> nameOf) {
        List<ColourTarget> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : loreById.entrySet()) {
            int rgb = dyeHex(entry.getValue());
            if (rgb < 0) {
                continue;
            }
            String id = entry.getKey();
            String name = nameOf.apply(id);
            out.add(ColourTarget.of(name == null || name.isBlank() ? prettify(id) : name, id, rgb,
                    ColourTarget.Kind.DYE, Certainty.WIKI, DYE_SOURCE));
        }
        return out;
    }

    /** The fixed colour a dye's lore names, or {@code -1} (no such sentence, or an animated dye). */
    public static int dyeHex(String lore) {
        if (lore == null) {
            return -1;
        }
        Matcher matcher = DYE_LORE.matcher(lore.toLowerCase(Locale.ROOT));
        return matcher.find() ? Integer.parseInt(matcher.group(1), 16) : -1;
    }

    private static String prettify(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ runtime

    /** Every target from the loaded catalogue and lore index. Cheap when nothing changed. */
    public static List<ColourTarget> current() {
        SkyBlockItemCatalog catalog = SkyBlockItemCatalog.getInstance();
        Collection<SkyBlockItemCatalog.Entry> entries = catalog.all();
        Map<String, String> lore = SkyBlockRepoRecipeProvider.getInstance().loreIndex();
        if (entries.size() == cachedCatalogSize && lore.size() == cachedLoreSize) {
            return cached;
        }
        List<CatalogItem> items = new ArrayList<>();
        for (SkyBlockItemCatalog.Entry entry : entries) {
            if (entry.color >= 0) {
                items.add(new CatalogItem(entry.id, entry.name, entry.color));
            }
        }
        List<ColourTarget> out = new ArrayList<>(armor(items, SeymourPieces.ids()));
        out.addAll(dyes(lore, id -> {
            SkyBlockItemCatalog.Entry entry = catalog.byId(id);
            return entry == null ? null : entry.name;
        }));
        cached = List.copyOf(out);
        cachedCatalogSize = entries.size();
        cachedLoreSize = lore.size();
        int armor = countArmor(out);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Seymour] {} colour targets ({} armor, {} dyes).",
                out.size(), armor, out.size() - armor);
        return cached;
    }

    private static int countArmor(List<ColourTarget> targets) {
        int n = 0;
        for (ColourTarget target : targets) {
            if (target.kind() == ColourTarget.Kind.ARMOR) {
                n++;
            }
        }
        return n;
    }
}
