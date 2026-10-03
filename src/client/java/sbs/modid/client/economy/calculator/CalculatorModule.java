/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.calculator;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Calculator module (Quality of Life): the floating in-game calculator window and the clickable
 * result chip on the container search bar.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; every option
 * writes to {@link SBSConfig.CalculatorSettings} and is read live by {@link CalculatorOverlay}, so
 * changes apply without a restart.
 */
public final class CalculatorModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public CalculatorModule() {
    }

    @Override
    public String id() {
        return "calculator";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Calculator";
    }

    @Override
    public String description() {
        return "A movable in-game calculator window with keypad, history and coin suffixes";
    }

    @Override
    public int accentColor() {
        return 0xFF4DD2B0;
    }

    private static SBSConfig.CalculatorSettings cfg() {
        return ConfigManager.getInstance().get().calculator;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Calculator", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A floating calculator window on any chest-style screen - for "
                                + "bazaar math without alt-tabbing. Understands shorthand like "
                                + "5m or 32k. Drag to move, Ctrl+Scroll to resize, '-' minimizes "
                                + "it to a button beside the search bar."),
                SettingRow.label("Floating window on any container screen - drag it, Ctrl+Scroll to resize, "
                        + "'-' minimizes it to a button left of the search bar"),

                SettingRow.toggle("Keypad", () -> cfg().keypad,
                        () -> { cfg().keypad = !cfg().keypad; save(); })
                        .describe("The clickable number pad. Off, the window shrinks to just the "
                                + "display and history, and you type with the keyboard."),
                SettingRow.label("Off shrinks the window to display + history and leaves typing to the keyboard"),

                SettingRow.rangeSlider("History Size", 5, 100, () -> cfg().historySize,
                        value -> { cfg().historySize = value; save(); }, "")
                        .describe("How many past calculations the history keeps. Click any "
                                + "history line to reuse its result."),
                SettingRow.label("Click any history line to append its result to the current input"),

                SettingRow.toggle("Clickable Search Result", () -> cfg().clickableSearchResult,
                        () -> { cfg().clickableSearchResult = !cfg().clickableSearchResult; save(); })
                        .describe("When you type math into the container search bar, a '= result' "
                                + "chip appears above it; this makes the chip clickable so the "
                                + "result goes back into the bar for further calculating."),
                SettingRow.label("Click the '= ...' chip above the container search bar to put the result "
                        + "back into the bar and keep calculating"),

                SettingRow.button("Clear History", () -> CalculatorOverlay.getInstance().clearHistory())
                        .describe("Empties the calculation history."));
    }
}
