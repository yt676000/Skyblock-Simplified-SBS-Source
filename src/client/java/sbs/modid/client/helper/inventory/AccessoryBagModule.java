/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Accessory Bag module (Inventory &amp; Items): highlights duplicate accessories in the bag menu, and
 * answers "which accessories am I still missing" by reading the bag's pages as they are opened and
 * comparing them against the full catalogue Hypixel publishes. Independent of the Recipe Viewer's
 * search highlight. Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class AccessoryBagModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public AccessoryBagModule() {
    }

    @Override
    public String id() {
        return "accessory_bag";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INVENTORY_ITEMS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.MENUS;
    }

    @Override
    public String displayName() {
        return "Accessory Bag";
    }

    @Override
    public String description() {
        return "Missing-accessory list and duplicate highlights for the Accessory Bag";
    }

    @Override
    public int accentColor() {
        return 0xFFE0605F;
    }

    private static SBSConfig.AccessoryBagSettings cfg() {
        return ConfigManager.getInstance().get().accessoryBag;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.button("Open Missing Accessories",
                        sbs.modid.client.helper.inventory.ui.MissingAccessoriesScreen::open)
                        .describe("The whole accessory catalogue with what this profile still "
                                + "lacks, what an owned higher tier already replaced, and the "
                                + "Magical Power each one is worth. Also on /sbs accessories."),
                SettingRow.keybind("Missing Accessories Key", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("Opens the missing-accessory screen from anywhere. Unbound by "
                                + "default."),
                SettingRow.toggle("Track Bag Contents", () -> cfg().trackOwned,
                        () -> { cfg().trackOwned = !cfg().trackOwned; save(); })
                        .describe("Reads each Accessory Bag page as you open it, so the missing "
                                + "list knows what you own. Hypixel never sends the bag unasked - "
                                + "with this off nothing can be compared and every accessory reads "
                                + "as missing."),
                SettingRow.toggle("Bag Button", () -> cfg().bagButton,
                        () -> { cfg().bagButton = !cfg().bagButton; save(); })
                        .describe("Shows a 'Missing' chip above the Accessory Bag menu. It sits "
                                + "outside the menu, so it never takes a click meant for a slot."),
                SettingRow.toggle("Include Rift Accessories", () -> cfg().includeRift,
                        () -> { cfg().includeRift = !cfg().includeRift; save(); })
                        .describe("Rift accessories are a separate progression that cannot leave "
                                + "the Rift. Off by default so the main list is not permanently "
                                + "short by twenty entries you may not want."),
                SettingRow.toggle("Show Upgraded Tiers", () -> cfg().includeSuperseded,
                        () -> { cfg().includeSuperseded = !cfg().includeSuperseded; save(); })
                        .describe("Lists the lower tiers an owned higher tier has replaced - the "
                                + "Wolf Talisman once you hold the Wolf Ring. They are not gaps, "
                                + "so they are hidden by default."),
                SettingRow.button("Forget Bag Contents",
                        () -> sbs.modid.client.helper.inventory.logic.AccessoryIndex.getInstance().clear())
                        .describe("Drops every bag page read so far for this profile. Only useful "
                                + "if the recorded contents have gone wrong - re-opening the bag "
                                + "refreshes each page on its own."),
                SettingRow.label("Highlights inside the bag menu"),
                SettingRow.toggle("Accessory Bag Highlights", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Highlights inside your Accessory Bag. Currently: marking "
                                + "accessories you own twice, since duplicates give no extra "
                                + "stats and only waste bag space."),
                SettingRow.toggle("Highlight Duplicates", () -> cfg().highlightDuplicates,
                        () -> { cfg().highlightDuplicates = !cfg().highlightDuplicates; save(); })
                        .describe("Boxes every accessory that appears more than once on the open "
                                + "bag page - candidates for selling or salvaging."),
                SettingRow.enumOptions("Duplicate Colour", () -> cfg().duplicateColor,
                        value -> { cfg().duplicateColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the duplicate boxes. Click to cycle."));
    }
}
