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
 *   <li><b>Special top tiers</b> come only from drops, NPCs or upgrade items (e.g. Smite VII =
 *       Smite VI + Severed Hand, Sharpness/Critical/Growth VII from drops/Dark Auction) – never
 *       from combining two books of the tier below. Drop tiers price as their own book; upgrade
 *       tiers (see {@link #UPGRADES}) as the required book plus the item.</li>
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

    /**
     * An enchant-upgrade item: applied to an item that already has {@code requiredTier}, it raises
     * the enchant by one level, up to {@code maxTier} (once for most, Silex up to Efficiency X).
     */
    record Upgrade(int requiredTier, int maxTier, String itemId, String itemLabel) {

        /** Upgrade items needed to reach {@code tier}, or 0 when this upgrade does not produce it. */
        int countFor(int tier) {
            return tier > requiredTier && tier <= maxTier ? tier - requiredTier : 0;
        }

        /** Row note, e.g. "= 6 + End Stone Idol" or "= 5 + 3x Silex". */
        String note(int tier) {
            int count = countFor(tier);
            return "= " + requiredTier + " + " + (count > 1 ? count + "x " : "") + itemLabel;
        }
    }

    /**
     * Every enchant-upgrade item, collected from the repo lore ("increases its X level by 1 …
     * requires X N", {@code run/config/sbs/repo/Repo_Item_Lore.json}, 2026-11-22). Their levels
     * have no book with real orders (ENCHANTMENT_ENDER_SLAYER_7 sits on the Bazaar at 0/0), so they
     * are priced as the required tier's book plus the item(s); the tier's own book still competes
     * and the cheaper wins. A new upgrade item = one line here.
     */
    private static final Map<String, Upgrade> UPGRADES = Map.ofEntries(
            Map.entry("ender_slayer", new Upgrade(6, 7, "ENDSTONE_IDOL", "End Stone Idol")),
            Map.entry("smite", new Upgrade(6, 7, "SEVERED_HAND", "Severed Hand")),
            Map.entry("bane_of_arthropods", new Upgrade(6, 7, "ENSNARED_SNAIL", "Ensnared Snail")),
            Map.entry("venomous", new Upgrade(6, 7, "FATEFUL_STINGER", "Fateful Stinger")),
            Map.entry("scavenger", new Upgrade(5, 6, "GOLDEN_BOUNTY", "Golden Bounty")),
            Map.entry("luck_of_the_sea", new Upgrade(6, 7, "GOLD_BOTTLE_CAP", "Gold Bottle Cap")),
            Map.entry("piscary", new Upgrade(6, 7, "TROUBLED_BUBBLE", "Troubled Bubble")),
            Map.entry("spiked_hook", new Upgrade(6, 7, "OCTOPUS_TENDRIL", "Octopus Tendril")),
            Map.entry("frail", new Upgrade(6, 7, "SEVERED_PINCER", "Severed Pincer")),
            Map.entry("forest_pledge", new Upgrade(5, 6, "WATER_HYACINTH", "Water Hyacinth")),
            Map.entry("stealth", new Upgrade(5, 6, "DISTANT_ECHO", "Distant Echo")),
            Map.entry("scuba", new Upgrade(5, 6, "VIBRANT_CORAL", "Vibrant Coral")),
            Map.entry("charm", new Upgrade(5, 6, "CHAIN_END_TIMES", "Chain of the End Times")),
            Map.entry("karma", new Upgrade(5, 6, "FORESTS_FAVOR", "Forest's Favor")),
            Map.entry("pesterminator", new Upgrade(5, 6, "PESTHUNTING_GUIDE", "Pesthunting Guide")),
            Map.entry("thorns", new Upgrade(3, 4, "PRICKLY_CREEPER", "Prickly Creeper")),
            Map.entry("efficiency", new Upgrade(5, 10, "SIL_EX", "Silex")));

    private EnchantMarketRules() {
    }

    /** True for stacking enchants (any applied tier is worth one base book). */
    static boolean isStacking(String enchantKey) {
        return STACKING.contains(normalize(enchantKey));
    }

    /** The upgrade item that produces this enchant tier, or {@code null} for book-only tiers. */
    static Upgrade upgradeFor(String enchantKey, int tier) {
        Upgrade upgrade = UPGRADES.get(normalize(enchantKey));
        return upgrade != null && upgrade.countFor(tier) > 0 ? upgrade : null;
    }

    /**
     * An upgrade tier's price: the cheaper of its own book and "required-tier book + n upgrade
     * items"; {@code null} when neither side is fully priced (an unpriced part never counts as 0).
     */
    static Long upgradedPrice(Upgrade upgrade, int tier, Long ownBook, Long baseBook, Long itemUnit) {
        Long composed = baseBook == null || itemUnit == null
                ? null : baseBook + itemUnit * upgrade.countFor(tier);
        if (ownBook == null) {
            return composed;
        }
        return composed == null ? ownBook : Math.min(ownBook, composed);
    }

    /** The highest tier of this enchant an anvil combine can produce (no limit when unlisted). */
    static int maxCombinableTier(String enchantKey) {
        return MAX_COMBINE.getOrDefault(normalize(enchantKey), Integer.MAX_VALUE);
    }

    private static String normalize(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT);
    }
}
