/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Pest Status module (Skills): the spray / repellent / bonus rows of Hypixel's Pests tab widget, plus
 * the Pesthunter Phillip Farming Fortune timer, as a Garden-only HUD card. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class PestStatusModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public PestStatusModule() {
    }

    @Override
    public String id() {
        return "pest_status";
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
        return "Pest Status";
    }

    @Override
    public String description() {
        return "Spray, repellent, pest bonus and the Pesthunter Phillip buff timer, on the Garden";
    }

    @Override
    public int accentColor() {
        return 0xFF57D977;
    }

    private static SBSConfig.PestStatusSettings cfg() {
        return ConfigManager.getInstance().get().pestStatus;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Pest Status", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A Garden card with the pest lines the Pest Timer card does not "
                                + "show: spray, repellent, bonus and cooldown. Needs the Pests "
                                + "widget turned on in Hypixel's /widgets."),
                SettingRow.label("Only ever drawn while the Pests widget is on the tab list"),

                SettingRow.toggle("Spray", () -> cfg().showSpray,
                        () -> { cfg().showSpray = !cfg().showSpray; save(); })
                        .describe("The plot-spray line: which plot is sprayed and for how much "
                                + "longer."),
                SettingRow.toggle("Repellent", () -> cfg().showRepellent,
                        () -> { cfg().showRepellent = !cfg().showRepellent; save(); })
                        .describe("The pest-repellent line: whether one is active and its time "
                                + "left."),
                SettingRow.toggle("Bonus", () -> cfg().showBonus,
                        () -> { cfg().showBonus = !cfg().showBonus; save(); })
                        .describe("The pest bonus line - your extra pest-spawn chance."),
                SettingRow.toggle("Cooldown", () -> cfg().showCooldown,
                        () -> { cfg().showCooldown = !cfg().showCooldown; save(); })
                        .describe("The pest-spawn cooldown line. The Pest Timer card already has "
                                + "this row, so keep it off if you run both cards."),
                SettingRow.label("Cooldown is already a row on the Pest Timer card"),

                SettingRow.toggle("Phillip Buff Timer", () -> cfg().showPhillip,
                        () -> { cfg().showPhillip = !cfg().showPhillip; save(); })
                        .describe("Counts down the Farming Fortune buff you get for handing pests "
                                + "to Pesthunter Phillip, with the fortune it is worth. Read from "
                                + "his chat line, so it starts at the hand-in - a buff from before "
                                + "you logged in is not known."),
                SettingRow.label("Starts at the hand-in; the Finnegan 60m version is picked up too"),
                SettingRow.label("Needs the Pests widget enabled in Hypixel's /widgets"));
    }
}
