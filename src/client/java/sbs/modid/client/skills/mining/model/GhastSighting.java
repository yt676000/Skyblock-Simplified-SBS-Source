/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.model;

/**
 * One observed Powder Ghast, as it was seen. Plain stamped data with no interpretation on top - the
 * interval, if there turns out to be one, is derived from a list of these and never stored.
 *
 * <p>{@link #session} is what makes the derivation honest. Two sightings only bound an interval if
 * nothing happened in between that could have hidden one, and a relog or a server hop is exactly
 * such a gap: the player was not there to see. Sightings carry the world session they belong to so
 * a pair spanning two sessions is never mistaken for a measured interval.
 *
 * @param at      wall-clock of the sighting
 * @param session id of the world session it was seen in; pairs across sessions are not intervals
 * @param island  island as {@code SkyBlockLocation} reported it
 * @param zone    zone as {@code SkyBlockLocation} reported it
 * @param source  how it was noticed - see {@link Source}
 * @param detail  the raw text behind the sighting (nametag or chat line), kept verbatim
 */
public record GhastSighting(long at, long session, String island, String zone, Source source,
                            String detail) {

    /**
     * How a sighting was noticed, because the two are worth very different amounts.
     *
     * <p>An {@link #ENTITY} sighting cannot tell a spawn from the player rounding a corner onto a
     * ghast that was already there, so a series of them bounds the interval from above at best. A
     * {@link #CHAT} sighting - if Hypixel announces the spawn at all, which is exactly what these
     * captures are meant to settle - is the real thing and is what any timer should be built on.
     */
    public enum Source {
        /** Seen in the world by nametag. Timing is "when I looked", not necessarily "when it spawned". */
        ENTITY,
        /** Announced in chat. The only source that dates the spawn itself. */
        CHAT
    }
}
