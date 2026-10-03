/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.List;

/**
 * Detects whether the player is in a Catacombs dungeon by reading the in-game <b>sidebar scoreboard</b>
 * – pure Vanilla 1.26.2, no Fabric API.
 *
 * <p>The lines themselves come from {@link SkyBlockLocation#sidebarLines()}; here we only look for the
 * dungeon marker "{@code The Catacombs (F}" / "{@code (M}" (e.g. the "{@code ⏣ The Catacombs (F6)}"
 * line). No world scanning, no timers.
 *
 * <p><b>Why the scoreboard and not the tab list.</b> The tab list also names the dungeon, on its
 * "{@code Dungeon: Catacombs}" line, and {@link SkyBlockLocation} uses that to know the island. But
 * only the scoreboard carries the <i>floor</i>, which is what separates a real run from the entrance
 * and drives every floor-specific feature - so dungeon gating stays on this reader.
 */
public final class DungeonScoreboard {

    private static final String CATACOMBS_F = "The Catacombs (F"; // normal floors F1-F7
    private static final String CATACOMBS_M = "The Catacombs (M"; // master mode M1-M7
    private static final String CATACOMBS_E = "The Catacombs (E"; // the Entrance floor

    private DungeonScoreboard() {
    }

    /** @return {@code true} when a sidebar line shows the Catacombs floor marker (e.g. "(F6)"/"(M2)"/"(E)"). */
    public static boolean isInDungeon() {
        for (String line : sidebarLines()) {
            if (line.contains(CATACOMBS_F) || line.contains(CATACOMBS_M) || line.contains(CATACOMBS_E)) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} when the player is anywhere in The Catacombs (broader than a specific floor). */
    public static boolean isInCatacombs() {
        for (String line : sidebarLines()) {
            if (line.contains("The Catacombs")) {
                return true;
            }
        }
        return false;
    }

    /** The current sidebar's lines, colour-stripped (empty when no sidebar / not in a world). */
    public static List<String> sidebarLines() {
        return SkyBlockLocation.sidebarLines();
    }
}
