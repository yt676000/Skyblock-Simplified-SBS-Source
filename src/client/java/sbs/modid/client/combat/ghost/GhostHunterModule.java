/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.ghost;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Ghost Hunter module (Combat): marks the ghost worth killing next in The Mist - the cheapest one to
 * actually reach, not the one that happens to be nearest - with a box and a tracer. Self-registered
 * via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class GhostHunterModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public GhostHunterModule() {
    }

    @Override
    public String id() {
        return "ghost_hunter";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Ghost Hunter";
    }

    @Override
    public String description() {
        return "Marks the best next ghost in The Mist - closest by walk AND turn time - and traces to it";
    }

    @Override
    public int accentColor() {
        return 0xFFB050FF;
    }

    private static SBSConfig.GhostHunterSettings cfg() {
        return ConfigManager.getInstance().get().ghostHunter;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Ghost Hunter", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Boxes the ghost worth going for next and draws a line to it. "
                                + "Not simply the nearest one: every ghost is scored by how long it "
                                + "would really take you to get on it - the walk, the climb, and "
                                + "how far your crosshair has to swing - and the cheapest wins."),
                SettingRow.label("The best next ghost, not the nearest one - turning costs time too"),

                SettingRow.rangeSlider("Turn Cost", 0, 40,
                        () -> cfg().turnCost,
                        value -> { cfg().turnCost = value; save(); }, " blocks")
                        .describe("How many blocks of walking a full 180° turn is worth. At 10, a "
                                + "ghost behind you has to be 10 blocks closer than one straight "
                                + "ahead before it is picked - so a ghost 5 blocks further away in "
                                + "front still wins. 0 turns the whole idea off and picks the "
                                + "nearest."),
                SettingRow.rangeSlider("Wall Penalty", 0, 40,
                        () -> cfg().wallPenalty,
                        value -> { cfg().wallPenalty = value; save(); }, " blocks")
                        .describe("Extra cost for a ghost you have no clear line to. The walk to "
                                + "one behind a wall is never the straight line its distance "
                                + "suggests. 0 ignores walls entirely."),
                SettingRow.rangeSlider("Contested Penalty", 0, 40,
                        () -> cfg().contestedPenalty,
                        value -> { cfg().contestedPenalty = value; save(); }, " blocks")
                        .describe("Extra cost for a ghost another player is standing closer to - "
                                + "usually their kill, not yours. 0 ignores other players."),
                SettingRow.rangeSlider("Max Range", 8, 120,
                        () -> cfg().maxRange,
                        value -> { cfg().maxRange = value; save(); }, " blocks")
                        .describe("How far away a ghost may be to be considered at all."),

                SettingRow.enumOptions("Colour", () -> cfg().color,
                        value -> { cfg().color = value; save(); }, v -> v.displayName())
                        .describe("The colour of the box and the pointer line. Click to cycle."),
                SettingRow.toggle("Show Pointer Line", () -> cfg().showTracer,
                        () -> { cfg().showTracer = !cfg().showTracer; save(); })
                        .anchor("show_tracer")
                        .describe("A line from your crosshair to the chosen ghost, so you can see "
                                + "which way to turn before the ghost itself is on screen."),
                SettingRow.toggle("Show Label", () -> cfg().showLabel,
                        () -> { cfg().showLabel = !cfg().showLabel; save(); })
                        .describe("Writes the distance above the chosen ghost, and marks it when "
                                + "another player is nearer to it than you."),
                SettingRow.toggle("Show Other Ghosts", () -> cfg().showOthers,
                        () -> { cfg().showOthers = !cfg().showOthers; save(); })
                        .describe("Draws every other ghost in range in a faint box too, so you can "
                                + "see what the choice was made from."),

                SettingRow.toggle("Only In The Mist", () -> cfg().onlyInTheMist,
                        () -> { cfg().onlyInTheMist = !cfg().onlyInTheMist; save(); })
                        .describe("Keeps the module quiet outside the ghost pit below the Dwarven "
                                + "Mines. Turn it off to try the highlight on a charged creeper "
                                + "anywhere else - a SkyBlock ghost IS one."),
                SettingRow.label("Ghosts are invisible charged creepers - the aura is the whole body"));
    }
}
