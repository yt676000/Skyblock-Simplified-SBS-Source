/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.sacrifice;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.economy.sacrifice.logic.SacrificeTracker;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Sacrifice Altar Profit (Economy): what each sacrifice in The End gave - Dragon Essence and bonus
 * loot - against what the sacrificed item was worth. Display only - nothing is sent or clicked.
 */
public final class SacrificeAltarModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SacrificeAltarModule() {
    }

    @Override
    public String id() {
        return "sacrifice_altar";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Sacrifice Altar Profit";
    }

    @Override
    public String description() {
        return "Dragon Essence and bonus loot from the Sacrifice Altar against what you gave up";
    }

    @Override
    public int accentColor() {
        return 0xFFB062E0;
    }

    private static SBSConfig.SacrificeAltarSettings cfg() {
        return ConfigManager.getInstance().get().sacrificeAltar;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Sacrifice Altar Profit", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A card in The End with your Sacrifice Altar results: sacrifices, Dragon "
                                + "Essence, bonus loot, what the items you gave up were worth and the net. "
                                + "Only your own sacrifices count. Default: on."),
                SettingRow.toggle("Show All-Time", () -> cfg().showAllTime,
                        () -> { cfg().showAllTime = !cfg().showAllTime; save(); })
                        .describe("Shows this profile's totals since tracking began instead of this "
                                + "session's. Default: off."),
                SettingRow.segmented("Price Side", List.of("Sell", "Buy"), () -> cfg().sellSide ? 0 : 1,
                        i -> { cfg().sellSide = i == 0; save(); })
                        .describe("Sell: Bazaar instant-sell, what you would get. Buy: Bazaar instant-buy, "
                                + "what it would cost you. Auction items use lowest BIN either way. "
                                + "Default: Sell."),
                SettingRow.segmented("Value Sacrificed Items", List.of("Full Appraisal", "Base Item"),
                        () -> cfg().fullAppraisal ? 0 : 1, i -> { cfg().fullAppraisal = i == 0; save(); })
                        .describe("Full Appraisal: the exact item with its reforge, enchants and upgrades. "
                                + "Base Item: only the item's own lowest BIN. A value marked ~ could not be "
                                + "read off the item and is the base item priced by name. Default: Full Appraisal."),
                SettingRow.button("Reset Session", () -> SacrificeTracker.getInstance().resetSession())
                        .describe("Starts this session's counts again from zero. Same as /sbs sacrifice reset."),
                SettingRow.button("Reset All-Time", () -> SacrificeTracker.getInstance().resetAllTime())
                        .describe("Clears this profile's all-time counts as well. Cannot be undone. "
                                + "Same as /sbs sacrifice reset all."),
                SettingRow.button("Move / Resize Card", () -> net.minecraft.client.Minecraft.getInstance()
                        .setScreenAndShow(new HudEditorScreen(
                                new HudElement[] {HudElement.SACRIFICE_ALTAR}, "Edit Sacrifice Altar")))
                        .describe("Opens the editor where you drag the card anywhere on the screen "
                                + "and scale it."));
    }
}
