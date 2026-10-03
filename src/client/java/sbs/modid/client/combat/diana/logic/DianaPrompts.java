/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.diana.model.BurrowRecord;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * The two moments the toolkit says something out loud, and the guard that stops it saying it twice.
 *
 * <h2>What these are for</h2>
 *
 * <p>Both prompts mean the same thing in practice - "there is nothing left to walk to, fire the
 * ability" - and they are the difference between a toolkit and a map. A player watching a marker
 * knows what to do; a player watching an empty screen does not know whether the feature is thinking
 * or broken.
 *
 * <p>Both are display: a title, a sound, a chat line, whichever channels the player picked. Nothing
 * here uses an ability, sends a command or moves anybody.
 *
 * <h2>The repeat guard is the whole subtlety</h2>
 *
 * <p>A failing arrow fit fails once per packet, and a finished chain stays finished. Without a
 * cooldown either would fire dozens of times in a row, and a prompt that fires dozens of times is
 * one the player switches off - which costs them the one firing that mattered.
 */
public final class DianaPrompts {

    /** How long the same prompt stays quiet after firing. */
    private static final long REPEAT_MS = 8_000L;

    private static long lastFailureAt;
    private static long lastChainEndAt;

    private DianaPrompts() {
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /** The arrow could not be fitted, and there is nothing to walk to. */
    public static void guessFailed() {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled || !cfg.promptOnGuessFailure) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFailureAt < REPEAT_MS) {
            return;
        }
        lastFailureAt = now;
        Alerts.send(new Alerts.Alert("Use your spade",
                "The arrow could not be read - fire the ability for a fresh guess.",
                SbsAudio.Tone.CHIME, null), cfg.promptChannels);
    }

    /**
     * A chain has ended.
     *
     * <p>Only says anything when there is genuinely nothing nearby: a chain ending next to three
     * other burrows is not a moment that needs announcing, and announcing it anyway is how a useful
     * prompt becomes noise. The radius is the player's, because how far "nearby" is depends on how
     * they move.
     */
    public static void chainEnded() {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled || !cfg.promptOnChainEnd) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChainEndAt < REPEAT_MS || somethingNearby(cfg.chainEndRadius)) {
            return;
        }
        lastChainEndAt = now;
        Alerts.send(new Alerts.Alert("Chain finished",
                "Nothing within " + cfg.chainEndRadius + " blocks - fire the ability for a new start burrow.",
                SbsAudio.Tone.CHIME, null), cfg.promptChannels);
    }

    /** Whether any burrow or live guess is within {@code radius} blocks of the player. */
    private static boolean somethingNearby(int radius) {
        Minecraft minecraft = Minecraft.getInstance();   // null outside a running client (tests)
        Player player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return true;
        }
        double limit = (double) radius * radius;
        for (BurrowRecord record : BurrowStore.getInstance().all()) {
            if (player.position().distanceToSqr(Vec3.atCenterOf(record.pos)) <= limit) {
                return true;
            }
        }
        for (GuessChain chain : ArrowGuess.getInstance().chains()) {
            BlockPos candidate = chain.current();
            if (candidate != null
                    && player.position().distanceToSqr(Vec3.atCenterOf(candidate)) <= limit) {
                return true;
            }
        }
        BlockPos spade = SpadeGuess.getInstance().guess();
        return spade != null && player.position().distanceToSqr(Vec3.atCenterOf(spade)) <= limit;
    }

    /** World change: a prompt held back on the last server has nothing to say on this one. */
    public static void reset() {
        lastFailureAt = 0L;
        lastChainEndAt = 0L;
    }
}
