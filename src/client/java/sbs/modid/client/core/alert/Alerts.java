/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.alert;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.EnumMap;
import java.util.Map;

/**
 * The one place an alert is raised, and the one place it is decided how it reaches the player.
 *
 * <p>A feature says <i>what</i> happened; the player's channel selection says <i>how</i> they are
 * told. Every alert therefore looks the same from the feature's side - build an {@link Alert}, hand
 * it here - and gains chat / title / HUD / sound / narrator support at once, instead of each
 * feature growing its own half of the matrix.
 *
 * <p>This replaced a desktop-notification pipeline. That path had to reach outside the process
 * (an AWT tray Minecraft's forced headless mode blocks, or a spawned {@code powershell.exe} that
 * endpoint protection flags), and everything it was for is covered from inside now: {@link SbsAudio}
 * is audible with the game muted, {@link NarratorVoice} gets through a fullscreen window.
 */
public final class Alerts {

    private Alerts() {
    }

    /**
     * One thing worth telling the player about.
     *
     * @param title   the short headline - what the title channel draws and the narrator speaks
     * @param detail  the longer line for chat; may be empty, in which case the title is used
     * @param tone    which ping the sound channel plays
     * @param hud     what the HUD channel should do, or {@code null} when the alert has no HUD
     *                element of its own (most do not)
     */
    public record Alert(String title, String detail, SbsAudio.Tone tone, Runnable hud) {

        /** The common case: a headline, a detail line and the standard chime. */
        public static Alert of(String title, String detail) {
            return new Alert(title, detail, SbsAudio.Tone.CHIME, null);
        }
    }

    private static SBSConfig.AlertSettings cfg() {
        return ConfigManager.getInstance().get().alerts;
    }

    /**
     * Delivers {@code alert} over every channel in {@code mask}.
     *
     * @return which channels actually took it - {@code true} for delivered, {@code false} for
     *         selected-but-failed. Drives {@code /sbs testnotify}; features ignore it.
     */
    public static Map<AlertChannel, Boolean> send(Alert alert, int mask) {
        Map<AlertChannel, Boolean> result = new EnumMap<>(AlertChannel.class);
        if (!AlertChannels.any(mask)) {
            return result;
        }
        SBSConfig.AlertSettings cfg = cfg();
        String detail = alert.detail() == null || alert.detail().isBlank()
                ? alert.title() : alert.detail();

        if (AlertChannels.has(mask, AlertChannel.CHAT)) {
            result.put(AlertChannel.CHAT, onGameThread(() -> SBSChat.send(
                    Component.literal(" " + alert.title() + "  •  " + detail)
                            .withColor(SBSChat.WHITE))));
        }
        if (AlertChannels.has(mask, AlertChannel.TITLE)) {
            result.put(AlertChannel.TITLE,
                    onGameThread(() -> AlertTitle.show(alert.title(), detail)));
        }
        if (AlertChannels.has(mask, AlertChannel.HUD)) {
            // Only the feature knows what its own card should do, so it hands in the action. An
            // alert with no HUD element of its own reports the channel as not delivered rather
            // than silently counting as success.
            result.put(AlertChannel.HUD,
                    alert.hud() != null && onGameThread(alert.hud()));
        }
        if (AlertChannels.has(mask, AlertChannel.SOUND)) {
            result.put(AlertChannel.SOUND, SbsAudio.play(alert.tone(), cfg.soundVolume));
        }
        if (AlertChannels.has(mask, AlertChannel.NARRATOR)) {
            result.put(AlertChannel.NARRATOR,
                    NarratorVoice.speak(alert.title() + ". " + detail, cfg.narratorVolume,
                            cfg.narratorLanguage));
        }
        return result;
    }

    /** Runs {@code action} on the game thread; {@code false} when there is no client to run it on. */
    private static boolean onGameThread(Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        minecraft.execute(action);
        return true;
    }

    /** Per-tick upkeep for the channels that have any: deferred speech, idle audio device. */
    public static void tick() {
        SBSConfig.AlertSettings cfg = cfg();
        NarratorVoice.tick(cfg.narratorVolume, cfg.narratorLanguage);
        SbsAudio.tick();
        AlertTitle.tick();
    }

    /** Drops anything still pending, so nothing spills across a warp. */
    public static void clearOnWorldChange() {
        NarratorVoice.clearPending();
        AlertTitle.clear();
    }
}
