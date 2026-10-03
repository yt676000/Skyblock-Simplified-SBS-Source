/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker;
import sbs.modid.client.skills.mining.nucleus.logic.TempleCheeseWaypoint;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Nucleus Run Profit Tracker (Skills &gt; Mining): what each Crystal Nucleus run earned, and the total
 * over every run, per profile. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p>Display only: it reads chat, the player's own inventory and an open HotM menu, and never opens,
 * clicks or claims anything. Off by default until a run has been watched in game on this client.
 */
public final class NucleusRunModule implements SbsModule {

    private static final List<String> SIDES = List.of("Instant-sell", "Sell offer");

    /** ServiceLoader needs a public no-arg constructor. */
    public NucleusRunModule() {
    }

    @Override
    public String id() {
        return "nucleus_run";
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
        return "Nucleus Run Profit Tracker";
    }

    @Override
    public String description() {
        return "Profit per Crystal Nucleus run and in total, per profile";
    }

    @Override
    public int accentColor() {
        return 0xFFD65FB4;
    }

    private static SBSConfig.NucleusRunSettings cfg() {
        return ConfigManager.getInstance().get().nucleusRun;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(List.of(
                SettingRow.toggle("Nucleus Run Profit Tracker", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Counts each Crystal Nucleus run: the loot bundle, the chest loot and "
                                + "sack gains on the way, less the Jungle Keys, Goblin Eggs, robot parts "
                                + "and Wishing Compasses it used up - and keeps a total over every run, "
                                + "per profile. Off by default: it has not been watched in game on this "
                                + "version yet. Keeps the Bazaar and lowest-BIN prices refreshing while on."),
                SettingRow.label("§8A run ends when the bundle is announced (the fifth crystal)."),

                SettingRow.label("— Mole Reminder —"),
                SettingRow.toggle("Mole Reminder", () -> cfg().moleReminder,
                        () -> { cfg().moleReminder = !cfg().moleReminder; save(); })
                        .describe("Reminds you when the Mole is not your active pet during a run: once "
                                + "when you reach the Crystal Nucleus with a crystal to place (or find the "
                                + "fifth), then at every crystal you place - urgently at 4/5, since the "
                                + "next one opens the bundle. The Mole can add one extra item to the "
                                + "bundle. Needs the tracker above to be on. On by default."),
                SettingRow.toggle("Show Mole Level Chance", () -> cfg().moleChance,
                        () -> { cfg().moleChance = !cfg().moleChance; save(); })
                        .describe("With a Mole below level 100 active, says its extra-drop chance once "
                                + "per run (level = percent, from the wiki). Off by default.")));
        rows.addAll(AlertChannelRows.forAlert("mole_reminder", "the Mole is not active",
                () -> cfg().moleReminderChannels, mask -> { cfg().moleReminderChannels = mask; save(); }));
        rows.addAll(List.of(

                SettingRow.label("— Card —"),
                SettingRow.toggle("Nucleus Run Card", () -> cfg().card,
                        () -> { cfg().card = !cfg().card; save(); })
                        .describe("Run time on the Hollows, the five crystals, this run so far, the last "
                                + "run, average per run, per hour and the total - Nucleus beside Run "
                                + "total. On by default."),
                SettingRow.toggle("Show Off The Crystal Hollows", () -> cfg().showEverywhere,
                        () -> { cfg().showEverywhere = !cfg().showEverywhere; save(); })
                        .describe("Also shows the card on other islands. The run counts only on the "
                                + "Crystal Hollows either way. Off by default."),
                SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.NUCLEUS_RUN}, "Edit Nucleus Run Card")))
                        .describe("Opens the editor where you drag the card and scale it."),
                SettingRow.toggle("Chat Summary After Each Run", () -> cfg().chatSummary,
                        () -> { cfg().chatSummary = !cfg().chatSummary; save(); })
                        .describe("When the bundle is announced: profit, time, the three most valuable "
                                + "drops and anything that had no price. Only you see it. On by default."),

                SettingRow.label("— Jungle Temple —"),
                SettingRow.toggle("Jungle Temple Cheese Waypoint", () -> cfg().templeCheese,
                        () -> { cfg().templeCheese = !cfg().templeCheese; save(); })
                        .describe("Once you stand at the Jungle Temple door - the Kalhuiki Door Guardian "
                                + "in view within 8 blocks, or one of its lines in your chat - marks the "
                                + "cheese spot at a fixed offset from it (29 east, 32 down, 48 south), "
                                + "with the distance. Shown until the Amethyst is found or the run ends; "
                                + "a new lobby needs the guardian again. Without the tracker above it "
                                + "stays until you change lobby. The offset is estimated: it has not been "
                                + "checked in game yet, so this is off by default."),
                SettingRow.color("Temple Cheese Colour", () -> cfg().templeCheeseColorHex,
                        () -> 0xFF000000 | TempleCheeseWaypoint.DEFAULT_RGB,
                        NucleusRunModule::openTempleColorPicker)
                        .describe("The colour of the cheese waypoint's beam and label. Clear it in the "
                                + "picker to go back to purple."),

                SettingRow.label("— Prices —"),
                SettingRow.segmented("Bazaar Price", SIDES, () -> cfg().priceSide,
                        value -> { cfg().priceSide = value; save(); })
                        .describe("Instant-sell values Bazaar items at the highest buy order (what "
                                + "selling now pays); Sell offer at the lowest sell offer. Other items "
                                + "use lowest BIN. No tax. A finished run keeps the prices it was "
                                + "valued at. Default: Instant-sell."),
                SettingRow.toggle("Count Self-Obtained Parts As Cost", () -> cfg().countSelfObtainedCosts,
                        () -> { cfg().countSelfObtainedCosts = !cfg().countSelfObtainedCosts; save(); })
                        .describe("On: every key, egg, part and compass used up is charged at market "
                                + "price. Off: one this run looted itself is free. On by default."),

                SettingRow.label("— Data —"),
                SettingRow.toggle("Capture Log", () -> cfg().captureLog,
                        () -> { cfg().captureLog = !cfg().captureLog; save(); })
                        .describe("Writes the Crystal Hollows lines this reads to the game log under "
                                + "[SBS][Nucleus], so wording Hypixel changes can be fixed from one run. "
                                + "On by default."),
                SettingRow.button("Reset History & Totals", NucleusRunModule::confirmReset)
                        .describe("Deletes this profile's run history, lifetime totals and the run in "
                                + "progress. Asks first.")));
        return rows;
    }

    private static void confirmReset() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen back = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        open(new ConfirmScreen(yes -> {
            if (yes) {
                NucleusRunTracker.getInstance().resetAll();
            }
            minecraft.setScreenAndShow(back);
        }, Component.literal("Reset Nucleus run history?"),
                Component.literal("Every finished run, the lifetime totals and the run in progress of "
                        + "this profile are deleted. This cannot be undone.")));
    }

    private static void openTempleColorPicker() {
        Screen previous = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        open(new sbs.modid.client.ui.theme.ThemeColorPickerScreen("Nucleus Run  •  Temple Cheese",
                cfg().templeCheeseColorHex, value -> {
                    cfg().templeCheeseColorHex = value == null ? "" : value;
                    save();
                }, previous));
    }

    private static void open(Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }
}
