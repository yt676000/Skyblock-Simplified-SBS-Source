/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.npc;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * NPC module: marks and routes to whoever the scoreboard's current objective tells you to talk to.
 *
 * <p>Hypixel states the next step but never where the person stands; this closes that gap using the
 * NPC coordinate table in {@link SkyblockNpcs}. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class NpcModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public NpcModule() {
    }

    @Override
    public String id() {
        return "npc";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.NAVIGATION;
    }

    @Override
    public String displayName() {
        return "Objective Route";
    }

    @Override
    public String description() {
        return "Marks and routes to the NPC or place your scoreboard Objective names";
    }

    @Override
    public int accentColor() {
        return 0xFF8FD14D;
    }

    private static SBSConfig.NpcSettings cfg() {
        return ConfigManager.getInstance().get().npc;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                // Relabelled from "Objective NPC"; anchored to the old slug so a favourite of the
                // old row still lands here.
                SettingRow.toggle("Route To Objective", () -> cfg().objectiveRouting,
                        () -> { cfg().objectiveRouting = !cfg().objectiveRouting; save(); })
                        .anchor("route_to_objective", "objective_npc")
                        .describe("Reads the Objective on your scoreboard and, when it names an NPC "
                                + "or place this mod has coordinates for, marks it and draws the "
                                + "route. Quiet otherwise - no chat, no alerts. A map click or a "
                                + "Quest Guide step always wins over it. Off does nothing at all. "
                                + "Default: on."),
                SettingRow.label("Nothing is shown when the objective names nothing known"),

                SettingRow.toggle("Waypoint", () -> cfg().waypoint,
                        () -> { cfg().waypoint = !cfg().waypoint; save(); })
                        .describe("Places a world marker on the objective's NPC, so you can see where "
                                + "they are. It appears once you are on their island and clears itself "
                                + "when you reach them."),
                SettingRow.label("A marker on the NPC, once you are on their island"),

                SettingRow.toggle("Pathfinding", () -> cfg().pathfinding,
                        () -> { cfg().pathfinding = !cfg().pathfinding; save(); })
                        .describe("Routes you to the marker with the pathfinding line, instead of only "
                                + "marking the spot. Needs the Waypoint toggle above - the route "
                                + "follows the marker."),
                SettingRow.label("Draws the route there, not just the marker"),

                SettingRow.toggle("Include Places", () -> cfg().includePlaces,
                        () -> { cfg().includePlaces = !cfg().includePlaces; save(); })
                        .describe("Also matches places and zones from the SkyBlock map (\"the Barn\", "
                                + "\"Royal Mines\"), not only NPCs. \"Collect\" or \"obtain\" "
                                + "objectives are never routed - there is no one place to go. "
                                + "Default: on."),
                SettingRow.keybind("Dismiss Objective Route", () -> cfg().dismissKey,
                                key -> { cfg().dismissKey = key; save(); })
                        .describe("Hides the route for the objective you have now, until the "
                                + "objective changes. Same as /sbs objective off."),
                SettingRow.label("§8/sbs objective go - warp towards a target on another island"),

                SettingRow.label(ObjectiveNpcTracker.getInstance().statusLine()),
                SettingRow.label("§8Search budget self-tunes to your PC: "
                        + sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().nodeBudget()
                        + " nodes/tick"),
                SettingRow.label("§8" + SkyblockNpcs.all().size() + " NPCs catalogued"),
                SettingRow.label("§8Coordinates are wiki data - an NPC Hypixel moved may be stale"));
    }
}
