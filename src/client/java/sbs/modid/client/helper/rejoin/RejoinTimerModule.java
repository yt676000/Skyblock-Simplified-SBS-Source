/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rejoin;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Rejoin Timer module (Quality of Life): after a SkyBlock kick a countdown appears, and when it runs
 * out it tells you it is safe to rejoin. The counting/detection lives in {@link RejoinTimer}; this
 * class only registers the module and its settings rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class RejoinTimerModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public RejoinTimerModule() {
    }

    @Override
    public String id() {
        return "rejoin_timer";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.TIMERS;
    }

    @Override
    public String displayName() {
        return "Rejoin Timer";
    }

    @Override
    public String description() {
        return "Countdown after a SkyBlock kick, then \"try rejoining now\" - or /sbs rejoin";
    }

    @Override
    public int accentColor() {
        return 0xFF57D977;
    }

    private static SBSConfig.RejoinTimerSettings cfg() {
        return ConfigManager.getInstance().get().rejoinTimer;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Rejoin Timer", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("When you get kicked to a lobby (server reboot, kick...), a "
                                + "countdown starts telling you when it is safe to rejoin "
                                + "SkyBlock - rejoining too early usually just kicks you again. "
                                + "/sbs rejoin starts it by hand."),
                SettingRow.label("Arms on a kick. Scheduled reboots do not arm it"),
                SettingRow.label("/sbs rejoin arms it by hand"),
                SettingRow.toggle("Detect Via Sidebar", () -> cfg().scoreboardFallback,
                        () -> { cfg().scoreboardFallback = !cfg().scoreboardFallback; save(); })
                        .describe("Watches the sidebar for the moment SkyBlock turns into a lobby. "
                                + "On its own that says nothing about why - a kick, a reboot and a "
                                + "lobby you asked for all look identical - so it is used to rule "
                                + "causes out, not to start the timer. See Prompt On Unknown Cause."),
                SettingRow.label("Ignores lobbies you asked for with /lobby, /play, ..."),
                SettingRow.toggle("Prompt On Unknown Cause", () -> cfg().promptOnUnknownCause,
                        () -> { cfg().promptOnUnknownCause = !cfg().promptOnUnknownCause; save(); })
                        .describe("Also start the countdown when you end up in a lobby and SBS "
                                + "could not tell why. Default off: it is usually a kick, but "
                                + "'usually' is what made the banner appear on every scheduled "
                                + "restart, and a prompt that is often wrong is one you stop "
                                + "reading. On, it catches kicks worded in ways this build does "
                                + "not recognise, at the cost of some false alarms."),
                SettingRow.label("Off: no banner unless SBS actually saw a kick"),
                SettingRow.rangeSlider("Wait Seconds", 5, 120, () -> cfg().seconds,
                        value -> { cfg().seconds = value; save(); }, "s")
                        .describe("How long the countdown waits before calling the rejoin safe."),
                SettingRow.toggle("Ding When Ready", () -> cfg().sound,
                        () -> { cfg().sound = !cfg().sound; save(); })
                        .describe("Plays a sound when the countdown reaches zero."),
                SettingRow.toggle("Auto-Rejoin at Zero", () -> cfg().autoRejoin,
                        () -> { cfg().autoRejoin = !cfg().autoRejoin; save(); })
                        .describe("Runs /play SKYBLOCK for you the moment the countdown ends, "
                                + "instead of just telling you it is time."),
                SettingRow.label("Auto-Rejoin runs /play SKYBLOCK when the countdown ends"));
    }
}
