/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.sound;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.core.sound.SoundControl;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Sound Manager module: mute or rescale individual game sounds, and control the mod's own alert
 * volume - for players who want a quiet game without turning the master volume to zero and losing
 * every cue with it.
 *
 * <p>The table itself lives in {@link SoundManagerScreen}; this page holds the mode switch and the
 * things that are one-liners. Two scopes exist and are deliberately separate: <b>the mod's alert
 * sounds</b> play through their own audio output and are set under SBS Settings, so nothing here can
 * silence a safety cue by accident; <b>the game's sounds</b> are what the table lists.
 */
public final class SoundManagerModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SoundManagerModule() {
    }

    @Override
    public String id() {
        return "sound_manager";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Sound Manager";
    }

    @Override
    public String description() {
        return "Mute or quieten individual game sounds instead of muting everything";
    }

    @Override
    public int accentColor() {
        return 0xFF6FC3DF;
    }

    private static SBSConfig.SoundSettings cfg() {
        return ConfigManager.getInstance().get().sounds;
    }

    private static void save() {
        ConfigManager.getInstance().save();
        SoundControl.invalidate();   // the hot-path tables are compiled from this
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Sound Manager", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Lets this module mute and quieten individual game sounds. While "
                                + "it is off the game's sound engine is not touched at all."),
                SettingRow.label("Mute single sounds instead of the whole game"),

                SettingRow.button("Open Sound List", SoundManagerScreen::open)
                        .describe("The table of every sound the game knows: search it, mute "
                                + "entries, set a volume per sound and preview them."),
                SettingRow.label(summary()),

                SettingRow.label("— Mode —"),
                SettingRow.cycle("Mode", () -> cfg().whitelistMode ? "Whitelist" : "Blacklist",
                        SoundManagerScreen::toggleModeWithConfirm)
                        .describe("Blacklist: everything plays except what you listed - the normal "
                                + "way round. Whitelist: NOTHING plays except what you listed, "
                                + "which silences the entire game including interface clicks and "
                                + "warning cues until you add them one by one. Switching to "
                                + "whitelist asks for confirmation first."),
                SettingRow.label(cfg().whitelistMode
                        ? "§eWhitelist: only the listed sounds play"
                        : "Blacklist: everything plays except the listed sounds"),

                SettingRow.button("Deactivate All Game Sounds", SoundManagerScreen::muteAllWithConfirm)
                        .describe("Mutes every sound in the list at once. Needs a second click to "
                                + "confirm, and Reset below puts it all back."),
                SettingRow.button("Reset Sound List", () -> {
                    cfg().listed.clear();
                    cfg().volumes.clear();
                    cfg().whitelistMode = false;
                    save();
                })
                        .describe("Clears every mute and per-sound volume and goes back to "
                                + "blacklist mode - the state a fresh install is in."),
                SettingRow.label("§8The mod's own alert volume is under SBS Settings"));
    }

    /** What the list currently holds, as a line for the page. */
    private static String summary() {
        SBSConfig.SoundSettings cfg = cfg();
        int listed = cfg.listed == null ? 0 : cfg.listed.size();
        int volumes = cfg.volumes == null ? 0 : cfg.volumes.size();
        if (listed == 0 && volumes == 0) {
            return "Nothing changed yet - every sound plays normally";
        }
        return listed + (cfg.whitelistMode ? " sound(s) allowed" : " sound(s) muted")
                + (volumes == 0 ? "" : ", " + volumes + " with a custom volume");
    }
}
