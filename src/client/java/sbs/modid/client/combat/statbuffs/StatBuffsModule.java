/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.statbuffs;

import sbs.modid.client.combat.statbuffs.logic.StatBuffTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Stat Buff Feedback module (Combat): the chat line that says what an ability actually gave you -
 * "+247 Strength" for a Ragnarock cast - measured off the tab list's stats widget rather than
 * guessed from the item's lore. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class StatBuffsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public StatBuffsModule() {
    }

    @Override
    public String id() {
        return "stat_buffs";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Stat Buff Feedback";
    }

    @Override
    public String description() {
        return "Chat line telling you how much Strength (and every other stat) an ability really "
                + "gave you, and when it runs out";
    }

    @Override
    public int accentColor() {
        return 0xFFFF7755;
    }

    private static SBSConfig.StatBuffSettings cfg() {
        return ConfigManager.getInstance().get().statBuffs;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Stat Buff Feedback", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Uses an ability that buffs your stats - a Ragnarock cast, a "
                                + "potion, an ability that trades one stat for another - and a chat "
                                + "line tells you what it actually did: \"+247 Strength "
                                + "(512 → 759)\". The numbers come from the stats in your tab list, "
                                + "so they are your real totals, not what the item's description "
                                + "promises."),
                SettingRow.label("Needs the stats widget switched on in your tab list"),

                SettingRow.toggle("Say When It Runs Out", () -> cfg().reportEnd,
                        () -> { cfg().reportEnd = !cfg().reportEnd; save(); })
                        .describe("A second line when your stats drop back to where they were, "
                                + "with how long the buff lasted. Off: only the gain is announced."),
                SettingRow.toggle("Report Every Change", () -> cfg().reportUnattributed,
                        () -> { cfg().reportUnattributed = !cfg().reportUnattributed; save(); })
                        .describe("Also announces stat changes that no ability caused - swapping a "
                                + "weapon, a pet levelling, a potion wearing off. Useful for a "
                                + "moment to see where a number comes from, noisy to leave on."),

                SettingRow.intField("Ability Window", 1, 60, () -> cfg().windowSeconds,
                        value -> { cfg().windowSeconds = value; save(); }, "s")
                        .describe("How long after using an item a stat change still counts as that "
                                + "item's doing. A cast takes seconds to finish, so this has to be "
                                + "longer than the longest cast you use - 12 seconds covers "
                                + "everything in the game today."),
                SettingRow.intField("Ignore Changes Below", 1, 100, () -> cfg().minChange,
                        value -> { cfg().minChange = value; save(); }, "")
                        .describe("Stat changes smaller than this are ignored, so a rounding "
                                + "difference in the tab list does not announce itself."),

                SettingRow.button("Print My Stats",
                        () -> StatBuffTracker.getInstance().printCurrent())
                        .describe("Prints the stats read out of your tab list right now. The way to "
                                + "check the widget is being read at all - if it says nothing was "
                                + "found, the tab stats widget is off."),
                SettingRow.toggle("Log To File", () -> cfg().debugLog,
                        () -> { cfg().debugLog = !cfg().debugLog; save(); })
                        .describe("Writes every reading and every measured change to latest.log. "
                                + "For working out why a buff was missed or measured wrong."));
    }
}
