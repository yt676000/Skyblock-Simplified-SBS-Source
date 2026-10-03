/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.logic;

import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.bazaar.logic.BazaarApiClient;
import sbs.modid.client.economy.bazaar.model.BazaarSnapshot;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.logic.RecipeCostResolver;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

/**
 * {@link MinionMath.Prices} over the mod's existing caches - the calculator adds no network path
 * of its own. Bazaar figures come from the shared {@link BazaarSnapshot} pull (via
 * {@link BazaarPriceCache}), NPC values from the daily-cached items catalogue, lowest BIN from the
 * name-keyed {@link LbinCache}, and acquisition costs from the recursive {@link RecipeCostResolver}.
 *
 * <p>All lookups are lock-free snapshot reads; {@code null} stays {@code null} - a price the
 * caches do not know is reported unknown, never guessed.
 */
public final class MinionPricesLive implements MinionMath.Prices {

    private static final MinionPricesLive INSTANCE = new MinionPricesLive();

    private MinionPricesLive() {
    }

    public static MinionPricesLive getInstance() {
        return INSTANCE;
    }

    @Override
    public Long bazaarSell(String itemId) {
        BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().get(itemId);
        return price == null || price.sell() <= 0 ? null : price.sell();
    }

    @Override
    public Double npcSell(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        return entry == null || entry.npcSellPrice <= 0 ? null : entry.npcSellPrice;
    }

    @Override
    public Long lbin(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return null;
        }
        return LbinCache.getInstance().getLbin(SkyblockItem.normalizeName(itemName));
    }

    @Override
    public Long acquisitionCost(String lookupId) {
        return RecipeCostResolver.getInstance().cost(lookupId);
    }

    @Override
    public long dailySellVolume(String itemId) {
        BazaarApiClient.Response snapshot = BazaarSnapshot.getInstance().peek();
        if (snapshot == null || snapshot.products == null) {
            return 0;
        }
        BazaarApiClient.Product product = snapshot.products.get(itemId);
        if (product == null || product.quick_status == null) {
            return 0;
        }
        return product.quick_status.sellMovingWeek / 7;
    }
}
