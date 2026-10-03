/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.pests;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Pests killed and what they dropped: one session's, or one profile's running total. Pure - every
 * time is passed in - so the attribution windows and the per-hour maths are unit-tested.
 *
 * <p><b>Two kill signals, one kill.</b> A kill can be seen twice: the chat line that names the pest
 * (wording ESTIMATED) and the tab widget's alive count dropping (read every scan, names nothing).
 * Whichever arrives first books the kill; the other, within {@value #MATCH_MS} ms, only confirms it -
 * and a chat line arriving after a count drop names the pest the drop booked as "Unknown".
 *
 * <p><b>Attribution.</b> A RARE DROP counts when it arrives within {@value #RARE_MS} ms of a kill. A
 * "[Sacks]" message counts when a kill falls inside the period it covers - it says "(Last 5s.)" to
 * "(Last 30s.)" in the logs - plus {@value #SLACK_MS} ms of slack. Nothing counts without a kill, so a
 * normal crop break never books a drop.
 *
 * <p><b>Pest Traps (ESTIMATED).</b> A trap's catch is paid out when the trap is emptied, long after
 * any kill, so it is a second source: {@link #onTrapCollected} opens a {@value #TRAP_MS} ms window,
 * and a drop arriving inside it is trap loot ({@link #trapDrops}), never kill loot. When a trap
 * window and a kill window overlap, the trap wins for lines arriving after the collection - see
 * {@link #attribute}. A pest a trap caught is counted in {@link #trapPests} by name when something
 * names it, else only in {@link #trapCaughtUnknown}.
 *
 * <p>The maps are the persisted fields (Gson writes them); the deques are transient bookkeeping.
 * Fields added later ({@code trap*}) are absent from older files and load as empty / 0.
 */
public final class PestLedger {

    /** How close the two kill signals must be to be the same kill. */
    public static final long MATCH_MS = 3_000L;
    /** A RARE DROP line within this long after a kill belongs to it. */
    public static final long RARE_MS = 3_000L;
    /** Added to a sack message's covered period, for the message arriving after its period ends. */
    public static final long SLACK_MS = 2_000L;
    /** Under this span there is no meaningful per-hour figure. */
    public static final long MIN_SPAN_MS = 60_000L;

    /** A drop line within this long after a trap collection is the trap's. */
    public static final long TRAP_MS = 5_000L;

    public static final String UNKNOWN = "Unknown";

    /** Where a drop line came from. */
    public enum Source { NONE, KILL, TRAP }

    /** Kills per pest name. */
    public Map<String, Integer> kills = new LinkedHashMap<>();
    /** Dropped amount per SkyBlock item id. */
    public Map<String, Long> drops = new LinkedHashMap<>();
    /** Start of the span the per-hour figure covers: the first kill or trap collection. */
    public long firstKillAt;
    public long lastEventAt;
    /** Trap loot per SkyBlock item id - kept apart from {@link #drops} (kill loot). */
    public Map<String, Long> trapDrops = new LinkedHashMap<>();
    /** Pests caught by traps, per name, when a menu or line named them. */
    public Map<String, Integer> trapPests = new LinkedHashMap<>();
    /** Pests caught by traps that nothing named. */
    public int trapCaughtUnknown;
    public int trapCollections;

    private transient ArrayDeque<Long> killTimes;
    private transient ArrayDeque<Long> namedPending;
    private transient ArrayDeque<Long> unknownPending;
    private transient long lastTrapAt;

    private ArrayDeque<Long> killTimes() {
        if (killTimes == null) {
            killTimes = new ArrayDeque<>();
        }
        return killTimes;
    }

    private ArrayDeque<Long> namedPending() {
        if (namedPending == null) {
            namedPending = new ArrayDeque<>();
        }
        return namedPending;
    }

    private ArrayDeque<Long> unknownPending() {
        if (unknownPending == null) {
            unknownPending = new ArrayDeque<>();
        }
        return unknownPending;
    }

    /** The chat line naming a killed pest. */
    public void onNamedKill(String pest, long now) {
        String name = pest == null || pest.isBlank() ? UNKNOWN : pest;
        prune(unknownPending(), now);
        if (!unknownPending().isEmpty() && !UNKNOWN.equals(name)) {
            // The count drop already booked this kill without a name: give it one.
            unknownPending().pollFirst();
            kills.merge(UNKNOWN, -1, Integer::sum);
            kills.remove(UNKNOWN, 0);
            kills.merge(name, 1, Integer::sum);
            return;
        }
        book(name, now);
        namedPending().addLast(now);
    }

    /** The alive count fell by {@code count} between two tab scans. */
    public void onCountDrop(int count, long now) {
        prune(namedPending(), now);
        for (int i = 0; i < count; i++) {
            if (!namedPending().isEmpty()) {
                namedPending().pollFirst();   // the chat line already booked this one
            } else {
                book(UNKNOWN, now);
                unknownPending().addLast(now);
            }
        }
    }

    private void book(String name, long now) {
        kills.merge(name, 1, Integer::sum);
        touch(now);
        killTimes().addLast(now);
        while (killTimes().size() > 64) {
            killTimes().pollFirst();
        }
    }

    /** Starts the span the per-hour figure covers, and extends it. */
    private void touch(long now) {
        if (firstKillAt == 0) {
            firstKillAt = now;
        }
        lastEventAt = Math.max(lastEventAt, now);
    }

    /**
     * A trap was emptied (trap menu closed, a collection line, the full-trap count fell). Signals
     * within {@value #MATCH_MS} ms of the previous one are the same collection and only move the
     * window.
     *
     * @return whether this was a new collection
     */
    public boolean onTrapCollected(long now) {
        boolean fresh = lastTrapAt == 0 || now - lastTrapAt > MATCH_MS;
        lastTrapAt = now;
        if (fresh) {
            trapCollections++;
        }
        touch(now);
        return fresh;
    }

    /** Pests a trap caught; {@code pest} null or blank = unnamed. */
    public void onTrapCaught(String pest, int count, long now) {
        if (count <= 0) {
            return;
        }
        if (pest == null || pest.isBlank()) {
            trapCaughtUnknown += count;
        } else {
            trapPests.merge(pest, count, Integer::sum);
        }
        touch(now);
    }

    /** Whether a drop arriving now falls in a trap window. */
    public boolean inTrapWindow(long now) {
        return lastTrapAt != 0 && lastTrapAt <= now && now - lastTrapAt <= TRAP_MS;
    }

    /**
     * Where a drop line arriving now belongs: {@code coveredSeconds} &lt;= 0 for a RARE DROP line,
     * else the period a "[Sacks]" message covers. A line inside a trap window is the trap's even
     * when a kill window is open too - that is the no-double-count rule.
     */
    public Source attribute(long now, int coveredSeconds) {
        if (inTrapWindow(now)) {
            return Source.TRAP;
        }
        boolean kill = coveredSeconds > 0 ? sackAttributable(now, coveredSeconds) : rareAttributable(now);
        return kill ? Source.KILL : Source.NONE;
    }

    /** Whether a RARE DROP arriving now belongs to a pest. */
    public boolean rareAttributable(long now) {
        for (Iterator<Long> it = killTimes().descendingIterator(); it.hasNext(); ) {
            long at = it.next();
            if (at <= now && now - at <= RARE_MS) {
                return true;
            }
        }
        return false;
    }

    /** Whether a "[Sacks]" message covering the last {@code coveredSeconds} belongs to a pest. */
    public boolean sackAttributable(long now, int coveredSeconds) {
        long from = now - coveredSeconds * 1000L - SLACK_MS;
        for (Iterator<Long> it = killTimes().descendingIterator(); it.hasNext(); ) {
            long at = it.next();
            if (at <= now && at >= from) {
                return true;
            }
        }
        return false;
    }

    public void addDrop(String itemId, long amount, long now) {
        if (itemId == null || amount <= 0) {
            return;
        }
        drops.merge(itemId, amount, Long::sum);
        lastEventAt = Math.max(lastEventAt, now);
    }

    /** Books a drop by its source; {@link Source#NONE} books nothing. */
    public void addDrop(String itemId, long amount, long now, Source source) {
        if (source == Source.KILL) {
            addDrop(itemId, amount, now);
        } else if (source == Source.TRAP && itemId != null && amount > 0) {
            trapDrops.merge(itemId, amount, Long::sum);
            touch(now);
        }
    }

    /** Pests traps caught, named or not. */
    public int totalTrapCaught() {
        int total = trapCaughtUnknown;
        for (int n : trapPests.values()) {
            total += n;
        }
        return total;
    }

    /** Trap loot items, summed. */
    public long trapItemCount() {
        long total = 0;
        for (long n : trapDrops.values()) {
            total += n;
        }
        return total;
    }

    /** Value of the trap loot alone. */
    public double trapValue(ToDoubleFunction<String> price) {
        return sum(trapDrops, price);
    }

    /** Kill loot, plus trap loot when {@code includeTraps}. */
    public double value(ToDoubleFunction<String> price, boolean includeTraps) {
        return value(price) + (includeTraps ? trapValue(price) : 0);
    }

    /** Value per hour over the whole span; trap loot counts in the hour it was collected. */
    public double perHour(ToDoubleFunction<String> price, boolean includeTraps) {
        long span = lastEventAt - firstKillAt;
        return firstKillAt == 0 || span < MIN_SPAN_MS ? 0 : value(price, includeTraps) * 3_600_000.0 / span;
    }

    /** Whether nothing at all was booked. */
    public boolean isEmpty() {
        return totalKills() == 0 && trapCollections == 0 && trapDrops.isEmpty() && totalTrapCaught() == 0;
    }

    /** Older files carry no trap fields, and an explicit JSON null reads as null: never keep one. */
    public void fillMissing() {
        if (kills == null) {
            kills = new LinkedHashMap<>();
        }
        if (drops == null) {
            drops = new LinkedHashMap<>();
        }
        if (trapDrops == null) {
            trapDrops = new LinkedHashMap<>();
        }
        if (trapPests == null) {
            trapPests = new LinkedHashMap<>();
        }
    }

    public int totalKills() {
        int total = 0;
        for (int n : kills.values()) {
            total += n;
        }
        return total;
    }

    /** Value of the kill loot at {@code price} per item id. */
    public double value(ToDoubleFunction<String> price) {
        return sum(drops, price);
    }

    private static double sum(Map<String, Long> items, ToDoubleFunction<String> price) {
        double total = 0;
        for (Map.Entry<String, Long> e : items.entrySet()) {
            total += price.applyAsDouble(e.getKey()) * e.getValue();
        }
        return total;
    }

    /** Value per hour over first kill to last event; 0 under {@link #MIN_SPAN_MS}. */
    public double perHour(ToDoubleFunction<String> price) {
        long span = lastEventAt - firstKillAt;
        return firstKillAt == 0 || span < MIN_SPAN_MS ? 0 : value(price) * 3_600_000.0 / span;
    }

    /** Value per pest killed; 0 with none. */
    public double perPest(ToDoubleFunction<String> price) {
        int total = totalKills();
        return total == 0 ? 0 : value(price) / total;
    }

    public void reset() {
        kills.clear();
        drops.clear();
        trapDrops.clear();
        trapPests.clear();
        trapCaughtUnknown = 0;
        trapCollections = 0;
        lastTrapAt = 0;
        firstKillAt = 0;
        lastEventAt = 0;
        killTimes().clear();
        namedPending().clear();
        unknownPending().clear();
    }

    private static void prune(ArrayDeque<Long> pending, long now) {
        while (!pending.isEmpty() && now - pending.peekFirst() > MATCH_MS) {
            pending.pollFirst();
        }
    }
}
