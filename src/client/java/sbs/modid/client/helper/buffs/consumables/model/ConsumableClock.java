/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.model;

/** When a consumable's remaining time goes down. */
public enum ConsumableClock {
    /** Only while the player is on SkyBlock, and (if the timer says so) not inside a dungeon. */
    ONLINE_ONLY,
    /** By the wall clock, including while the game is closed. */
    REAL_TIME,
    /** Only while the parent timer exists and is itself counting down (mixins under a God Potion). */
    DEPENDENT
}
