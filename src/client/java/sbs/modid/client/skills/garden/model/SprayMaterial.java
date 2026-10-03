/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.model;

import net.minecraft.world.item.ItemStack;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.List;
import java.util.Locale;

/**
 * The six materials a Sprayonator can spray onto a Garden plot, with the pests each one attracts.
 *
 * <p>Ids are the real SkyBlock ones, taken from the item repo (every one of these carries the lore
 * "can be spread across a garden plot with a Sprayonator") - which is how {@code TASTY_CHEESE} turns
 * out to be {@code CHEESE_FUEL}: the display name and the id disagree, and only the id resolves an
 * icon.
 *
 * <p>The display names double as the parser's vocabulary: neither the Sprayonator's lore nor the
 * Pests widget carries an item id, so a material is recognised by the name Hypixel prints.
 */
public enum SprayMaterial {

    COMPOST("COMPOST", "Compost", "Earthworm, Mosquito"),
    PLANT_MATTER("PLANT_MATTER", "Plant Matter", "Locust, Slug"),
    DUNG("DUNG", "Dung", "Beetle, Fly"),
    HONEY_JAR("HONEY_JAR", "Honey Jar", "Moth, Cricket"),
    TASTY_CHEESE("CHEESE_FUEL", "Tasty Cheese", "Rat, Mite"),
    JELLY("JELLY", "Jelly", "Praying Mantis, Dragonfly, Firefly");

    private final String itemId;
    private final String displayName;
    private final String attracts;

    SprayMaterial(String itemId, String displayName, String attracts) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.attracts = attracts;
    }

    /** The SkyBlock item id, used for the icon and to remember a spray across a restart. */
    public String itemId() {
        return itemId;
    }

    /** The name Hypixel prints ("Tasty Cheese"), which is also what the card shows. */
    public String displayName() {
        return displayName;
    }

    /** The pests this spray pulls in, for the settings page. */
    public String attracts() {
        return attracts;
    }

    /** The renderable icon, shared and read-only - for {@code g.item} only, never modify it. */
    public ItemStack icon() {
        return SkyBlockItemIcons.getInstance().iconShared(itemId, null, 1);
    }

    /** The material whose name appears in {@code text}, or {@code null} when none does. */
    public static SprayMaterial inText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (SprayMaterial material : values()) {
            if (lower.contains(material.displayName.toLowerCase(Locale.ROOT))) {
                return material;
            }
        }
        return null;
    }

    /** The material with this SkyBlock item id, or {@code null} - the reverse of {@link #itemId()}. */
    public static SprayMaterial byId(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        for (SprayMaterial material : values()) {
            if (material.itemId.equals(itemId)) {
                return material;
            }
        }
        return null;
    }

    /** Every material, in the order the Sprayonator cycles them. */
    public static List<SprayMaterial> all() {
        return List.of(values());
    }
}
