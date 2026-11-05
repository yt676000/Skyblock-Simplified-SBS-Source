/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.gyro;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Gyro Helper module (Combat): while the Gyrokinetic Wand is held, a ring where Gravity Storm would
 * land and how far it pulls. State in {@link SBSConfig.GyroHelperSettings}, tick work in
 * {@link sbs.modid.client.combat.gyro.logic.GyroState}, drawing in
 * {@link sbs.modid.client.combat.gyro.render.GyroPreview}.
 */
public final class GyroHelperModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public GyroHelperModule() {
    }

    @Override
    public String id() {
        return "gyro_helper";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Gyro Helper";
    }

    @Override
    public String description() {
        return "Shows where your Gyrokinetic Wand's Gravity Storm would land and how far it pulls";
    }

    @Override
    public int accentColor() {
        return 0xFFB44DFF;
    }

    private static SBSConfig.GyroHelperSettings cfg() {
        return ConfigManager.getInstance().get().gyroHelper;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Gyro Helper", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("While you hold a Gyrokinetic Wand, draws a circle where Gravity "
                                + "Storm would land and how far it pulls mobs. Display only - you "
                                + "still aim and cast yourself."),
                SettingRow.toggle("Dungeons Only", () -> cfg().dungeonsOnly,
                        () -> { cfg().dungeonsOnly = !cfg().dungeonsOnly; save(); })
                        .describe("Only draw the circle inside a dungeon."),
                SettingRow.toggle("Mobs In Range", () -> cfg().showMobCount,
                        () -> { cfg().showMobCount = !cfg().showMobCount; save(); })
                        .describe("Shows how many mobs stand inside the circle next to its centre."),
                SettingRow.toggle("Out Of Range Hint", () -> cfg().showOutOfRange,
                        () -> { cfg().showOutOfRange = !cfg().showOutOfRange; save(); })
                        .describe("When no block is within reach, says so beside the crosshair "
                                + "instead of drawing a circle."),
                SettingRow.enumOptions("Ready Colour", () -> cfg().readyColor,
                        value -> { cfg().readyColor = value; save(); }, v -> v.displayName())
                        .describe("The circle's colour while Gravity Storm is ready."),
                SettingRow.enumOptions("Cooldown Colour", () -> cfg().cooldownColor,
                        value -> { cfg().cooldownColor = value; save(); }, v -> v.displayName())
                        .describe("The circle's colour, dimmed, while the 30 second cooldown runs."),
                SettingRow.intField("Range Override", 0, 60, () -> cfg().rangeOverride,
                        value -> { cfg().rangeOverride = value; save(); }, "blocks")
                        .describe("How far the cast reaches. 0 uses the wand's description, or 24."),
                SettingRow.intField("Radius Override", 0, 30, () -> cfg().radiusOverride,
                        value -> { cfg().radiusOverride = value; save(); }, "blocks")
                        .describe("How far the storm pulls. 0 uses the wand's description, or 10."));
    }
}
