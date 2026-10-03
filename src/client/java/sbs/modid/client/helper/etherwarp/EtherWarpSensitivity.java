/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.etherwarp;

import net.minecraft.network.chat.Component;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Feature 3 of the Ether Warp module: <b>reduced mouse sensitivity while the ability is armed</b>,
 * so a block 57 blocks out can be aimed at without overshooting it.
 *
 * <p>Sibling of {@link EtherWarpZoom} and built the same way: independently toggleable, and derived
 * state rather than a stored mode. {@code MouseSensitivityMixin} asks {@link #apply} for the
 * sensitivity every time {@code MouseHandler.turnPlayer} reads it, and this class recomputes the
 * answer from the live sneak input and held item.
 *
 * <p><b>Why nothing is written back.</b> The obvious implementation – overwrite
 * {@code Options.sensitivity} when the ability arms and restore it afterwards – can strand the
 * reduced value in {@code options.txt} forever if the game exits, crashes or disconnects while
 * sneaking. Here the player's saved setting is never touched, so "restore the normal value" is not a
 * step that can be missed: it is simply what happens the moment the ability disarms.
 *
 * <p>The slider is the sensitivity to use while aiming, as a percentage of the player's normal
 * setting: 100% is no reduction, 25% is a quarter as fast.
 */
public final class EtherWarpSensitivity {

    /** Bounds of the configurable reduction, in percent of the player's normal sensitivity. */
    public static final int MIN_PERCENT = 10;
    public static final int MAX_PERCENT = 100;

    private EtherWarpSensitivity() {
    }

    /** Whether the reduction applies right now: enabled AND the etherwarp ability armed. */
    public static boolean active() {
        return ConfigManager.getInstance().get().etherWarp.reduceSensitivity && EtherWarp.armed();
    }

    /**
     * The sensitivity {@code turnPlayer} should use: scaled while the ability is armed, and the
     * player's own value untouched otherwise.
     *
     * @param sensitivity the value vanilla just read from the options
     */
    public static double apply(double sensitivity) {
        if (!active()) {
            return sensitivity;
        }
        SBSConfig.EtherWarpSettings cfg = ConfigManager.getInstance().get().etherWarp;
        int pct = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, cfg.sensitivityPercent));
        return sensitivity * (pct / 100.0);
    }

    /**
     * Called for every fresh in-world key press; toggles the feature on the configured key.
     *
     * <p>Flips the stored setting rather than a separate runtime flag, so the key and the settings
     * screen can never disagree about whether the feature is on.
     */
    public static void onKeyPressed(int keyCode) {
        SBSConfig.EtherWarpSettings cfg = ConfigManager.getInstance().get().etherWarp;
        if (cfg.sensitivityKey == -1 || keyCode != cfg.sensitivityKey) {
            return;
        }
        cfg.reduceSensitivity = !cfg.reduceSensitivity;
        ConfigManager.getInstance().save();
        SBSChat.send(Component.literal("Ether Warp Sensitivity ")
                .withColor(SBSChat.WHITE)
                .append(Component.literal(cfg.reduceSensitivity ? "ON" : "OFF")
                        .withColor(cfg.reduceSensitivity ? 0x57D977 : 0xE0605F)));
    }
}
