/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.customskin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.customskin.logic.CustomSkinStore;
import sbs.modid.client.helper.customskin.ui.CustomSkinScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Custom Skin module (Inventory & Items): give any item the look of any other item.
 *
 * <p>Press the hotkey while holding an item and pick what it should look like from every Hypixel and
 * vanilla item, optionally tinted. The item still <i>is</i> itself – name, lore, stats and behaviour
 * are untouched, and nothing is written into the stack or sent to the server; the swap happens
 * client-side at render time only. See {@link CustomSkinStore}.
 */
public final class CustomSkinModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public CustomSkinModule() {
    }

    @Override
    public String id() {
        return "custom_skin";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INVENTORY_ITEMS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.ITEMS;
    }

    @Override
    public String displayName() {
        return "Custom Skin";
    }

    @Override
    public String description() {
        return "Make any item look like any other Hypixel or vanilla item, in any color - purely client-side";
    }

    @Override
    public int accentColor() {
        return 0xFF9B5BFF;
    }

    private static SBSConfig.CustomSkinSettings cfg() {
        return ConfigManager.getInstance().get().customSkin;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** Opens the picker for the held item – the same entry point as the hotkey. */
    public static void openForHeldItem() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (held.isEmpty()) {
            minecraft.player.sendSystemMessage(
                    Component.literal("§8[§bSBS§8]§r §7Hold an item to give it a custom skin."));
            return;
        }
        minecraft.setScreenAndShow(new CustomSkinScreen(held));
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Custom Skin", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Lets you give any item the look of any other item. Hold an item, "
                                + "press the open key, and pick its new look. Only the visuals "
                                + "change - the name, lore, stats and behavior stay the item's own, "
                                + "and nothing is written into the item or sent to the server."),
                SettingRow.keybind("Open Key", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("Press this while holding an item to open the skin picker for it. "
                                + "Unbound by default."),
                SettingRow.button("Skin Held Item", CustomSkinModule::openForHeldItem)
                        .describe("Opens the picker for whatever is in your hand right now, without "
                                + "needing a key bound."),

                SettingRow.toggle("Also Skin Worn Armor", () -> cfg().applyToWornArmor,
                        () -> { cfg().applyToWornArmor = !cfg().applyToWornArmor; save(); })
                        .describe("Applies armor skins to your player model as well, not just the "
                                + "inventory icon. A worn piece can only take the look of another "
                                + "piece for the same slot - armor is drawn from equipment "
                                + "textures, which weapons and blocks do not have."),

                SettingRow.label("Skins are client-side only - nobody else sees them"),
                SettingRow.button("Remove All Skins", () -> CustomSkinStore.getInstance().clearAll())
                        .describe("Clears every saved skin, restoring all your items to their real "
                                + "look."));
    }
}
