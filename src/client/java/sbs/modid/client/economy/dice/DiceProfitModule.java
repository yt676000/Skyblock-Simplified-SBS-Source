/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.dice;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.economy.dice.logic.DiceTracker;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Dice Profit (Economy): what rolling the Archfiend and High Class Archfiend Dice has cost and paid.
 * Display only - nothing is sent and nothing is rolled for the player.
 */
public final class DiceProfitModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public DiceProfitModule() {
    }

    @Override
    public String id() {
        return "dice_profit";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Dice Profit";
    }

    @Override
    public String description() {
        return "Rolls, coins spent and won, dyes and the net profit of your Archfiend Dice";
    }

    @Override
    public int accentColor() {
        return 0xFFD0453A;
    }

    private static SBSConfig.DiceProfitSettings cfg() {
        return ConfigManager.getInstance().get().diceProfit;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Dice Profit", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A card with what your Archfiend Dice and High Class Archfiend Dice "
                                + "rolls cost and paid: rolls, coins spent, coins won from 6s, dyes from "
                                + "7s and the net. Only your own rolls count. Default: off."),
                SettingRow.toggle("Show All-Time", () -> cfg().showAllTime,
                        () -> { cfg().showAllTime = !cfg().showAllTime; save(); })
                        .describe("Shows this profile's totals since tracking began instead of this "
                                + "session's. Default: off."),
                SettingRow.toggle("Show Roll Distribution", () -> cfg().showDistribution,
                        () -> { cfg().showDistribution = !cfg().showDistribution; save(); })
                        .describe("Adds a line with how often each number from 1 to 7 was rolled. "
                                + "Default: off."),
                SettingRow.toggle("Count Dice Lost", () -> cfg().countDiceLoss,
                        () -> { cfg().countDiceLoss = !cfg().countDiceLoss; save(); })
                        .describe("A 6 or a 7 uses up the dice. On, its lowest BIN is taken off the "
                                + "net. Default: on."),
                SettingRow.toggle("Only Show After A Roll", () -> cfg().showOnlyAfterRoll,
                        () -> { cfg().showOnlyAfterRoll = !cfg().showOnlyAfterRoll; save(); })
                        .describe("The card appears when you roll a dice and disappears again a few "
                                + "seconds later. Off, it stays on screen once anything was rolled. "
                                + "Always shown in the HUD editor. Default: on."),
                SettingRow.intField("Show For", 1, 120, () -> cfg().showSeconds,
                        value -> { cfg().showSeconds = value; save(); }, "s")
                        .describe("How many seconds the card stays after a roll, 1 to 120. Only used "
                                + "with Only Show After A Roll. Default: 10."),
                SettingRow.button("Reset Session", () -> DiceTracker.getInstance().resetSession())
                        .describe("Starts this session's counts again from zero. Same as /sbs dice reset."),
                SettingRow.button("Reset All-Time", () -> DiceTracker.getInstance().resetAllTime())
                        .describe("Clears this profile's all-time counts as well. Cannot be undone. "
                                + "Same as /sbs dice reset all."),
                SettingRow.button("Move / Resize Card", () -> net.minecraft.client.Minecraft.getInstance()
                        .setScreenAndShow(new HudEditorScreen(
                                new HudElement[] {HudElement.DICE_PROFIT}, "Edit Dice Profit")))
                        .describe("Opens the editor where you drag the card anywhere on the screen "
                                + "and scale it."));
    }
}
