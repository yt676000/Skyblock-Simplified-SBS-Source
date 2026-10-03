/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.model;

import sbs.modid.client.combat.carry.model.SlayerBoss;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Static knowledge about the slayer fights: which minibosses belong to which slayer, and the bulk
 * drops each boss showers (for attributing sack gains to the session's profit). Names are matched
 * against nametag text case-insensitively.
 */
public final class SlayerData {

    /** Miniboss display names per slayer (matched as substrings of a nametag). */
    public static final Map<SlayerBoss, List<String>> MINIBOSSES = Map.of(
            SlayerBoss.REVENANT, List.of(
                    "Revenant Sycophant", "Revenant Champion", "Deformed Revenant",
                    "Atoned Champion", "Atoned Revenant"),
            SlayerBoss.TARANTULA, List.of(
                    "Tarantula Vermin", "Tarantula Beast", "Mutant Tarantula"),
            SlayerBoss.SVEN, List.of(
                    "Pack Enforcer", "Sven Follower", "Sven Alpha"),
            SlayerBoss.VOIDGLOOM, List.of(
                    "Voidling Devotee", "Voidling Radical", "Voidcrazed Maniac"),
            SlayerBoss.INFERNO, List.of(
                    "Flare Demon", "Kindleheart Demon", "Burningsoul Demon"));

    /** Bulk drop ids per slayer: a sack "+N" of one of these while the quest runs is boss loot. */
    public static final Map<SlayerBoss, List<String>> BULK_DROPS = Map.of(
            SlayerBoss.REVENANT, List.of("REVENANT_FLESH", "FOUL_FLESH", "REVENANT_VISCERA"),
            SlayerBoss.TARANTULA, List.of("TARANTULA_WEB", "TOXIC_ARROW_POISON", "SPIDER_CATALYST"),
            SlayerBoss.SVEN, List.of("WOLF_TOOTH", "HAMSTER_WHEEL", "GRIZZLY_BAIT"),
            SlayerBoss.VOIDGLOOM, List.of("NULL_SPHERE", "TWILIGHT_ARROW_POISON", "ENCHANTED_ENDER_PEARL"),
            SlayerBoss.INFERNO, List.of("DERELICT_ASHE", "MAGMA_ARROW"),
            SlayerBoss.BLOODFIEND, List.of("COVEN_SEAL"));

    private SlayerData() {
    }

    /** The slayer a miniboss nametag belongs to, or {@code null} when the tag names none. */
    public static SlayerBoss minibossOf(String strippedNametag) {
        if (strippedNametag == null || strippedNametag.isEmpty()) {
            return null;
        }
        String lower = strippedNametag.toLowerCase(Locale.ROOT);
        for (Map.Entry<SlayerBoss, List<String>> entry : MINIBOSSES.entrySet()) {
            for (String name : entry.getValue()) {
                if (lower.contains(name.toLowerCase(Locale.ROOT))) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** Whether an item id is a known bulk drop of the given slayer. */
    public static boolean isBulkDrop(SlayerBoss boss, String itemId) {
        if (boss == null || itemId == null) {
            return false;
        }
        List<String> drops = BULK_DROPS.get(boss);
        return drops != null && drops.contains(itemId.toUpperCase(Locale.ROOT));
    }
}
