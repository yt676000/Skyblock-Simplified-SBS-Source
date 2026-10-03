/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.itemvalue;

import net.minecraft.world.item.ItemStack;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.ChatPriceCache;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.economy.prices.LbinCache;

import java.util.List;
import java.util.Locale;

/**
 * What an item is worth, answered <b>instantly and offline</b>: the market price of the item plus
 * every priced thing applied onto it (stars, books, gems, the reforge stone, runes, potato books…).
 *
 * <p><b>Why this exists next to {@link ItemValueService}.</b> That service answers the same question
 * far more thoroughly – full price history, five time windows, per-component rows – by asking the
 * price API once per component. That is right for "check this one item", and impossible for "put a
 * number on every slot of the chest I just opened": a double chest is a hundred stacks and several
 * hundred components. This class answers from the caches the mod already keeps warm ({@link LbinCache},
 * {@link BazaarPriceCache}, {@link ChatPriceCache}), so a whole container costs map lookups and no
 * network at all. The trade-off is deliberate: one number, priced now, no history.
 *
 * <p><b>Two sides, asked for explicitly.</b> "What is this worth" and "what would this cost me" are
 * different questions and the Bazaar spread is the difference between them, so {@link Side} is a
 * required argument rather than a default: {@link #of} values a stack you own on the {@link Side#SELL}
 * side, while a build/craft cost is {@link Side#BUY}. Lowest BIN answers both for auctionables –
 * there is only the one price.
 *
 * <p><b>Nothing unpriceable is counted as zero.</b> An item whose id no market knows is reported as
 * a miss ({@link Appraisal#unpricedParts}), never as a worthless one, so a total can be labelled as
 * the understatement it is instead of quietly claiming a Spirit Sword is free.
 */
public final class ItemAppraisal {

    /** Bazaar enchantment products are keyed {@code ENCHANTMENT_<ENCHANT>_<TIER>}. */
    private static final String BOOK_PREFIX = "ENCHANTMENT_";

    /** Which end of the market a price is read from. */
    public enum Side {
        /** What you could get for it: lowest BIN, or the Bazaar instant-<b>sell</b> price. */
        SELL,
        /** What it would cost you: lowest BIN, or the Bazaar instant-<b>buy</b> price. */
        BUY
    }

    /** A valued stack: the worth of one item, of the whole stack, and what could not be priced. */
    public record Appraisal(long unit, long total, int pricedParts, int unpricedParts) {

        public static final Appraisal NONE = new Appraisal(0, 0, 0, 0);

        /** Whether anything at all could be priced (a total of 0 is otherwise meaningless). */
        public boolean priced() {
            return pricedParts > 0;
        }

        /** Whether every component resolved – if not, {@link #total} is a floor, not a figure. */
        public boolean complete() {
            return unpricedParts == 0;
        }
    }

    private ItemAppraisal() {
    }

    /**
     * Values one stack: the base item (times its stack size) plus its modifiers.
     *
     * <p>Modifiers are counted <b>once</b>, not once per item in the stack: anything carrying
     * ExtraAttributes worth pricing is a unique item that never stacks in the first place, so
     * multiplying them would only ever be wrong.
     */
    public static Appraisal of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Appraisal.NONE;
        }
        long unit = 0;
        int priced = 0;
        int unpriced = 0;

        // The base item gets the whole candidate list (SkyBlock id, pet/book/rune form, display
        // name, either shard spelling), because that is what makes a custom Hypixel item resolve at
        // all. Through ItemPriceKey rather than PriceLookup directly, so this path and the two
        // hunting screens resolve a stack identically - which they did not, and shards paid for it.
        List<String> candidates = ItemPriceKey.keysFor(stack);
        Long base = firstPrice(candidates, Side.SELL);
        if (base != null) {
            unit += base;
            priced++;
        } else {
            unpriced++;
        }

        String baseId = candidates.isEmpty() ? null : candidates.get(0);
        for (ItemModifiers.Part part : ItemModifiers.parseModifiers(stack, baseId)) {
            Long value = partPrice(part, Side.SELL);
            if (value == null) {
                unpriced++;
                continue;
            }
            unit += value;
            priced++;
        }

        long baseValue = base == null ? 0L : base;
        int count = Math.max(1, stack.getCount());
        return new Appraisal(unit, unit + baseValue * (count - 1), priced, unpriced);
    }

    /**
     * What one {@link ItemModifiers.Part} is worth, already scaled by its count – the single place
     * that knows a book part prices by tier composition while everything else prices per unit.
     *
     * @return the part's total value, or {@code null} when no market knows it
     */
    public static Long partPrice(ItemModifiers.Part part, Side side) {
        if (part == null) {
            return null;
        }
        if (part.isBook()) {
            // The composition factor is already inside bookPrice; a book part's count is always 1.
            return bookPrice(part.enchantKey, part.enchantTier, side);
        }
        Long unit = part.itemId == null ? null : price(part.itemId, side);
        return unit == null ? null : Math.round(unit * part.count);
    }

    /**
     * The market value of an applied enchantment under {@link EnchantMarketRules}, or {@code null}
     * when no tier of it is on the Bazaar.
     *
     * <p>Mirrors {@link ItemValueService}'s book logic against the local Bazaar snapshot: stacking
     * enchants are one base book however high they have been levelled, tiers above an enchant's
     * combine limit are drop-only and price as their own book, and everything else takes the
     * cheapest way of building tier T – {@code 2^(T-k)} books of any lower tier k that exists.
     */
    public static Long bookPrice(String enchantKey, int tier, Side side) {
        if (enchantKey == null || tier <= 0) {
            return null;
        }
        String key = enchantKey.toUpperCase(Locale.ROOT);
        if (EnchantMarketRules.isStacking(enchantKey)) {
            for (int k = 1; k <= tier; k++) {
                Long value = price(BOOK_PREFIX + key + "_" + k, side);
                if (value != null) {
                    return value;   // levelled by gameplay: any applied tier is worth one book
                }
            }
            return null;
        }
        if (tier > EnchantMarketRules.maxCombinableTier(enchantKey)) {
            return price(BOOK_PREFIX + key + "_" + tier, side);
        }
        Long best = null;
        for (int k = 1; k <= tier; k++) {
            Long value = price(BOOK_PREFIX + key + "_" + k, side);
            if (value == null) {
                continue;
            }
            long composed = Math.round(value * Math.pow(2, tier - k));
            if (best == null || composed < best) {
                best = composed;
            }
        }
        return best;
    }

    /** The first candidate id any market knows a price for, or {@code null}. */
    public static Long firstPrice(List<String> candidates, Side side) {
        for (String candidate : candidates) {
            Long value = price(candidate, side);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * One id's current price: lowest BIN first (the Auction House is where anything with modifiers
     * actually changes hands, and it has only the one price), then the Bazaar on the requested
     * {@link Side}, then the persistent chat-order cache as a last resort for products the Bazaar
     * snapshot has not covered yet.
     *
     * @return the unit price, or {@code null} when no market knows this id
     */
    public static Long price(String itemId, Side side) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String id = itemId.toUpperCase(Locale.ROOT);
        Long lbin = LbinCache.getInstance().getLbin(id);
        if (lbin != null && lbin > 0) {
            return lbin;
        }
        Long bazaar = bazaarPrice(id, side);
        if (bazaar != null) {
            return bazaar;
        }
        // The other shard spelling, and only after the id's own has missed. A caller that derived
        // this id from a display name has "<NAME>_SHARD" where the Bazaar keys "SHARD_<NAME>"; the
        // non-attribute shards are keyed the other way round and have already hit above.
        String alias = ItemPriceKey.shardAlias(id);
        if (alias != null) {
            Long aliased = bazaarPrice(alias, side);
            if (aliased != null) {
                return aliased;
            }
        }
        Long chat = ChatPriceCache.getInstance().getUnitPrice(id);
        return chat != null && chat > 0 ? chat : null;
    }

    /** One id's Bazaar price on the requested side, or {@code null} when the book says nothing. */
    private static Long bazaarPrice(String id, Side side) {
        BazaarPriceCache.BzPrice bazaar = BazaarPriceCache.getInstance().get(id);
        if (bazaar == null) {
            return null;
        }
        long value = side == Side.BUY ? bazaar.buy() : bazaar.sell();
        return value > 0 ? value : null;
    }
}
