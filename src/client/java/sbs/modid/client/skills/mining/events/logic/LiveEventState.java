/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.logic;

import sbs.modid.client.skills.mining.events.model.MiningEvent;

/**
 * What is happening in the lobby the player is standing in: the running event, its countdown, an
 * announced next event and the latest Powder Ghast. Pure - every method takes the clock - so the
 * countdown, the resync and the lobby reset are unit tested without a game.
 *
 * <p><b>Belongs to one lobby.</b> {@link #enter} with a different lobby or island clears everything:
 * a countdown carried across a hop describes the server you left, with full confidence.
 *
 * <p><b>The countdown</b> runs locally from the last sync. A scoreboard reading with a remaining
 * value is a sync and wins over everything; without one, the remaining time is the exact start plus
 * a learned duration, which the caller passes in (it lives in the history, not here).
 */
public final class LiveEventState {

    /** Where a remaining time came from - shown, because an estimate must not read as a fact. */
    public enum RemainingSource {
        SCOREBOARD,
        ESTIMATED,
        NONE
    }

    /**
     * How long an announcement stays {@code KNOWN} past the start it named, so a {@code STARTED!} line
     * a moment late still finds it, and a missed one does not leave "starts in 0:00" up for good.
     */
    static final long ANNOUNCE_GRACE_MS = 10_000L;

    /**
     * How long a countdown may sit at zero with no end line before the event is dropped. The end
     * line normally arrives within a second; this only covers a missed one.
     */
    static final long OVERRUN_MS = 120_000L;

    /** A scoreboard that named the event and then stops naming it for this long has ended it. */
    static final long SCOREBOARD_GONE_MS = 5_000L;

    private String lobby = "";
    private String island = "";

    private MiningEvent event;
    private String rawName;
    private long startedAt;
    private boolean startExact;

    private long syncedRemainingMs = -1L;
    private long syncedAt;
    private long scoreboardSeenAt;

    private MiningEvent announced;
    private String announcedRaw;
    private long announcedStartAt;

    private String ghastZone;
    private long ghastAt;

    // ------------------------------------------------------------------ lobby

    /**
     * The player is in {@code lobby} on {@code island}. Returns {@code true} when that is a different
     * place from before and the state was cleared. An unknown lobby ({@code ""}) adopts the first
     * real id without clearing, since the id often arrives a second after the first chat line.
     */
    public boolean enter(String lobby, String island, long now) {
        String l = lobby == null ? "" : lobby;
        String i = island == null ? "" : island;
        if (this.lobby.equals(l) && this.island.equals(i)) {
            return false;
        }
        boolean adopt = this.lobby.isEmpty() && this.island.equals(i);
        if (!adopt) {
            reset();
        }
        this.lobby = l;
        this.island = i;
        return !adopt;
    }

    /** Clears everything, including the lobby. */
    public void reset() {
        lobby = "";
        island = "";
        clearEvent();
        announced = null;
        announcedRaw = null;
        announcedStartAt = 0L;
        ghastZone = null;
        ghastAt = 0L;
    }

    private void clearEvent() {
        event = null;
        rawName = null;
        startedAt = 0L;
        startExact = false;
        syncedRemainingMs = -1L;
        syncedAt = 0L;
        scoreboardSeenAt = 0L;
    }

    // ------------------------------------------------------------------ input

    /** {@code The X event starts in N seconds!} */
    public void announce(MiningEvent next, String raw, int seconds, long now) {
        announced = next;
        announcedRaw = raw;
        announcedStartAt = now + Math.max(0, seconds) * 1000L;
    }

    /** An event began - exactly ({@code STARTED!}) or only noticed running (scoreboard). */
    public void start(MiningEvent started, String raw, long now, boolean exact) {
        clearEvent();
        event = started;
        rawName = raw;
        startedAt = now;
        startExact = exact;
        announced = null;
        announcedRaw = null;
        announcedStartAt = 0L;
    }

    /**
     * The {@code STARTED!} line for an event already noticed on the scoreboard a moment earlier:
     * the start becomes exact, the countdown sync is kept.
     */
    public void confirmStart(long now) {
        startedAt = now;
        startExact = true;
        announced = null;
    }

    public void end() {
        clearEvent();
    }

    /**
     * A scoreboard reading naming {@code named}. A remaining value resyncs the countdown; without one,
     * only the "still running" mark is refreshed. Starting an event from here is the caller's call.
     */
    public void scoreboard(MiningEvent named, int remainingSeconds, long now) {
        if (event != named) {
            return;
        }
        scoreboardSeenAt = now;
        if (remainingSeconds >= 0) {
            syncedRemainingMs = remainingSeconds * 1000L;
            syncedAt = now;
        }
    }

    /**
     * The scoreboard named no event. Ends the running one only if the scoreboard had been naming it -
     * a sidebar that never shows events says nothing by its silence.
     */
    public boolean scoreboardSilent(long now) {
        if (event != null && scoreboardSeenAt > 0 && now - scoreboardSeenAt > SCOREBOARD_GONE_MS) {
            clearEvent();
            return true;
        }
        return false;
    }

    /** The Powder Ghast spawn line; the zone follows on the next line. */
    public void ghast(long now) {
        ghastAt = now;
        ghastZone = null;
    }

    public void ghastZone(String zone, long now) {
        if (ghastAt == 0L || now - ghastAt > 10_000L) {
            ghastAt = now;
        }
        ghastZone = zone;
    }

    /**
     * Drops what has gone stale: an announcement well past its start, an event whose countdown sat at
     * zero for {@link #OVERRUN_MS}. Returns {@code true} when an event was dropped.
     */
    public boolean expire(long now, long estimatedDurationMs) {
        if (announced != null && now > announcedStartAt + ANNOUNCE_GRACE_MS) {
            announced = null;
            announcedRaw = null;
        }
        if (event == null) {
            return false;
        }
        long remaining = remainingMs(now, estimatedDurationMs);
        if (remaining == 0L && overrunMs(now, estimatedDurationMs) > OVERRUN_MS) {
            clearEvent();
            return true;
        }
        return false;
    }

    private long overrunMs(long now, long estimatedDurationMs) {
        if (syncedRemainingMs >= 0) {
            return now - (syncedAt + syncedRemainingMs);
        }
        return now - (startedAt + estimatedDurationMs);
    }

    // ------------------------------------------------------------------ output

    /**
     * Milliseconds left, never negative, or {@code -1} when nothing supports a number. The scoreboard
     * sync wins; otherwise an exact start plus {@code estimatedDurationMs} ({@code <= 0} = none).
     */
    public long remainingMs(long now, long estimatedDurationMs) {
        if (event == null) {
            return -1L;
        }
        if (syncedRemainingMs >= 0) {
            return Math.max(0L, syncedRemainingMs - (now - syncedAt));
        }
        if (startExact && estimatedDurationMs > 0) {
            return Math.max(0L, startedAt + estimatedDurationMs - now);
        }
        return -1L;
    }

    public RemainingSource remainingSource(long estimatedDurationMs) {
        if (event == null) {
            return RemainingSource.NONE;
        }
        if (syncedRemainingMs >= 0) {
            return RemainingSource.SCOREBOARD;
        }
        return startExact && estimatedDurationMs > 0 ? RemainingSource.ESTIMATED : RemainingSource.NONE;
    }

    public String lobby() {
        return lobby;
    }

    public String island() {
        return island;
    }

    public MiningEvent event() {
        return event;
    }

    public String rawName() {
        return rawName;
    }

    public long startedAt() {
        return startedAt;
    }

    public boolean startExact() {
        return startExact;
    }

    /** The announced next event, or {@code null}. */
    public MiningEvent announced() {
        return announced;
    }

    public String announcedRaw() {
        return announcedRaw;
    }

    public long announcedStartAt() {
        return announcedStartAt;
    }

    public String ghastZone() {
        return ghastZone;
    }

    public long ghastAt() {
        return ghastAt;
    }
}
