/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.wishlist.logic;

import sbs.modid.client.economy.wishlist.model.AuctionItem;
import sbs.modid.client.economy.wishlist.model.WishlistEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether one auctioned item satisfies one wishlist entry. Pure: no game state, no config,
 * no network, so every rule here is covered by {@code WishlistMatcherTest}.
 *
 * <p><b>Minimums, not equality.</b> Every positive criterion asks "at least": a higher enchant level,
 * more stars, more books, more open slots or a better gem than asked for still matches, and anything
 * the entry does not mention is ignored. Exclusion is the blacklist's job alone, so the two never
 * contradict each other: a criterion can only add a requirement, a blacklist token can only remove
 * a candidate.
 */
public final class WishlistMatcher {

    /** Gem qualities, worst first. The index is the rank compared against a requirement. */
    public static final List<String> QUALITIES = List.of("ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT");

    private WishlistMatcher() {
    }

    /**
     * The outcome: {@code matched}, or the first criterion that failed, worded for a debug line.
     *
     * @param matched whether the item satisfies the entry
     * @param reason  empty on a match, otherwise what was missing or excluded
     */
    public record Result(boolean matched, String reason) {

        static final Result MATCH = new Result(true, "");

        static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * Checks {@code item} listed at {@code price} against {@code entry}.
     *
     * <p>The item's identity is checked by id when both sides have one. Matching by name is the
     * caller's prefilter (the crawl only decodes auctions whose name key is on the wishlist), so an
     * entry typed by hand, which has no id, is not rejected here for lacking one.
     */
    public static Result match(WishlistEntry entry, AuctionItem item, long price) {
        if (entry == null || item == null) {
            return Result.fail("nothing to compare");
        }
        if (!isBlank(entry.itemId) && !isBlank(item.itemId())
                && !entry.itemId.equalsIgnoreCase(item.itemId())) {
            return Result.fail("different item " + item.itemId());
        }
        if (entry.maxPrice > 0 && price > entry.maxPrice) {
            return Result.fail("price " + price + " above " + entry.maxPrice);
        }
        Result blocked = blacklisted(entry.blacklist, item);
        if (blocked != null) {
            return blocked;
        }
        if (entry.enchants != null) {
            for (var want : entry.enchants.entrySet()) {
                String key = want.getKey().toLowerCase(Locale.ROOT);
                int have = item.enchants().getOrDefault(key, 0);
                if (have < want.getValue()) {
                    return Result.fail("enchant " + key + " " + have + " < " + want.getValue());
                }
            }
        }
        if (!isBlank(entry.reforge) && !entry.reforge.equalsIgnoreCase(item.reforge())) {
            return Result.fail("reforge " + (item.reforge().isEmpty() ? "none" : item.reforge()));
        }
        if (item.stars() < entry.minStars) {
            return Result.fail("stars " + item.stars() + " < " + entry.minStars);
        }
        if (entry.recomb && !item.recombobulated()) {
            return Result.fail("not recombobulated");
        }
        if (item.potatoBooks() < entry.minPotatoBooks) {
            return Result.fail("potato books " + item.potatoBooks() + " < " + entry.minPotatoBooks);
        }
        int open = openSlots(item).size();
        if (open < entry.minGemSlots) {
            return Result.fail("gem slots " + open + " < " + entry.minGemSlots);
        }
        String gemMiss = missingGem(entry.gems, item.gems());
        if (gemMiss != null) {
            return Result.fail(gemMiss);
        }
        return Result.MATCH;
    }

    /**
     * Slots that count as open: listed in {@code unlocked_slots}, or holding a gem.
     *
     * <p>A slot that is open by default (no unlock cost) is in neither list until a gem goes in, so
     * an empty free slot is not counted. That under-counts, which can only cost a match, never cause
     * a wrong one.
     */
    static Set<String> openSlots(AuctionItem item) {
        Set<String> open = new HashSet<>(item.unlockedSlots());
        for (AuctionItem.Gem gem : item.gems()) {
            open.add(gem.slot());
        }
        return open;
    }

    /**
     * Assigns applied gems to the requirements, each gem used once, and names the first requirement
     * that cannot be met; {@code null} when all are.
     *
     * <p>The strictest requirement is served first and takes the <i>weakest</i> gem that still
     * satisfies it, which keeps the better gems for the requirements after it. For thresholds on one
     * ordered scale that greedy choice finds an assignment whenever one exists.
     */
    static String missingGem(List<WishlistEntry.GemRequirement> wanted, List<AuctionItem.Gem> applied) {
        if (wanted == null || wanted.isEmpty()) {
            return null;
        }
        List<WishlistEntry.GemRequirement> order = new ArrayList<>(wanted);
        // Specific types before ANY, then the higher quality first.
        order.sort(Comparator.<WishlistEntry.GemRequirement>comparingInt(r -> isAny(r.type) ? 1 : 0)
                .thenComparing(r -> -rank(r.minQuality)));
        List<AuctionItem.Gem> pool = new ArrayList<>(applied);
        pool.sort(Comparator.comparingInt(g -> rank(g.quality())));
        for (WishlistEntry.GemRequirement req : order) {
            int need = Math.max(1, req.count);
            int minRank = rank(req.minQuality);
            for (int n = 0; n < need; n++) {
                AuctionItem.Gem pick = null;
                for (AuctionItem.Gem gem : pool) {
                    if (rank(gem.quality()) >= minRank
                            && (isAny(req.type) || req.type.equalsIgnoreCase(gem.type()))) {
                        pick = gem;
                        break;
                    }
                }
                if (pick == null) {
                    return "missing " + need + "x " + req.minQuality.toLowerCase(Locale.ROOT) + " "
                            + req.type.toLowerCase(Locale.ROOT);
                }
                pool.remove(pick);
            }
        }
        return null;
    }

    /** The first blacklist token the item hits, as a failed result; {@code null} when none does. */
    static Result blacklisted(List<String> blacklist, AuctionItem item) {
        if (blacklist == null) {
            return null;
        }
        for (String raw : blacklist) {
            if (isBlank(raw)) {
                continue;
            }
            String token = raw.trim().toLowerCase(Locale.ROOT);
            int colon = token.indexOf(':');
            String kind = colon < 0 ? token : token.substring(0, colon).trim();
            String value = colon < 0 ? "" : token.substring(colon + 1).trim();
            boolean hit = switch (kind) {
                // Skin ids carry the item's name around the skin's ("..._SKIN_CYBERPUNK"), so a
                // named skin matches as a part of the id rather than the whole of it.
                case "skin" -> value.isEmpty() ? !item.skin().isEmpty() : item.skin().contains(value);
                // Dye ids are "DYE_<NAME>"; the player writes the name with or without the prefix.
                case "dye" -> value.isEmpty() ? !item.dye().isEmpty() : stripDye(value).equals(stripDye(item.dye()));
                case "reforge" -> !value.isEmpty() && value.equalsIgnoreCase(item.reforge());
                case "enchant" -> !value.isEmpty() && item.enchants().getOrDefault(value, 0) > 0;
                case "gem" -> !value.isEmpty()
                        && item.gems().stream().anyMatch(g -> value.equalsIgnoreCase(g.type()));
                default -> false;
            };
            if (hit) {
                return Result.fail("blacklisted " + token);
            }
        }
        return null;
    }

    private static String stripDye(String id) {
        return id.startsWith("dye_") ? id.substring(4) : id;
    }

    /** Rank of a quality on {@link #QUALITIES}; unknown qualities rank below rough. */
    static int rank(String quality) {
        return quality == null ? -1 : QUALITIES.indexOf(quality.toUpperCase(Locale.ROOT));
    }

    private static boolean isAny(String type) {
        return isBlank(type) || "ANY".equalsIgnoreCase(type);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
