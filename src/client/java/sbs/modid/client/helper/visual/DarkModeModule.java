/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.visual.logic.DarkMode;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Dark Mode module (Visuals): the three world-wide ways to take the glare out of the game - your own
 * time of day, one uniform brightness for everything, and every block darkened.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}. The work is
 * in {@link DarkMode} and its three hooks ({@code DarkModeClockMixin}, {@code DarkModeLightmapMixin}
 * and the two block-shade mixins); all of them read the config live, so every row applies within a
 * tick and none of them needs a restart.
 */
public final class DarkModeModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public DarkModeModule() {
    }

    @Override
    public String id() {
        return "dark_mode";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.VISUALS;
    }

    @Override
    public String displayName() {
        return "Dark Mode";
    }

    @Override
    public String description() {
        return "Your own time of day, one uniform brightness, and every block darkened";
    }

    @Override
    public int accentColor() {
        return 0xFF6E5BD0;
    }

    private static SBSConfig.DarkModeSettings cfg() {
        return ConfigManager.getInstance().get().darkMode;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Client Side Time", () -> cfg().clientTime,
                        () -> { cfg().clientTime = !cfg().clientTime; save(); })
                        .describe("Shows the time of day you pick instead of the server's. Only you "
                                + "see it - nothing is sent to the server, and everyone else keeps "
                                + "their own time. The sun, moon, stars and the colour of the sky "
                                + "all follow, so it is a real change of time and not a tinted sky."),
                SettingRow.rangeSlider("Time of Day", 0, 23,
                        () -> cfg().timeOfDay,
                        value -> { cfg().timeOfDay = value; save(); }, ":00")
                        .describe("The hour to show while Client Side Time is on - 0:00 is midnight, "
                                + "6:00 sunrise, 12:00 noon, 18:00 sunset."),
                SettingRow.label("Only you see this time - the server is never told"),
                SettingRow.toggle("Uniform Brightness", () -> cfg().uniformBrightness,
                        () -> { cfg().uniformBrightness = !cfg().uniformBrightness; save(); })
                        .describe("Lights the whole world at one level, with no light gradient left: "
                                + "a cave, a lit island and a dungeon corridor all end up equally "
                                + "bright. Unlike Fullbright it can go darker than normal, which is "
                                + "what makes it useful here. Fullbright wins if you have both on."),
                SettingRow.rangeSlider("Brightness", 0, 100,
                        () -> cfg().brightness,
                        value -> { cfg().brightness = value; save(); }, "%")
                        .describe("That level - near black at 0%, as bright as the game ever gets at "
                                + "100%. Your Minecraft brightness setting is not touched, so there "
                                + "is nothing to restore when you turn it off."),
                SettingRow.toggle("Darken Blocks", () -> cfg().darkenBlocks,
                        () -> { cfg().darkenBlocks = !cfg().darkenBlocks; save(); })
                        .describe("Darkens every block in the world, the same way Dark End Blocks and "
                                + "Dim Mist Blocks darken theirs. Nothing is downloaded and your own "
                                + "resource pack is left alone."),
                SettingRow.rangeSlider("Block Darkness", 0, 100,
                        () -> cfg().blockDarkness,
                        value -> { cfg().blockDarkness = value; save(); }, "%")
                        .describe("Reverse brightness for those blocks, on the same scale as End "
                                + "Darkness and Mist Darkness: natural brightness at 0%, pitch black "
                                + "at 100%."),
                SettingRow.label("The End and The Mist keep their own look - this covers the rest"));
    }
}
