/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Recursive acquisition cost: what obtaining one unit of an item cheapest-path costs right now,
 * where every node is {@code min(direct buy price, cheapest recipe priced by this same rule)}.
 *
 * <p>{@link PriceEstimator} deliberately stays one level deep for its tooltip comparison; this
 * resolver exists for the callers that need the whole tree - a minion craft priced down to raw
 * materials, where an ingredient like Enchanted Snow Block is itself never bought directly.
 *
 * <p><b>Guards.</b> A per-call visiting set breaks recipe cycles (scraped data does contain them:
 * A crafts from B, B un-crafts from A), and a depth cap bounds pathological chains. Results are
 * memoized with a short TTL since every leaf is a live market price. {@code COINS} is the pseudo-id
 * NPC trade costs use and is worth exactly 1 by definition.
 *
 * <p>Thread-safe; called from background threads only (leaf lookups are lock-free cache reads,
 * but a cold tree walks many recipes).
 */
public final class RecipeCostResolver {

    private static final RecipeCostResolver INSTANCE = new RecipeCostResolver(
            id -> PriceEstimator.getInstance().buyPrice(id),
            id -> RecipeRegistry.getInstance().recipesFor(id));

    private static final long CACHE_TTL_MS = 30_000L;
    private static final int MAX_DEPTH = 8;

    private final Function<String, Long> buyPrice;
    private final Function<String, List<SbsRecipe>> recipes;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Injectable for tests; production goes through {@link #getInstance()}. */
    RecipeCostResolver(Function<String, Long> buyPrice, Function<String, List<SbsRecipe>> recipes) {
        this.buyPrice = buyPrice;
        this.recipes = recipes;
    }

    public static RecipeCostResolver getInstance() {
        return INSTANCE;
    }

    /**
     * Cheapest acquisition cost per unit of {@code lookupId}, or {@code null} when neither a
     * market price nor a fully priceable recipe exists (unknown, never guessed).
     */
    public Long cost(String lookupId) {
        if (lookupId == null || lookupId.isEmpty()) {
            return null;
        }
        return resolve(lookupId.toUpperCase(Locale.ROOT), new HashSet<>(), 0);
    }

    private Long resolve(String id, Set<String> visiting, int depth) {
        if ("COINS".equals(id)) {
            return 1L;
        }
        CacheEntry cached = cache.get(id);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.computedAt < CACHE_TTL_MS) {
            return cached.cost;
        }
        if (depth > MAX_DEPTH || !visiting.add(id)) {
            // Cycle or runaway chain: fall back to the direct price alone, and do NOT cache -
            // this partial answer depends on the path that led here.
            return buyPrice.apply(id);
        }
        try {
            Long direct = buyPrice.apply(id);
            Long crafted = cheapestRecipe(id, visiting, depth);
            Long best;
            if (direct == null) {
                best = crafted;
            } else if (crafted == null) {
                best = direct;
            } else {
                best = Math.min(direct, crafted);
            }
            cache.put(id, new CacheEntry(best, now));
            return best;
        } finally {
            visiting.remove(id);
        }
    }

    /** The cheapest fully-priceable recipe for {@code id} per unit produced, or {@code null}. */
    private Long cheapestRecipe(String id, Set<String> visiting, int depth) {
        List<SbsRecipe> candidates = new ArrayList<>(recipes.apply(id));
        candidates.sort(Comparator.comparingInt(RecipeCostResolver::sourceRank));
        Long best = null;
        for (SbsRecipe recipe : candidates) {
            long total = 0;
            boolean complete = !recipe.ingredients.isEmpty();
            for (ItemRef ingredient : recipe.ingredients) {
                Long unit = resolve(ingredient.lookupId(), visiting, depth + 1);
                if (unit == null) {
                    complete = false;
                    break;
                }
                total += unit * Math.max(1, ingredient.count);
            }
            if (!complete || total <= 0) {
                continue;
            }
            int produced = recipe.result == null ? 1 : Math.max(1, recipe.result.count);
            long perUnit = Math.round((double) total / produced);
            if (best == null || perUnit < best) {
                best = perUnit;
            }
        }
        return best;
    }

    /** Same source preference as {@link PriceEstimator}: scraped menus, then database, vanilla last. */
    private static int sourceRank(SbsRecipe recipe) {
        if ("vanilla".equalsIgnoreCase(recipe.category)) {
            return 2;
        }
        return SkyBlockRepoRecipeProvider.SOURCE_NAME.equals(recipe.source) ? 1 : 0;
    }

    private record CacheEntry(Long cost, long computedAt) {
    }
}
