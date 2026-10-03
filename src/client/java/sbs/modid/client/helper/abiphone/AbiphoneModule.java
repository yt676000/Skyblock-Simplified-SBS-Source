/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.abiphone;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Abiphone module (Inventory & Items): reskins every Abiphone menu as a fancy phone UI. Self-registered
 * via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; state in
 * {@link SBSConfig.AbiphoneSettings}. Pure display - it only restyles the container, never clicks it.
 */
public final class AbiphoneModule implements SbsModule {

    public AbiphoneModule() {
    }

    @Override
    public String id() {
        return "abiphone";
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
        return "Abiphone";
    }

    @Override
    public String description() {
        return "Reskins the Abiphone menus as a fancy phone (contacts, minigames, ringtones, ...)";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.AbiphoneSettings cfg() {
        return ConfigManager.getInstance().get().abiphone;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Abiphone Phone UI", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Redraws every Abiphone menu to look like an actual phone: a "
                                + "bezel around the screen, app-style cells, the works. Purely "
                                + "visual - the menus behave exactly as before."),
                SettingRow.label("Every Abiphone menu drawn as a phone: bezel, status bar, app cells"),
                SettingRow.toggle("Status Bar", () -> cfg().statusBar,
                        () -> { cfg().statusBar = !cfg().statusBar; save(); })
                        .describe("The phone-style top bar with carrier, clock, signal and battery "
                                + "- decoration only."),
                SettingRow.label("Carrier + clock + signal + battery at the top of the phone"));
    }
}
