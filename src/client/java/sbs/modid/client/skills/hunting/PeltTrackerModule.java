/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Pelt Tracker module (Skills): finds the animal Trevor the Trapper sends you after and keeps a box
 * + tracer on it - through walls and through its own invisibility gimmicks, because finding it IS
 * the quest. Detection is chat-armed (Trevor's announcement) and health-fingerprinted (each rarity
 * tier has a fixed max health no ambient desert animal carries). Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class PeltTrackerModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public PeltTrackerModule() {
    }

    @Override
    public String id() {
        return "pelt_tracker";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.HUNTING;
    }

    @Override
    public String displayName() {
        return "Pelt Tracker";
    }

    @Override
    public String description() {
        return "Boxes and traces the animal Trevor the Trapper sends you after";
    }

    @Override
    public int accentColor() {
        return 0xFFFF9A2E;
    }

    private static SBSConfig.PeltSettings cfg() {
        return ConfigManager.getInstance().get().pelt;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Pelt Tracker", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Finds the trapper's quest animal among the desert's ordinary "
                                + "livestock: Hypixel writes the tier straight into its nametag, "
                                + "and no normal animal is called Untrackable. Works on every tier "
                                + "and every species."),
                SettingRow.label("Found by its nametag alone - no need to have seen Trevor's chat line"),

                SettingRow.toggle("Highlight The Animal", () -> cfg().highlightAnimal,
                        () -> { cfg().highlightAnimal = !cfg().highlightAnimal; save(); })
                        .describe("Boxes the quest animal through walls, with its rarity above the "
                                + "box. An animal mid-invisibility keeps its box - the nametag "
                                + "stand stays behind, and that is what is tracked."),
                SettingRow.enumOptions("Box Colour", () -> cfg().color,
                        value -> { cfg().color = value; save(); }, v -> v.displayName())
                        .describe("The colour of the box and pointer line."),
                SettingRow.toggle("Pointer Line To The Animal", () -> cfg().showTracer,
                        () -> { cfg().showTracer = !cfg().showTracer; save(); })
                        .anchor("tracer_to_the_animal")
                        .describe("A line from your crosshair to the animal, so you know which way "
                                + "to ride before the box is even on screen."),
                SettingRow.toggle("Found Ping", () -> cfg().foundPing,
                        () -> { cfg().foundPing = !cfg().foundPing; save(); })
                        .describe("One chat line and a ping the moment the animal is first "
                                + "detected."),

                SettingRow.toggle("Pelts On The Crest", () -> cfg().crestCounter,
                        () -> { cfg().crestCounter = !cfg().crestCounter; save(); })
                        .describe("Writes the Trapper Crest's collected-pelt count over the item "
                                + "in your hotbar, where a stack count would go. The number is read "
                                + "from the crest's own Collected tooltip line, so it is Hypixel's "
                                + "count and not one this mod keeps."),
                SettingRow.label("§8Nothing is drawn if the crest carries no Collected line"),

                SettingRow.toggle("Trapper Cooldown", () -> cfg().trapperCooldown,
                        () -> { cfg().trapperCooldown = !cfg().trapperCooldown; save(); })
                        .describe("Writes Trevor's state one line under his name while you stand "
                                + "near him. Green Ready means he will take you now, and it is "
                                + "only shown once a cooldown has actually been watched run out."),
                SettingRow.label("§8Talk to check = nothing seen yet this session"),
                SettingRow.label("§8~14s = counted from the setting below, 14s = Trevor's own number"),
                SettingRow.label("§8Ready? = the assumption ran out, Ready = he confirmed it"),
                SettingRow.intField("Assumed Cooldown", 1, 600, () -> cfg().cooldownSeconds,
                        value -> { cfg().cooldownSeconds = value; save(); }, "s")
                        .describe("What the countdown starts from after a hunt ends, shown with a "
                                + "~ because it is only an assumption. The moment Trevor states "
                                + "the real remaining time himself his number replaces it, and the "
                                + "~ goes away. Set this to whatever your own cooldown really is."),
                SettingRow.label("§8Every detection is logged as [SBS][Pelt] with the full nametag"));
    }
}
