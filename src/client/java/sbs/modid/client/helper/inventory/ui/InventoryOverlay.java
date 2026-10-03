/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.ui;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Shared state for the two Inventory Overlay modes, which are separate features with one setting
 * each and no dependency on the other:
 *
 * <ul>
 *   <li><b>HUD overlay</b> ({@link #hudActive}) – the inventory drawn above the hotbar while
 *       playing. Read-only by nature: it is HUD paint, so it cannot capture the mouse and can never
 *       interrupt normal play. Moved and resized through the existing GUI editor.</li>
 *   <li><b>Transparent screen</b> ({@link #screenActive}) – the real inventory screen with its
 *       dimming backdrop removed and its panel made translucent, so items are operated exactly as
 *       in vanilla while the game stays visible behind.</li>
 * </ul>
 *
 * <p>Together they cover both halves of "see the inventory while playing" and "operate the inventory
 * without losing sight of the game", and either can be used alone.
 */
public final class InventoryOverlay {

    /**
     * Opacity bounds shared by both modes. The bottom is 0 - the overlay's own toggle is what turns
     * it off, so an invisible overlay is a look someone asked for (their own inventory, read from
     * the item names alone) rather than a state to be protected against.
     */
    public static final int MIN_OPACITY = 0;
    public static final int MAX_OPACITY = 100;

    private InventoryOverlay() {
    }

    public static SBSConfig.InventoryOverlaySettings cfg() {
        return ConfigManager.getInstance().get().inventoryOverlay;
    }

    /** Whether the HUD overlay should be drawn right now. */
    public static boolean hudActive() {
        return cfg().hudOverlay;
    }

    /**
     * Whether the transparent-screen treatment applies to the screen that is currently open. Scoped
     * to the player's own inventory, so chests and SkyBlock menus keep their normal look.
     */
    public static boolean screenActive() {
        return cfg().transparentScreen
                && sbs.modid.client.core.api.ScreenAccess.current() instanceof InventoryScreen;
    }

    /** The configured opacity, clamped, as a 0-255 alpha. */
    public static int alpha() {
        int pct = Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, cfg().opacity));
        return pct * 255 / 100;
    }

    /** Rescales a packed ARGB colour's alpha by the configured opacity. */
    public static int withOpacity(int argb) {
        int pct = Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, cfg().opacity));
        int a = ((argb >>> 24) & 0xFF) * pct / 100;
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
