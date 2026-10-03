/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.sacks.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.bazaar.logic.BazaarApiClient;
import sbs.modid.client.economy.bazaar.logic.LocalFlipEngine;
import sbs.modid.client.economy.bazaar.model.BazaarSnapshot;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.helper.sacks.model.SackPriceMode;
import sbs.modid.client.skills.mining.logic.GemstoneCatalog;

import java.util.List;
import java.util.Locale;

/**
 * What one unit out of a sack is worth, by the route the player picked.
 *
 * <h2>The three routes are three different numbers</h2>
 * <ul>
 *   <li><b>Sell Offer</b> - the top <i>ask</i>, minus sell tax. You undercut it and wait.</li>
 *   <li><b>Insta-Sell</b> - the top <i>bid</i>, minus sell tax. You take what is on the table.</li>
 *   <li><b>NPC</b> - what a shop pays, untaxed, and absent for most items.</li>
 * </ul>
 *
 * <h2>The side names are inside out, and that is the bug in this area</h2>
 * Hypixel names its two order-book summaries for the action <i>you</i> take, not for what they hold:
 * {@code buy_summary} is the <b>sell offers</b> (the asks you buy from) and {@code sell_summary} is
 * the <b>buy orders</b> (the bids you sell into). This is written out and twice-verified in
 * {@link BazaarApiClient.Product}. Swapping them here would make Insta-Sell read <i>higher</i> than
 * Sell Offer, which is backwards in a way that still looks like a plausible number - so it is worth
 * saying once more at the only place in this feature that touches them.
 *
 * <h2>Top of book, not quick status</h2>
 * {@code quick_status} carries weighted averages over the whole book, which is not what anybody
 * means by "the price". The first entry of a summary is the best level, and that is what is read.
 *
 * <h2>Warm caches only</h2>
 * {@link BazaarSnapshot#peek()} never blocks and never fetches - it returns null while the snapshot
 * is cold, which is what lets the panel say "prices loading" instead of showing a sack full of
 * zeros. Nothing in this class goes near the network.
 */
public final class SackPricing {

    /** A priced unit: {@code coins} per item, or absent when this market does not price it. */
    public record UnitPrice(long coins, boolean priced) {

        public static final UnitPrice NONE = new UnitPrice(0, false);

        public static UnitPrice of(long coins) {
            return new UnitPrice(coins, true);
        }
    }

    private SackPricing() {
    }

    /** Whether the Bazaar snapshot has anything in it - false means "loading", not "worthless". */
    public static boolean bazaarReady() {
        BazaarApiClient.Response response = BazaarSnapshot.getInstance().peek();
        return response != null && response.products != null && !response.products.isEmpty();
    }

    /**
     * What one of {@code id} is worth under {@code mode}, after tax where tax applies.
     *
     * @param id the SkyBlock item id, e.g. {@code ENCHANTED_COBBLESTONE} or {@code ROUGH_JADE_GEM}
     */
    public static UnitPrice unit(String id, SackPriceMode mode) {
        if (id == null || id.isEmpty()) {
            return UnitPrice.NONE;
        }
        String key = productId(id);
        return switch (mode) {
            case NPC -> npc(key);
            case SELL_OFFER -> taxed(topOfBook(key, true));
            case INSTA_SELL -> taxed(topOfBook(key, false));
        };
    }

    /**
     * The Bazaar product id for an item id.
     *
     * <p>Gemstones are the case this exists for: a sack lists them per tier, and each tier is its
     * own product ({@code ROUGH_JADE_GEM}, {@code FLAWED_JADE_GEM}, ...). {@link GemstoneCatalog}
     * already owns that mapping and is asked rather than re-derived; anything it does not recognise
     * is passed through unchanged, which is right for every non-gemstone sack.
     */
    static String productId(String id) {
        String upper = id.toUpperCase(Locale.ROOT);
        GemstoneCatalog.Gem gem = GemstoneCatalog.byItemId(upper);
        return gem == null ? upper : gem.bazaarId();
    }

    /**
     * The best price on one side of the book, or absent.
     *
     * @param askSide true for the sell offers ({@code buy_summary}), false for the buy orders
     *                ({@code sell_summary}) - see the class docs before touching this
     */
    private static UnitPrice topOfBook(String productId, boolean askSide) {
        BazaarApiClient.Response response = BazaarSnapshot.getInstance().peek();
        if (response == null || response.products == null) {
            return UnitPrice.NONE;
        }
        BazaarApiClient.Product product = response.products.get(productId);
        if (product == null) {
            return UnitPrice.NONE;
        }
        List<BazaarApiClient.Summary> book = askSide ? product.buy_summary : product.sell_summary;
        if (book == null || book.isEmpty()) {
            return UnitPrice.NONE;
        }
        double best = book.get(0).pricePerUnit;
        if (!(best > 0)) {
            return UnitPrice.NONE;
        }
        return UnitPrice.of(Math.round(best));
    }

    /** NPC sell value, untaxed. A catalogue entry with {@code 0} has no NPC value at all. */
    private static UnitPrice npc(String id) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(id);
        if (entry == null || !(entry.npcSellPrice > 0)) {
            return UnitPrice.NONE;
        }
        return UnitPrice.of(Math.round(entry.npcSellPrice));
    }

    /**
     * The sell tax off a Bazaar price. Both Bazaar routes pay it - a filled sell offer is taxed
     * exactly like an instant sell - and the rate is the player's existing Bazaar Flipper setting,
     * never a second copy of it.
     */
    private static UnitPrice taxed(UnitPrice gross) {
        if (!gross.priced()) {
            return UnitPrice.NONE;
        }
        double rate = LocalFlipEngine.taxRate(
                ConfigManager.getInstance().get().bazaar.bazaarFlipperLevel);
        return UnitPrice.of(Math.round(gross.coins() * (1.0 - rate)));
    }

    /** The tax rate currently in force, for the panel to name in its tooltip. */
    public static double taxRate() {
        return LocalFlipEngine.taxRate(ConfigManager.getInstance().get().bazaar.bazaarFlipperLevel);
    }

    /**
     * Which mode would pay the most for this item, or null when nothing prices it. Used only to
     * mark a row - it never changes what is being shown, which stays the mode the player chose.
     */
    public static SackPriceMode bestFor(String id) {
        SackPriceMode best = null;
        long bestCoins = 0;
        for (SackPriceMode mode : SackPriceMode.values()) {
            UnitPrice price = unit(id, mode);
            if (price.priced() && price.coins() > bestCoins) {
                bestCoins = price.coins();
                best = mode;
            }
        }
        return best;
    }
}
