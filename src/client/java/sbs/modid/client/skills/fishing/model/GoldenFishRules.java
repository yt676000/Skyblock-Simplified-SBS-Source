/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.model;

import sbs.modid.client.helper.rift.model.Certainty;

/**
 * Every Golden Fish number the timer uses, in one place so one probe session can correct them.
 *
 * <p><b>None of these has been seen in game.</b> The maintainer's ~270 instance logs contain no Golden
 * Fish line at all, the official wiki is gone (it redirects to its shutdown notice) and the mirror
 * refused the request, so there was not even a wiki figure to tag {@link Certainty#WIKI}. Each value
 * is a recollection of community knowledge, tagged {@link Certainty#ESTIMATED}, and the feature ships
 * off by default because of it. The probe checklist is in
 * {@code docs/features/golden-fish-timer.md}.
 */
public final class GoldenFishRules {

    /** Continuous lava fishing before a Golden Fish can first appear. */
    public static final long SPAWN_EARLIEST_MS = 8 * 60_000L;
    /** Continuous lava fishing by which one is expected to have appeared. */
    public static final long SPAWN_LATEST_MS = 12 * 60_000L;
    /** Going this long without a lava cast throws the progress away. */
    public static final long RESET_IDLE_MS = 3 * 60_000L;
    /** How long a Golden Fish stays up before it leaves on its own. */
    public static final long STAY_MS = 60_000L;
    /** Hooks it takes to land one. */
    public static final int HOOKS_TO_CATCH = 3;
    /** Whether landing (or losing) one starts the continuous-fishing clock over. */
    public static final boolean RESTART_AFTER_FISH = true;

    /** How much every number above is worth trusting. Promote only after a probe. */
    public static final Certainty CERTAINTY = Certainty.ESTIMATED;

    /**
     * How early the "about to reset" warning fires. Not a game fact - a UI choice, so it is not
     * covered by {@link #CERTAINTY}.
     */
    public static final long RESET_WARNING_LEAD_MS = 30_000L;

    private GoldenFishRules() {
    }
}
