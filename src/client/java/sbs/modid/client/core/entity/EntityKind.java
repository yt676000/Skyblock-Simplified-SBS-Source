/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.entity;

/** The bucket an entity is filed under in {@link EntityIndex}; fixed for the entity's life. */
public enum EntityKind {
    ARMOR_STAND,
    /** Players, real and fake alike - real-ness can change after load, so it is asked per use. */
    PLAYER,
    /** Every other living entity: mobs, animals. */
    LIVING,
    ITEM,
    OTHER
}
