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
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Equipment in the inventory (Inventory &amp; Items): the necklace, cloak, belt and gloves last seen
 * in the Equipment menu, drawn as a column beside the armor. Display only - the client is never
 * sent equipment outside that menu, so this is always a capture and always says how old it is.
 */
public final class EquipmentDisplayModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public EquipmentDisplayModule() {
    }

    @Override
    public String id() {
        return "equipment_display";
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
        return "Equipment in Inventory";
    }

    @Override
    public String description() {
        return "Your last-seen necklace, cloak, belt and gloves beside the armor slots";
    }

    private static SBSConfig.EquipmentDisplaySettings cfg() {
        return ConfigManager.getInstance().get().equipmentDisplay;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Show Equipment", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Reads your four equipment pieces whenever the Equipment menu is "
                                + "open and shows them left of your armor in the inventory. They are "
                                + "pictures, not slots: a click only runs /equipment."),
                SettingRow.intField("Stale After", 1, 720, () -> cfg().staleHours,
                        value -> { cfg().staleHours = value; save(); }, "h")
                        .describe("A capture older than this gets an orange marker. Switching to "
                                + "another loadout marks it stale straight away."));
    }
}
