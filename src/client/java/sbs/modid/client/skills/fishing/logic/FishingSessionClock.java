/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

/**
 * The fishing session's "Time": how long the player actually fished, built from activity events
 * (cast, reel, bite, catch, loot share) rather than from the wall clock.
 *
 * <p><b>Rule.</b> The time between two consecutive events counts in full when it is at most the
 * pause length, and not at all when it is longer - that gap was a break (the bazaar, afk, a lobby
 * swap). The stretch since the latest event counts live under the same rule, so the clock runs while
 * the player is between casts and stops once they have been away for the pause length. Crossing that
 * threshold takes the live stretch back out, which is the one moment the shown time can step down:
 * it happens only after the pause length of inactivity, when nobody is fishing.
 *
 * <p>Events are timestamps, never ticks, so a skipped tick, a lag spike or a gated-off tick task
 * cannot add or lose time. Kept free of Minecraft so the rule is tested, not fished for.
 */
public final class FishingSessionClock {

    /** Sum of the closed gaps that counted. */
    private long closedMs;
    /** The latest event, or -1 before the first. */
    private long lastEventAt = -1;

    /** One activity event at {@code now}; a gap longer than {@code pauseMs} is a break. */
    public void onEvent(long now, long pauseMs) {
        if (lastEventAt >= 0) {
            long gap = now - lastEventAt;
            if (gap > 0 && gap <= pauseMs) {
                closedMs += gap;
            }
        }
        if (now > lastEventAt) {
            lastEventAt = now;
        }
    }

    /** Fished time at {@code now}, in milliseconds. */
    public long millis(long now, long pauseMs) {
        if (lastEventAt < 0) {
            return 0;
        }
        long open = now - lastEventAt;
        return closedMs + (open > 0 && open <= pauseMs ? open : 0);
    }

    /** The latest event, or -1 before the first. */
    public long lastEventAt() {
        return lastEventAt;
    }

    /** Forgets the session. */
    public void reset() {
        closedMs = 0;
        lastEventAt = -1;
    }
}
