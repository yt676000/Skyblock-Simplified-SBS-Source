/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.hitboxes;

/**
 * What an entity is, for Entity Hitboxes: one toggle and one colour per category. The decision
 * ({@link #classify}) takes plain facts so it is unit-tested; {@link EntityHitboxes} gathers them
 * from the live entity with the mod's existing helpers.
 */
public enum HitboxCategory {
    PLAYER(0xFFFFFF),
    SELF(0xFFFFFF),
    SKYBLOCK_MOB(0xFF5555),
    PASSIVE(0x55FF55),
    HOSTILE(0xFFAA00),
    ARMOR_STAND(0xAAAAAA),
    ITEM(0xFFFF55),
    PROJECTILE(0x55FFFF),
    /** A player-shaped entity that is not on the tab list - drawn as Other when NPCs are not hidden. */
    NPC(0xFFFFFF),
    OTHER(0xFFFFFF);

    private static final HitboxCategory[] VALUES = values();

    private final int defaultRgb;

    HitboxCategory(int defaultRgb) {
        this.defaultRgb = defaultRgb;
    }

    public int defaultRgb() {
        return defaultRgb;
    }

    static HitboxCategory byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    /**
     * The category from facts about the entity, first match wins.
     *
     * @param self          the local player itself
     * @param playerShaped  rendered as a player (an {@code Avatar})
     * @param realPlayer    on the tab list ({@code RealPlayers.isRealPlayer})
     * @param armorStand    an armour stand
     * @param item          a dropped item
     * @param projectile    an arrow, pearl, fireball ...
     * @param living        a living entity
     * @param skyblockNamed carries a SkyBlock mob nametag, on itself or on the stand above it
     * @param hostile       a vanilla hostile mob ({@code Enemy})
     */
    public static HitboxCategory classify(boolean self, boolean playerShaped, boolean realPlayer,
                                          boolean armorStand, boolean item, boolean projectile, boolean living,
                                          boolean skyblockNamed, boolean hostile) {
        if (self) {
            return SELF;
        }
        if (playerShaped) {
            return realPlayer ? PLAYER : NPC;
        }
        if (armorStand) {
            return ARMOR_STAND;
        }
        if (item) {
            return ITEM;
        }
        if (projectile) {
            return PROJECTILE;
        }
        if (living) {
            if (skyblockNamed) {
                return SKYBLOCK_MOB;
            }
            return hostile ? HOSTILE : PASSIVE;
        }
        return OTHER;
    }
}
