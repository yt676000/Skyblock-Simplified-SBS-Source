/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.milestone;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Milestone module (Quality of Life): the "Your Milestone" line from the party-stats tab widget as a
 * movable HUD card, so it is readable without holding tab through a fight. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class MilestoneModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public MilestoneModule() {
    }

    @Override
    public String id() {
        return "milestone";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Milestone";
    }

    @Override
    public String description() {
        return "Your milestone and its progress as a HUD card, read from the tab widget";
    }

    @Override
    public int accentColor() {
        return 0xFFFFD166;
    }

    private static SBSConfig.MilestoneSettings cfg() {
        return ConfigManager.getInstance().get().milestone;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Milestone Card", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Puts the 'Your Milestone' line from the tab list on the HUD - "
                                + "your current event/party milestone without holding tab. Only "
                                + "drawn while the tab list is showing one."),
                SettingRow.label("Only drawn while the tab widget is showing a milestone"),

                SettingRow.toggle("Show Progress", () -> cfg().showProgress,
                        () -> { cfg().showProgress = !cfg().showProgress; save(); })
                        .describe("The percent-to-next-milestone line under it."),
                SettingRow.label("The \"7.1% to ...\" line under it"));
    }
}
