/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.requeue;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.dungeons.requeue.logic.RequeueController;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Auto Requeue module (Dungeons): after a completed Catacombs or Kuudra run, queues the same
 * instance again after a delay - or one floor / tier up or down after {@code !up} / {@code !down}.
 * The logic is in {@code logic/}; this class registers the module and its rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class AutoRequeueModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public AutoRequeueModule() {
    }

    @Override
    public String id() {
        return "auto_requeue";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.DUNGEONS;
    }

    @Override
    public String displayName() {
        return "Auto Requeue";
    }

    @Override
    public String description() {
        return "Queues the next Catacombs / Kuudra run after a completed one; respects !dt, !up goes a floor up";
    }

    @Override
    public int accentColor() {
        return 0xFFB36BFF;
    }

    private static SBSConfig.AutoRequeueSettings cfg() {
        return ConfigManager.getInstance().get().autoRequeue;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Dungeons", () -> cfg().dungeons,
                        () -> { cfg().dungeons = !cfg().dungeons; save(); })
                        .describe("After a completed Catacombs run, queue the same floor again - right "
                                + "in the boss room when Hypixel offers its re-queue, else once you are out. One command per run, never retried."),
                SettingRow.rangeSlider("Dungeons Delay", 0, 60, () -> cfg().dungeonsDelay,
                        value -> { cfg().dungeonsDelay = value; save(); }, "s")
                        .describe("Seconds after the re-queue offer (or after leaving) before the requeue."),
                SettingRow.toggle("Kuudra", () -> cfg().kuudra,
                        () -> { cfg().kuudra = !cfg().kuudra; save(); })
                        .describe("After a completed Kuudra run, queue the same tier again once you are "
                                + "back on the Crimson Isle. Needs the Kuudra module on (it knows the "
                                + "tier) and the tier's key in your inventory."),
                SettingRow.rangeSlider("Kuudra Delay", 0, 60, () -> cfg().kuudraDelay,
                        value -> { cfg().kuudraDelay = value; save(); }, "s")
                        .describe("Seconds after arriving on the Crimson Isle before the requeue."),
                SettingRow.intField("Stop After", 1, RequeueController.MAX_CONSECUTIVE_LIMIT,
                        () -> cfg().maxConsecutive, value -> { cfg().maxConsecutive = value; save(); }, "")
                        .describe("Requeues in a row before it stops and waits for you. 1 to "
                                + RequeueController.MAX_CONSECUTIVE_LIMIT + " - there is no unlimited."),
                SettingRow.toggle("Requeue After A Loss", () -> cfg().afterLoss,
                        () -> { cfg().afterLoss = !cfg().afterLoss; save(); })
                        .describe("Also requeue after a lost Kuudra run (DEFEAT)."),
                SettingRow.keybind("Cancel Key", () -> cfg().cancelKey,
                        key -> { cfg().cancelKey = key; save(); })
                        .describe("Cancels the countdown and resets the counter. Moving, opening a "
                                + "menu, typing or attacking cancels it too; so does /sbs requeue cancel."),
                SettingRow.label("Only the party leader sends; others just see the countdown"),
                SettingRow.label("A pending !dt stops the requeue until !undt"),
                SettingRow.toggle("Show Card", () -> cfg().showHud,
                        () -> { cfg().showHud = !cfg().showHud; save(); })
                        .describe("The countdown card: next instance, time left, why it stopped."),
                SettingRow.button("Edit Auto Requeue GUI", () -> Minecraft.getInstance().setScreenAndShow(
                        new HudEditorScreen(new HudElement[] {HudElement.AUTO_REQUEUE}, "Edit Auto Requeue GUI")))
                        .describe("Drag the countdown card anywhere on the screen and scale it."));
    }
}
