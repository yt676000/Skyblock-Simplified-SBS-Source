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
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Frozen Corpse highlight module (Skills): boxes Glacite-Mineshaft corpses once they have been seen, so a
 * spotted corpse can be walked back to even after it leaves render distance. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class FrozenCorpseModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public FrozenCorpseModule() {
    }

    @Override
    public String id() {
        return "frozen_corpse_highlight";
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
        return "Frozen Corpse Highlight";
    }

    @Override
    public String description() {
        return "Boxes Glacite Mineshaft corpses once seen, so you can find them again";
    }

    @Override
    public int accentColor() {
        return 0xFF00E5FF;
    }

    private static SBSConfig.FrozenCorpseSettings cfg() {
        return ConfigManager.getInstance().get().frozenCorpse;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Only On Mining Islands", MiningRoutesModule::islandLock,
                        MiningRoutesModule::toggleIslandLock)
                        .describe("Keeps every mining feature quiet unless you are on a mining "
                                + "island. One shared switch - changing it here changes it for "
                                + "all mining modules."),
                SettingRow.label("Shared by every mining feature; off anywhere else"),
                SettingRow.label("§8" + sbs.modid.client.skills.SkillIslands.describe(
                        sbs.modid.client.skills.SkillIslands.MINING_ISLANDS)),

                SettingRow.toggle("Frozen Corpse Highlight", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .anchor("frozen_corpse_highlight", "frozen_corpse_esp")
                        .describe("Remembers every Frozen Corpse you have walked past in a Glacite "
                                + "Mineshaft and keeps a box on it, so you can loot them all at "
                                + "the end instead of hunting for them again."),
                SettingRow.label("Remembers corpses you have seen in a Mineshaft and boxes them"),
                SettingRow.enumOptions("Box Colour", () -> cfg().color,
                        value -> { cfg().color = value; save(); }, v -> v.displayName())
                        .describe("The color of the corpse boxes. Click to cycle."),
                SettingRow.toggle("Show Pointer Lines", () -> cfg().showTracers,
                        () -> { cfg().showTracers = !cfg().showTracers; save(); })
                        .anchor("show_tracers")
                        .describe("Draws a thin line from your crosshair to every remembered "
                                + "corpse, so you can walk the lines when leaving the shaft."),
                SettingRow.label("A line from your crosshair to every remembered corpse"));
    }
}
