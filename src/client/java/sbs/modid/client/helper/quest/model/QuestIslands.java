/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.model;

import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which SkyBlock island the player is on – the check that decides whether a quest waypoint's
 * coordinates mean anything here.
 *
 * <p><b>Why this is needed at all.</b> Every SkyBlock island reports the same Minecraft dimension,
 * so a dimension check passes everywhere. But each island has its own coordinate space: the Crimson
 * Isle waypoint at {@code -389, 94, -479} names a completely different place on the Hub. Routing to
 * it from the wrong island does not fail – it silently walks you somewhere arbitrary, which is
 * exactly the symptom of "the path is not applied to the quest coordinates".
 *
 * <p><b>How.</b> Hypixel publishes the current zone on the sidebar as {@code ⏣ Village} and the
 * island on the tab list as {@code Area: Hub}, both read by {@link SkyBlockLocation}. Hub sub-areas
 * (Village, Graveyard, Colosseum, ...) all share the Hub's coordinates, so the quest data maps areas
 * to islands and this resolves the live location through that table - a candidate may name either
 * the zone or the island, whichever the quest data recorded.
 *
 * <p><b>The table is quest data, not code.</b> Hypixel has dozens of sub-areas and renames them, so
 * the mapping lives in the quest's own JSON next to the waypoints it qualifies. Consequently an
 * unknown area resolves to {@code null} and routing simply does not start – reported to the player
 * rather than guessed at, because guessing means routing to the wrong coordinates.
 *
 * <p>A quest whose file declares no islands (and waypoints with no {@code island}) is not
 * restricted at all – {@link #onIslandOf} passes everywhere, which is the same behaviour as before
 * any island data existed.
 */
public final class QuestIslands {

    private QuestIslands() {
    }

    /**
     * The island id the player is currently on, or {@code null} when the area is unknown / the
     * sidebar is not readable (not on SkyBlock).
     *
     * @param quest the active quest, whose {@code islands} table maps areas to islands
     */
    public static String currentIsland(Quest quest) {
        if (quest == null || quest.islands == null || quest.islands.isEmpty()) {
            return null;
        }
        String zone = SkyBlockLocation.zone().toLowerCase(Locale.ROOT);
        String island = SkyBlockLocation.island().toLowerCase(Locale.ROOT);
        if (zone.isEmpty() && island.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : quest.islands.entrySet()) {
            for (String candidate : entry.getValue()) {
                if (candidate == null) {
                    continue;
                }
                String name = candidate.toLowerCase(Locale.ROOT);
                if (name.equals(zone) || name.equals(island)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** Whether the player is standing on the island a waypoint's coordinates belong to. */
    public static boolean onIslandOf(Quest quest, Quest.QuestWaypoint waypoint) {
        if (waypoint == null) {
            return false;
        }
        if (waypoint.island == null || waypoint.island.isBlank()) {
            return true; // no island declared - the data does not restrict it
        }
        return waypoint.island.equals(currentIsland(quest));
    }

    /** "Crimson Isle" from "crimson_isle" – for telling the player where to go. */
    public static String prettyIsland(String islandId) {
        if (islandId == null || islandId.isBlank()) {
            return "?";
        }
        StringBuilder out = new StringBuilder(islandId.length());
        boolean upper = true;
        for (char c : islandId.toCharArray()) {
            if (c == '_') {
                out.append(' ');
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    /** The current location as the game reports it, for showing why routing is not running. */
    public static String currentArea() {
        String described = SkyBlockLocation.describe();
        return "unknown".equals(described) ? "" : described;
    }
}
