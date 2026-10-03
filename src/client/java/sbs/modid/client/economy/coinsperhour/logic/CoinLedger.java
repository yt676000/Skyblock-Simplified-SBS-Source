/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.coinsperhour.logic;

import sbs.modid.client.economy.coinsperhour.logic.CoinLines.Event;
import sbs.modid.client.economy.coinsperhour.logic.CoinLines.Kind;
import sbs.modid.client.economy.coinsperhour.logic.CoinLines.Source;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The accounting behind Coins per Hour: purse readings and coin chat lines in, earned / spent /
 * moved out, over an active-time clock that stops while the player is idle.
 *
 * <h2>The rule</h2>
 * A purse change is earned + spent + moved. Each change is explained by the coin lines (see
 * {@link CoinLines}) that arrived within {@link #MATCH_WINDOW_MS} of it, either side - the chat line
 * and the scoreboard update are separate packets and either can come first. A line explains up to
 * its own amount, in the same direction; whatever no line explains is booked to
 * {@link Source#UNKNOWN}, which stays visible as its own row and is never hidden. Unexplained income
 * counts as earned (mob coins, drops sold by a feature that prints nothing); unexplained outgoings
 * count as spent. Transfers - the bank, a refunded order - are never either.
 *
 * <p>Bazaar and auction <b>claims</b> are booked separately, so the player can decide at read time
 * whether they are earnings (a flip realised) or a transfer (money that was already theirs, held by
 * the Bazaar) without rebooking anything.
 *
 * <h2>The clock</h2>
 * Starts at the first purse reading and advances only while the last activity - a purse change or a
 * movement the tracker reports - is within the idle limit. Idle time past the limit is not counted;
 * the limit itself is, because nobody can tell a pause from a stop until it has gone on that long.
 *
 * <p>Pure: every call takes the time.
 */
public final class CoinLedger {

    /** How far apart a purse change and the line explaining it may arrive. */
    public static final long MATCH_WINDOW_MS = 2_000L;
    /** Rates are not stated for less active time than this. */
    public static final long MIN_RATE_MS = 60_000L;

    private record PendingDelta(long signedAmount, long at, long[] remaining) {
    }

    private static final class PendingEvent {
        final Event event;
        final long at;
        long remaining;

        PendingEvent(Event event, long at) {
            this.event = event;
            this.at = at;
            this.remaining = event.amount();
        }
    }

    private final List<PendingDelta> deltas = new ArrayList<>();
    private final List<PendingEvent> events = new ArrayList<>();

    private final Map<Source, Long> earned = new EnumMap<>(Source.class);
    private final Map<Source, Long> claims = new EnumMap<>(Source.class);
    private final Map<Source, Long> spent = new EnumMap<>(Source.class);
    private long transferIn;
    private long transferOut;

    private long purse = -1L;
    private long lastActivity = Long.MIN_VALUE / 2;
    private long lastTick = -1L;
    private long activeMs;
    /** The idle limit the last tick ran with, for activity reported without one. */
    private long idleLimit = Long.MAX_VALUE;

    // ------------------------------------------------------------------ inputs

    /** A purse reading off the scoreboard. The first one is the baseline, not a gain. */
    public void onPurse(long value, long now) {
        if (value < 0) {
            return;
        }
        if (purse < 0) {
            purse = value;
            lastTick = now;
            lastActivity = now;
            return;
        }
        long delta = value - purse;
        purse = value;
        if (delta == 0) {
            return;
        }
        onActivity(now);
        deltas.add(new PendingDelta(delta, now, new long[] {Math.abs(delta)}));
        match(now);
    }

    /** A coin chat line. */
    public void onEvent(Event event, long now) {
        if (event == null) {
            return;
        }
        events.add(new PendingEvent(event, now));
        match(now);
    }

    /** The player moved, turned or pressed something. */
    public void onActivity(long now) {
        onActivity(now, idleLimit);
    }

    /**
     * Activity with the idle limit known: coming back from idle restarts the clock at {@code now},
     * so the stretch between going idle and returning is never counted.
     */
    public void onActivity(long now, long idleMs) {
        if (lastTick >= 0 && now - lastActivity > idleMs && now > lastTick) {
            lastTick = now;
        }
        lastActivity = now;
    }

    /** Advances the clock and books what has waited out the window. Call often. */
    public void tick(long now, long idleMs) {
        idleLimit = idleMs;
        if (lastTick >= 0 && now > lastTick) {
            long activeUntil = Math.min(now, lastActivity + idleMs);
            if (activeUntil > lastTick) {
                activeMs += activeUntil - lastTick;
            }
        }
        if (lastTick >= 0) {
            lastTick = now;
        }
        settle(now);
    }

    /**
     * A world change: the next reading may be a different profile or a purse that moved while no
     * scoreboard was showing, so it is a new baseline rather than a change.
     */
    public void rebaseline() {
        purse = -1L;
    }

    /** Everything back to zero. */
    public void reset() {
        deltas.clear();
        events.clear();
        earned.clear();
        claims.clear();
        spent.clear();
        transferIn = 0;
        transferOut = 0;
        purse = -1L;
        lastTick = -1L;
        activeMs = 0;
    }

    // ------------------------------------------------------------------ matching

    private void match(long now) {
        for (PendingDelta delta : deltas) {
            boolean in = delta.signedAmount > 0;
            for (PendingEvent pending : events) {
                if (delta.remaining[0] == 0) {
                    break;
                }
                if (pending.remaining == 0 || pending.event.in() != in
                        || Math.abs(pending.at - delta.at) > MATCH_WINDOW_MS) {
                    continue;
                }
                long take = Math.min(delta.remaining[0], pending.remaining);
                book(pending.event, take);
                pending.remaining -= take;
                delta.remaining[0] -= take;
            }
        }
        deltas.removeIf(delta -> delta.remaining[0] == 0);
        events.removeIf(pending -> pending.remaining == 0);
    }

    private void settle(long now) {
        Iterator<PendingDelta> it = deltas.iterator();
        while (it.hasNext()) {
            PendingDelta delta = it.next();
            if (now - delta.at > MATCH_WINDOW_MS) {
                bookUnknown(delta.signedAmount > 0, delta.remaining[0]);
                it.remove();
            }
        }
        // A line whose coins never showed on the purse (or were already explained) is dropped once
        // no reading can pair with it any more.
        events.removeIf(pending -> now - pending.at > MATCH_WINDOW_MS * 2);
    }

    private void book(Event event, long amount) {
        if (event.kind() == Kind.TRANSFER) {
            if (event.in()) {
                transferIn += amount;
            } else {
                transferOut += amount;
            }
        } else if (event.kind() == Kind.EARN) {
            (event.claim() ? claims : earned).merge(event.source(), amount, Long::sum);
        } else {
            spent.merge(event.source(), amount, Long::sum);
        }
    }

    private void bookUnknown(boolean in, long amount) {
        if (amount <= 0) {
            return;
        }
        (in ? earned : spent).merge(Source.UNKNOWN, amount, Long::sum);
    }

    // ------------------------------------------------------------------ reading

    /**
     * Earned by source, claims folded in for the sources whose claims count. Ordered by the
     * {@link Source} declaration, UNKNOWN last.
     */
    public Map<Source, Long> earnedBySource(boolean bazaarClaimsEarn, boolean auctionClaimsEarn) {
        Map<Source, Long> out = new EnumMap<>(earned);
        claims.forEach((source, amount) -> {
            if ((source == Source.BAZAAR && bazaarClaimsEarn)
                    || (source == Source.AUCTION && auctionClaimsEarn)) {
                out.merge(source, amount, Long::sum);
            }
        });
        return out;
    }

    public long earned(boolean bazaarClaimsEarn, boolean auctionClaimsEarn) {
        return earnedBySource(bazaarClaimsEarn, auctionClaimsEarn).values().stream()
                .mapToLong(Long::longValue).sum();
    }

    /** Claims NOT counted as earnings, shown with the transfers. */
    public long uncountedClaims(boolean bazaarClaimsEarn, boolean auctionClaimsEarn) {
        long total = 0;
        for (Map.Entry<Source, Long> entry : claims.entrySet()) {
            boolean counted = (entry.getKey() == Source.BAZAAR && bazaarClaimsEarn)
                    || (entry.getKey() == Source.AUCTION && auctionClaimsEarn);
            if (!counted) {
                total += entry.getValue();
            }
        }
        return total;
    }

    public Map<Source, Long> spentBySource() {
        return new EnumMap<>(spent);
    }

    public long spent() {
        return spent.values().stream().mapToLong(Long::longValue).sum();
    }

    public long transferIn() {
        return transferIn;
    }

    public long transferOut() {
        return transferOut;
    }

    public long activeMs() {
        return activeMs;
    }

    /** Whether the clock is running right now. */
    public boolean active(long now, long idleMs) {
        return lastTick >= 0 && now - lastActivity <= idleMs;
    }

    public boolean started() {
        return lastTick >= 0;
    }

    /** {@code value} per active hour, or {@code 0} under {@link #MIN_RATE_MS} of active time. */
    public long perHour(long value) {
        return activeMs < MIN_RATE_MS ? 0L : Math.round(value * 3_600_000.0 / activeMs);
    }
}
