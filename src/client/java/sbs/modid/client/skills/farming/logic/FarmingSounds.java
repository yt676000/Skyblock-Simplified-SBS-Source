/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.farming.model.HoeLevels;

import java.util.Locale;

/**
 * Decides whether a sound the game is about to play belongs to a farming feature that has been
 * asked to keep quiet. Called from {@code FarmingSoundMuteMixin} for every sound.
 *
 * <p><b>Why the vacuum list is configurable and empty by default.</b> Hypixel plays its ability
 * sounds through ordinary vanilla sound events, and which event the pest vacuum uses is not
 * something a mod can know without hearing it – guessing would silence something else instead
 * (the alert pling this very mod plays, for instance). So the vacuum mute starts in learn mode:
 * every sound heard while a vacuum is held is logged once per second as {@code [SBS][Vacuum]}, and
 * pasting the id into the setting mutes exactly that one. One deliberate line beats a denylist that
 * is wrong in a way nobody can see.
 *
 * <p>The hoe level-up mute needs none of that: it is scoped by {@link HoeLevels#muted()} to the two
 * and a half seconds after the level-up chat line, and only the celebratory sound families can fire
 * in that window.
 */
public final class FarmingSounds {

    /** Sound id fragments that make up a level-up jingle. */
    private static final String[] LEVEL_UP_SOUNDS = {
            "levelup", "level_up", "challenge_complete", "player.levelup", "ui.toast"};

    private static long lastVacuumLogAt;

    private FarmingSounds() {
    }

    /** Whether the sound with this id should be dropped. {@code id} is the full "namespace:path". */
    public static boolean muted(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (HoeLevels.getInstance().muted() && sbs.modid.client.skills.SkillIslands.farmingAllowed()) {
            for (String fragment : LEVEL_UP_SOUNDS) {
                if (lower.contains(fragment)) {
                    return true;
                }
            }
        }
        return vacuumMuted(lower);
    }

    /** The vacuum half: learn mode logs, a configured fragment mutes. */
    private static boolean vacuumMuted(String lower) {
        if (!sbs.modid.client.skills.garden.logic.PestTracker.getInstance().vacuumMuted()) {
            return false;
        }
        String configured = ConfigManager.getInstance().get().garden.vacuumSoundIds;
        if (configured == null || configured.isBlank()) {
            long now = System.currentTimeMillis();
            if (now - lastVacuumLogAt > 1_000L) {
                lastVacuumLogAt = now;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Vacuum] heard while holding the vacuum: {}", lower);
            }
            return false;
        }
        for (String fragment : configured.toLowerCase(Locale.ROOT).split(",")) {
            String trimmed = fragment.trim();
            if (!trimmed.isEmpty() && lower.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }
}
