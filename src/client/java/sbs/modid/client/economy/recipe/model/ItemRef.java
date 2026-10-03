/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.model;

import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

/**
 * A lightweight reference to an item inside a recipe – enough to identify, display and icon it
 * without holding live {@link ItemStack}s (so it is Gson-serializable for the disk cache).
 *
 * <p>{@code skyblockId} is the Hypixel internal id (e.g. "ENCHANTED_DIAMOND") when known;
 * {@code material} is the vanilla registry path (e.g. "diamond") used to build the icon stack;
 * {@code name} is the clean display name; {@code count} the required / produced amount.
 */
public final class ItemRef {

    public String skyblockId;
    public String material;
    public String name;
    public int count = 1;

    public ItemRef() {
    }

    public ItemRef(String skyblockId, String material, String name, int count) {
        this.skyblockId = skyblockId;
        this.material = material;
        this.name = name;
        this.count = Math.max(1, count);
    }

    /** Best id for lookups: the SkyBlock id, else the vanilla material upper-cased. */
    public String lookupId() {
        if (skyblockId != null && !skyblockId.isEmpty()) {
            return skyblockId;
        }
        return material == null ? "" : material.toUpperCase(Locale.ROOT);
    }

    /**
     * The renderable icon stack, resolved through the shared {@link SkyBlockItemIcons} registry
     * (catalogue material + custom skull skins + legacy-id mapping; barrier only as a last resort).
     * Returns a fresh copy – safe for callers that modify the stack.
     */
    public ItemStack iconStack() {
        return SkyBlockItemIcons.getInstance().icon(skyblockId, material, count);
    }

    /**
     * The icon as a shared, read-only stack for <b>pure rendering</b> ({@code g.item} /
     * {@code g.itemDecorations}). Skips {@link #iconStack}'s per-call copy – with a full item grid
     * that copy was the single biggest per-frame cost of the Recipe Viewer. Never modify it.
     */
    public ItemStack displayStack() {
        return SkyBlockItemIcons.getInstance().iconShared(skyblockId, material, count);
    }
}
