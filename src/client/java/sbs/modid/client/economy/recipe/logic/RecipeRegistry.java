/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.ChatPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.model.ItemFilter;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Central aggregation point for every {@link RecipeProvider} – the single source the Recipe Viewer
 * UI reads from.
 *
 * <p>Provider results are cached in an id-indexed snapshot and only rebuilt when marked dirty (a new
 * recipe was scraped) or on the periodic refresh, so lookups and searches stay fast: searches hit the
 * pre-built index and the {@link SkyBlockItemCatalog}, never the providers. Acquisition methods are
 * resolved from the systems SBS already runs (recipes → Crafting, Bazaar caches → Bazaar, LBIN cache
 * → Auction House), so nothing is fetched twice.
 */
public final class RecipeRegistry {

    private static final RecipeRegistry INSTANCE = new RecipeRegistry();

    private final List<RecipeProvider> providers = new ArrayList<>();

    private volatile Map<String, List<SbsRecipe>> byResultId = Map.of();
    private volatile Map<String, List<SbsRecipe>> byIngredientId = Map.of();
    private volatile List<SbsRecipe> all = List.of();
    private volatile boolean dirty = true;
    private volatile long lastRebuild;

    /** Periodic safety refresh so newly scraped recipes appear even without a dirty mark. */
    private static final long REBUILD_INTERVAL_MS = 10_000L;

    private RecipeRegistry() {
        register(SkyBlockRepoRecipeProvider.getInstance()); // pre-populated database (no discovery needed)
        register(ScrapedRecipeStore.getInstance());         // in-game captures supplement / correct it
        register(EnchantmentRecipeProvider.getInstance());  // bazaar enchants: search + combine recipe
        // Future providers (Forge, Kat, NPC, vanilla display book, ...) register here.
    }

    public static RecipeRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized void register(RecipeProvider provider) {
        providers.add(provider);
        dirty = true;
    }

    public void markDirty() {
        dirty = true;
    }

    /** All recipes producing the given lookup id (SkyBlock id or upper-cased vanilla material). */
    public List<SbsRecipe> recipesFor(String lookupId) {
        rebuildIfNeeded();
        if (lookupId == null) {
            return List.of();
        }
        List<SbsRecipe> found = byResultId.get(SkyBlockItemCatalog.canonicalId(lookupId));
        return found != null ? found : List.of();
    }

    /** All recipes that USE the given item as an ingredient (the "usages" view). */
    public List<SbsRecipe> usagesFor(String lookupId) {
        rebuildIfNeeded();
        if (lookupId == null) {
            return List.of();
        }
        List<SbsRecipe> found = byIngredientId.get(SkyBlockItemCatalog.canonicalId(lookupId));
        return found != null ? found : List.of();
    }

    /**
     * Search across the item catalogue and every known recipe result: case-insensitive partial match
     * on display names and SkyBlock ids, plus the {@code rarity:<tier>} filter token. Returns
     * de-duplicated item refs, capped at {@code limit}.
     */
    public List<ItemRef> search(String query, int limit) {
        rebuildIfNeeded();
        List<ItemRef> results = new ArrayList<>();
        SkyBlockItemCatalog.Query parsed = SkyBlockItemCatalog.Query.parse(query);
        if (parsed.text.isEmpty() && parsed.tier == null) {
            return results;
        }
        Set<String> seen = new LinkedHashSet<>();

        // Known recipe results first (they always have a viewable recipe). Recipes carry no tier, so
        // when a rarity filter is active the (tier-aware) catalogue below is the sole source.
        if (parsed.tier == null) {
            String q = parsed.text;
            for (SbsRecipe recipe : all) {
                ItemRef result = recipe.result;
                if (result == null) {
                    continue;
                }
                if ((result.name != null && result.name.toLowerCase(Locale.ROOT).contains(q))
                        || result.lookupId().toLowerCase(Locale.ROOT).contains(q)) {
                    if (seen.add(result.lookupId()) && results.size() < limit) {
                        results.add(result);
                    }
                }
            }
        }
        // Then the official item catalogue (handles the rarity token itself).
        for (SkyBlockItemCatalog.Entry entry : SkyBlockItemCatalog.getInstance().search(query, limit)) {
            if (seen.add(entry.id.toUpperCase(Locale.ROOT)) && results.size() < limit) {
                results.add(entry.toRef());
            }
        }
        // Finally the LORE index: items whose description mentions the query ("Blessed", "double
        // drops", an ability name). Name/id hits above rank first; needs 3+ chars so a single letter
        // never floods the grid with description matches. Only for plain queries (no rarity token).
        if (parsed.tier == null && parsed.text.length() >= 3 && results.size() < limit) {
            String q = parsed.text;
            SkyBlockItemCatalog catalog = SkyBlockItemCatalog.getInstance();
            for (Map.Entry<String, String> lore
                    : SkyBlockRepoRecipeProvider.getInstance().loreIndex().entrySet()) {
                if (results.size() >= limit) {
                    break;
                }
                if (!lore.getValue().contains(q)) {
                    continue;
                }
                SkyBlockItemCatalog.Entry entry = catalog.byId(lore.getKey());
                if (entry != null && seen.add(entry.id.toUpperCase(Locale.ROOT))) {
                    results.add(entry.toRef());
                }
            }
        }
        return collapseEnchantGroups(results);
    }

    /**
     * The category-filtered view behind the Recipe Viewer's filter buttons: every catalogue entry in
     * {@code filter}, narrowed further by the query if there is one. A blank query is meaningful here
     * (unlike in {@link #search}) – picking "Armor" and typing nothing is exactly how you browse a
     * category. Results are sorted by name <i>before</i> the cap, so the list is alphabetical rather
     * than "whatever the map iterated first". {@link ItemFilter#ALL} falls through to {@link #search}.
     */
    public List<ItemRef> browse(String query, ItemFilter filter, int limit) {
        if (filter == null || filter == ItemFilter.ALL) {
            return search(query, limit);
        }
        SkyBlockItemCatalog.Query parsed = SkyBlockItemCatalog.Query.parse(query);
        List<SkyBlockItemCatalog.Entry> matches = new ArrayList<>();
        for (SkyBlockItemCatalog.Entry entry : SkyBlockItemCatalog.getInstance().all()) {
            if (!filter.matches(entry)) {
                continue;
            }
            if (parsed.tier != null && (entry.tierLower == null || !entry.tierLower.contains(parsed.tier))) {
                continue;
            }
            if (!parsed.text.isEmpty()
                    && !entry.nameLower.contains(parsed.text) && !entry.idLower.contains(parsed.text)) {
                continue;
            }
            matches.add(entry);
        }
        matches.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        List<ItemRef> results = new ArrayList<>(Math.min(matches.size(), limit));
        for (SkyBlockItemCatalog.Entry entry : matches) {
            if (results.size() >= limit) {
                break;
            }
            results.add(entry.toRef());
        }
        return collapseEnchantGroups(results);
    }

    /**
     * Collapses every enchant-book level (Legion 1..max) into a single grouped row, so a search for
     * "legion" returns one "Legion 1–10" entry instead of ten. The representative opens the max-tier
     * combine recipe; the Recipe Viewer shows a group tooltip on hover. Gated on the Recipe Viewer's
     * {@code groupEnchants} toggle; anything that is not an enchant id passes through untouched.
     */
    private List<ItemRef> collapseEnchantGroups(List<ItemRef> results) {
        if (!sbs.modid.client.core.config.ConfigManager.getInstance().get().recipeViewer.groupEnchants) {
            return results;
        }
        List<ItemRef> collapsed = new ArrayList<>();
        Set<String> keysDone = new java.util.HashSet<>();
        for (ItemRef ref : results) {
            String key = EnchantmentRecipeProvider.keyOf(ref.lookupId());
            if (key == null) {
                collapsed.add(ref);
                continue;
            }
            if (!keysDone.add(key)) {
                continue; // a representative for this enchant is already in the list
            }
            ItemRef group = EnchantmentRecipeProvider.groupRef(key);
            collapsed.add(group != null ? group : ref);
        }
        return collapsed;
    }

    /** Every known way to obtain the item, resolved from the systems SBS already tracks. */
    public List<String> acquisitionMethods(String lookupId) {
        List<String> methods = new ArrayList<>();
        if (lookupId == null || lookupId.isEmpty()) {
            return methods;
        }
        String id = lookupId.toUpperCase(Locale.ROOT);
        for (SbsRecipe recipe : recipesFor(id)) {
            String method = recipe.category + (recipe.source == null || recipe.source.isEmpty()
                    ? "" : " (" + recipe.source + ")");
            if (!methods.contains(method)) {
                methods.add(method);
            }
        }
        if (BazaarPriceCache.getInstance().getBuy(id) != null
                || ChatPriceCache.getInstance().getUnitPrice(id) != null) {
            methods.add("Bazaar");
        }
        if (LbinCache.getInstance().getLbin(id) != null) {
            methods.add("Auction House");
        }
        return methods;
    }

    private void rebuildIfNeeded() {
        long now = System.currentTimeMillis();
        if (!dirty && now - lastRebuild < REBUILD_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            if (!dirty && now - lastRebuild < REBUILD_INTERVAL_MS) {
                return;
            }
            List<SbsRecipe> collected = new ArrayList<>();
            for (RecipeProvider provider : providers) {
                collected.addAll(provider.loadRecipes());
            }
            Map<String, List<SbsRecipe>> index = new HashMap<>();
            Map<String, List<SbsRecipe>> usageIndex = new HashMap<>();
            for (SbsRecipe recipe : collected) {
                // Canonical ids merge the two data-variant conventions (WOOD_STEP:4 / WOOD_STEP-4),
                // so a recipe is found no matter which side named the hovered item.
                if (recipe.result != null) {
                    index.computeIfAbsent(SkyBlockItemCatalog.canonicalId(recipe.result.lookupId()),
                            k -> new ArrayList<>()).add(recipe);
                }
                for (ItemRef ingredient : recipe.ingredients) {
                    usageIndex.computeIfAbsent(SkyBlockItemCatalog.canonicalId(ingredient.lookupId()),
                            k -> new ArrayList<>()).add(recipe);
                }
            }
            this.all = List.copyOf(collected);
            this.byResultId = Map.copyOf(index);
            this.byIngredientId = Map.copyOf(usageIndex);
            this.dirty = false;
            this.lastRebuild = now;
        }
    }
}
