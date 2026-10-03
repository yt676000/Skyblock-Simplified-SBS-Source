/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.yearofthepig;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Year of the Pig module (Events live under Skills, next to the other session trackers): the Shiny
 * Orb profit tracker plus the helpers that make the 90-second pig chase winnable – pig highlights,
 * lines to your pig and its orb, the countdown card and the expiry warning. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class YearOfThePigModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public YearOfThePigModule() {
    }

    @Override
    public String id() {
        return "year_of_the_pig";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public String displayName() {
        return "Year of the Pig";
    }

    @Override
    public String description() {
        return "Shiny Orb profit tracker, pig & orb lines, 90s countdown";
    }

    @Override
    public int accentColor() {
        return 0xFFFF8FC7;
    }

    private static SBSConfig.YearOfThePigSettings cfg() {
        return ConfigManager.getInstance().get().yearOfThePig;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Year of the Pig", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Helpers for the Year of the Pig event: a profit tracker for the "
                                + "Shiny Orbs, a countdown on your placed orb, and highlights on "
                                + "the shiny pigs and orbs in the world."),
                SettingRow.label("The Shiny Pig event: orbs cost 5,000 each, so profit can go red"),

                SettingRow.label("— Profit Tracker —"),
                SettingRow.toggle("Session Tracker", () -> cfg().showTracker,
                        () -> { cfg().showTracker = !cfg().showTracker; save(); })
                        .describe("A card with your session: orbs used, success rate, drops and "
                                + "profit. Orbs cost 5,000 coins each, so the profit line can go "
                                + "negative."),
                SettingRow.button("Move / Resize Tracker", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.SHINY_PIG_TRACKER}, "Edit Shiny Pig Tracker")))
                        .describe("Opens the editor where you drag the tracker card anywhere on "
                                + "the screen and scale it."),
                SettingRow.button("Reset Session Stats",
                        () -> ShinyOrbTracker.getInstance().resetSession())
                        .describe("Sets the session numbers back to zero. The all-time drop file "
                                + "on disk is kept."),
                SettingRow.label("All-time drops: config/sbs/tracker/shinypigtracker.txt"),

                SettingRow.label("— Orb Timer —"),
                SettingRow.toggle("Countdown Card", () -> cfg().showTimer,
                        () -> { cfg().showTimer = !cfg().showTimer; save(); })
                        .describe("A countdown above the crosshair while your Shiny Orb is placed "
                                + "- orbs expire after 90 seconds, and an expired orb is 5,000 "
                                + "coins gone."),
                SettingRow.button("Move / Resize Timer", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.SHINY_ORB_TIMER}, "Edit Shiny Orb Timer")))
                        .describe("Opens the editor where you drag the countdown anywhere on the "
                                + "screen and scale it."),
                SettingRow.toggle("Expiry Warning Sound", () -> cfg().expiryWarning,
                        () -> { cfg().expiryWarning = !cfg().expiryWarning; save(); })
                        .describe("Plays a warning sound shortly before your orb expires, so you "
                                + "can run back and use it."),
                SettingRow.rangeSlider("Warn At", 3, 45, () -> cfg().expiryWarningSeconds,
                        value -> { cfg().expiryWarningSeconds = value; save(); }, "s")
                        .describe("How many seconds before expiry the warning sound plays."),

                SettingRow.label("— World Helpers —"),
                SettingRow.toggle("Highlight Shiny Pigs", () -> cfg().highlightPigs,
                        () -> { cfg().highlightPigs = !cfg().highlightPigs; save(); })
                        .describe("Draws a box around the event's shiny pigs, so you spot yours "
                                + "in the crowd."),
                SettingRow.toggle("Include Plain Pigs", () -> cfg().highlightAllPigs,
                        () -> { cfg().highlightAllPigs = !cfg().highlightAllPigs; save(); })
                        .describe("Also boxes ordinary pigs. Turn this on only if the event's pigs "
                                + "are not being recognised by name for you."),
                SettingRow.label("On if the event's pigs aren't detected by name"),
                SettingRow.toggle("Line To Pig", () -> cfg().lineToPig,
                        () -> { cfg().lineToPig = !cfg().lineToPig; save(); })
                        .describe("Draws a line from your crosshair to the shiny pig, so you can "
                                + "chase it without losing it."),
                SettingRow.toggle("Line Pig → Orb", () -> cfg().lineToOrb,
                        () -> { cfg().lineToOrb = !cfg().lineToOrb; save(); })
                        .describe("Draws a line from the pig to your placed orb, showing the "
                                + "direction you need to herd it."),
                SettingRow.enumOptions("Pig Color", () -> cfg().pigColor,
                        value -> { cfg().pigColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the pig boxes and their line. Click to cycle."),
                SettingRow.enumOptions("Orb Color", () -> cfg().orbColor,
                        value -> { cfg().orbColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the orb highlight and its line. Click to cycle."),
                SettingRow.rangeSlider("Line Thickness", 1, 6, () -> cfg().lineThickness,
                        value -> { cfg().lineThickness = value; save(); }, "px")
                        .describe("Thickness of the helper lines in pixels."),

                SettingRow.label("— Alerts —"),
                SettingRow.toggle("Orb Charged Sound", () -> cfg().chargedAlert,
                        () -> { cfg().chargedAlert = !cfg().chargedAlert; save(); })
                        .describe("Plays a sound when your orb is fully charged and ready to be "
                                + "clicked for the reward."),
                SettingRow.toggle("\"Already Clicked\" Sound", () -> cfg().takenAlert,
                        () -> { cfg().takenAlert = !cfg().takenAlert; save(); })
                        .describe("Plays a (different) sound if you click an orb you already "
                                + "collected from - saves the second look."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
