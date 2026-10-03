/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.alert;

/**
 * The ways an alert can reach the player. Each is independently selectable per alert, so the player
 * picks what actually gets through to them rather than accepting whatever a feature happened to
 * choose.
 *
 * <p>Stored as a bitmask ({@link AlertChannels}) so a whole selection is one {@code int} in the
 * config - a per-alert set of booleans would be five fields per feature and a migration every time
 * a channel is added.
 *
 * <p>There is deliberately no desktop-notification channel. It was removed in 2026-08: reaching the
 * OS meant either an AWT tray that Minecraft's forced headless mode makes unavailable, or spawning
 * {@code powershell.exe}, which pattern-matches malware and trips endpoint protection. What it was
 * for - being noticed while the game is fullscreen and muted - is what {@link #SOUND} (independent
 * audio output, its own volume) and {@link #NARRATOR} (OS text-to-speech) now cover from inside the
 * process.
 */
public enum AlertChannel {

    /** A line in chat. The one channel nothing can hide, and the only durable one. */
    CHAT("Chat", 1),

    /** Big text above the crosshair. Impossible to miss, useless once you look away. */
    TITLE("Title", 1 << 1),

    /** The feature's own HUD card / on-screen element, where it has one. */
    HUD("HUD", 1 << 2),

    /** A ping through the mod's own audio output - audible with Minecraft's sliders at zero. */
    SOUND("Sound", 1 << 3),

    /** Spoken through the OS text-to-speech voice. Gets through fullscreen and a muted game. */
    NARRATOR("Narrator", 1 << 4);

    private final String displayName;
    private final int bit;

    AlertChannel(String displayName, int bit) {
        this.displayName = displayName;
        this.bit = bit;
    }

    public String displayName() {
        return displayName;
    }

    /** This channel's bit in an {@link AlertChannels} mask. */
    public int bit() {
        return bit;
    }
}
