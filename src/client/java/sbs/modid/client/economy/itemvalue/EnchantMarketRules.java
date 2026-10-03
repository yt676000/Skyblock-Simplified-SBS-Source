/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.itemvalue;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Market rules for enchanted-book pricing – which enchants can and cannot be built by
 * anvil-combining lower-tier books (Hypixel wiki, July 2026):
 * <ul>
 *   <li><b>Stacking enchants</b> are applied as their base book and level purely through
 *       gameplay (Champion via kills, Hecatomb via S runs, Expertise via fishing, Compact via
 *       mining, Cultivating via farming, Toxophilite via bow combat XP, Absorb via foraging,
 *       Pesterminator via pests). Higher tiers never exist as books, so the market value of ANY
 *       applied tier is exactly one base book – never {@code 2^(T-1)} copies.</li>
 *   <li><b>Special top tiers</b> come only from drops, NPCs or item crafts (e.g. Smite VII =
 *       Smite VI + Severed Hand, Sharpness/Critical/Growth VII from drops/Dark Auction) – never
 *       from combining two books of the tier below. Their real cost is the top-tier book's own
 *       market price, which already embeds the special ingredient/drop value.</li>
 * </ul>
 * Enchants not listed here follow the standard anvil rule (two equal books → one book of the
 * next tier), which keeps tier composition valid for ultimates (Chimera V = 16× Chimera I) and
 * ordinary table enchants.
 */
final class EnchantMarketRules {

    /** Applied at base tier and leveled by gameplay – price any tier as ONE base book. */
    private static final Set<String> STACKING = Set.of(
            "champion", "compact", "cultivating", "expertise", "hecatomb",
            "toxophilite", "pesterminator", "absorb");

    /**
     * Enchant → highest tier an anvil combine can produce; every tier above it is drop/craft-only
     * and must be priced as its own book. Unlisted enchants have no combine limit.
     */
    private static final Map<String, Integer> MAX_COMBINE = Map.ofEntries(
            Map.entry("sharpness", 6),        // VII: drop only
            Map.entry("smite", 6),            // VII: Smite VI + Severed Hand
            Map.entry("critical", 6),         // VII: drop only
            Map.entry("ender_slayer", 6),     // VII: drop only
            Map.entry("giant_killer", 6),     // VII: drop only
            Map.entry("titan_killer", 6),     // VII: drop only
            Map.entry("growth", 6),           // VII: Dark Auction / drops
            Map.entry("protection", 6),       // VII: drop only
            Map.entry("power", 6),            // VII: drop only
            Map.entry("luck", 6),             // VII: drop only
            Map.entry("luck_of_the_sea", 6),  // VII: drop only
            Map.entry("thunderlord", 6),      // VII: drop only
            Map.entry("lethality", 5),        // VI: drop only
            Map.entry("venomous", 5),         // VI: Experiments only; VII: VI + Fateful Stinger
            Map.entry("scavenger", 5),        // VI: drop only
            Map.entry("looting", 4),          // V: drop only
            Map.entry("life_steal", 4),       // V: NPC / drop only
            Map.entry("syphon", 4),           // V: drop only
            Map.entry("first_strike", 4),     // V: drop only
            Map.entry("triple_strike", 4),    // V: drop only
            Map.entry("overload", 4),         // V: dungeon drop only
            Map.entry("mana_steal", 2),       // III: slayer drop only
            Map.entry("feather_falling", 6),  // VII+: drop only
            Map.entry("infinite_quiver", 6),  // VII+: drop only
            Map.entry("divine_gift", 1));     // II/III: drop only

    /** A top tier that is crafted as "lower-tier book + a special item" instead of combined. */
    record CraftedTier(int baseTier, String itemId, String itemLabel) {
    }

    /**
     * NOTE (maintenance): Hypixel keeps introducing top tiers that are crafted as
     * "book + special item". Entries here are <b>competing fallbacks</b>, not overrides: the value
     * service prices BOTH the tier's own book (when the market knows it) and this craft
     * composition, and the cheaper one wins – so adding an entry is always safe, and when a new
     * game update introduces another such craft it just needs one line here (wrong/missing ids
     * simply lose the competition). Known crafts:
     */
    private static final Map<String, CraftedTier> CRAFTED_TIERS = Map.of(
            "smite:7", new CraftedTier(6, "SEVERED_HAND", "Severed Hand"),
            "venomous:7", new CraftedTier(6, "FATEFUL_STINGER", "Fateful Stinger"),
            "scavenger:6", new CraftedTier(5, "GOLDEN_BOUNTY", "Golden Bounty"),
            "ender_slayer:7", new CraftedTier(6, "END_STONE_IDOL", "End Stone Idol"));

    private EnchantMarketRules() {
    }

    /** True for stacking enchants (any applied tier is worth one base book). */
    static boolean isStacking(String enchantKey) {
        return STACKING.contains(normalize(enchantKey));
    }

    /** The "book + item" craft of this enchant tier, or {@code null} for normal market tiers. */
    static CraftedTier craftedTier(String enchantKey, int tier) {
        return CRAFTED_TIERS.get(normalize(enchantKey) + ":" + tier);
    }

    /** The highest tier of this enchant an anvil combine can produce (no limit when unlisted). */
    static int maxCombinableTier(String enchantKey) {
        return MAX_COMBINE.getOrDefault(normalize(enchantKey), Integer.MAX_VALUE);
    }

    private static String normalize(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT);
    }
}
