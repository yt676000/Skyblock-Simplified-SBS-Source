/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.fallenstar.logic;

import sbs.modid.client.helper.map.model.MapLocation;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the game says about a Fallen Star, and where that points.
 *
 * <p><b>The crash line is CONFIRMED</b>, from the play instance's logs (2026-08-03, 14:01:31, the only
 * occurrence in 2026-07-08..2026-09-24):
 * <pre>✯ A Fallen Star has crashed at Royal Mines! Nearby ore and Powder drops are amplified!</pre>
 * It names the <i>zone</i>, not a position. Nothing in those logs ends it, so the end line is
 * unknown and the star is cleared by {@link #ACTIVE_TIMEOUT_MS} instead. The same session later has
 * players "killed by Star Sentry" nearby - a lead on what guards the star, not evidence of where.
 *
 * <p>Pure: text and a location list in, a zone and a point out.
 */
public final class FallenStarLines {

    /**
     * How long a star is treated as active with no end line. {@code ESTIMATED} - never observed; one
     * constant so a probe corrects it in one place.
     */
    public static final long ACTIVE_TIMEOUT_MS = 30L * 60_000L;

    /** Anchored so a player quoting it in chat does not raise a star. */
    private static final Pattern CRASH =
            Pattern.compile("^✯? ?A Fallen Star has crashed at (.+?)! Nearby ore and Powder drops are amplified!$");

    private FallenStarLines() {
    }

    /** The zone a crash line names, or {@code null} when the line is not the crash line. */
    public static String crashZone(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = CRASH.matcher(raw.replaceAll("§.", "").trim());
        return matcher.matches() ? matcher.group(1).trim() : null;
    }

    /** The alert headline for a crash in {@code zone}. */
    public static String alertTitle(String zone) {
        return "Fallen Star at " + zone;
    }

    /**
     * The point to send the player to for {@code zone}: the map location named exactly that, else the
     * first location whose area is that zone. {@code null} when the map does not know the zone - the
     * alert still fires, only the waypoint is skipped.
     */
    public static MapLocation zoneCentre(String zone, List<MapLocation> locations) {
        if (zone == null || locations == null) {
            return null;
        }
        for (MapLocation location : locations) {
            if (zone.equalsIgnoreCase(location.name)) {
                return location;
            }
        }
        for (MapLocation location : locations) {
            if (zone.equalsIgnoreCase(location.area)) {
                return location;
            }
        }
        return null;
    }
}
