/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.model;

/**
 * One event this client watched in one lobby, as stored in {@code mining_events.json}.
 *
 * <p>Plain data, no player names. Every statistic - durations, the start-to-start cycle, the
 * end-to-start gap - is derived from a list of these and never stored, so a better derivation
 * applies to the whole history at once.
 *
 * <p>{@link #presence} makes the pairs honest: two observations only bound an interval when the
 * player stayed in the lobby between them. A relog or a hop could hide a whole event, so pairs across
 * two presences are never read as one interval.
 *
 * @param island     island as {@code SkyBlockLocation} reported it ({@code Dwarven Mines})
 * @param lobby      normalised lobby id ({@code m24CD})
 * @param event      {@link MiningEvent} constant name
 * @param rawName    the name as the game wrote it - kept for {@link MiningEvent#UNKNOWN}
 * @param start      wall-clock start, or {@code 0} when it was not seen
 * @param end        wall-clock end, or {@code 0} when it was not seen
 * @param startExact {@code true} when {@link #start} is the {@code STARTED!} line itself, not the
 *                   first moment the event was noticed already running
 * @param endExact   {@code true} when {@link #end} is the {@code ENDED!} line itself
 * @param presence   id of the continuous stay in the lobby this was seen in
 * @param source     what first reported it: {@code chat} or {@code scoreboard}
 */
public record EventObservation(String island, String lobby, String event, String rawName, long start,
                               long end, boolean startExact, boolean endExact, long presence,
                               String source) {

    public MiningEvent eventType() {
        return MiningEvent.byId(event);
    }

    /** The newest moment this observation says anything about. */
    public long lastSeen() {
        return Math.max(start, end);
    }

    public EventObservation withEnd(long at, boolean exact) {
        return new EventObservation(island, lobby, event, rawName, start, at, startExact, exact,
                presence, source);
    }

    /** Whether this record is complete enough to keep: an id and at least one timestamp. */
    public boolean valid() {
        return island != null && lobby != null && event != null && (start > 0 || end > 0);
    }
}
