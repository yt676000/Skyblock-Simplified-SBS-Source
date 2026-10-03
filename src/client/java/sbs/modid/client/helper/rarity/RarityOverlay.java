/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rarity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.ui.render.SciFiRender;

/**
 * Shared drawing for the item-rarity overlay, reused by both the container-slot mixin
 * ({@code ItemRarityMixin}) and the live HUD hotbar ({@code HudMixin}).
 *
 * <p>Draws a single translucent, rarity-colored shape over the whole 16x16 item icon – a circle for
 * {@link RarityOverlayMode#ROUND} (a rounded rectangle with a full-radius corner) or a filled square
 * for {@link RarityOverlayMode#SQUARE}. Uses only {@code net.minecraft.*} primitives (via the shared
 * {@link SciFiRender}); it never touches the item stacks themselves.
 */
public final class RarityOverlay {

    /** The 1px outline is the same hue, darkened and somewhat more opaque so it reads as an edge. */
    private static final float OUTLINE_DARKEN = 0.65F;
    private static final int OUTLINE_EXTRA_ALPHA = 0x3E;

    /** Fill alpha from the user's opacity slider (5–80%), clamped defensively. */
    private static int fillAlpha() {
        int percent = Math.max(5, Math.min(80,
                ConfigManager.getInstance().get().itemOverlay.rarityOpacity));
        return Math.round(percent / 100.0F * 255);
    }

    /** Standard inventory item icon size. */
    private static final int ITEM_SIZE = 16;

    private RarityOverlay() {
    }

    /** Draws the rarity overlay for one item at {@code (x, y)} with the given size, if the mode is on. */
    public static void draw(GuiGraphicsExtractor g, int x, int y, int size, Rarity rarity, RarityOverlayMode mode) {
        if (rarity == null || mode == null || mode == RarityOverlayMode.OFF) {
            return;
        }
        int fill = translucent(rarity.color());
        int outline = darkened(rarity.color());
        switch (mode) {
            case SQUARE -> {
                g.fill(x, y, x + size, y + size, fill);
                g.outline(x, y, size, size, outline);
            }
            case ROUND -> {
                // Darker same-hue ring: draw the darkened circle first, the lighter fill 1px inset.
                SciFiRender.roundedRect(g, x, y, size, size, size / 2, outline);
                SciFiRender.roundedRect(g, x + 1, y + 1, size - 2, size - 2, (size - 2) / 2, fill);
            }
            default -> {
            }
        }
    }

    /**
     * Draws rarity overlays over the nine live HUD hotbar slots (works with the inventory closed).
     * Positions mirror vanilla: the hotbar's left edge is {@code guiWidth/2 - 91}, items start 3px in
     * and step 20px, sitting 3px above the bottom.
     */
    public static void renderHotbar(GuiGraphicsExtractor g) {
        RarityOverlayMode mode = ConfigManager.getInstance().get().itemOverlay.rarityMode;
        if (mode == RarityOverlayMode.OFF) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        int leftEdge = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - ITEM_SIZE - 3;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            Rarity rarity = Rarity.detect(stack);
            if (rarity == null) {
                continue;
            }
            int x = leftEdge + 3 + slot * 20;
            draw(g, x, y, ITEM_SIZE, rarity, mode);
        }
    }

    /** Applies the configured overlay alpha to a rarity's (opaque) color. */
    private static int translucent(int argb) {
        return (fillAlpha() << 24) | (argb & 0xFFFFFF);
    }

    /** Same hue, darkened (RGB scaled) and somewhat more opaque – used for the 1px outline. */
    private static int darkened(int argb) {
        int alpha = Math.min(0xFF, fillAlpha() + OUTLINE_EXTRA_ALPHA);
        int r = (int) (((argb >> 16) & 0xFF) * OUTLINE_DARKEN);
        int g = (int) (((argb >> 8) & 0xFF) * OUTLINE_DARKEN);
        int b = (int) ((argb & 0xFF) * OUTLINE_DARKEN);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }
}
