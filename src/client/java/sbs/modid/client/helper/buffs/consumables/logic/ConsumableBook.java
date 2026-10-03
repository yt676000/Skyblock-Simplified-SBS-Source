/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.logic;

import sbs.modid.client.helper.buffs.consumables.model.ConsumableClock;
import sbs.modid.client.helper.buffs.consumables.model.ConsumableKind;
import sbs.modid.client.helper.buffs.consumables.model.ConsumableTimer;
import sbs.modid.client.helper.buffs.consumables.model.ConsumableTimer.Confidence;
import sbs.modid.client.helper.buffs.consumables.model.ConsumableTimer.Source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Every active consumable timer of one profile, and every rule about them: how each clock counts,
 * which source wins, when an alert is due. Pure - time and the player's situation are passed in -
 * and Gson-friendly, so {@link ConsumableStore} persists it as it is.
 *
 * <p><b>Precedence.</b> A server read (tab footer, menu, the "expires in" line) replaces an
 * estimate. A coarser read than the current server value ("16 hours" against a chat line's exact
 * "28h 48m") only replaces it when the current value falls outside the read's window - otherwise
 * the footer would round a precise timer away every two seconds.
 *
 * <p><b>Alerts.</b> A warning fires once per timer instance when its remaining time is at or below
 * the threshold, the timer is counting, and it is not a restored value waiting for confirmation. A
 * correction that drops the time by more than {@link #SILENT_CORRECTION_MS} skips every threshold it
 * jumped over, silently. Consuming the item again re-arms the thresholds that are ahead again.
 */
public final class ConsumableBook {

    /** Where the player is, as far as the clocks care. */
    public record Context(boolean onSkyBlock, boolean inDungeon) {
    }

    /** One kind's alert settings. {@code warnMs <= 0} means no warning, only the end. */
    public record Policy(boolean enabled, long warnMs, boolean endAlert) {
        public static final Policy OFF = new Policy(false, 0, false);
    }

    /** Something worth telling the player. */
    public sealed interface Event permits Warning, Ended {
    }

    public record Warning(String label, ConsumableKind kind, long remainingMs, long thresholdMs) implements Event {
    }

    /** {@code estimated}: the server never said so - the local count reached zero. */
    public record Ended(String label, ConsumableKind kind, boolean estimated) implements Event {
    }

    /** The outcome of comparing a restored timer's two predictions with the first server read. */
    public record ClockCheck(String key, long serverMs, long onlineMs, long realMs,
                             ConsumableClock matched, boolean switched) {
    }

    /** Larger downward corrections skip the thresholds they jump over. */
    static final long SILENT_CORRECTION_MS = 3 * 60_000L;
    /** A restored timer nothing confirms is trusted again after this long on SkyBlock. */
    static final long RESTORE_RELEASE_MS = 2 * 60_000L;
    /** How long a timer the server announces the end of waits at zero for that announcement. */
    static final long END_GRACE_MS = 90_000L;
    /** The most one advance step counts on an online clock (a stalled client is not play time). */
    static final long MAX_ONLINE_STEP_MS = 60_000L;
    /** A server read this far above the current value is a refill nobody announced in chat. */
    static final long REFILL_JUMP_MS = 10 * 60_000L;

    public Map<String, ConsumableTimer> timers = new LinkedHashMap<>();
    /** Wall clock of the last {@link #advance} - the start of the offline gap on restore. */
    public long lastAdvancedAt;
    /** Clocks a relog has shown to be different from the starting table, per key. */
    public Map<String, ConsumableClock> learnedClocks = new LinkedHashMap<>();

    // ------------------------------------------------------------------ clocks

    /** Whether {@code t} is not counting down right now. */
    public boolean paused(ConsumableTimer t, Context ctx) {
        return switch (t.clock) {
            case REAL_TIME -> false;
            case ONLINE_ONLY -> !ctx.onSkyBlock() || (t.pausedInDungeon && ctx.inDungeon());
            case DEPENDENT -> {
                ConsumableTimer parent = t.parentKey == null ? null : timers.get(t.parentKey);
                yield parent == null || parent.clock == ConsumableClock.DEPENDENT || paused(parent, ctx);
            }
        };
    }

    /**
     * Counts every timer down by the time since the last call, by its own clock, and returns the
     * alerts that became due.
     */
    public List<Event> advance(long now, Context ctx, Function<ConsumableKind, Policy> policy) {
        long wall = lastAdvancedAt == 0 ? 0 : Math.max(0, now - lastAdvancedAt);
        long online = Math.min(wall, MAX_ONLINE_STEP_MS);
        lastAdvancedAt = now;
        List<Event> events = new ArrayList<>();
        // Decide every pause before anything moves, so a parent ending this step does not change
        // whether its mixins counted during it.
        Set<String> pausedKeys = new HashSet<>();
        for (ConsumableTimer t : timers.values()) {
            if (paused(t, ctx)) {
                pausedKeys.add(t.key);
            }
        }
        for (ConsumableTimer t : new ArrayList<>(timers.values())) {
            if (t.restoredUnconfirmed && ctx.onSkyBlock()) {
                t.restoredOnlineMs += online;
                if (t.restoredOnlineMs >= RESTORE_RELEASE_MS) {
                    t.restoredUnconfirmed = false;
                }
            }
            if (!t.known() || pausedKeys.contains(t.key)) {
                continue;
            }
            long step = t.clock == ConsumableClock.REAL_TIME ? wall : online;
            t.remainingMs = Math.max(0, t.remainingMs - step);
            Policy p = policy.apply(t.kind);
            if (t.remainingMs > 0) {
                t.zeroAt = 0;
                warn(t, p, events);
                continue;
            }
            if (t.zeroAt == 0) {
                t.zeroAt = now;
            }
            if (announcesEnd(t.kind) && now - t.zeroAt < END_GRACE_MS) {
                continue;   // the server's "has expired!" is due; it is the better end signal
            }
            Ended ended = end(t, false);
            if (ended != null) {
                events.add(new Ended(ended.label(), ended.kind(), true));
            }
        }
        return events;
    }

    private void warn(ConsumableTimer t, Policy p, List<Event> events) {
        if (!p.enabled() || p.warnMs() <= 0 || t.restoredUnconfirmed) {
            return;
        }
        long threshold = p.warnMs();
        if (t.remainingMs > threshold || threshold >= t.skipAtOrAbove || t.firedThresholds.contains(threshold)) {
            return;
        }
        t.firedThresholds.add(threshold);
        events.add(new Warning(t.label(), t.kind, t.remainingMs, threshold));
    }

    /** The kinds whose end Hypixel announces in chat ("God Potion has expired!"). */
    private static boolean announcesEnd(ConsumableKind kind) {
        return kind == ConsumableKind.GOD_POTION || kind == ConsumableKind.BOOSTER_COOKIE
                || kind == ConsumableKind.OTHER;
    }

    /**
     * After loading from disk: real-time timers lose the offline gap, online ones keep their value.
     * Everything becomes an estimate, and remembers both predictions for the clock check.
     */
    public void restore(long now) {
        long gap = lastAdvancedAt == 0 ? 0 : Math.max(0, now - lastAdvancedAt);
        for (ConsumableTimer t : timers.values()) {
            ConsumableClock learned = learnedClocks.get(t.key);
            if (learned != null && t.clock != ConsumableClock.DEPENDENT) {
                t.clock = learned;
            }
            t.restoredUnconfirmed = true;
            t.restoredOnlineMs = 0;
            if (!t.known()) {
                continue;
            }
            t.predictedOnlineMs = t.remainingMs;
            t.predictedRealMs = Math.max(0, t.remainingMs - gap);
            t.offlineGapMs = gap;
            t.clockCheckPending = gap > 0 && t.clock != ConsumableClock.DEPENDENT;
            if (t.clock == ConsumableClock.REAL_TIME) {
                t.remainingMs = t.predictedRealMs;
            }
            t.confidence = Confidence.ESTIMATED;
        }
        lastAdvancedAt = now;
    }

    // ------------------------------------------------------------------ sources

    /**
     * A server-stated remaining time for {@code name}. Creates the timer when it is missing.
     *
     * @return the clock check this read settled, or {@code null}
     */
    public ClockCheck serverRead(String name, ConsumableKind kind, long ms, long precisionMs,
                                 Source source, long now) {
        if (ms < 0) {
            return null;
        }
        ConsumableTimer t = find(name);
        if (t == null) {
            t = create(name, kind);
            set(t, ms, precisionMs, source, Confidence.SERVER, now);
            return null;
        }
        if (t.confidence == Confidence.SERVER && t.known() && precisionMs > t.precisionMs
                && t.remainingMs >= ms - precisionMs / 2 && t.remainingMs <= ms + precisionMs) {
            t.syncedAt = now;   // consistent with a more precise value we already have
            return null;
        }
        ClockCheck check = clockCheck(t, ms, precisionMs);
        boolean wasRestored = t.restoredUnconfirmed;
        if (t.known() && t.remainingMs - ms > SILENT_CORRECTION_MS) {
            t.skipAtOrAbove = Math.min(t.skipAtOrAbove, ms);
        } else if (!wasRestored && t.known() && ms - t.remainingMs > Math.max(REFILL_JUMP_MS, 2 * precisionMs)) {
            rearm(t, ms);   // drunk again without a chat line we recognised
        }
        set(t, ms, precisionMs, source, Confidence.SERVER, now);
        t.restoredUnconfirmed = false;
        return check;
    }

    private ClockCheck clockCheck(ConsumableTimer t, long serverMs, long precisionMs) {
        if (!t.clockCheckPending || !t.known()) {
            return null;
        }
        t.clockCheckPending = false;
        if (t.offlineGapMs <= 2 * precisionMs + 120_000L) {
            return null;   // the gap is too small for this read to tell the two clocks apart
        }
        long own = t.clock == ConsumableClock.REAL_TIME ? t.predictedRealMs : t.predictedOnlineMs;
        long counted = Math.max(0, own - t.remainingMs);   // what it has counted since the restore
        long online = Math.max(0, t.predictedOnlineMs - counted);
        long real = Math.max(0, t.predictedRealMs - counted);
        long errOnline = Math.abs(serverMs - online);
        long errReal = Math.abs(serverMs - real);
        long tolerance = 2 * precisionMs + 60_000L;
        ConsumableClock matched = null;
        if (Math.min(errOnline, errReal) <= tolerance) {
            matched = errOnline <= errReal ? ConsumableClock.ONLINE_ONLY : ConsumableClock.REAL_TIME;
        }
        boolean switched = matched != null && matched != t.clock;
        if (switched) {
            t.clock = matched;
            learnedClocks.put(t.key, matched);
        }
        return new ClockCheck(t.key, serverMs, online, real, matched, switched);
    }

    /**
     * A consume line. A stacking buff (God Potion, mixin, cookie) that is already running gains the
     * duration - "the duration of god potions can be stacked!", the lore says - and becomes an
     * estimate until the next server read; anything else starts from the stated duration.
     */
    public void consume(ConsumableLines.Consumed c, long now) {
        ConsumableTimer t = find(c.name());
        if (t == null) {
            t = create(c.name(), c.kind());
        }
        long next;
        Confidence confidence;
        long precision;
        if (c.stacks() && t.known() && c.durationMs() >= 0) {
            next = t.remainingMs + c.durationMs();
            confidence = Confidence.ESTIMATED;
            precision = Math.max(t.precisionMs, c.precisionMs());
        } else {
            next = c.durationMs();
            confidence = c.stated() ? Confidence.SERVER : Confidence.ESTIMATED;
            precision = c.precisionMs();
        }
        if (c.detail() != null) {
            t.detail = c.detail();
        }
        rearm(t, next);
        set(t, next, precision, Source.CHAT, confidence, now);
        t.restoredUnconfirmed = false;
    }

    /**
     * A potion effect was gained ("BUFF! You have gained Speed III!"). The line has no duration;
     * {@code estimateMs} is the used item's lore figure, or {@link ConsumableTimer#UNKNOWN}.
     */
    public void effectGained(String name, long estimateMs, long precisionMs, long now) {
        ConsumableTimer t = find(name);
        if (t == null) {
            t = create(name, ConsumableKind.POTION);
        } else {
            t.level = ConsumableTimer.splitLevel(name)[1];
        }
        long next = estimateMs >= 0 ? Math.max(estimateMs, t.remainingMs) : t.remainingMs;
        rearm(t, next);
        set(t, next, estimateMs >= 0 ? precisionMs : t.precisionMs,
                estimateMs >= 0 ? Source.LORE_ESTIMATE : Source.CHAT, Confidence.ESTIMATED, now);
        t.restoredUnconfirmed = false;
    }

    /** The server says {@code name} ran out. Returns the alert, or {@code null} when it was not tracked. */
    public Ended expired(String name) {
        ConsumableTimer t = find(name);
        return t == null ? null : end(t, true);
    }

    /**
     * Applies one tab footer reading. The cookie is read everywhere; potions and the God Potion only
     * outside dungeons, where their effects are stored rather than shown.
     */
    public List<Event> applyFooter(EffectsFooter.Reading r, Context ctx, long now, List<ClockCheck> checks) {
        List<Event> events = new ArrayList<>();
        if (r.cookie() != null) {
            add(checks, serverRead(ConsumableKind.COOKIE_NAME, ConsumableKind.BOOSTER_COOKIE,
                    r.cookie().millis(), r.cookie().precisionMs(), Source.TAB, now));
        } else if (r.cookieInactive()) {
            add(events, endKey(ConsumableKind.COOKIE_KEY));
        }
        if (ctx.inDungeon() || !r.blockSeen()) {
            return events;
        }
        if (r.godPotion() != null) {
            add(checks, serverRead("God Potion", ConsumableKind.GOD_POTION, r.godPotion().millis(),
                    r.godPotion().precisionMs(), Source.TAB, now));
        } else if (r.godPotionAbsent()) {
            add(events, endKey(ConsumableKind.GOD_POTION_KEY));
        }
        if (r.noEffects()) {
            for (ConsumableTimer t : new ArrayList<>(timers.values())) {
                if (t.kind == ConsumableKind.POTION) {
                    add(events, end(t, false));
                }
            }
        }
        for (EffectsFooter.Effect e : r.effects()) {
            if (e.remainingMs() > 0) {
                add(checks, serverRead(e.name(), ConsumableKind.POTION, e.remainingMs(), e.precisionMs(),
                        Source.TAB, now));
            }
        }
        return events;
    }

    /** Applies the Active Effects menu; a complete listing ends every potion it does not show. */
    public List<Event> applyMenu(EffectsMenu.Reading r, long now, List<ClockCheck> checks) {
        List<Event> events = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (EffectsMenu.Effect e : r.effects()) {
            ConsumableKind kind = ConsumableKind.ofName(e.name());
            add(checks, serverRead(e.name(), kind == ConsumableKind.OTHER ? ConsumableKind.POTION : kind,
                    e.remainingMs(), e.precisionMs(), Source.MENU, now));
            ConsumableTimer t = find(e.name());
            if (t != null) {
                listed.add(t.key);
            }
        }
        if (r.complete()) {
            for (ConsumableTimer t : new ArrayList<>(timers.values())) {
                if (t.kind == ConsumableKind.POTION && !listed.contains(t.key)) {
                    add(events, end(t, false));
                }
            }
        }
        return events;
    }

    // ------------------------------------------------------------------ queries

    /** Soonest end first, unknown times last. */
    public List<ConsumableTimer> sorted() {
        List<ConsumableTimer> out = new ArrayList<>(timers.values());
        out.sort(Comparator.comparingLong((ConsumableTimer t) -> t.known() ? t.remainingMs : Long.MAX_VALUE)
                .thenComparing(t -> t.key));
        return out;
    }

    /**
     * The timer {@code name} refers to: by key, else by a key that ends in it or that it ends in -
     * the truffle is consumed as "Refined Dark Cacao Truffle" and expires as "Dark Cacao Truffle".
     */
    public ConsumableTimer find(String name) {
        String key = ConsumableTimer.keyOf(name);
        if (key.isEmpty()) {
            return null;
        }
        ConsumableTimer exact = timers.get(key);
        if (exact != null) {
            return exact;
        }
        for (ConsumableTimer t : timers.values()) {
            if (t.key.endsWith("_" + key) || key.endsWith("_" + t.key)) {
                return t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ internals

    private ConsumableTimer create(String name, ConsumableKind kind) {
        ConsumableTimer t = new ConsumableTimer(name, kind);
        ConsumableClock learned = learnedClocks.get(t.key);
        if (learned != null && t.clock != ConsumableClock.DEPENDENT) {
            t.clock = learned;
        }
        timers.put(t.key, t);
        return t;
    }

    private static void set(ConsumableTimer t, long ms, long precisionMs, Source source,
                            Confidence confidence, long now) {
        t.remainingMs = ms;
        t.precisionMs = Math.max(1_000L, precisionMs);
        t.source = source;
        t.confidence = confidence;
        t.syncedAt = now;
        t.zeroAt = 0;
    }

    /** A refill: every threshold that is ahead of {@code nextMs} may fire again. */
    private static void rearm(ConsumableTimer t, long nextMs) {
        t.skipAtOrAbove = Long.MAX_VALUE;
        t.expiredAlerted = false;
        if (nextMs >= 0) {
            t.firedThresholds.removeIf(threshold -> threshold < nextMs);
        }
    }

    private Ended endKey(String key) {
        ConsumableTimer t = timers.get(key);
        return t == null ? null : end(t, false);
    }

    /**
     * Removes {@code t} and returns its alert - unless that already went out, or the timer was a
     * restored value nothing had confirmed and {@code announced} is false. The server's own "has
     * expired!" line is announced; a footer that no longer lists a stale timer is not.
     */
    private Ended end(ConsumableTimer t, boolean announced) {
        timers.remove(t.key);
        if (t.expiredAlerted || (t.restoredUnconfirmed && !announced)) {
            return null;
        }
        t.expiredAlerted = true;
        return new Ended(t.label(), t.kind, false);
    }

    private static <T> void add(List<T> list, T value) {
        if (value != null) {
            list.add(value);
        }
    }
}
