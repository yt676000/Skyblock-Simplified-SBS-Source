/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.foraging.logic.BeaconTuning;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Beacon Tuning module (Skills): the frequency minigame at Galatea's two beacons.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; every option
 * writes to {@link SBSConfig.BeaconTuningSettings} and is read live by {@link BeaconTuning}, so
 * changes apply without a restart.
 */
public final class BeaconTuningModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public BeaconTuningModule() {
    }

    @Override
    public String id() {
        return "beacon_tuning";
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
        return "Beacon Tuning";
    }

    @Override
    public String description() {
        return "Marks the colour, speed and pitch matching the beat at the Moonglade and Torrhus beacons";
    }

    @Override
    public int accentColor() {
        return 0xFF4FD1A5;
    }

    private static SBSConfig.BeaconTuningSettings cfg() {
        return ConfigManager.getInstance().get().beaconTuning;
    }

    /**
     * The island gate is Foraging's, not this module's: "foraging features belong on the foraging
     * islands" is one decision, and the next Galatea module inherits the answer instead of asking
     * the player again.
     */
    private static SBSConfig.ForagingSettings foraging() {
        return ConfigManager.getInstance().get().foraging;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Beacon Tuning", () -> cfg().enabled,
                                () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Rings the option matching the beat in the beacon menus on "
                                + "Galatea, so tuning a panel is reading three rings instead of "
                                + "guessing at a beat you only hear once. It only ever marks - the "
                                + "clicks stay yours.")
                        .inDevelopment(),
                SettingRow.label("§8Marks the matching colour, speed and pitch - never clicks for you"),

                SettingRow.toggle("Mark What Already Matches", () -> cfg().markMatching,
                                () -> { cfg().markMatching = !cfg().markMatching; save(); })
                        .describe("Also draws a thin ring around the traits the beacon is already "
                                + "set to, so you can see at a glance that they are right rather "
                                + "than wondering whether they were missed."),
                SettingRow.label("§8Thin ring on traits that are already correct"),

                SettingRow.toggle("Only on Galatea", () -> foraging().islandLock,
                                () -> { foraging().islandLock = !foraging().islandLock; save(); })
                        .describe("Keeps the helper to the islands the beacons stand on. Off, it "
                                + "reads any menu whose name mentions a beacon - only useful if "
                                + "Hypixel puts one somewhere new."),
                SettingRow.label("§8" + SkillIslands.describe(SkillIslands.FORAGING_ISLANDS)),

                SettingRow.label("§8The menu wording is not confirmed yet: if nothing is marked, run"),
                SettingRow.label("§8§f/sbs probe§8 at the beacon and send the captured file back"));
    }
}
