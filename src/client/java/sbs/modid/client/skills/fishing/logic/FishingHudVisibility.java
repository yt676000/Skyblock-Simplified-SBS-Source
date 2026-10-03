/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import sbs.modid.client.skills.fishing.render.FishingHud;
import sbs.modid.client.skills.fishing.render.SeaCreatureListHud;

/**
 * When the fishing tracker panels ({@link FishingHud}, {@link SeaCreatureListHud}) are drawn.
 *
 * <p>Split out of the panels themselves because both share the rule and the player picks it once in
 * the Fishing settings.
 */
public enum FishingHudVisibility {

    /** Holding a rod, or the session collected something and has not gone quiet since. */
    ROD_OR_DATA("Rod or Data"),

    /** Only while a rod is actually in hand; the panel disappears the moment it is put away. */
    ROD_ONLY("Only with Rod"),

    /** Always on screen while the module is enabled, rod or not. */
    ALWAYS("Always");

    private final String displayName;

    FishingHudVisibility(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The next mode in the cycle (used by the settings cycle button). */
    public FishingHudVisibility next() {
        FishingHudVisibility[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /**
     * Whether a panel should be drawn right now.
     *
     * <p>{@code hasData} alone is not enough for {@link #ROD_OR_DATA}: a session keeps its catches
     * for as long as the game runs, so once anything has been caught that flag never goes false
     * again and the panels stayed on screen for the rest of the session, wherever you went. It is
     * therefore paired with {@link FishingTracker#sessionIdle()}, so the data has to be from a
     * session that is still going. Nothing is lost when they hide – the numbers are still there and
     * the panels return on the next cast.
     *
     * @param hasData true when the panel has something of its own to show (creatures, catches, ...)
     */
    public boolean shouldRender(Player player, boolean hasData) {
        return switch (this) {
            case ALWAYS -> true;
            case ROD_ONLY -> holdingRod(player);
            case ROD_OR_DATA -> holdingRod(player)
                    || (hasData && !FishingTracker.getInstance().sessionIdle());
        };
    }

    /** True while a fishing rod (every SkyBlock rod is a rod item) is in either hand. */
    private static boolean holdingRod(Player player) {
        return player.getMainHandItem().getItem() == Items.FISHING_ROD
                || player.getOffhandItem().getItem() == Items.FISHING_ROD;
    }
}
