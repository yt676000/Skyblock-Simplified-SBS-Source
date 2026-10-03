/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.mining.logic.GemstoneTracker;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Gemstone Profit module (Skills): coins per hour from mined gemstones, priced off the cached Bazaar
 * snapshot. Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p>Its own module rather than a fourth card on the Mining Helpers page, because it answers a
 * different question with different inputs: the helper cards read the tab widget and report state,
 * while this reads the sack feed and reports money. It is also off by default and the others are on,
 * which one shared master switch could not express.
 */
public final class GemstoneProfitModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public GemstoneProfitModule() {
    }

    @Override
    public String id() {
        return "gemstone_profit";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.MINING;
    }

    @Override
    public String displayName() {
        return "Gemstone Profit";
    }

    @Override
    public String description() {
        return "Coins per hour from mined gemstones, after Bazaar tax";
    }

    @Override
    public int accentColor() {
        return 0xFFB45FD6;
    }

    private static SBSConfig.GemstoneProfitSettings cfg() {
        return ConfigManager.getInstance().get().gemstoneProfit;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Gemstone Profit", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Counts the gemstones you pick up and prices them off the Bazaar. "
                                + "Off by default because keeping the prices fresh costs a "
                                + "background request every minute, which is wasted on a player "
                                + "who does not mine gemstones."),
                SettingRow.label("§8Counts come from the [Sacks] chat message"),
                SettingRow.label("§8- you need a Gemstone Sack and its notifications on"),

                SettingRow.label("— Card —"),
                SettingRow.toggle("Profit Card", () -> cfg().card,
                        () -> { cfg().card = !cfg().card; save(); })
                        .describe("A card with what this session's gemstones are worth, sold as "
                                + "mined and after tax. Hidden off the mining islands, and hidden "
                                + "until you have actually mined one."),
                SettingRow.toggle("Show Projected Per Hour", () -> cfg().perHour,
                        () -> { cfg().perHour = !cfg().perHour; save(); })
                        .describe("Adds the hourly rate your current session projects to. Labelled "
                                + "as a projection on the card, and it waits out the first minute - "
                                + "a rate measured over ten seconds is not a rate."),
                SettingRow.toggle("Break Down Per Gemstone", () -> cfg().perGemstone,
                        () -> { cfg().perGemstone = !cfg().perGemstone; save(); })
                        .describe("One row per gemstone and grade instead of a single total, so you "
                                + "can see which ones are actually paying."),
                SettingRow.button("Move / Resize Profit Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.GEMSTONE_PROFIT}, "Edit Gemstone Profit Card")))
                        .describe("Opens the editor where you drag the card anywhere on the screen "
                                + "and scale it."),

                SettingRow.label("— Advice —"),
                SettingRow.toggle("Sell Now vs Combine", () -> cfg().combineAdvice,
                        () -> { cfg().combineAdvice = !cfg().combineAdvice; save(); })
                        .describe("Compares selling what you mined against combining it up a grade "
                                + "first, and names whichever is worth more. The combining ratios "
                                + "behind this are NOT yet verified in game - the card labels how "
                                + "much each answer is trusted, and this switch turns the whole "
                                + "recommendation off if you would rather have the raw figure."),
                SettingRow.toggle("Warn On Thin Markets", () -> cfg().volumeWarning,
                        () -> { cfg().volumeWarning = !cfg().volumeWarning; save(); })
                        .describe("Says so when your hourly output is large compared with how much "
                                + "of that gemstone actually trades in an hour. High grades are the "
                                + "ones this catches - some Perfect gemstones move only a few "
                                + "hundred a week, so one player's output moves the price and the "
                                + "quoted value stops being what you would get."),

                SettingRow.label("— Assumptions —"),
                SettingRow.intField("Bazaar Flipper Level", 0, 2, () -> cfg().bazaarFlipperLevel,
                        value -> { cfg().bazaarFlipperLevel = value; save(); }, "")
                        .describe("Your Bazaar Flipper level, which sets the sell tax: 1.25% at "
                                + "level 0, 1.125% at I, 1.00% at II. Left at 0 by default because "
                                + "that is the highest tax - guessing high understates the profit, "
                                + "and that is the cheaper direction to be wrong in."),
                SettingRow.button("Reset Session",
                        () -> GemstoneTracker.getInstance().resetSession())
                        .describe("Sets the counts and the projected rate back to zero and starts "
                                + "measuring again from now."),
                SettingRow.label("§8" + SkillIslands.describe(SkillIslands.MINING_ISLANDS)));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
