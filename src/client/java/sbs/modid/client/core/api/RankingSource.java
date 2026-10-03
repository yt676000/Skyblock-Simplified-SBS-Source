/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Resolves a {@link SBSConfig.SourceChoice} — server ranking or local ranking — including the
 * one-time move to the server that happens when a licence token first appears.
 *
 * <p><b>Why a shared class rather than an {@code if} in each feed.</b> Four features carry this
 * choice, and the rule has one subtle part: the auto-switch fires <i>once ever</i>, not once per
 * session and not whenever a token happens to be present. Written out four times, one of them ends
 * up re-asserting the server every launch and quietly overwriting a player who chose local — a bug
 * that looks like the setting "not saving" and is close to impossible to pin down from a report.
 *
 * <p>The pin is stored, not derived, for the same reason: a derived answer ("has a token → server")
 * cannot tell the difference between a player who never chose and one who chose local on purpose.
 */
public final class RankingSource {

    private RankingSource() {
    }

    /**
     * Whether this feature should compute locally right now, applying the one-time auto-switch on
     * the way through.
     *
     * <p>Safe to call from a background thread and cheap after the pin is set: two field reads.
     * Called from the feeds' refresh path rather than from a render pass, so the config write can
     * never land mid-frame.
     */
    public static boolean useLocal(SBSConfig    .SourceChoice choice, String area) {
        if (choice == null) {
            return true; // an absent choice is a config from before this existed: local still works
        }
        if (!choice.pinned && SbsApi.hasLicence()) {
            choice.preferLocal = false;
            choice.pinned = true;
            ConfigManager.getInstance().save();
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][{}] Licence token found - switching to the server ranking once. "
                            + "The choice is yours from here.", area);
        }
        return choice.preferLocal;
    }

    /**
     * Records the player's pick and pins it, so the auto-switch can never overwrite it afterwards.
     *
     * <p>Pinning on an explicit pick matters for the tokenless case: someone who deliberately picks
     * "Server" before entering a token has said what they want, and the auto-switch firing later
     * would be a no-op that still deserves not to happen.
     */
    public static void choose(SBSConfig.SourceChoice choice, boolean local) {
        if (choice == null) {
            return;
        }
        choice.preferLocal = local;
        choice.pinned = true;
        ConfigManager.getInstance().save();
    }
}
