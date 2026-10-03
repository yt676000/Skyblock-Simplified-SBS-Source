/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.logic;

import net.minecraft.network.chat.Component;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Fullbright: lights the world uniformly, so caves, the Deep Caverns and unlit dungeon rooms are
 * readable without carrying torches or squinting at a gamma slider.
 *
 * <p><b>Why not gamma.</b> The obvious implementation – push {@code Options.gamma} past its 1.0
 * maximum – does not actually work any more, and reading {@code lightmap.fsh} shows why: gamma
 * arrives as {@code BrightnessFactor} and is only a {@code mix()} weight between the lit colour and
 * a brightened version of <i>itself</i>. In a pitch-black cave that colour is ~0, and no mix weight
 * brightens 0. It also writes to the player's saved option, which a crash can then strand.
 *
 * <p><b>What this does instead.</b> The same shader starts from
 * {@code max(AmbientColor, NightVisionColor * NightVisionFactor)}. Handing it a white night-vision
 * colour at full factor floors every lightmap texel at white – true fullbright, and neutral rather
 * than night vision's green tint, because the colour is ours. Applied by
 * {@code LightmapFullbrightMixin} on the render state each frame; nothing is written back to the
 * options, so there is no state to restore and none to strand.
 */
public final class Fullbright {

    private Fullbright() {
    }

    private static SBSConfig.FullbrightSettings cfg() {
        return ConfigManager.getInstance().get().fullbright;
    }

    /** Whether the world should be fully lit right now. Read once per lightmap update. */
    public static boolean active() {
        return cfg().enabled;
    }

    /**
     * Called for every fresh in-world key press; toggles on the configured key.
     *
     * <p>Flips the stored setting rather than a separate runtime flag, so the key and the settings
     * screen can never disagree about whether fullbright is on.
     */
    public static void onKeyPressed(int keyCode) {
        SBSConfig.FullbrightSettings cfg = cfg();
        if (cfg.toggleKey == -1 || keyCode != cfg.toggleKey) {
            return;
        }
        cfg.enabled = !cfg.enabled;
        ConfigManager.getInstance().save();
        SBSChat.send(Component.literal("Fullbright ")
                .withColor(SBSChat.WHITE)
                .append(Component.literal(cfg.enabled ? "ON" : "OFF")
                        .withColor(cfg.enabled ? 0x57D977 : 0xE0605F)));
    }
}
