/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.trophyfish;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.trophyfish.logic.TrophyFishTracker;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Trophy Fish (Skills): every trophy fish x tier, what is still missing, and this session's catches.
 *
 * <p>Its own card rather than rows on Fishing: that card is already long and about the fishing
 * trackers, while this has two HUD cards, a menu sync and alerts of its own - and a player looking
 * for "trophy" should find a card with that name. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class TrophyFishModule implements SbsModule {

    public TrophyFishModule() {
    }

    @Override
    public String id() {
        return "trophy_fish";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public String displayName() {
        return "Trophy Fish";
    }

    @Override
    public String description() {
        return "Every trophy fish and tier you have caught, what is missing, and this session";
    }

    @Override
    public int accentColor() {
        return 0xFFFF7733;
    }

    private static SBSConfig.TrophyFishSettings cfg() {
        return ConfigManager.getInstance().get().trophyFish;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("Trophy Fish", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Learns your trophy fish counts by reading Odger's Trophy Fishing menu on "
                        + "the Crimson Isle when you open it (it never clicks anything), stored per "
                        + "SkyBlock profile. Off by default while the menu format is still being "
                        + "checked in game."));
        rows.add(SettingRow.label("Open Odger's Trophy Fishing menu once to fill the grid"));
        rows.add(SettingRow.toggle("Count Catches From Chat", () -> cfg().chatCounting,
                        () -> { cfg().chatCounting = !cfg().chatCounting; save(); })
                .describe("Adds each \"TROPHY FISH!\" catch message to the counts between menu "
                        + "visits, and feeds the session card. Off by default: the exact message "
                        + "has not been checked in game yet, so a catch might not be recognised."));
        rows.add(SettingRow.toggle("Trophy Grid Card", () -> cfg().gridHud,
                        () -> { cfg().gridHud = !cfg().gridHud; save(); })
                .describe("A card with every trophy fish and its Bronze, Silver, Gold and Diamond "
                        + "counts, missing tiers dimmed, and how many fish you have per tier. Says "
                        + "how long ago the menu was last read."));
        rows.add(SettingRow.toggle("Only Show Missing", () -> cfg().compact,
                        () -> { cfg().compact = !cfg().compact; save(); })
                .describe("Shrinks the grid card to a list of the fish you still need and which "
                        + "tiers of each."));
        rows.add(SettingRow.toggle("Session Card", () -> cfg().sessionHud,
                        () -> { cfg().sessionHud = !cfg().sessionHud; save(); })
                .describe("Trophy fish caught since you started the game, per fish and tier, with "
                        + "time spent fishing (breaks over five minutes left out) and catches per "
                        + "hour. Needs chat counting."));
        rows.add(SettingRow.button("Reset Session", () -> TrophyFishTracker.getInstance().session().reset())
                .describe("Starts the session card from zero. Your saved counts are not touched."));
        rows.add(SettingRow.toggle("Only On Crimson Isle", () -> cfg().onlyCrimsonIsle,
                        () -> { cfg().onlyCrimsonIsle = !cfg().onlyCrimsonIsle; save(); })
                .describe("Hides both cards anywhere but the Crimson Isle, the only place trophy "
                        + "fish bite."));
        rows.add(SettingRow.toggle("Alert On New Tier", () -> cfg().alertNewTier,
                        () -> { cfg().alertNewTier = !cfg().alertNewTier; save(); })
                .describe("Tells you when you catch your first Gold or Diamond of a fish. Only "
                        + "after one menu sync - before that, nothing knows what is new."));
        rows.add(SettingRow.toggle("Alert On Any Diamond", () -> cfg().alertAnyDiamond,
                        () -> { cfg().alertAnyDiamond = !cfg().alertAnyDiamond; save(); })
                .describe("Tells you about every Diamond catch, not only the first."));
        rows.addAll(AlertChannelRows.forAlert("trophy_fish", "you catch a new or Diamond trophy fish",
                () -> cfg().alertChannels, mask -> { cfg().alertChannels = mask; save(); }));
        return rows;
    }
}
