/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.mayor;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Mayor Vote Reminder (Economy): tells you that you have not voted yet, until you have.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}. The state
 * itself - which election, and whether this profile voted in it - is kept by
 * {@link MayorVoteTracker} per account + SkyBlock profile, not here; these are only the preferences.
 */
public final class MayorVoteModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public MayorVoteModule() {
    }

    @Override
    public String id() {
        return "mayor_vote";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Mayor Vote Reminder";
    }

    @Override
    public String description() {
        return "Remembers whether you voted in the running mayor election and reminds you until you have";
    }

    @Override
    public int accentColor() {
        return 0xFFFFB347;
    }

    private static SBSConfig.MayorVoteSettings cfg() {
        return ConfigManager.getInstance().get().mayorVote;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(List.of(
                SettingRow.toggle("Mayor Vote Reminder", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Tracks whether you have voted in the election that is running "
                                + "and tells you while you have not. Your vote is remembered per "
                                + "account and profile, so it survives a restart."),
                SettingRow.label("Detected from the booth itself - nothing is ever clicked for you"),

                SettingRow.toggle("Chat Reminder", () -> cfg().remind,
                        () -> { cfg().remind = !cfg().remind; save(); })
                        .describe("A chat line while an election is running and your vote is still "
                                + "not cast. Off, the feature is only the sidebar row and the "
                                + "reminder stays silent."),
                SettingRow.intField("Repeat Every", 1, 240,
                        () -> cfg().repeatMinutes,
                        value -> { cfg().repeatMinutes = value; save(); }, "min")
                        .describe("How long between repeats of the reminder. It stops the moment "
                                + "you vote and never fires when no election is running."),
                SettingRow.label("— Extra ways to be told, on top of the chat line —")));
        rows.addAll(AlertChannelRows.forAlert("vote_reminder", "you still have not voted",
                () -> cfg().extraChannels,
                value -> { cfg().extraChannels = value; save(); }));
        rows.add(SettingRow.label(
                "§8The sidebar row lives with the other rows in Custom Scoreboard"));
        return rows;
    }
}
