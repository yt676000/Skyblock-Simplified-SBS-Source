/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.beacon;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Beacon Tuner module (Skills): reads the Moonglade beacon's beat and names its pitch, so it is not
 * matched by ear.
 *
 * <p><b>Off by default, because it is unfinished.</b> It reads the beat and says nothing about the
 * controls, which is half of what was asked for — the half that can be built without opening a menu
 * nobody has captured. Shipping it disabled is the convention for a feature that works but is
 * knowingly incomplete; the settings row says so rather than leaving a player to discover it.
 */
public final class BeaconTunerModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public BeaconTunerModule() {
    }

    @Override
    public String id() {
        return "beacon_tuner";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.FORAGING;
    }

    @Override
    public String displayName() {
        return "Beacon Tuner";
    }

    @Override
    public String description() {
        return "Reads the Moonglade beacon's beat: its pitch as a note, and its measured speed";
    }

    @Override
    public int accentColor() {
        return 0xFF8FD6FF;
    }

    private static SBSConfig.BeaconTunerSettings cfg() {
        return ConfigManager.getInstance().get().beaconTuner;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Beacon Tuner", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("At the beacon in South Reaches, a card naming the beat's pitch as "
                                + "a note and its speed as a measured interval, so neither has to be "
                                + "matched by ear.")
                        .inDevelopment(),
                SettingRow.label("§8Only at the beacon, in South Reaches on Moonglade Marsh."),
                SettingRow.label("§8It reads the beat. It does not read your control settings, and"),
                SettingRow.label("§8it never tunes anything - the dials stay yours."),
                SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.BEACON_TUNER}, "Edit Beacon Tuner")))
                        .describe("Opens the editor where you drag the card anywhere on the screen "
                                + "and scale it."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
