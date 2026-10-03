/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.cooldowns;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Item Overlay module: the "Show Item Cooldown" visuals, driven by the vanilla
 * {@link ItemCooldowns} the client already tracks (ender pearls, shields, custom-triggered
 * cooldowns – anything the server communicates through the vanilla cooldown system).
 *
 * <p>Slot / hotbar overlay: a white, ~10%-opacity sheet covering the whole icon at 100% cooldown
 * that drains downward – the top pixel rows disappear first as the cooldown runs out. HUD text
 * ("10.1s") is provided via {@link #remainingSeconds()} for the crosshair-side display.
 */
public final class CooldownOverlay {

    /** White at ~10% opacity. */
    private static final int OVERLAY_COLOR = 0x1AFFFFFF;
    private static final int ITEM_SIZE = 16;

    private CooldownOverlay() {
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().itemOverlay.showItemCooldown;
    }

    /** Draws the draining overlay over one item icon at {@code (x, y)} if it is on cooldown. */
    public static void draw(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        if (!enabled() || stack == null || stack.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        // SkyBlock ability cooldowns (our own clock) first; vanilla cooldowns (ender pearls, ...)
        // as the secondary source – whichever is larger drives the drain.
        float percent = Math.max(
                AbilityCooldowns.getInstance().percent(stack),
                minecraft.player.getCooldowns().getCooldownPercent(stack, 0.0F));
        if (percent <= 0.0F) {
            return;
        }
        // Drain downward: the visible sheet shrinks from the top, its remainder anchored at the
        // bottom of the icon (full height at 100%, gone row by row as the cooldown expires).
        int height = Math.round(percent * ITEM_SIZE);
        if (height > 0) {
            g.fill(x, y + ITEM_SIZE - height, x + ITEM_SIZE, y + ITEM_SIZE, OVERLAY_COLOR);
        }
    }

    /** Overlays for the nine live HUD hotbar slots (same geometry as the rarity overlay). */
    public static void renderHotbar(GuiGraphicsExtractor g) {
        if (!enabled()) {
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
            draw(g, inventory.getItem(slot), leftEdge + 3 + slot * 20, y);
        }
    }

    /**
     * Remaining cooldown of the held item in seconds, or {@code -1} when none. SkyBlock ability
     * cooldowns (our own clock) take precedence; the vanilla mirror ({@link CooldownTracker}) is
     * the fallback for genuine vanilla cooldowns.
     */
    public static double remainingSeconds() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return -1;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (held == null || held.isEmpty()) {
            return -1;
        }
        double ability = AbilityCooldowns.getInstance().remainingSeconds(held);
        if (ability > 0) {
            return ability;
        }
        ItemCooldowns cooldowns = minecraft.player.getCooldowns();
        return CooldownTracker.getInstance().remainingSeconds(cooldowns.getCooldownGroup(held));
    }
}
