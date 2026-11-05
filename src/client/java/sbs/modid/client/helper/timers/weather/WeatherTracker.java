/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers.weather;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.scoreboard.ScoreboardSnapshot;
import sbs.modid.client.core.scoreboard.ScoreboardSnapshots;
import sbs.modid.client.helper.timers.EventTimers;
import sbs.modid.client.helper.timers.TimerEntry;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The Weather row of the event timers card: the next weather on the island you are on (and the next
 * extreme one), or the running one with its time left. Driven once a second from
 * {@link EventTimers}' scan; everything time-shaped comes from {@link WeatherSchedule}.
 *
 * <p>It also carries the <b>probe</b> that pins down what Hypixel has not published: between XX:33
 * and XX:57 UTC every sidebar line that appears is logged with the island and the UTC time, and so
 * is every chat line naming a weather. It reads the shared {@link ScoreboardSnapshot} and only diffs
 * when its revision moves, so it adds no sidebar reads.
 */
public final class WeatherTracker {

    private static final WeatherTracker INSTANCE = new WeatherTracker();

    private static final long ALERT_LEAD_SECONDS = 60L;

    /** The weather the sidebar named this hour, or {@code null}; overrides the schedule. */
    private String liveWeather;
    private long liveHour = Long.MIN_VALUE;

    /** What the row was built from last, so a change can bump the card's data revision. */
    private String lastArea = "";
    private String lastLive;

    private int probeRevision = -1;
    private Set<String> probeLines = Set.of();

    private long alertedStart = Long.MIN_VALUE;

    private WeatherTracker() {
    }

    public static WeatherTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.TimersSettings cfg() {
        return ConfigManager.getInstance().get().timers;
    }

    /**
     * The island the row is about: the one you are on if it has weather, else the favourite (when
     * one is set), else {@code null}.
     */
    private static WeatherData.Island shownIsland() {
        WeatherData.Island here = WeatherData.forLocation(SkyBlockLocation.zone(), SkyBlockLocation.island());
        return here != null ? here : WeatherData.find(cfg().weatherFavouriteIsland);
    }

    /** Called once a second by the event timers scan. Returns true when the row's inputs changed. */
    public boolean tick(long nowMs) {
        if (!cfg().showWeather) {
            return false;
        }
        long now = Math.floorDiv(nowMs, 1_000L);
        WeatherData.Island here = WeatherData.forLocation(SkyBlockLocation.zone(), SkyBlockLocation.island());
        probe(now, here);
        detect(now, here);
        alert(now);

        WeatherData.Island shown = shownIsland();
        String area = shown == null ? "" : shown.area();
        String live = liveWeather;
        boolean changed = !area.equals(lastArea) || !Objects.equals(live, lastLive);
        lastArea = area;
        lastLive = live;
        return changed;
    }

    // ------------------------------------------------------------------ probe

    private static boolean inProbeWindow(long now) {
        long minute = Math.floorMod(now, 3_600L) / 60L;
        return minute >= 33 && minute < 57;
    }

    private void probe(long now, WeatherData.Island here) {
        ScoreboardSnapshot snapshot = ScoreboardSnapshots.current();
        if (snapshot.revision() == probeRevision) {
            return;
        }
        probeRevision = snapshot.revision();
        Set<String> lines = new HashSet<>(snapshot.texts());
        if (inProbeWindow(now)) {
            for (String line : snapshot.texts()) {
                if (!probeLines.contains(line) && !line.isBlank()) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Weather] utc={} island={} sidebar+='{}'",
                            utc(now), describe(here), line);
                }
            }
        }
        probeLines = lines;
    }

    /** Every chat line that names a weather or says "weather" - wired from {@code ChatListeners}. */
    public void onChat(String raw) {
        if (!cfg().showWeather || !WeatherData.namesWeather(raw)) {
            return;
        }
        long now = System.currentTimeMillis() / 1_000L;
        WeatherData.Island here = WeatherData.forLocation(SkyBlockLocation.zone(), SkyBlockLocation.island());
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Weather] utc={} island={} chat='{}'", utc(now),
                describe(here), raw);
    }

    private static String describe(WeatherData.Island here) {
        return here != null ? here.area() : SkyBlockLocation.island() + "/" + SkyBlockLocation.zone();
    }

    private static String utc(long now) {
        long inDay = Math.floorMod(now, 86_400L);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", inDay / 3_600, (inDay / 60) % 60, inDay % 60);
    }

    // ------------------------------------------------------------------ detection

    /**
     * The sidebar flashes the running weather. Its exact wording is not captured yet, so any line
     * naming this island's mild or extreme weather as a whole word counts - only while the schedule
     * says one is running, which keeps "Rain" in some unrelated line from setting it outside the hour.
     * Seeing the extreme one pins the 3-hour phase.
     */
    private void detect(long now, WeatherData.Island here) {
        long hour = WeatherSchedule.hourIndex(now);
        if (liveHour != hour || !WeatherSchedule.active(now)) {
            liveWeather = null;
        }
        if (here == null || !WeatherSchedule.active(now)) {
            return;
        }
        for (String line : ScoreboardSnapshots.current().texts()) {
            String lower = line.toLowerCase(Locale.ROOT);
            // Extreme first: "Thunderstorm" lines must not be read as a mild "Rain" on the same row.
            if (WeatherData.containsWord(lower, here.extreme())) {
                liveWeather = here.extreme();
                liveHour = hour;
                pinPhase(now, here);
                return;
            }
            if (WeatherData.containsWord(lower, here.mild())) {
                liveWeather = here.mild();
                liveHour = hour;
                return;
            }
        }
    }

    private static void pinPhase(long now, WeatherData.Island here) {
        int phase = WeatherSchedule.phaseOf(now);
        if (cfg().weatherExtremePhase == phase) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Weather] extreme phase {} from {} at utc={}", phase,
                here.extreme(), utc(now));
        cfg().weatherExtremePhase = phase;
        ConfigManager.getInstance().save();
    }

    // ------------------------------------------------------------------ alert

    private void alert(long now) {
        var cfg = cfg();
        if (!cfg.weatherAlert) {
            return;
        }
        WeatherData.Island island = shownIsland();
        if (island == null || cfg.weatherAlertHidden.contains(island.area())) {
            return;
        }
        long start = WeatherSchedule.nextStart(now);
        long left = start - now;
        if (left > ALERT_LEAD_SECONDS || left <= 0 || alertedStart == start) {
            return;
        }
        alertedStart = start;
        String name = upcomingName(island, start);
        String text = island.area() + ": " + name + " in " + EventTimers.shortDuration(left);
        Alerts.send(Alerts.Alert.of(name + " in " + EventTimers.shortDuration(left), text),
                cfg.weatherAlertChannels);
    }

    // ------------------------------------------------------------------ row

    /** The card row, or {@code null} when no island with weather is in view. */
    public TimerEntry row() {
        var cfg = cfg();
        if (!cfg.showWeather) {
            return null;
        }
        WeatherData.Island island = shownIsland();
        if (island == null) {
            return null;
        }
        long now = System.currentTimeMillis() / 1_000L;
        int phase = cfg.weatherExtremePhase;
        boolean here = island == WeatherData.forLocation(SkyBlockLocation.zone(), SkyBlockLocation.island());
        String label = here ? "Weather" : "Weather (" + island.area() + ")";

        if (WeatherSchedule.active(now)) {
            String live = here ? liveWeather : null;
            boolean extreme = WeatherSchedule.extremeHour(WeatherSchedule.hourIndex(now), phase);
            String name = live != null ? live
                    : phase >= 0 ? island.weather(extreme) : island.mild() + "/" + island.extreme();
            String value = name + " - " + EventTimers.shortDuration(WeatherSchedule.currentEnd(now) - now)
                    + " left" + stats(island, name.equals(island.extreme()));
            TimerEntry.Certainty certainty = live != null ? TimerEntry.Certainty.MEASURED
                    : phase >= 0 ? TimerEntry.Certainty.LEARNED : TimerEntry.Certainty.DERIVED;
            return new TimerEntry(label, value, certainty);
        }

        long start = WeatherSchedule.nextStart(now);
        String value = upcomingName(island, start) + " in " + EventTimers.shortDuration(start - now);
        long extremeAt = WeatherSchedule.nextExtremeStart(now, phase);
        if (extremeAt < 0) {
            value += " · extreme every 3rd, phase unknown";
        } else if (extremeAt != start) {
            value += " · " + island.extreme() + " in " + EventTimers.shortDuration(extremeAt - now);
        }
        return new TimerEntry(label, value, phase >= 0 ? TimerEntry.Certainty.LEARNED
                : TimerEntry.Certainty.DERIVED);
    }

    private static String upcomingName(WeatherData.Island island, long start) {
        int phase = cfg().weatherExtremePhase;
        if (phase < 0) {
            return island.mild() + "/" + island.extreme();
        }
        return island.weather(WeatherSchedule.extremeHour(WeatherSchedule.hourIndex(start), phase));
    }

    private static String stats(WeatherData.Island island, boolean extreme) {
        if (!cfg().weatherShowStats) {
            return "";
        }
        String text = island.stats();
        if (extreme && !island.bonus().isEmpty()) {
            text = text.isEmpty() ? island.bonus() : text + "; " + island.bonus();
        }
        return text.isEmpty() ? "" : " (" + text + ")";
    }

}
