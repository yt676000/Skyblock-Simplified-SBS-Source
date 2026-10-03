/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import sbs.modid.client.skills.fishing.model.GoldenFishRules;

import java.util.Locale;

/**
 * The Golden Fish rules as a clock, with no game access: every input is a timestamp the caller
 * passes in, so the tests drive it through whole sessions in microseconds.
 *
 * <p>The rules, as written in {@link GoldenFishRules} (all ESTIMATED):
 * <ul>
 *   <li>the first lava cast starts a continuous-fishing clock;</li>
 *   <li>a gap of more than {@link GoldenFishRules#RESET_IDLE_MS} between lava casts starts it over -
 *   checked both on the next cast and on every tick, so the HUD shows the reset when it happens;</li>
 *   <li>a Golden Fish can appear from {@link GoldenFishRules#SPAWN_EARLIEST_MS}; once it is up it has
 *   {@link GoldenFishRules#STAY_MS} before it leaves, and each reel while it is up counts one hook;</li>
 *   <li>landing it, losing it, or it leaving ends the fish and (with
 *   {@link GoldenFishRules#RESTART_AFTER_FISH}) starts the clock over.</li>
 * </ul>
 * The spawn itself is never predicted - only the chat line puts a fish up.
 */
public final class GoldenFishTimer {

    /** What a {@link #tick} call noticed, for the caller to turn into an alert. */
    public enum Event {
        NONE,
        /** Idle long enough that the progress resets within the warning lead. Fires once per gap. */
        RESET_WARNING,
        /** The idle gap passed the limit; the clock is gone. */
        RESET,
        /** The fish outstayed {@link GoldenFishRules#STAY_MS} without a catch or escape line. */
        EXPIRED
    }

    /** What the HUD draws. Times are milliseconds, never negative. */
    public record View(boolean active, long fishingMs, long sinceCastMs, long resetInMs,
                       boolean resetWarning, boolean fishUp, long fishLeftMs, int hooks,
                       boolean canSpawn) {
    }

    /** 0 when there is no continuous-fishing clock. */
    private long sessionStart;
    private long lastCast;
    /** 0 when no fish is up. */
    private long fishUpAt;
    private int hooks;
    private boolean warned;

    /** One cast whose bobber reached lava on the Crimson Isle. */
    public void onLavaCast(long now) {
        if (sessionStart == 0 || now - lastCast > GoldenFishRules.RESET_IDLE_MS) {
            sessionStart = now;
        }
        lastCast = now;
        warned = false;
    }

    /** The rod was reeled in; a hook on the fish if one is up. */
    public void onReel(long now) {
        if (fishUpAt != 0) {
            hooks++;
        }
    }

    /** The spawn line. Also starts a clock, in case the line arrives before any cast was seen. */
    public void onSpawn(long now) {
        if (sessionStart == 0) {
            sessionStart = now;
            lastCast = now;
        }
        fishUpAt = now;
        hooks = 0;
    }

    /** The catch or escape line. */
    public void onFishGone(long now) {
        endFish(now);
    }

    /** Forget everything: a server hop, a world change, leaving the island. */
    public void reset() {
        sessionStart = 0;
        lastCast = 0;
        fishUpAt = 0;
        hooks = 0;
        warned = false;
    }

    public Event tick(long now) {
        if (fishUpAt != 0 && now - fishUpAt > GoldenFishRules.STAY_MS) {
            endFish(now);
            return Event.EXPIRED;
        }
        if (sessionStart == 0 || fishUpAt != 0) {
            return Event.NONE;   // a fish that is up is the point; nobody is idling
        }
        long idle = now - lastCast;
        if (idle > GoldenFishRules.RESET_IDLE_MS) {
            reset();
            return Event.RESET;
        }
        if (!warned && idle > GoldenFishRules.RESET_IDLE_MS - GoldenFishRules.RESET_WARNING_LEAD_MS) {
            warned = true;
            return Event.RESET_WARNING;
        }
        return Event.NONE;
    }

    public View view(long now) {
        if (sessionStart == 0) {
            return new View(false, 0, 0, 0, false, false, 0, 0, false);
        }
        long fishing = Math.max(0, now - sessionStart);   // waiting for a bite is fishing too
        long since = Math.max(0, now - lastCast);
        long resetIn = Math.max(0, GoldenFishRules.RESET_IDLE_MS - since);
        boolean up = fishUpAt != 0;
        long left = up ? Math.max(0, GoldenFishRules.STAY_MS - (now - fishUpAt)) : 0;
        boolean warning = !up && resetIn <= GoldenFishRules.RESET_WARNING_LEAD_MS;
        return new View(true, fishing, since, resetIn, warning, up, left, hooks,
                fishing >= GoldenFishRules.SPAWN_EARLIEST_MS);
    }

    private void endFish(long now) {
        fishUpAt = 0;
        hooks = 0;
        if (GoldenFishRules.RESTART_AFTER_FISH) {
            sessionStart = now;
            lastCast = now;
            warned = false;
        }
    }

    /** "3:07", or "45s" under a minute. */
    public static String clock(long ms) {
        long s = ms / 1000;
        return s < 60 ? s + "s" : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
