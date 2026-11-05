/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.crafthelper;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Craft Helper module (Economy): an item's whole recipe tree with have / need per ingredient, down to
 * base materials; a star to favourite items; and the favourite of the moment on the HUD, so what is
 * still missing can be read while collecting it.
 *
 * <p>Display only by design: it counts and shows, and never clicks, crafts or opens a menu. The tree
 * lives in the Recipe Viewer (an item's detail, "Tree") and on the HUD card.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class CraftHelperModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public CraftHelperModule() {
    }

    @Override
    public String id() {
        return "craft_helper";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Craft Helper";
    }

    @Override
    public String description() {
        return "An item's full recipe tree with what you have and what you still need, also on the HUD";
    }

    @Override
    public int accentColor() {
        return 0xFF6FD08C;
    }

    private static SBSConfig.CraftHelperSettings cfg() {
        return ConfigManager.getInstance().get().craftHelper;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Craft Helper", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Adds a Tree view to an item in the Recipe Viewer: every ingredient "
                                + "down to base materials, each with how many you have and how many "
                                + "you need. Star an item there to favourite it and put it on the HUD. "
                                + "It only counts and shows - it never crafts or clicks. Default: on."),
                SettingRow.label("Open the Recipe Viewer on an item, then \"Tree\" - star it to keep it"),

                SettingRow.toggle("Count Inventory", () -> cfg().countInventory,
                        () -> { cfg().countInventory = !cfg().countInventory; save(); })
                        .describe("Counts what is in your inventory. Default: on."),
                SettingRow.toggle("Count Sacks", () -> cfg().countSacks,
                        () -> { cfg().countSacks = !cfg().countSacks; save(); })
                        .describe("Counts what your sacks held the last time you opened them. Needs "
                                + "Sacks switched on under Storage Search, which is what remembers "
                                + "them. Default: on."),
                SettingRow.label("Sack amounts are read from the sack's text - not yet checked in game"),
                SettingRow.toggle("Count Storage", () -> cfg().countStorage,
                        () -> { cfg().countStorage = !cfg().countStorage; save(); })
                        .describe("Counts Ender Chest pages, backpacks, the Personal Vault and island "
                                + "chests as they were the last time you opened them. A storage never "
                                + "opened counts as empty. Default: on."),

                SettingRow.rangeSlider("Max Depth", 1, 12, () -> cfg().maxDepth,
                        value -> { cfg().maxDepth = value; save(); }, "")
                        .describe("How many levels of ingredients the tree goes down before it stops. "
                                + "Default: 8."),
                SettingRow.toggle("Fold Completed Branches", () -> cfg().collapseCompleted,
                        () -> { cfg().collapseCompleted = !cfg().collapseCompleted; save(); })
                        .describe("Folds a branch away once everything in it is held or can be made "
                                + "from what you hold. Click a row to open or fold it by hand. "
                                + "Default: on."),
                SettingRow.rangeSlider("HUD Rows", 4, 30, () -> cfg().hudRows,
                        value -> { cfg().hudRows = value; save(); }, "")
                        .describe("How many tree rows the HUD card shows before it scrolls. Scroll it "
                                + "with an inventory open. Default: 12."),
                SettingRow.button("Move / Resize Craft Helper Card", () -> net.minecraft.client.Minecraft
                                .getInstance().setScreenAndShow(new HudEditorScreen(
                                        new HudElement[] {HudElement.CRAFT_HELPER}, "Edit Craft Helper Card")))
                        .describe("Opens the editor where you drag the card anywhere on the screen."));
    }
}
