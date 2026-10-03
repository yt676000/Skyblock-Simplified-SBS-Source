/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.warp;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.SBSConfig;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which warps this player has actually unlocked - learned, because Hypixel does not tell us.
 *
 * <p><b>There is no API for this.</b> Fast-travel unlocks appear in no endpoint and in no menu we can
 * read without opening it, so the only honest source is the game's own answer when a warp is tried.
 * That gives three states and a deliberate default:
 * <ul>
 *   <li>{@link State#UNLOCKED} – a warp that has worked. Recorded when the island actually changes to
 *       the one the warp goes to.</li>
 *   <li>{@link State#LOCKED} – Hypixel refused it with the "haven't unlocked" line.</li>
 *   <li>{@link State#UNKNOWN} – never tried. <b>Offered anyway.</b> Optimism is the right default: a
 *       fresh install knows nothing, and refusing to suggest anything until the player has proved
 *       each warp by hand would make the feature useless on day one. The cost of being wrong is one
 *       refused command, which is exactly the event that corrects the record.</li>
 * </ul>
 *
 * <p><b>Scoped to the SkyBlock profile.</b> Fast travel is unlocked per profile, so a warp locked on
 * your Ironman must not disappear from your main. Keyed by {@link ProfileContext#profile()}, with a
 * blank bucket for the frames before the profile is known - which merges into nothing worse than the
 * old behaviour of guessing, and self-corrects on the next attempt.
 *
 * <p><b>Cooldown.</b> Hypixel rate-limits warping and commands generally. This class holds the clock
 * (when the last warp went out, when a "too fast" refusal was seen) so callers can wait rather than
 * hammer the server - being kicked for command spam is a far worse failure than arriving a second
 * later.
 */
public final class WarpAvailability {

    /** What we know about one warp command. */
    public enum State {
        UNKNOWN,
        UNLOCKED,
        LOCKED
    }

    /**
     * How long to leave between two warps. Hypixel's own limit is not published; this is a
     * conservative floor that keeps a two-step chain (island, then a warp on it) from tripping the
     * command rate limit.
     */
    public static final long COOLDOWN_MS = 3_000L;

    /** Extra backoff after Hypixel has explicitly said we were too fast. */
    private static final long TOO_FAST_BACKOFF_MS = 5_000L;

    private static long lastWarpAt;
    private static long blockedUntil;

    private WarpAvailability() {
    }

    private static SBSConfig.WarpSettings cfg() {
        return ConfigManager.getInstance().get().warp;
    }

    /** The bucket for the profile in play; blank while the profile is still unknown. */
    private static Map<String, String> states() {
        SBSConfig.WarpSettings cfg = cfg();
        if (cfg.warpStates == null) {
            cfg.warpStates = new LinkedHashMap<>();
        }
        return cfg.warpStates.computeIfAbsent(profileKey(), key -> new LinkedHashMap<>());
    }

    private static String profileKey() {
        String profile = ProfileContext.getInstance().profile();
        return profile == null ? "" : profile;
    }

    /** Commands are compared case-insensitively and without the leading slash. */
    private static String key(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    // ------------------------------------------------------------------ reading

    /** What is known about {@code command} on the current profile. */
    public static State state(String command) {
        String stored = states().get(key(command));
        if (stored == null) {
            return State.UNKNOWN;
        }
        try {
            return State.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return State.UNKNOWN;   // a value written by a build that knew more states than this one
        }
    }

    /**
     * Whether a warp may be suggested. Everything except a warp we have watched Hypixel refuse -
     * see the class docs for why unknown counts as available.
     */
    public static boolean usable(String command) {
        return state(command) != State.LOCKED;
    }

    // ------------------------------------------------------------------ learning

    /** Records that a warp worked. */
    public static void markUnlocked(String command) {
        put(command, State.UNLOCKED);
    }

    /** Records that Hypixel refused a warp as not unlocked. */
    public static void markLocked(String command) {
        put(command, State.LOCKED);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Map] '{}' is not unlocked on this profile", command);
    }

    /**
     * Forgets everything learned on the current profile. Offered in the settings because the record
     * is a guess built from refusals: unlocking a warp in-game produces no message, so a warp that
     * was locked when you last tried it stays locked here until something says otherwise.
     */
    public static void reset() {
        states().clear();
        ConfigManager.getInstance().save();
    }

    /** How many warps are on record for this profile, for the settings status line. */
    public static int knownCount() {
        return states().size();
    }

    private static void put(String command, State state) {
        String stored = states().put(key(command), state.name());
        if (!state.name().equals(stored)) {
            ConfigManager.getInstance().save();   // only when the answer actually changed
        }
    }

    // ------------------------------------------------------------------ cooldown

    /** Call immediately after sending a warp command. */
    public static void noteWarpSent() {
        lastWarpAt = System.currentTimeMillis();
    }

    /** Call when Hypixel says we are sending commands too fast. */
    public static void noteTooFast() {
        blockedUntil = System.currentTimeMillis() + TOO_FAST_BACKOFF_MS;
    }

    /** Milliseconds to wait before the next warp may go out; {@code 0} when it may go now. */
    public static long cooldownRemaining() {
        long now = System.currentTimeMillis();
        long untilCooldown = lastWarpAt + COOLDOWN_MS - now;
        long untilUnblocked = blockedUntil - now;
        return Math.max(0, Math.max(untilCooldown, untilUnblocked));
    }

    /** Drops the cooldown clock - used when a navigation is cancelled, so the next one is not held up. */
    public static void clearCooldown() {
        lastWarpAt = 0;
        blockedUntil = 0;
    }
}
