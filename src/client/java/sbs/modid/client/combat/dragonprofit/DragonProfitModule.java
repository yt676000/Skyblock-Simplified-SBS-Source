/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.dragonprofit;

import sbs.modid.client.combat.dragonprofit.logic.DragonProfitTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Dragon Profit Tracker (Combat): per End dragon fight, the Summoning Eyes you placed against the
 * loot you got, with your damage and position. Display only - nothing is sent.
 */
public final class DragonProfitModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public DragonProfitModule() {
    }

    @Override
    public String id() {
        return "dragon_profit";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Dragon Profit Tracker";
    }

    @Override
    public String description() {
        return "Eyes placed, loot, damage, position and profit of your End dragon fights";
    }

    @Override
    public int accentColor() {
        return 0xFF9B59D0;
    }

    private static SBSConfig.DragonProfitSettings cfg() {
        return ConfigManager.getInstance().get().dragonProfit;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Dragon Profit Tracker", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A card with your End dragon fights: the Summoning Eyes you placed, the "
                                + "loot you got, your damage and position, and the profit per fight, per "
                                + "session and per dragon type. Only your own eyes and loot count. Default: on."),
                SettingRow.toggle("Show Everywhere", () -> cfg().showEverywhere,
                        () -> { cfg().showEverywhere = !cfg().showEverywhere; save(); })
                        .describe("Shows the card on every island, not only in the Dragon's Nest. Default: off."),
                SettingRow.toggle("Show All-Time", () -> cfg().showAllTime,
                        () -> { cfg().showAllTime = !cfg().showAllTime; save(); })
                        .describe("Shows this profile's totals since tracking began instead of this "
                                + "session's. Default: off."),
                SettingRow.toggle("Show Per Dragon Type", () -> cfg().showPerType,
                        () -> { cfg().showPerType = !cfg().showPerType; save(); })
                        .describe("Adds one line per dragon type with its fights and profit. Default: off."),
                SettingRow.toggle("Price At Sell Offer", () -> cfg().sellOffer,
                        () -> { cfg().sellOffer = !cfg().sellOffer; save(); })
                        .describe("Values eyes and Bazaar loot at the sell offer price instead of the "
                                + "instant sell price. Auction items always use the lowest BIN. Default: off."),
                SettingRow.toggle("Fragments At Instant Sell", () -> cfg().fragmentsAtInstantSell,
                        () -> { cfg().fragmentsAtInstantSell = !cfg().fragmentsAtInstantSell; save(); })
                        .describe("Always values dragon fragments at the instant sell price, even with "
                                + "Price At Sell Offer on. Default: on."),
                SettingRow.button("Reset Session", () -> DragonProfitTracker.getInstance().resetSession())
                        .describe("Starts this session's fights again from zero."),
                SettingRow.button("Reset All-Time", () -> DragonProfitTracker.getInstance().resetAllTime())
                        .describe("Clears this profile's all-time totals as well. Cannot be undone."),
                SettingRow.button("Move / Resize Card", () -> net.minecraft.client.Minecraft.getInstance()
                        .setScreenAndShow(new HudEditorScreen(
                                new HudElement[] {HudElement.DRAGON_PROFIT}, "Edit Dragon Profit")))
                        .describe("Opens the editor where you drag the card anywhere on the screen "
                                + "and scale it."));
    }
}
