/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.garden.logic.GardenMenuHelpers;
import sbs.modid.client.skills.garden.logic.PestTracker;
import sbs.modid.client.skills.garden.model.GardenLevel;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Garden module (Skills): the features that are about the <i>island</i> – the Garden level, the
 * pests that spawn on it, and the numbers its menus leave you to work out.
 *
 * <p>Kept apart from the Farming module on purpose. Farming features follow the tool in your hand
 * and work wherever you farm; these follow the Garden itself and are meaningless anywhere else. A
 * player looking for "why does my pest warning not fire" should not have to scroll past crop
 * milestones to find it.
 *
 * <p>Logic lives in {@link GardenLevel}, {@link PestTracker} and {@link GardenMenuHelpers}; this
 * class only registers the module and its rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class GardenModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public GardenModule() {
    }

    @Override
    public String id() {
        return "garden";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.FARMING_GARDEN;
    }

    @Override
    public String displayName() {
        return "Garden";
    }

    @Override
    public String description() {
        return "Garden level, pest timer and warnings, and coin values in the Garden menus";
    }

    @Override
    public int accentColor() {
        return 0xFF8FD14D;
    }

    private static SBSConfig.GardenSettings cfg() {
        return ConfigManager.getInstance().get().garden;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new java.util.ArrayList<>(List.of(
                SettingRow.label("— Garden Level —"),
                SettingRow.toggle("Garden Level Card", () -> cfg().gardenLevel,
                        () -> { cfg().gardenLevel = !cfg().gardenLevel; save(); })
                        .describe("A card with your Garden level and XP progress, read from the "
                                + "Garden tab widget."),
                SettingRow.label("Level and progress from the Garden tab widget"),
                SettingRow.toggle("Overflow Levels", () -> cfg().gardenLevelOverflow,
                        () -> { cfg().gardenLevelOverflow = !cfg().gardenLevelOverflow; save(); })
                        .describe("Keeps counting levels past the real maximum, so long-term "
                                + "grinders still see a number move."),
                SettingRow.label("Keep counting past the last real level"),
                SettingRow.button("Move / Resize Level Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.GARDEN_LEVEL}, "Edit Garden Level Card")))
                        .describe("Opens the editor where you drag the level card anywhere on the "
                                + "screen and scale it."),

                SettingRow.label("— Pests —"),
                SettingRow.toggle("Pest Card", () -> cfg().pestTimer,
                        () -> { cfg().pestTimer = !cfg().pestTimer; save(); })
                        .describe("A card with everything pest: how many are alive, which plots "
                                + "are infested, when the last one spawned and the cooldown until "
                                + "the next can."),
                SettingRow.label("Pests alive, infested plots, last spawn and the cooldown"),
                SettingRow.toggle("Spawn Title", () -> cfg().pestSpawnTitle,
                        () -> { cfg().pestSpawnTitle = !cfg().pestSpawnTitle; save(); })
                        .describe("A big text above the crosshair the moment a pest spawns."),
                SettingRow.toggle("Spawn Sound", () -> cfg().pestSpawnSound,
                        () -> { cfg().pestSpawnSound = !cfg().pestSpawnSound; save(); })
                        .describe("A ping the moment a pest spawns."),
                SettingRow.label("— How you are told a pest spawned —"),
                SettingRow.toggle("Cooldown Warning", () -> cfg().pestCooldownWarning,
                        () -> { cfg().pestCooldownWarning = !cfg().pestCooldownWarning; save(); })
                        .describe("An in-game title and ping when the pest cooldown is up, i.e. "
                                + "when farming can turn up a pest again."),
                SettingRow.label("— How you are told the cooldown is up —"),
                SettingRow.intField("Warn Before", 0, 60, () -> cfg().pestWarnBeforeSeconds,
                        value -> { cfg().pestWarnBeforeSeconds = value; save(); }, "s")
                        .describe("How many seconds early that heads-up comes, so you can be back "
                                + "at the crops by the time the cooldown actually ends."),
                SettingRow.label("Warn this many seconds before pests can spawn again"),
                SettingRow.toggle("Cooldown Card", () -> cfg().pestCooldownHud,
                        () -> { cfg().pestCooldownHud = !cfg().pestCooldownHud; save(); })
                        .describe("A small card with just the countdown, which turns amber the "
                                + "moment pests can spawn again. Only shown on the Garden."),
                SettingRow.button("Move / Resize Cooldown Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.PEST_COOLDOWN}, "Edit Pest Cooldown Card")))
                        .describe("Opens the editor where you drag that card anywhere on the "
                                + "screen and scale it."),
                SettingRow.label("Just the countdown, amber when pests can spawn again"),
                SettingRow.toggle("Custom Cooldown", () -> cfg().pestCustomCooldown,
                        () -> { cfg().pestCustomCooldown = !cfg().pestCustomCooldown; save(); })
                        .describe("Use your own cooldown length everywhere instead of the one "
                                + "Hypixel shows in the tab list."),
                SettingRow.toggle("Show Hypixel's Cooldown", () -> cfg().pestShowHypixelCooldown,
                        () -> { cfg().pestShowHypixelCooldown = !cfg().pestShowHypixelCooldown;
                            save(); })
                        .describe("With Custom Cooldown on, also show Hypixel's own number in "
                                + "small text under your countdown whenever the two differ by "
                                + "more than 5 seconds."),
                SettingRow.intField("Cooldown", 75, 1200, () -> cfg().pestCooldownSeconds,
                        value -> { cfg().pestCooldownSeconds = value; save(); }, "s")
                        .describe("Your cooldown length in seconds. Hypixel's base is 300 "
                                + "(5 minutes); 75 s is the fastest any setup can reach and 1200 s "
                                + "the slowest, under Pest Repellent MAX."),
                SettingRow.toggle("Finnegan's Pest Eradicator", () -> cfg().pestFinnegan,
                        () -> { cfg().pestFinnegan = !cfg().pestFinnegan; save(); })
                        .describe("Tell the timer Mayor Finnegan's Pest Eradicator perk is active "
                                + "- the perk changes the real cooldown, and the timer is only "
                                + "honest if it knows."),
                SettingRow.intField("Cooldown (Finnegan)", 75, 1200, () -> cfg().pestCooldownFinnegan,
                        value -> { cfg().pestCooldownFinnegan = value; save(); }, "s")
                        .describe("Your cooldown length while the Finnegan toggle is on. The "
                                + "perk takes 20% off, so 240 s from the 300 s base."),
                SettingRow.label("The perk changes the real cooldown - set it here to keep the timer honest"),
                SettingRow.toggle("Pest Highlight", () -> cfg().pestHighlight,
                        () -> { cfg().pestHighlight = !cfg().pestHighlight; save(); })
                        .anchor("pest_highlight", "pest_esp")
                        .describe("Boxes the pests themselves so they are visible against the "
                                + "crops - but only while they are actually on your screen."),
                SettingRow.toggle("Pest Pointer Lines", () -> cfg().pestHighlightTracer,
                        () -> { cfg().pestHighlightTracer = !cfg().pestHighlightTracer; save(); })
                        .anchor("pest_tracers")
                        .describe("A line from your crosshair to each pest, so you can fly "
                                + "straight there. Lines to the infested plots themselves are "
                                + "the separate Plot Pointer Lines toggle below."),
                SettingRow.label("A line from your crosshair to each pest"),
                SettingRow.label("Boxes pests, but only while they are on screen"),
                SettingRow.enumOptions("Pest Box Colour", () -> cfg().pestHighlightColor,
                        value -> { cfg().pestHighlightColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the pest boxes and lines. Click to cycle."),
                SettingRow.toggle("Infested Plot Highlight", () -> cfg().pestPlotHighlight,
                        () -> { cfg().pestPlotHighlight = !cfg().pestPlotHighlight; save(); })
                        .describe("Outlines every plot the tab list reports as infested - all of "
                                + "them at once, from anywhere on the Garden. Driven by the "
                                + "Pests widget's own Plots line, so no pest has to be nearby "
                                + "or even loaded."),
                SettingRow.label("All infested plots at once, straight from the tab list"),
                SettingRow.enumOptions("Plot Box Colour", () -> cfg().pestPlotColor,
                        value -> { cfg().pestPlotColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the plot outlines. Click to cycle."),
                SettingRow.toggle("Plot Pointer Lines", () -> cfg().pestPlotTracer,
                        () -> { cfg().pestPlotTracer = !cfg().pestPlotTracer; save(); })
                        .anchor("plot_tracers")
                        .describe("A line from your crosshair to the centre of every infested "
                                + "plot. Off by default - with several plots infested at once "
                                + "the converging lines get busy fast."),
                SettingRow.label("Lines to every infested plot's centre - can get busy"),
                SettingRow.toggle("Tinted Plot Walls", () -> cfg().pestPlotWalls,
                        () -> { cfg().pestPlotWalls = !cfg().pestPlotWalls; save(); })
                        .describe("Fills the plot's border with a see-through colored wall "
                                + "instead of just edge lines - much more visible from far away."),
                SettingRow.rangeSlider("Plot Outline Height", 1, 256, () -> cfg().pestPlotWallHeight,
                        value -> { cfg().pestPlotWallHeight = value; save(); }, "blocks")
                        .describe("How many blocks up the plot outline and walls reach, measured "
                                + "from the Garden's bedrock floor - the one height nothing can "
                                + "move, so the outline never shifts however deep a plot is dug or "
                                + "wherever you stand. Set it high enough to also clear the "
                                + "surface, since a dug plot's depth counts into it."),
                SettingRow.label("Blocks up from bedrock - fixed, never follows you or the terrain"),
                SettingRow.intField("Wall Tint", 0, 100, () -> cfg().pestPlotWallOpacity,
                        value -> { cfg().pestPlotWallOpacity = value; save(); }, "%")
                        .describe("How solid the tinted walls are - keep it low enough to still "
                                + "see your farm through them."),
                SettingRow.label("Low enough to still see through the wall"),
                SettingRow.toggle("Mute Vacuum", () -> cfg().muteVacuum,
                        () -> { cfg().muteVacuum = !cfg().muteVacuum; save(); })
                        .describe("Silences the pest vacuum's sound effects."),
                SettingRow.text("Vacuum Sound Ids", "left empty = learn mode", 200,
                        () -> cfg().vacuumSoundIds,
                        value -> { cfg().vacuumSoundIds = value; save(); })
                        .describe("Which sound ids count as vacuum sounds. Leave it empty first: "
                                + "every sound heard while holding a vacuum is then logged as "
                                + "[SBS][Vacuum], and you paste the id (or part of it) here to "
                                + "mute exactly that sound."),
                SettingRow.label("Empty logs every sound heard with a vacuum held as [SBS][Vacuum]"),
                SettingRow.label("Paste the id (or part of it) here to mute exactly that sound"),
                SettingRow.button("Move / Resize Pest Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.PEST_TIMER, HudElement.PEST_ALERT},
                        "Edit Pest Overlays")))
                        .describe("Opens the editor where you drag the pest card and the spawn "
                                + "title anywhere on the screen and scale them."),

                SettingRow.label("— Menus —"),
                SettingRow.toggle("SkyMart Copper Value", () -> cfg().skyMartCopperPrice,
                        () -> { cfg().skyMartCopperPrice = !cfg().skyMartCopperPrice; save(); })
                        .describe("Adds the coins-per-copper value to every SkyMart item, using "
                                + "live Bazaar prices - so you buy the item that converts your "
                                + "copper into the most coins."),
                SettingRow.label("Coins per copper for every SkyMart line, from the Bazaar"),
                SettingRow.toggle("Plot Price", () -> cfg().plotPrice,
                        () -> { cfg().plotPrice = !cfg().plotPrice; save(); })
                        .describe("Shows what a plot's compost cost is worth in coins."),
                SettingRow.label("The compost cost of a plot, in coins"),
                SettingRow.toggle("Milestone Numbers", () -> cfg().milestoneNumbers,
                        () -> { cfg().milestoneNumbers = !cfg().milestoneNumbers; save(); })
                        .describe("Writes the milestone tier directly on each slot of the crop "
                                + "milestone menu, instead of it hiding in every tooltip."),
                SettingRow.toggle("Upgrade Numbers", () -> cfg().upgradeNumbers,
                        () -> { cfg().upgradeNumbers = !cfg().upgradeNumbers; save(); })
                        .describe("Writes the upgrade level directly on each slot of the Garden "
                                + "upgrade menus."),
                SettingRow.label("Tier / level on the slot instead of in every tooltip")));

        // The two pest alerts each get the shared channel picker, inserted right after the label
        // that introduces them - built here rather than inline because they are lists, not rows.
        insertAfter(rows, "— How you are told a pest spawned —",
                AlertChannelRows.forAlert("pest_spawn", "a pest spawns",
                        () -> cfg().pestSpawnChannels,
                        value -> { cfg().pestSpawnChannels = value; save(); }));
        insertAfter(rows, "— How you are told the cooldown is up —",
                AlertChannelRows.forAlert("pest_ready", "pests can spawn again",
                        () -> cfg().pestReadyChannels,
                        value -> { cfg().pestReadyChannels = value; save(); }));
        return rows;
    }

    /** Puts {@code extra} directly after the row whose label is {@code marker}. */
    private static void insertAfter(List<SettingRow> rows, String marker, List<SettingRow> extra) {
        for (int i = 0; i < rows.size(); i++) {
            if (marker.equals(rows.get(i).label())) {
                rows.addAll(i + 1, extra);
                return;
            }
        }
        rows.addAll(extra);   // marker gone (renamed): still show them rather than losing them
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
