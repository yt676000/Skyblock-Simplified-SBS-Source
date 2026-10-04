/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.helper.loadouts;

import net.minecraft.world.entity.Entity;

/**
 * The client-only entities the mod builds to draw a model inside a GUI (the Loadouts preview player,
 * the Armor Sets stand). They are never added to the level and sit at 0,0,0, so vanilla's nametag
 * checks treat them like any other entity - a preview near the world origin would otherwise get the
 * full floating name drawn across its card. Nametag mixins ask {@link #isPreview} and stay off them.
 *
 * <p>The ids are negative because the server only hands out positive entity ids, so a real entity
 * can never collide with one.
 */
public final class PreviewEntities {

    /** The Loadouts overlay / Equipped Loadout card preview player. */
    public static final int LOADOUT_PLAYER_ID = -0x5B5D;

    /** The Armor Sets overlay's armor stand. */
    public static final int ARMOR_STAND_ID = -0x5B5E;

    private PreviewEntities() {
    }

    /** Whether this id belongs to one of the GUI preview entities. */
    public static boolean isPreviewId(int id) {
        return id == LOADOUT_PLAYER_ID || id == ARMOR_STAND_ID;
    }

    /** Whether this entity is a GUI preview model (never a real player). */
    public static boolean isPreview(Entity entity) {
        return entity != null && isPreviewId(entity.getId());
    }
}
