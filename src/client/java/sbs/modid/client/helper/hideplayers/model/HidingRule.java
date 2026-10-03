/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.hideplayers.model;

/**
 * Who may be hidden - the whole rule, with no Minecraft types, so it is unit-tested.
 *
 * <p>Never yourself, never anything that is not a real player (an entity missing from the tab list
 * is one of Hypixel's player-shaped NPCs), never an exception the player switched on. Of the rest:
 * everyone when "everywhere" is on; otherwise whoever is inside your radius (the first mode) or
 * inside the NPC radius of an NPC you are close to (the second). The modes are independent.
 */
public final class HidingRule {

    /**
     * What the tick knows about one player entity.
     *
     * @param distanceSq    squared distance to you
     * @param npcDistanceSq squared distance to the nearest NPC you are close to, or
     *                      {@link Double#POSITIVE_INFINITY} when there is none
     */
    public record Candidate(boolean self, boolean inTabList, boolean partyMember, boolean dungeonTeam,
                            boolean trusted, double distanceSq, double npcDistanceSq) {

        /** A candidate with no NPC near you - the first mode alone. */
        public Candidate(boolean self, boolean inTabList, boolean partyMember, boolean dungeonTeam,
                         boolean trusted, double distanceSq) {
            this(self, inTabList, partyMember, dungeonTeam, trusted, distanceSq, Double.POSITIVE_INFINITY);
        }
    }

    /**
     * The player's settings, read once per tick.
     *
     * @param nearMe    the first mode: hide within {@code radius} of you
     * @param nearNpcs  the second mode: hide within {@code npcRadius} of an NPC you are close to
     */
    public record Settings(boolean everywhere, int radius, boolean keepParty, boolean keepDungeonTeam,
                           boolean keepTrusted, boolean nearMe, boolean nearNpcs, int npcRadius) {

        /** The first mode alone, as the card had before the NPC mode. */
        public Settings(boolean everywhere, int radius, boolean keepParty, boolean keepDungeonTeam,
                        boolean keepTrusted) {
            this(everywhere, radius, keepParty, keepDungeonTeam, keepTrusted, true, false, 0);
        }
    }

    /** How close you must be to an NPC for the NPC mode to act around it, in blocks. */
    public static final double NPC_ACTIVE_RANGE = 10.0;

    private HidingRule() {
    }

    public static boolean mayHide(Candidate candidate, Settings settings) {
        if (candidate.self() || !candidate.inTabList()) {
            return false;
        }
        if ((settings.keepParty() && candidate.partyMember())
                || (settings.keepDungeonTeam() && candidate.dungeonTeam())
                || (settings.keepTrusted() && candidate.trusted())) {
            return false;
        }
        if (settings.everywhere()) {
            return true;
        }
        double radius = Math.max(0, settings.radius());
        if (settings.nearMe() && candidate.distanceSq() <= radius * radius) {
            return true;
        }
        double npcRadius = Math.max(0, settings.npcRadius());
        return settings.nearNpcs() && candidate.npcDistanceSq() <= npcRadius * npcRadius;
    }

    /**
     * Whether hiding runs at all right now. Dungeons, Kuudra and a live slayer boss are where the
     * players around you are information, so there it needs its own switch.
     */
    public static boolean activeHere(boolean inCombatArea, boolean hideInCombat) {
        return !inCombatArea || hideInCombat;
    }
}
