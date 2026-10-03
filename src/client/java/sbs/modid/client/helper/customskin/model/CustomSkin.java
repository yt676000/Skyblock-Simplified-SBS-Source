/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.customskin.model;

/**
 * One saved skin: which item an item should <b>look</b> like, and an optional tint.
 *
 * <p>A mutable class with public fields rather than a record because this is persisted straight to
 * {@code config.json} by Gson, which needs a no-arg constructor and plain fields.
 */
public final class CustomSkin {

    /**
     * What to render instead. Two forms, told apart by the colon:
     * <ul>
     *   <li>a vanilla registry id – {@code minecraft:diamond_sword},</li>
     *   <li>a Hypixel SkyBlock id – {@code HYPERION} (resolved through the live item catalogue,
     *       so it keeps that item's real skull texture / custom model / glint).</li>
     * </ul>
     */
    public String skinItem = "";

    /** Packed {@code RRGGBB} tint, or {@code -1} for the skin item's own colours. */
    public int color = -1;

    /** Gson needs a no-arg constructor. */
    public CustomSkin() {
    }

    public CustomSkin(String skinItem, int color) {
        this.skinItem = skinItem;
        this.color = color;
    }

    /** Whether this entry actually names an item to render. */
    public boolean isSet() {
        return skinItem != null && !skinItem.isBlank();
    }

    /** True for a vanilla registry id, false for a Hypixel SkyBlock id. */
    public boolean isVanilla() {
        return skinItem != null && skinItem.indexOf(':') >= 0;
    }

    /** The cache key for the built stack: the same item at a different tint is a different stack. */
    public String cacheKey() {
        return skinItem + "|" + color;
    }
}
