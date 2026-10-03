/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Mining Routes module (Skills): create custom waypoint routes with connecting lines, drawn
 * in the world; share them as a copy/paste string. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; state in {@link SBSConfig.MiningRoutesSettings}.
 */
public final class MiningRoutesModule implements SbsModule {

    public MiningRoutesModule() {
    }

    @Override
    public String id() {
        return "mining_routes";
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
        return "Mining Routes";
    }

    @Override
    public String description() {
        return "Custom mining routes: waypoints joined by lines, importable from others";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.MiningRoutesSettings cfg() {
        return ConfigManager.getInstance().get().miningRoutes;
    }

    /**
     * The island gate is one setting shared by every mining module, so the row is offered here and in
     * the other mining modules, all bound to the same field - whichever one you find it in, it is the
     * same switch.
     */
    static boolean islandLock() {
        return ConfigManager.getInstance().get().mining.islandLock;
    }

    static void toggleIslandLock() {
        var mining = ConfigManager.getInstance().get().mining;
        mining.islandLock = !mining.islandLock;
        save();
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    public static void openScreen() {
        Minecraft.getInstance().setScreenAndShow(
                new sbs.modid.client.skills.mining.ui.MiningRoutesScreen(null));
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Only On Mining Islands", MiningRoutesModule::islandLock,
                        MiningRoutesModule::toggleIslandLock)
                        .describe("Keeps every mining feature quiet unless you are on a mining "
                                + "island (Dwarven Mines, Crystal Hollows...). One shared switch - "
                                + "changing it here changes it for all mining modules."),
                SettingRow.label("Shared by every mining feature; off anywhere else"),
                SettingRow.label("§8" + SkillIslands.describe(SkillIslands.MINING_ISLANDS)),

                SettingRow.toggle("Mining Routes", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Draw your own mining path in the world as connected waypoints - "
                                + "for example a Coleweight-style commission loop - and run it "
                                + "lap after lap. Routes can be shared with others as a text "
                                + "string via the clipboard."),
                SettingRow.label("Custom waypoint routes drawn in the world; share them by string"),

                SettingRow.button("Open Routes", MiningRoutesModule::openScreen)
                        .describe("Opens the routes screen: create and select routes, "
                                + "import/export them, delete points."),
                SettingRow.keybind("Add Waypoint Key", () -> cfg().addWaypointKey,
                        key -> { cfg().addWaypointKey = key; save(); })
                        .describe("Press while standing where the next waypoint should be - it is "
                                + "added to the selected route at your feet."),
                SettingRow.keybind("Open Routes Key", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("A key that opens the routes screen while playing."),
                SettingRow.label("Press Add Waypoint at your feet to drop a point onto the selected route"),

                SettingRow.intField("Line Width", 1, 6, () -> cfg().lineWidth,
                        value -> { cfg().lineWidth = value; save(); }, "px")
                        .describe("Thickness of the lines between waypoints, in pixels."),
                SettingRow.toggle("Waypoint Boxes", () -> cfg().showWaypointBoxes,
                        () -> { cfg().showWaypointBoxes = !cfg().showWaypointBoxes; save(); })
                        .describe("Draws a box at every waypoint, not just the connecting lines."),
                SettingRow.toggle("Waypoint Numbers", () -> cfg().showLabels,
                        () -> { cfg().showLabels = !cfg().showLabels; save(); })
                        .describe("Numbers the waypoints in route order, so you can see where the "
                                + "loop starts and which way it runs."));
    }
}
