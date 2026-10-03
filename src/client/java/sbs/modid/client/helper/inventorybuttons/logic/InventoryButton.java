/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventorybuttons.logic;

import net.minecraft.world.item.ItemStack;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

/**
 * One user-defined button drawn over the open inventory.
 *
 * <p>A plain mutable POJO because Gson (de)serialises it straight out of the config, like
 * {@code CommandKeybind} and the other persisted settings objects.
 *
 * <p>The position is stored <b>relative to the container GUI's top-left corner</b>, not in screen
 * coordinates: the inventory is centred, so it moves with the window size and the GUI scale, and an
 * absolute position would drift away from it. Negative values are legal and normal – that is a
 * button parked just outside the panel's left or top edge.
 */
public final class InventoryButton {

    /** Label shown in the editor and in the tooltip. */
    public String name = "Button";

    /** The command run on click, with or without a leading slash. */
    public String command = "";

    /** SkyBlock item id used as the icon (e.g. {@code ENCHANTED_DIAMOND}). */
    public String icon = "COMPASS";

    /** Offset from the container GUI's top-left corner, in GUI pixels. */
    public int x;
    public int y;

    /**
     * Size in GUI pixels, freely settable per button and per axis. Read through {@link #width()} /
     * {@link #height()} rather than directly: a button written by an older version has no size in its
     * JSON at all, and a hand-edited config can hold anything.
     */
    public int w = InventoryButtons.SIZE;
    public int h = InventoryButtons.SIZE;

    /** Gson needs a no-arg constructor. */
    public InventoryButton() {
    }

    public InventoryButton(String name, int x, int y) {
        this.name = name;
        this.x = x;
        this.y = y;
    }

    /** Width in GUI pixels, falling back to the slot size for a config that predates free sizing. */
    public int width() {
        return w <= 0 ? InventoryButtons.SIZE
                : Math.clamp(w, InventoryButtons.MIN_SIZE, InventoryButtons.MAX_SIZE);
    }

    /** Height in GUI pixels, falling back to the slot size for a config that predates free sizing. */
    public int height() {
        return h <= 0 ? InventoryButtons.SIZE
                : Math.clamp(h, InventoryButtons.MIN_SIZE, InventoryButtons.MAX_SIZE);
    }

    /** Sets both axes, each clamped into the legal range. Free otherwise – no aspect ratio is forced. */
    public void resize(int newWidth, int newHeight) {
        this.w = Math.clamp(newWidth, InventoryButtons.MIN_SIZE, InventoryButtons.MAX_SIZE);
        this.h = Math.clamp(newHeight, InventoryButtons.MIN_SIZE, InventoryButtons.MAX_SIZE);
    }

    /** The renderable icon, resolved through the shared registry (barrier only as a last resort). */
    public ItemStack iconStack() {
        return SkyBlockItemIcons.getInstance().icon(icon, null, 1);
    }

    /** The command without its leading slash, ready for {@code sendCommand}. Empty when unset. */
    public String cleanCommand() {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    /** Whether this button would actually do something when clicked. */
    public boolean runnable() {
        return !cleanCommand().isEmpty();
    }
}
