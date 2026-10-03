/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.customskin.logic;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.helper.customskin.model.CustomSkin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Custom Skin module's store: which item each of your items should <b>look</b> like.
 *
 * <p><b>Item identity.</b> Skins are keyed on {@link SkyblockItem#uniqueKey}, the same identity the
 * Item Renamer uses – the SkyBlock {@code uuid} when the item has one (so a skin follows that one
 * Hyperion and nothing else), otherwise its SkyBlock id, otherwise its name. Nothing is ever written
 * into the stack itself: the skin is looked up again on every render, so it is purely client-side,
 * survives restarts and server swaps, and can never be seen by or sent to Hypixel.
 *
 * <p><b>Rendering.</b> {@link #skinFor} returns a fully built replacement stack, which the render
 * mixins substitute for the real one at the point the model is resolved. That covers the inventory
 * icon, the item in your hand, dropped items and item frames in one place, because they all resolve
 * their model through {@code ItemModelResolver.appendItemLayers}. Tooltips and names are untouched –
 * they never pass through the model resolver.
 *
 * <p>The built stacks are cached per skin (item + tint) and handed out <b>shared and read-only</b>:
 * this is called for every rendered item every frame, so building a stack per call would be the
 * single hottest allocation in the render loop. Nothing downstream of the model resolver mutates the
 * stack it is given.
 */
public final class CustomSkinStore {

    private static final CustomSkinStore INSTANCE = new CustomSkinStore();

    /** Built skin stacks by {@link CustomSkin#cacheKey()}; cleared whenever a skin is edited. */
    private final Map<String, ItemStack> built = new ConcurrentHashMap<>();

    private CustomSkinStore() {
    }

    public static CustomSkinStore getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.CustomSkinSettings cfg() {
        return ConfigManager.getInstance().get().customSkin;
    }

    // ------------------------------------------------------------------
    // Lookup (render path – keep allocation-free)
    // ------------------------------------------------------------------

    /**
     * The stack to render instead of {@code original}, or {@code null} when it has no skin. The
     * returned stack is <b>shared and must not be modified</b>.
     */
    public ItemStack skinFor(ItemStack original) {
        SBSConfig.CustomSkinSettings cfg = cfg();
        if (!cfg.enabled || original == null || original.isEmpty() || cfg.skins.isEmpty()) {
            return null;
        }
        String key = SkyblockItem.uniqueKey(original);
        if (key == null) {
            return null;
        }
        CustomSkin skin = cfg.skins.get(key);
        if (skin == null || !skin.isSet()) {
            return null;
        }
        ItemStack stack = built.get(skin.cacheKey());
        if (stack == null) {
            stack = build(skin);
            if (stack == null) {
                return null;
            }
            built.put(skin.cacheKey(), stack);
        }
        // A skin that resolved to nothing renders as the real item rather than as a barrier.
        return stack.isEmpty() ? null : stack;
    }

    /** The saved skin of an item, or {@code null}. */
    public CustomSkin get(ItemStack stack) {
        String key = SkyblockItem.uniqueKey(stack);
        return key == null ? null : cfg().skins.get(key);
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /** Saves (or replaces) the skin of an item. */
    public void set(ItemStack target, String skinItem, int color) {
        String key = SkyblockItem.uniqueKey(target);
        if (key == null || skinItem == null || skinItem.isBlank()) {
            return;
        }
        cfg().skins.put(key, new CustomSkin(skinItem, color));
        save();
    }

    /** Removes the skin of an item, restoring its real look. */
    public void clear(ItemStack target) {
        String key = SkyblockItem.uniqueKey(target);
        if (key != null && cfg().skins.remove(key) != null) {
            save();
        }
    }

    /** Removes every saved skin. */
    public void clearAll() {
        if (!cfg().skins.isEmpty()) {
            cfg().skins.clear();
            save();
        }
    }

    /** How many items currently carry a skin. */
    public int count() {
        return cfg().skins.size();
    }

    private void save() {
        built.clear(); // an edited skin must be rebuilt, not served from the old cache
        ConfigManager.getInstance().save();
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /**
     * Builds the renderable stack for a skin: a plain vanilla item, or a SkyBlock item resolved
     * through {@link SkyBlockItemIcons} so it keeps that item's real appearance (skull texture,
     * custom model, leather tint, permanent glint). Returns {@code null} when nothing resolves.
     */
    private ItemStack build(CustomSkin skin) {
        ItemStack stack;
        if (skin.isVanilla()) {
            Identifier id = Identifier.tryParse(skin.skinItem);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                return null;
            }
            stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        } else {
            SkyBlockItemCatalog.Entry entry =
                    SkyBlockItemCatalog.getInstance().byId(skin.skinItem);
            String material = entry != null ? entry.material : null;
            // icon() copies, so the cached stack here is ours to tint.
            stack = SkyBlockItemIcons.getInstance().icon(skin.skinItem, material, 1);
            if (stack.isEmpty() || stack.is(Items.BARRIER)) {
                return null; // catalogue not loaded yet / unknown id – retry on the next edit
            }
        }
        if (skin.color >= 0) {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(skin.color & 0xFFFFFF));
        }
        return stack;
    }

    /**
     * Whether an item's model actually reacts to the tint, i.e. whether the colour picker will do
     * anything visible. Only models with a dye tint layer (leather armour, wolf armour) do; for
     * everything else the component is stored and simply ignored by the model, so the picker says so
     * rather than leaving the player wondering why nothing changed.
     */
    public static boolean isTintable(ItemStack stack) {
        return stack != null && !stack.isEmpty() && isTintable(stack.getItem());
    }

    public static boolean isTintable(Item item) {
        return item != null && item.components().has(DataComponents.DYED_COLOR);
    }

    /** The stored id of a vanilla item ({@code minecraft:diamond_sword}). */
    public static String vanillaId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** A hex string ({@code RRGGBB}, {@code #} optional) as packed RGB, or {@code -1} if invalid. */
    public static int parseHex(String hex) {
        if (hex == null) {
            return -1;
        }
        String clean = hex.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (clean.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(clean, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
