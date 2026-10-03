/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.quest.logic.QuestDatabase;
import sbs.modid.client.helper.quest.logic.QuestTracker;
import sbs.modid.client.helper.quest.logic.QuestWaypoints;
import sbs.modid.client.helper.quest.model.Quest;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.helper.quest.ui.QuestDetailScreen;
import sbs.modid.client.helper.quest.ui.QuestGuideScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * The Quest Guide module, registering itself.
 *
 * <p>Everything the menu needs to show this module – its identity <b>and</b> its settings page –
 * lives here, in the quest package, instead of being spread across {@code ModuleManager} and
 * {@code ModuleSettings}. Those two are shared files every feature branch edits; this one is only
 * ever touched by whoever works on quests.
 *
 * <p>Discovered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class QuestGuideModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public QuestGuideModule() {
    }

    @Override
    public String id() {
        return "quest_guide";
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
        return "Quest Guide";
    }

    @Override
    public String description() {
        return "Guided quest objectives with waypoints, item list and live cost";
    }

    @Override
    public int accentColor() {
        return 0xFFE0A030;
    }

    private static sbs.modid.client.core.config.SBSConfig.QuestGuideSettings cfg() {
        return ConfigManager.getInstance().get().questGuide;
    }

    private static sbs.modid.client.core.config.SBSConfig.PathfindingSettings path() {
        return ConfigManager.getInstance().get().pathfinding;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Quest Guide", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Step-by-step guides through SkyBlock quest lines: a checklist "
                                + "overlay, a waypoint to the current step, and the item list "
                                + "with live prices. The guides ship with the mod - no licence "
                                + "token needed."),
                SettingRow.button("Open Quests", () -> open(new QuestGuideScreen()))
                        .describe("Opens the quest list to pick which quest to track."),
                SettingRow.button("Items & Cost", () -> {
                    Quest quest = QuestTracker.getInstance().quest();
                    if (quest != null) {
                        open(new QuestDetailScreen(quest, null));
                    }
                })
                        .describe("Opens the tracked quest's item list with what everything costs "
                                + "right now. Does nothing while no quest is tracked."),
                SettingRow.toggle("Show Quest Overlay", () -> cfg().showOverlay,
                        () -> { cfg().showOverlay = !cfg().showOverlay; save(); })
                        .describe("The checklist card with the current quest step and what to "
                                + "bring. Only shows while a quest is tracked."),
                SettingRow.toggle("Show Step Waypoint & Path", () -> cfg().showWaypoint,
                        () -> {
                            cfg().showWaypoint = !cfg().showWaypoint;
                            save();
                            QuestWaypoints.sync();
                        })
                        .describe("Marks the current step's location in the world and draws a "
                                + "walkable path to it - as long as you are on the island the "
                                + "step happens on."),
                SettingRow.label("Routes you to the current step - only on its own island"),
                // The path look lives here too, not only under Developer: it now serves a shipped
                // feature, and a player without dev mode must still be able to set it.
                SettingRow.enumOptions("Path Style", () -> path().pathStyle,
                        value -> { path().pathStyle = value; save(); }, v -> v.displayName())
                        .describe("How the path is drawn (line, blocks...). Shared with every "
                                + "other SBS feature that draws paths. Click to cycle."),
                SettingRow.enumOptions("Path Color", () -> path().pathColor,
                        value -> {
                            path().pathColor = value;
                            path().pathColorHex = "";
                            save();
                        }, v -> v.displayName())
                        .describe("The path's color. Shared with every other SBS path. Click to "
                                + "cycle."),
                SettingRow.enumOptions("Waypoint Color", () -> path().waypointColor,
                        value -> {
                            path().waypointColor = value;
                            path().waypointColorHex = "";
                            save();
                        }, v -> v.displayName())
                        .describe("The color of the destination marker. Click to cycle."),
                SettingRow.button("Move / Resize Overlay", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.QUEST_GUIDE}, "Edit Quest Overlay")))
                        .describe("Opens the editor where you drag the quest card anywhere on the "
                                + "screen and scale it."),
                SettingRow.button("Skip Current Step", () -> QuestTracker.getInstance().skipStep())
                        .describe("Marks the current step done by hand - for when you already did "
                                + "it and the auto-detection missed it."),
                SettingRow.button("Back One Step", () -> QuestTracker.getInstance().previousStep())
                        .describe("Goes back one step - the opposite of Skip, for when a step was "
                                + "skipped too early."),
                SettingRow.label("Progress follows the NPC dialogue automatically"),
                SettingRow.label(status()));
    }

    /** The tracked quest and its progress, or why there is none (snapshot at page build time). */
    private static String status() {
        QuestTracker tracker = QuestTracker.getInstance();
        if (tracker.error() != null) {
            return tracker.error();
        }
        Quest quest = tracker.quest();
        if (quest == null) {
            return QuestDatabase.all().isEmpty() ? "No quest data bundled" : "No quest active";
        }
        return quest.name + "  •  step " + (tracker.stepIndex() + 1) + "/" + quest.stepCount();
    }
}
