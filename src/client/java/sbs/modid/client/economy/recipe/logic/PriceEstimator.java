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
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estimated prices and crafting margins for the tooltip comparison ("is crafting cheaper than
 * buying?").
 *
 * <p>{@link #buyPrice} is what acquiring the item directly costs right now: the Bazaar instant-buy
 * price for bazaar items (falling back to the chat-captured unit price), otherwise the Auction House
 * lowest BIN. {@link #craftingCost} sums the buy prices of a recipe's raw ingredients (first known
 * recipe, one level deep – raw material prices, no recursive crafting). If any ingredient has no
 * known price the cost is unknown ({@code null}) rather than misleading.
 *
 * <p>Tooltips run every frame, so crafting costs are cached with a short TTL; all underlying reads
 * are lock-free snapshot lookups.
 */
public final class PriceEstimator {

    private static final PriceEstimator INSTANCE = new PriceEstimator();

    private static final long CACHE_TTL_MS = 10_000L;

    private final Map<String, CostEntry> costCache = new ConcurrentHashMap<>();

    private PriceEstimator() {
    }

    public static PriceEstimator getInstance() {
        return INSTANCE;
    }

    /** Direct acquisition price (BZ instant-buy preferred, else lowest BIN), or {@code null}. */
    public Long buyPrice(String lookupId) {
        if (lookupId == null || lookupId.isEmpty()) {
            return null;
        }
        String id = lookupId.toUpperCase(Locale.ROOT);
        Long bazaar = BazaarPriceCache.getInstance().getBuy(id);
        if (bazaar != null) {
            return bazaar;
        }
        Long chat = ChatPriceCache.getInstance().getUnitPrice(id);
        if (chat != null) {
            return chat;
        }
        return LbinCache.getInstance().getLbin(id);
    }

    /** Sum of the raw-ingredient buy prices of the item's first known recipe, or {@code null}. */
    public Long craftingCost(String lookupId) {
        if (lookupId == null || lookupId.isEmpty()) {
            return null;
        }
        String id = lookupId.toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CostEntry cached = costCache.get(id);
        if (cached != null && now - cached.computedAt < CACHE_TTL_MS) {
            return cached.cost;
        }
        Long cost = compute(id);
        costCache.put(id, new CostEntry(cost, now));
        return cost;
    }

    private Long compute(String id) {
        // SkyBlock recipe sources first: live-scraped Hypixel menus are ground truth, then the
        // curated SkyBlock recipe database; vanilla-derived recipes (if a provider ever adds them)
        // are only a last resort – a vanilla layout must never misprice a custom item.
        List<SbsRecipe> recipes = new ArrayList<>(RecipeRegistry.getInstance().recipesFor(id));
        recipes.sort(Comparator.comparingInt(PriceEstimator::sourceRank));
        for (SbsRecipe recipe : recipes) {
            long total = 0;
            boolean complete = true;
            for (ItemRef ingredient : recipe.ingredients) {
                Long unit = buyPrice(ingredient.lookupId());
                if (unit == null) {
                    complete = false;
                    break;
                }
                total += unit * Math.max(1, ingredient.count);
            }
            if (complete && total > 0) {
                return total;
            }
        }
        return null;
    }

    /** Lower rank = tried first: scraped Hypixel menus, SkyBlock database, vanilla/other last. */
    private static int sourceRank(SbsRecipe recipe) {
        if ("vanilla".equalsIgnoreCase(recipe.category)) {
            return 2;
        }
        return SkyBlockRepoRecipeProvider.SOURCE_NAME.equals(recipe.source) ? 1 : 0;
    }

    private record CostEntry(Long cost, long computedAt) {
    }
}
