/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import sbs.modid.client.core.config.ConfigManager;

/**
 * The one entry point for every sound the mod plays on its own initiative: alert tones, pings,
 * protection denies, tracker dings.
 *
 * <p>Everything here is gated on the master switch {@code alerts.sbsSounds} ("SBS Sounds"), which is
 * off by default. Each feature keeps its own sound toggle and channel mask underneath it, so turning
 * the master on restores exactly the per-feature behaviour the player had configured.
 *
 * <p>Game and server sounds never pass through here, and neither does the Sound Manager's preview
 * button: those are the game's sounds, not the mod's. The build task {@code sbsSoundCheck} fails on a
 * direct play call anywhere else, so a new feature cannot bypass the master by accident.
 *
 * <p>The switch is read as one cached field. It is refreshed from the config every client tick
 * ({@link #sync()}) and immediately when the settings row flips it, so a config load, a profile
 * switch or an imported share all take effect within a tick.
 */
public final class SbsSounds {

    /** Cached copy of {@code alerts.sbsSounds}; written on the game thread, read from any. */
    private static volatile boolean enabled;

    /** What a greyed-out per-feature sound row and {@code /sbs testnotify} say while the master is off. */
    public static final String OFF_REASON = "SBS Sounds is off";

    private SbsSounds() {
    }

    /** Whether the mod may play its own sounds right now. */
    public static boolean enabled() {
        return enabled;
    }

    /** Re-reads the master switch from the config. Called from the client tick. */
    public static void sync() {
        enabled = ConfigManager.getInstance().get().alerts.sbsSounds;
    }

    /** Flips the master switch, saves, and applies it at once. */
    public static void toggle() {
        var alerts = ConfigManager.getInstance().get().alerts;
        alerts.sbsSounds = !alerts.sbsSounds;
        ConfigManager.getInstance().save();
        enabled = alerts.sbsSounds;
    }

    /**
     * Plays a vanilla sound event at the local player, through Minecraft's sound engine (so the
     * game's volume sliders apply). Does nothing while the master is off or there is no player.
     * Game thread only.
     */
    public static void play(SoundEvent sound, float volume, float pitch) {
        if (!enabled) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(sound, volume, pitch);
        }
    }

    /**
     * Plays one of the mod's synthesized alert tones on its independent audio output.
     *
     * @return whether the tone was played; {@code false} while the master is off - see
     *         {@link SbsAudio#play} for the other cases
     */
    public static boolean tone(SbsAudio.Tone tone, int volumePercent) {
        if (!enabled) {
            return false;
        }
        return SbsAudio.play(tone, volumePercent);
    }
}
