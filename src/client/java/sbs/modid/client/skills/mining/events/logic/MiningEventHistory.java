/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.logic;

import sbs.modid.client.skills.mining.events.model.EventObservation;
import sbs.modid.client.skills.mining.events.model.MiningEvent;
import sbs.modid.client.skills.mining.events.model.NextEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Every event this client watched, and what they support saying - durations, the cycle, the next
 * start. Pure: no game, no files, the clock passed in.
 *
 * <p><b>Nothing here is a game constant.</b> The play-instance logs show one event starting every
 * ~20 minutes per lobby and fixed durations per event (see the spec), but none of those numbers is
 * written down here: each figure is a statistic over this client's own observations, and below the
 * sample minimum the answer is "unknown", never a default.
 *
 * <p><b>The next start is anchored on the last start</b> where it can be. The start-to-start cycle
 * is the same after every event in the logs, while the end-to-start gap depends on which event ran,
 * so a gap median per island mixes several distributions. The gap is the fallback for a lobby whose
 * last event was only seen ending.
 *
 * <p><b>A pair only counts inside one stay.</b> Two observations make an interval only when they
 * share island, lobby and presence id and are consecutive - a relog in between could hide a whole
 * event, and that pair would read as a cycle twice as long.
 */
public final class MiningEventHistory {

    /** Intervals needed before a next-start window is shown. */
    public static final int MIN_INTERVALS = 5;

    /** Complete observations of one event needed before its duration is estimated. */
    public static final int MIN_DURATIONS = 3;

    /** How long an observation is kept. */
    public static final long RETENTION_MS = 14L * 24 * 60 * 60 * 1000;

    private final List<EventObservation> observations = new ArrayList<>();

    // ------------------------------------------------------------------ editing

    public List<EventObservation> all() {
        return List.copyOf(observations);
    }

    public int size() {
        return observations.size();
    }

    /** Replaces everything (a load); invalid entries are skipped. */
    public void replaceAll(Collection<EventObservation> loaded) {
        observations.clear();
        if (loaded != null) {
            for (EventObservation observation : loaded) {
                if (observation != null && observation.valid()) {
                    observations.add(observation);
                }
            }
        }
        observations.sort(Comparator.comparingLong(EventObservation::lastSeen));
    }

    public void add(EventObservation observation) {
        if (observation != null && observation.valid()) {
            observations.add(observation);
        }
    }

    /**
     * Records the end of the open observation of {@code event} in this stay. Returns {@code false}
     * when there is none (the start was not seen) - the caller then adds an end-only observation.
     */
    public boolean close(String lobby, long presence, MiningEvent event, long end, boolean exact) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            EventObservation open = observations.get(i);
            if (open.presence() == presence && Objects.equals(open.lobby(), lobby)) {
                if (open.end() == 0L && open.eventType() == event) {
                    observations.set(i, open.withEnd(end, exact));
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Upgrades the open observation of {@code event} in this stay to an exact start - the
     * {@code STARTED!} line arriving after the scoreboard already reported the event.
     */
    public boolean confirmStart(String lobby, long presence, MiningEvent event, long start) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            EventObservation open = observations.get(i);
            if (open.presence() == presence && Objects.equals(open.lobby(), lobby)) {
                if (open.end() == 0L && open.eventType() == event && !open.startExact()) {
                    observations.set(i, new EventObservation(open.island(), open.lobby(), open.event(),
                            open.rawName(), start, 0L, true, false, presence, open.source()));
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /** Drops observations older than {@link #RETENTION_MS}. Returns how many went. */
    public int prune(long now) {
        int before = observations.size();
        observations.removeIf(o -> now - o.lastSeen() > RETENTION_MS);
        return before - observations.size();
    }

    // ------------------------------------------------------------------ statistics

    /** Start-to-next-start intervals on {@code island}, sorted. */
    public List<Long> cycles(String island) {
        return pairs(island, true);
    }

    /** End-to-next-start gaps on {@code island}, sorted. */
    public List<Long> gaps(String island) {
        return pairs(island, false);
    }

    private List<Long> pairs(String island, boolean cycle) {
        List<EventObservation> sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparingLong(EventObservation::lastSeen));
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            EventObservation first = sorted.get(i);
            if (!Objects.equals(first.island(), island)) {
                continue;
            }
            EventObservation next = nextInStay(sorted, i);
            if (next == null || !next.startExact()) {
                continue;
            }
            long interval;
            if (cycle) {
                interval = first.startExact() ? next.start() - first.start() : -1L;
            } else {
                interval = first.endExact() ? next.start() - first.end() : -1L;
            }
            if (interval > 0) {
                out.add(interval);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static EventObservation nextInStay(List<EventObservation> sorted, int index) {
        EventObservation first = sorted.get(index);
        for (int j = index + 1; j < sorted.size(); j++) {
            EventObservation candidate = sorted.get(j);
            if (candidate.presence() == first.presence() && Objects.equals(candidate.lobby(), first.lobby())
                    && Objects.equals(candidate.island(), first.island())) {
                return candidate;
            }
        }
        return null;
    }

    /** Exact start-to-end durations of {@code event}, any island, sorted. */
    public List<Long> durations(MiningEvent event) {
        List<Long> out = new ArrayList<>();
        for (EventObservation o : observations) {
            if (o.eventType() == event && o.startExact() && o.endExact() && o.end() > o.start()) {
                out.add(o.end() - o.start());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * How long {@code event} runs, or {@code -1} below {@link #MIN_DURATIONS}. The median for an event
     * that runs its full time; the longest seen for one that can end early, so its estimate is an
     * upper bound ("up to") rather than a midpoint it often does not reach.
     */
    public long estimatedDurationMs(MiningEvent event) {
        if (event == null || event == MiningEvent.UNKNOWN) {
            return -1L;
        }
        List<Long> durations = durations(event);
        if (durations.size() < MIN_DURATIONS) {
            return -1L;
        }
        return event.endsEarly() ? durations.get(durations.size() - 1) : quantile(durations, 0.5);
    }

    /**
     * The next start in {@code lobby} on {@code island} from this history alone - {@code ESTIMATED} or
     * {@code UNKNOWN}, never {@code KNOWN} (only the game's announcement is that).
     *
     * <p>Anchored on the newest observation in that lobby: its exact start plus the cycle window, or
     * failing that its exact end plus the gap window. {@code UNKNOWN} when the newest observation has
     * neither, when the matching statistic has fewer than {@link #MIN_INTERVALS} samples, or once
     * twice the median has passed with nothing seen - an event may have run while nobody watched.
     */
    public NextEvent next(String island, String lobby, long now) {
        EventObservation latest = null;
        for (EventObservation o : observations) {
            if (Objects.equals(o.island(), island) && Objects.equals(o.lobby(), lobby)
                    && (latest == null || o.lastSeen() >= latest.lastSeen())) {
                latest = o;
            }
        }
        List<Long> cycles = cycles(island);
        List<Long> gaps = gaps(island);
        int samples = Math.max(cycles.size(), gaps.size());
        if (latest == null) {
            return NextEvent.unknown(samples);
        }
        long anchor;
        List<Long> window;
        if (latest.startExact() && cycles.size() >= MIN_INTERVALS) {
            anchor = latest.start();
            window = cycles;
        } else if (latest.endExact() && gaps.size() >= MIN_INTERVALS) {
            anchor = latest.end();
            window = gaps;
        } else {
            return NextEvent.unknown(samples);
        }
        long median = quantile(window, 0.5);
        if (now - anchor > 2 * median) {
            return NextEvent.unknown(window.size());
        }
        return new NextEvent(NextEvent.Confidence.ESTIMATED, anchor + quantile(window, 0.25),
                anchor + quantile(window, 0.75), null, null, window.size());
    }

    /** The newest observation on {@code island}, any lobby, or {@code null}. */
    public EventObservation latestOn(String island) {
        EventObservation latest = null;
        for (EventObservation o : observations) {
            if (Objects.equals(o.island(), island) && (latest == null || o.lastSeen() >= latest.lastSeen())) {
                latest = o;
            }
        }
        return latest;
    }

    /** Nearest-rank quantile of a sorted, non-empty list. */
    static long quantile(List<Long> sorted, double q) {
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
