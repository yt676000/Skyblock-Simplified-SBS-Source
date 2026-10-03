/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.rift.logic.RiftAreas;
import sbs.modid.client.helper.rift.logic.RiftState;
import sbs.modid.client.helper.rift.logic.RiftTime;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Rift Time module: the ф countdown on a movable HUD card, plus the warnings that come with it.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; settings in
 * {@link SBSConfig.RiftTimeSettings}, the reading in {@link RiftTime}, the card in
 * {@code render/RiftTimeHud}.
 */
public final class RiftTimeModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public RiftTimeModule() {
    }

    @Override
    public String id() {
        return "rift_time";
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
        return "Rift Time";
    }

    @Override
    public String description() {
        return "The ф countdown as a HUD card, with low-time and area warnings";
    }

    @Override
    public int accentColor() {
        return 0xFFB44DFF;
    }

    private static SBSConfig.RiftTimeSettings cfg() {
        return ConfigManager.getInstance().get().riftTime;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(List.of(
                SettingRow.toggle("Rift Time", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A card with the ф time you have left in the Rift. It only exists "
                                + "inside the Rift - there is nothing to draw anywhere else, so it "
                                + "cannot be left on somewhere it does not belong."),
                SettingRow.label("Only ever drawn inside the Rift"),

                SettingRow.toggle("Show Maximum", () -> cfg().showMax,
                        () -> { cfg().showMax = !cfg().showMax; save(); })
                        .describe("Puts the time you entered this visit with under the countdown. "
                                + "Hypixel only publishes the countdown, so the maximum is the "
                                + "highest value seen this visit - exact when the mod was running "
                                + "as you arrived. When it is only the highest seen so far, the "
                                + "row says '(seen)' rather than passing a guess off as the real "
                                + "number."),
                SettingRow.toggle("Show Bar", () -> cfg().showBar,
                        () -> { cfg().showBar = !cfg().showBar; save(); })
                        .describe("Draws the remaining fraction as a bar. Hidden by itself when the "
                                + "maximum is not known, since there would be nothing to fill "
                                + "against."),
                SettingRow.toggle("Show Drain Rate", () -> cfg().showDrainRate,
                        () -> { cfg().showDrainRate = !cfg().showDrainRate; save(); })
                        .describe("Says when the area you are standing in runs the clock at "
                                + "anything but normal speed - frozen in the Wizard Tower, Gallery "
                                + "and Mirrorverse, half speed at the Colosseum, double in the "
                                + "Time Chamber. A '?' after it means that rule is from the wiki "
                                + "and has not been watched happening yet."),
                SettingRow.toggle("Show Motes", () -> cfg().showMotes,
                        () -> { cfg().showMotes = !cfg().showMotes; save(); })
                        .describe("Adds your motes purse to the same card. Off by default: motes "
                                + "already have a row on the sidebar, and the clock is what this "
                                + "card is for."),

                SettingRow.label("§8—— Colour thresholds ——"),
                SettingRow.rangeSlider("Amber Below", 5, 90, () -> cfg().warnPercent,
                        value -> { cfg().warnPercent = value; save(); }, "%")
                        .describe("The countdown turns amber at or below this share of the time you "
                                + "entered with. A share rather than a number of seconds, because "
                                + "two minutes left means something very different with eight "
                                + "minutes of Rift Time than with eighty."),
                SettingRow.rangeSlider("Red Below", 1, 50, () -> cfg().criticalPercent,
                        value -> { cfg().criticalPercent = value; save(); }, "%")
                        .describe("And red at or below this share. Under ten seconds the countdown "
                                + "blinks as well, whatever these are set to."),

                SettingRow.label("§8—— Alerts ——"),
                SettingRow.intField("Warn At", 0, 3600, () -> cfg().warnSeconds,
                        value -> { cfg().warnSeconds = value; save(); }, "s")
                        .describe("Raise the first warning with this many seconds left. 0 turns it "
                                + "off. In seconds rather than a percentage so it still works when "
                                + "the maximum is unknown."),
                SettingRow.intField("Critical At", 0, 3600, () -> cfg().criticalSeconds,
                        value -> { cfg().criticalSeconds = value; save(); }, "s")
                        .describe("And the louder one at this many. Crossing both at once says only "
                                + "the worse of the two."),
                SettingRow.toggle("Area Warnings", () -> cfg().areaWarnings,
                        () -> { cfg().areaWarnings = !cfg().areaWarnings; save(); })
                        .describe("Warns when you are somewhere that wants more time than you are "
                                + "holding (the Mirrorverse asks for three minutes), and when you "
                                + "are deep enough into a draining area that what is left may not "
                                + "cover walking back out.")));

        rows.addAll(AlertChannelRows.forAlert("rift_time_low", "Rift Time runs low",
                () -> cfg().alertChannels,
                mask -> { cfg().alertChannels = mask; save(); }));

        rows.add(SettingRow.label("§8—— Status ——"));
        rows.add(SettingRow.label("§8" + statusLine()));
        rows.add(SettingRow.label("§8Areas: " + RiftAreas.status()));
        return List.copyOf(rows);
    }

    /**
     * Where the live number is coming from, or why there is none.
     *
     * <p>Worth a row of its own because the failure is otherwise invisible: Hypixel serves the clock
     * on the scoreboard, the action bar and the tab list at different times, all three are read, and
     * a card that simply never appears gives the player nothing to report. Naming the source turns
     * "it does not work" into "it says it is reading nothing", which is a bug report.
     */
    private static String statusLine() {
        if (!RiftState.getInstance().inRift()) {
            return "Not in the Rift";
        }
        RiftTime time = RiftTime.getInstance();
        if (!time.available()) {
            return "In the Rift, but no ф value on the scoreboard, action bar or tab list";
        }
        return "Reading " + RiftTime.format(time.remaining()) + " from "
                + time.source().displayName()
                + (time.maxCertain() ? ", maximum known" : ", maximum is the highest seen");
    }
}
