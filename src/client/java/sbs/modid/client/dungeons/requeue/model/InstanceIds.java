/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.requeue.model;

/**
 * The {@code /joininstance} ids for the Catacombs and Kuudra, and the Kuudra key item per tier.
 *
 * <p>The one copy of these tables: {@code PartyChatCommands} ({@code !f7}, {@code !m7}, {@code !t5})
 * and Auto Requeue both read them from here. The instance tables are the ones {@code !f7} has shipped
 * with for a long time; the key ids are from the bundled {@code sbs-skyblock-items.json}.
 */
public final class InstanceIds {

    private static final String[] FLOOR_NAMES = {
            "ENTRANCE", "FLOOR_ONE", "FLOOR_TWO", "FLOOR_THREE",
            "FLOOR_FOUR", "FLOOR_FIVE", "FLOOR_SIX", "FLOOR_SEVEN"};

    /** Kuudra tiers 1-5 as Hypixel names them; index 0 is unused. */
    private static final String[] KUUDRA_TIERS = {
            "", "NORMAL", "HOT", "BURNING", "FIERY", "INFERNAL"};

    /**
     * The key each tier consumes. A table, never a string built from the tier name: tier 1's key is
     * {@code KUUDRA_TIER_KEY}, not {@code KUUDRA_NORMAL_TIER_KEY}.
     */
    private static final String[] KUUDRA_KEYS = {
            "", "KUUDRA_TIER_KEY", "KUUDRA_HOT_TIER_KEY", "KUUDRA_BURNING_TIER_KEY",
            "KUUDRA_FIERY_TIER_KEY", "KUUDRA_INFERNAL_TIER_KEY"};

    public static final int MAX_FLOOR = 7;
    public static final int MAX_KUUDRA_TIER = 5;

    private InstanceIds() {
    }

    /** {@code CATACOMBS_FLOOR_SIX} / {@code MASTER_CATACOMBS_FLOOR_SIX}; {@code null} out of range (no M0). */
    public static String dungeon(boolean master, int floor) {
        if (floor < (master ? 1 : 0) || floor > MAX_FLOOR) {
            return null;
        }
        return (master ? "MASTER_CATACOMBS_" : "CATACOMBS_") + FLOOR_NAMES[floor];
    }

    /** {@code KUUDRA_NORMAL} ... {@code KUUDRA_INFERNAL}; {@code null} outside 1-5. */
    public static String kuudra(int tier) {
        return tier < 1 || tier > MAX_KUUDRA_TIER ? null : "KUUDRA_" + KUUDRA_TIERS[tier];
    }

    /** The SkyBlock item id of the key a tier needs; {@code null} outside 1-5. */
    public static String kuudraKey(int tier) {
        return tier < 1 || tier > MAX_KUUDRA_TIER ? null : KUUDRA_KEYS[tier];
    }
}
