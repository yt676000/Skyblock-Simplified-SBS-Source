/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.casing.logic;

import sbs.modid.client.dungeons.casing.model.CaseItem;
import sbs.modid.client.core.item.Rarity;

import java.util.List;

/**
 * Dungeon reward-chest loot pools for the case-opening reel.
 *
 * <p>Every id here was checked against Hypixel's live item resource, so each renders its real icon
 * rather than a fallback. A pool is only the set of items that can appear on the reel – it never
 * encodes drop rates and never decides what the player receives; that is fixed by the server before
 * the animation starts. The rarity on each item just drives how often it shows up as filler and how
 * loud its landing effect is.
 *
 * <p>Kept as a plain table so more floors are one entry away.
 */
public final class DungeonLoot {

    private DungeonLoot() {
    }

    /**
     * Master Mode Catacombs Floor 7 – the full M7 drop set. The four Wither armour sets
     * (Necron's/Storm's/Goldor's/Maxor's), Necron's blades, the dungeon scrolls, master stars, and
     * the rare specials (Dark Claymore, Auto Recombobulator).
     */
    public static final List<CaseItem> M7 = List.of(
            // Specials / weapons
            new CaseItem("DARK_CLAYMORE", Rarity.LEGENDARY),
            new CaseItem("NECRON_HANDLE", Rarity.LEGENDARY),
            new CaseItem("NECRON_BLADE", Rarity.LEGENDARY),
            new CaseItem("AUTO_RECOMBOBULATOR", Rarity.LEGENDARY),
            // Necron's armour (Power)
            new CaseItem("POWER_WITHER_HELMET", Rarity.LEGENDARY),
            new CaseItem("POWER_WITHER_CHESTPLATE", Rarity.LEGENDARY),
            new CaseItem("POWER_WITHER_LEGGINGS", Rarity.LEGENDARY),
            new CaseItem("POWER_WITHER_BOOTS", Rarity.LEGENDARY),
            // Storm's armour (Wise)
            new CaseItem("WISE_WITHER_HELMET", Rarity.LEGENDARY),
            new CaseItem("WISE_WITHER_CHESTPLATE", Rarity.LEGENDARY),
            new CaseItem("WISE_WITHER_LEGGINGS", Rarity.LEGENDARY),
            new CaseItem("WISE_WITHER_BOOTS", Rarity.LEGENDARY),
            // Goldor's armour (Tank)
            new CaseItem("TANK_WITHER_HELMET", Rarity.LEGENDARY),
            new CaseItem("TANK_WITHER_CHESTPLATE", Rarity.LEGENDARY),
            new CaseItem("TANK_WITHER_LEGGINGS", Rarity.LEGENDARY),
            new CaseItem("TANK_WITHER_BOOTS", Rarity.LEGENDARY),
            // Maxor's armour (Speed)
            new CaseItem("SPEED_WITHER_HELMET", Rarity.LEGENDARY),
            new CaseItem("SPEED_WITHER_CHESTPLATE", Rarity.LEGENDARY),
            new CaseItem("SPEED_WITHER_LEGGINGS", Rarity.LEGENDARY),
            new CaseItem("SPEED_WITHER_BOOTS", Rarity.LEGENDARY),
            // Scrolls
            new CaseItem("IMPLOSION_SCROLL", Rarity.EPIC),
            new CaseItem("SHADOW_WARP_SCROLL", Rarity.EPIC),
            new CaseItem("WITHER_SHIELD_SCROLL", Rarity.EPIC),
            // Master stars
            new CaseItem("FIRST_MASTER_STAR", Rarity.EPIC),
            new CaseItem("SECOND_MASTER_STAR", Rarity.EPIC),
            new CaseItem("THIRD_MASTER_STAR", Rarity.EPIC),
            new CaseItem("FOURTH_MASTER_STAR", Rarity.EPIC),
            new CaseItem("FIFTH_MASTER_STAR", Rarity.EPIC),
            // Materials
            new CaseItem("WITHER_BLOOD", Rarity.EPIC),
            new CaseItem("WITHER_CATALYST", Rarity.RARE),
            new CaseItem("NECRONS_LADDER", Rarity.RARE));
}
