/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.alert;

import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Builds the per-alert channel picker every feature that raises an alert shows.
 *
 * <p>One toggle per channel plus a summary label, generated from {@link AlertChannel} - so a feature
 * spends four lines on "how do you want to be told" instead of reimplementing the matrix, and a new
 * channel appears on every alert at once instead of being added feature by feature.
 */
public final class AlertChannelRows {

    private AlertChannelRows() {
    }

    /**
     * The picker rows for one alert.
     *
     * <p>The rows are anchored on {@code scope} rather than taking the id their label would give
     * them. Every alert produces the same five labels ("Chat", "Sound", ...), so a module with two
     * alerts would otherwise have two rows claiming {@code sound} and one of them would be
     * unreachable by id – which is exactly what the option-id audit reported the first time it ran.
     *
     * @param scope     stable key for THIS alert within its module ("pest_spawn"), never reworded
     * @param alertName how the alert reads in the descriptions ("pest spawns")
     * @param mask      reads the current selection
     * @param onChange  writes it back (and saves)
     */
    public static List<SettingRow> forAlert(String scope, String alertName,
                                            IntSupplier mask, IntConsumer onChange) {
        List<SettingRow> rows = new ArrayList<>(AlertChannel.values().length + 1);
        for (AlertChannel channel : AlertChannel.values()) {
            rows.add(SettingRow.toggle(channel.displayName(),
                            () -> AlertChannels.has(mask.getAsInt(), channel),
                            () -> onChange.accept(AlertChannels.toggle(mask.getAsInt(), channel)))
                    .anchor("alert_" + scope + "_" + channel.name().toLowerCase(java.util.Locale.ROOT))
                    .describe(describe(channel, alertName)));
        }
        // A snapshot from page-build time, like every other summary label in the settings UI.
        rows.add(SettingRow.label("Telling you when " + alertName + ": " + summary(mask)));
        return rows;
    }

    /**
     * The channel summary, or "see above" when the config cannot be read.
     *
     * <p>That only happens outside a running game: {@code LicenceMarks} walks every module's rows
     * in the unit tests, and a summary read there made the whole page throw - which silently
     * dropped that module's licence mark. In game the config is always loaded.
     */
    private static String summary(IntSupplier mask) {
        try {
            return AlertChannels.describe(mask.getAsInt());
        } catch (IllegalStateException e) {
            return "see the switches above";
        }
    }

    /** What each channel means, in the context of the alert it is being set for. */
    private static String describe(AlertChannel channel, String alertName) {
        return switch (channel) {
            case CHAT -> "Write a line in chat when " + alertName
                    + ". The only channel that is still there after you look away.";
            case TITLE -> "Show it as big text above the crosshair when " + alertName + ".";
            case HUD -> "Mark it on this feature's own HUD card when " + alertName
                    + " - only does something for alerts that have one.";
            case SOUND -> "Play the mod's own alert ping when " + alertName
                    + ". It has its own volume and does not go through Minecraft's sound engine, "
                    + "so you still hear it with the game muted.";
            case NARRATOR -> "Speak it out loud through your system's text-to-speech voice when "
                    + alertName + ". Gets through a fullscreen, fully muted game, and never "
                    + "changes your Minecraft narrator setting."
                    + (NarratorVoice.available() ? "" : " (Unavailable on this system.)");
        };
    }
}
