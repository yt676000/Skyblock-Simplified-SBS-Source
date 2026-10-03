/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.npcshop.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.bazaar.logic.BazaarApiClient;
import sbs.modid.client.economy.bazaar.logic.LocalFlipEngine;
import sbs.modid.client.economy.bazaar.model.BazaarSnapshot;
import sbs.modid.client.economy.npcshop.model.NpcFlipMath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The NPC flip ranking: every learned coins-only offer whose item sells on the Bazaar for more than
 * the NPC charges, after the Bazaar's sell tax, best profit per unit first.
 *
 * <p><b>Top of book, from the shared snapshot</b> ({@link BazaarSnapshot#peek()} - never a fetch, so
 * this can run on the render thread and costs no request of its own): insta-sell is the highest buy
 * order, sell-offer the lowest sell offer. {@code quick_status} prices are averages and are not used.
 * Volume is {@code quick_status.sellMovingWeek}, the units sold into buy orders over a week - the
 * figure that says whether a flip can actually be sold.
 *
 * <p>Rebuilt at most once a second, and at once when the catalogue learns something.
 */
public final class NpcFlips {

    public record Flip(NpcShopCatalog.Entry entry, double unitPrice, double profitPerUnit, long weekVolume) {
    }

    /** The whole answer: the kept flips plus what the header needs to be honest about coverage. */
    public record Ranking(List<Flip> flips, int offers, int shops, int unranked, boolean haveBazaar) {
    }

    private static final long RANK_INTERVAL_MS = 1_000L;

    private static Ranking ranking = new Ranking(List.of(), 0, 0, 0, false);
    private static Map<String, Flip> byOffer = Map.of();
    private static long rankedAt;
    private static int rankedGeneration = -1;
    private static int rankedSettings;

    private NpcFlips() {
    }

    private static SBSConfig.BazaarSettings cfg() {
        return ConfigManager.getInstance().get().bazaar;
    }

    public static synchronized Ranking ranking() {
        refresh();
        return ranking;
    }

    /** The flip for an offer in the open shop, when it passed the filters; else {@code null}. */
    public static synchronized Flip flipFor(String npc, String itemId) {
        refresh();
        return npc == null || itemId == null ? null
                : byOffer.get(npc.toLowerCase(Locale.ROOT) + "|" + itemId.toUpperCase(Locale.ROOT));
    }

    private static void refresh() {
        SBSConfig.BazaarSettings cfg = cfg();
        NpcShopCatalog catalog = NpcShopCatalog.getInstance();
        int generation = catalog.generation();
        int settings = java.util.Objects.hash(cfg.npcFlipMinProfit, cfg.npcFlipMinVolume, cfg.npcFlipSellSide,
                cfg.bazaarFlipperLevel);
        long now = System.currentTimeMillis();
        if (generation == rankedGeneration && settings == rankedSettings && now - rankedAt < RANK_INTERVAL_MS) {
            return;
        }
        rankedAt = now;
        rankedGeneration = generation;
        rankedSettings = settings;

        BazaarApiClient.Response snapshot = BazaarSnapshot.getInstance().peek();
        List<NpcShopCatalog.Entry> offers = catalog.all();
        double tax = LocalFlipEngine.taxRate(cfg.bazaarFlipperLevel);
        List<Flip> kept = new ArrayList<>();
        Map<String, Flip> index = new HashMap<>();
        int unranked = 0;
        for (NpcShopCatalog.Entry entry : offers) {
            BazaarApiClient.Product product = snapshot == null || snapshot.products == null ? null
                    : snapshot.products.get(entry.itemId);
            Double price = topOfBook(product, cfg.npcFlipSellSide == 1);
            if (!NpcFlipMath.rankable(entry.cost(), price)) {
                unranked++;
                continue;
            }
            double profit = NpcFlipMath.profitPerUnit(entry.coins, entry.stackSize, price, tax);
            long volume = product.quick_status == null ? 0 : product.quick_status.sellMovingWeek;
            if (profit < Math.max(cfg.npcFlipMinProfit, 0.000001) || volume < cfg.npcFlipMinVolume) {
                continue;
            }
            Flip flip = new Flip(entry, price, profit, volume);
            kept.add(flip);
            index.put(entry.npc.toLowerCase(Locale.ROOT) + "|" + entry.itemId, flip);
        }
        kept.sort(Comparator.comparingDouble(Flip::profitPerUnit).reversed());
        ranking = new Ranking(List.copyOf(kept), offers.size(), catalog.shopCount(), unranked, snapshot != null);
        byOffer = index;
    }

    /** Highest buy order (insta-sell) or lowest sell offer (sell-offer mode), or {@code null}. */
    static Double topOfBook(BazaarApiClient.Product product, boolean sellOffer) {
        if (product == null) {
            return null;
        }
        List<BazaarApiClient.Summary> book = sellOffer ? product.buy_summary : product.sell_summary;
        if (book == null || book.isEmpty() || book.get(0) == null || book.get(0).pricePerUnit <= 0) {
            return null;
        }
        return book.get(0).pricePerUnit;
    }
}
