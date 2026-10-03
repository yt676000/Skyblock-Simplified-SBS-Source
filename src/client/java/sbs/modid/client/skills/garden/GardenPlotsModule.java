/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.garden.ui.GardenPlotsOverlay;
import sbs.modid.client.skills.garden.ui.GardenPlotsScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Garden Plots module (Skills): the Garden's plot grid on a hotkey - real SkyBlock icons, plots the
 * Pests widget calls infested flashing red, and one click to teleport. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class GardenPlotsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public GardenPlotsModule() {
    }

    @Override
    public String id() {
        return "garden_plots";
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
        return "Garden Plots";
    }

    @Override
    public String description() {
        return "The Garden plot grid on a hotkey: pest warnings and one-click plot teleports";
    }

    @Override
    public int accentColor() {
        return 0xFF57D977;
    }

    private static SBSConfig.GardenPlotsSettings cfg() {
        return ConfigManager.getInstance().get().gardenPlots;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /**
     * Opens the standalone plot grid (settings button and the in-world hotkey both land here).
     *
     * <p>Inside a container the window form is used instead, and it is restored by its own button
     * beside the search bar rather than by this hotkey - key presses never reach here while a screen
     * is open, which is exactly the same way the Recipe Viewer's window is reopened.
     */
    public static void openScreen() {
        if (!cfg().enabled) {
            return;
        }
        Minecraft.getInstance().setScreenAndShow(new GardenPlotsScreen());
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Garden Plots", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Your Garden's plot layout as a small grid: click a plot to "
                                + "teleport to it (/plottp). The layout is learned the first time "
                                + "you open Hypixel's Configure Plots menu."),
                SettingRow.label("The plot grid on a hotkey, with one-click teleports"),

                SettingRow.button("Open Plot Grid", GardenPlotsModule::openScreen)
                        .describe("Opens the plot grid screen."),
                SettingRow.keybind("Open Key", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("A key that opens the plot grid while playing. Click the row, "
                                + "press a key; Esc unbinds."),
                SettingRow.label("Icons load the first time you open Configure Plots"),

                SettingRow.keybind("Teleport To Infested Plot", () -> cfg().infestedKey,
                        key -> { cfg().infestedKey = key; save(); })
                        .describe("One press teleports you to a plot the Pests widget lists as "
                                + "infested. Press again to go to the next one, so you can walk "
                                + "them. Does nothing when no plot is infested, and nothing "
                                + "outside the Garden. It never teleports you on its own - only "
                                + "when you press it."),
                SettingRow.options("Infested Order",
                        () -> List.of("Nearest first", "By plot number"),
                        () -> cfg().infestedNearestFirst ? "Nearest first" : "By plot number",
                        picked -> {
                            cfg().infestedNearestFirst = "Nearest first".equals(picked);
                            save();
                        })
                        .describe("The order repeated presses walk the infested plots in. There is "
                                + "no 'most pests' order because there is no such number to sort "
                                + "by: the widget lists a plot once however many pests are on it."),
                SettingRow.label("Press again for the next infested plot"),

                SettingRow.toggle("Flash Infested Plots", () -> cfg().flashInfested,
                        () -> { cfg().flashInfested = !cfg().flashInfested; save(); })
                        .describe("Pulses the plots that currently have pests red in the grid, so "
                                + "the click that kills the pests is also the click that gets you "
                                + "there."),
                SettingRow.label("Pulses the plots the Pests tab widget lists, red"),

                SettingRow.toggle("Window In Inventory", () -> cfg().showInInventory,
                        () -> { cfg().showInInventory = !cfg().showInInventory; save(); })
                        .describe("Also offers the plot grid as a small movable window beside your "
                                + "inventory while on the Garden. It starts minimized as a button "
                                + "left of the search bar."),
                SettingRow.label("A movable, minimizable window beside the inventory, Garden only"),
                SettingRow.label("Starts minimized; its button sits left of the search bar"));
    }
}
