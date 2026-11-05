/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.timers.weather.WeatherData;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Event Timers module (Quality of Life): the SkyBlock event clock as one HUD card - Dark Auction,
 * Jacob's Contest, the Crimson Isle volcano and the lobby's age. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class TimersModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public TimersModule() {
    }

    @Override
    public String id() {
        return "event_timers";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.TIMERS;
    }

    @Override
    public String displayName() {
        return "Event Timers";
    }

    @Override
    public String description() {
        return "Dark Auction, Jacob's Contest, volcano and lobby age on one HUD card";
    }

    @Override
    public int accentColor() {
        return 0xFF6FD8C0;
    }

    private static SBSConfig.TimersSettings cfg() {
        return ConfigManager.getInstance().get().timers;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new java.util.ArrayList<>(List.of(
                SettingRow.toggle("Event Timers", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A clock card in the top-right with the recurring events: time "
                                + "to the next Dark Auction, Jacob's Contest, the volcano, and "
                                + "how old your lobby is."),
                SettingRow.label("One card for the events worth watching the clock for"),

                SettingRow.toggle("Lobby Age", () -> cfg().showLobbyAge,
                        () -> { cfg().showLobbyAge = !cfg().showLobbyAge; save(); })
                        .describe("How long the server you are on has been up, in Minecraft days - "
                                + "the number F3 shows. Useful in Crystal Hollows, where old "
                                + "lobbies are mined out and a Day-0 lobby is fresh. Several "
                                + "lobbies in a row showing the same day is normal: Hypixel "
                                + "restarts SkyBlock servers in waves, so they share a boot time."),
                SettingRow.label("The server's own age - a fresh Crystal Hollows lobby is Day 0"),

                SettingRow.toggle("Dark Auction", () -> cfg().showDarkAuction,
                        () -> { cfg().showDarkAuction = !cfg().showDarkAuction; save(); })
                        .describe("Countdown to the next Dark Auction (one every real hour)."),
                SettingRow.toggle("Jacob's Contest", () -> cfg().showJacobContest,
                        () -> { cfg().showJacobContest = !cfg().showJacobContest; save(); })
                        .describe("Countdown to the next Jacob's Farming Contest. It needs to know "
                                + "which days contests fall on first: open your SkyBlock calendar "
                                + "once and it reads them off it, or leave it and it works the same "
                                + "out the next time a contest runs. Until then the row says "
                                + "'learning...' rather than guessing at a time."),
                SettingRow.label("Open your calendar once to calibrate it, or wait for a contest"),

                SettingRow.toggle("Volcano", () -> cfg().showVolcano,
                        () -> { cfg().showVolcano = !cfg().showVolcano; save(); })
                        .describe("The Crimson Isle volcano status, read from the tab widget. It "
                                + "erupts roughly every 2 to 2.5 hours."),
                SettingRow.label("The current eruption from the tab widget; erupts every 2-2.5h"),

                SettingRow.toggle("SkyBlock Date", () -> cfg().showSkyblockDate,
                        () -> { cfg().showSkyblockDate = !cfg().showSkyblockDate; save(); })
                        .describe("The current in-game SkyBlock date and season."),

                SettingRow.button("Forget Jacob Calibration",
                        () -> { cfg().jacobPhase = -1; save(); })
                        .describe("Throws away what the contest predictor has learned so it "
                                + "re-learns from your calendar or from the next contest. Use it if "
                                + "the Jacob countdown ever looks wrong."),
                SettingRow.label("Use if contest predictions ever look wrong"),

                SettingRow.label("— Event calendar —"),
                SettingRow.toggle("Event Calendar", () -> cfg().showCalendar,
                        () -> { cfg().showCalendar = !cfg().showCalendar; save(); })
                        .describe("Adds the rest of SkyBlock's events to the card - Hoppity's Hunt, "
                                + "Season of Jerry, the election, Traveling Zoo, Spooky Festival and "
                                + "more - each with how long until it starts, or ends while it runs "
                                + "(green running, yellow upcoming). When the tab list's event line "
                                + "names one, its own countdown is used; otherwise it is worked out "
                                + "from the calendar and marked with ~. Default: on."),
                SettingRow.intField("Calendar Rows", 1, 20, () -> cfg().calendarRows,
                        value -> { cfg().calendarRows = value; save(); }, "")
                        .describe("How many calendar events the card lists, soonest first. "
                                + "Default: 5."),
                SettingRow.toggle("Show Mayor Events", () -> cfg().calendarMayorEvents,
                        () -> { cfg().calendarMayorEvents = !cfg().calendarMayorEvents; save(); })
                        .describe("Events that only happen under a particular mayor (Fishing "
                                + "Festival with Marina, Mining Fiesta with Cole) are hidden while "
                                + "that mayor is not in office. On, they are listed as \"if <mayor> "
                                + "is elected\" instead. Default: off."),
                SettingRow.intField("Alert Before Start", 0, 120, () -> cfg().calendarAlertMinutes,
                        value -> { cfg().calendarAlertMinutes = value; save(); }, " min")
                        .describe("Tells you this many minutes before any calendar event you have "
                                + "switched on starts, once per event. 0 turns it off. Default: 0."),
                SettingRow.label("— Which events —")));
        for (sbs.modid.client.helper.timers.model.CalendarData.Event event
                : CalendarEvents.getInstance().events()) {
            String id = event.id;
            rows.add(SettingRow.toggle(event.name, () -> !cfg().calendarHidden.contains(id),
                            () -> {
                                if (!cfg().calendarHidden.remove(id)) {
                                    cfg().calendarHidden.add(id);
                                }
                                save();
                            })
                    .anchor("calendar_event_" + id)
                    .describe(event.name + " on the calendar. " + event.note
                            + (event.mayor == null ? "" : " Only while " + event.mayor.substring(0, 1)
                            .toUpperCase(java.util.Locale.ROOT) + event.mayor.substring(1) + " is in office.")
                            + " Default: shown."));
        }
        rows.add(SettingRow.label("— Island weather —"));
        rows.add(SettingRow.toggle("Weather", () -> cfg().showWeather,
                        () -> { cfg().showWeather = !cfg().showWeather; save(); })
                .describe("Countdown to the next weather on the island you are on (one every real hour, "
                        + "XX:35 to XX:55 UTC) and to the next extreme one (every third). While it runs "
                        + "the row says which weather and how long is left. Which hours are extreme is "
                        + "learned the first time the scoreboard shows an extreme weather. Default: on."));
        rows.add(SettingRow.toggle("Weather Stats", () -> cfg().weatherShowStats,
                        () -> { cfg().weatherShowStats = !cfg().weatherShowStats; save(); })
                .describe("Adds what the running weather gives - its stats and the extreme bonus. "
                        + "Default: on."));
        List<String> favourites = new java.util.ArrayList<>();
        favourites.add("None");
        for (WeatherData.Island island : WeatherData.islands()) {
            favourites.add(island.area());
        }
        rows.add(SettingRow.options("Favourite Island", () -> favourites,
                        () -> cfg().weatherFavouriteIsland.isEmpty() ? "None" : cfg().weatherFavouriteIsland,
                        picked -> {
                            cfg().weatherFavouriteIsland = "None".equals(picked) ? "" : picked;
                            save();
                        })
                .describe("Where you are has no weather: show this island's instead. None hides the "
                        + "row there. Default: None."));
        rows.add(SettingRow.toggle("Weather Alert", () -> cfg().weatherAlert,
                        () -> { cfg().weatherAlert = !cfg().weatherAlert; save(); })
                .describe("Tells you one minute before a weather starts on the shown island. "
                        + "Default: off."));
        for (WeatherData.Island island : WeatherData.islands()) {
            String area = island.area();
            rows.add(SettingRow.toggle("Alert: " + area, () -> !cfg().weatherAlertHidden.contains(area),
                            () -> {
                                if (!cfg().weatherAlertHidden.remove(area)) {
                                    cfg().weatherAlertHidden.add(area);
                                }
                                save();
                            })
                    .anchor("weather_alert_" + area.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", "_"))
                    .describe("Weather alert for " + area + " (" + island.mild() + " / "
                            + island.extreme() + "). Default: on."));
        }
        rows.add(SettingRow.button("Forget Weather Phase",
                        () -> { cfg().weatherExtremePhase = -1; save(); })
                .describe("Throws away which hours were learned to be extreme, so it re-learns from "
                        + "the next extreme weather."));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("weather_start",
                "an island weather is about to start", () -> cfg().weatherAlertChannels,
                value -> { cfg().weatherAlertChannels = value; save(); }));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("calendar_start",
                "a calendar event is about to start", () -> cfg().calendarAlertChannels,
                value -> { cfg().calendarAlertChannels = value; save(); }));
        return rows;
    }
}
