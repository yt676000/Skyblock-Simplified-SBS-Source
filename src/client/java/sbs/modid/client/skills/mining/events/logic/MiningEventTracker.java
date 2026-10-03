/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.logic;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.SBSConfig.MiningEventSettings;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.skills.mining.events.model.EventObservation;
import sbs.modid.client.skills.mining.events.model.MiningEvent;
import sbs.modid.client.skills.mining.events.model.NextEvent;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Mining Event Timer's glue: chat and tick hooks in, {@link LiveEventState} and
 * {@link MiningEventHistory} updated, alerts out, the history kept on disk.
 *
 * <p>Only runs in the Dwarven Mines and the Crystal Hollows. Chat is the primary source - its lines
 * are CONFIRMED - and the sidebar resyncs the countdown when it carries one (UNVERIFIED; see
 * {@link MiningEventScoreboard}). Until that shape is known, every distinct sidebar line in the two
 * islands and every event-shaped chat line is logged under {@code [SBS][MiningEvents]}.
 *
 * <p>Display only: nothing here sends a command, warps or changes lobby.
 */
public final class MiningEventTracker {

    private static final MiningEventTracker INSTANCE = new MiningEventTracker();

    public static final String DWARVEN_MINES = "Dwarven Mines";
    public static final String CRYSTAL_HOLLOWS = "Crystal Hollows";

    private static final long TICK_MS = 1_000L;
    private static final long SAVE_MS = 10_000L;
    /** How long the latest Powder Ghast stays on the card. It has no announced end. */
    public static final long GHAST_SHOWN_MS = 5L * 60_000L;
    private static final long ENDING_ALERT_MS = 60_000L;
    private static final long NEXT_ALERT_MS = 120_000L;
    /** A scoreboard start upgraded by a {@code STARTED!} line this soon after is the same event. */
    private static final long CONFIRM_WINDOW_MS = 30_000L;
    private static final int SIDEBAR_LOG_CAP = 300;

    /** Every chat line worth capturing while the formats are still being learned. */
    private static final Pattern CAPTURE = Pattern.compile(
            "(?i)powder|gone with the wind|better together|goblin raid|raffle|gourmand|ghast|event|started|ended|ends in");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static final Type LIST_TYPE = new TypeToken<List<EventObservation>>() {
    }.getType();

    private final LiveEventState live = new LiveEventState();
    private final MiningEventHistory history = new MiningEventHistory();
    private final MiningEventChat chat = new MiningEventChat();

    /** Id of the current continuous stay; a new one on every world change. */
    private long presence = System.currentTimeMillis();

    private String loadedAccount;
    private boolean dirty;
    private long lastTickAt;
    private long lastSaveAt;

    private final Set<String> loggedUnknown = new HashSet<>();
    private final Set<String> loggedSidebar = new HashSet<>();
    private boolean endingAlerted;
    private long nextAlertedFor;

    private MiningEventTracker() {
    }

    public static MiningEventTracker getInstance() {
        return INSTANCE;
    }

    private static MiningEventSettings cfg() {
        return ConfigManager.getInstance().get().miningEvents;
    }

    /** Whether {@code island} is one of the two islands these events run on. */
    public static boolean miningIsland(String island) {
        return DWARVEN_MINES.equalsIgnoreCase(island) || CRYSTAL_HOLLOWS.equalsIgnoreCase(island);
    }

    // ------------------------------------------------------------------ hooks

    /** Every chat line; the component is only read for its hover text when capturing. */
    public void onChat(String text, Component message) {
        MiningEventSettings cfg = cfg();
        if (!cfg.enabled || text == null) {
            return;
        }
        String island = SkyBlockLocation.island();
        if (!miningIsland(island)) {
            chat.reset();
            return;
        }
        long now = System.currentTimeMillis();
        String lobby = MiningLobby.current();
        if (cfg.captureLog) {
            String plain = MiningEventChat.plain(text);
            if (!plain.isEmpty() && CAPTURE.matcher(plain).find()) {
                String hover = hoverText(message);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningEvents] chat lobby={} island={} at={} raw=\"{}\"{}",
                        lobby.isEmpty() ? "?" : lobby, island, now, text,
                        hover.isEmpty() ? "" : " hover=\"" + hover + "\"");
            }
        }
        MiningEventChat.Signal signal = chat.accept(text);
        if (signal == null) {
            return;
        }
        live.enter(lobby, island, now);
        ensureLoaded();
        switch (signal.type()) {
            case ANNOUNCED -> {
                logUnknown(signal);
                live.announce(signal.event(), signal.rawName(), signal.seconds(), now);
            }
            case STARTED -> started(signal.event(), signal.rawName(), now, true, "chat");
            case ENDED -> ended(signal.event(), signal.rawName(), now, true);
            case GHAST -> {
                live.ghast(now);
                if (cfg.ghastAlert) {
                    Alerts.send(Alerts.Alert.of("Powder Ghast", "The Powder Ghast spawned in this lobby"),
                            cfg.alertChannels);
                }
            }
            case GHAST_ZONE -> live.ghastZone(signal.zone(), now);
        }
    }

    /** Every client tick; throttled to once a second. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastTickAt < TICK_MS) {
            return;
        }
        lastTickAt = now;
        MiningEventSettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        String island = SkyBlockLocation.island();
        if (!miningIsland(island)) {
            return;
        }
        ensureLoaded();
        String lobby = MiningLobby.current();
        if (live.enter(lobby, island, now)) {
            loggedSidebar.clear();
            endingAlerted = false;
        }
        List<String> sidebar = SkyBlockLocation.sidebarLines();
        if (cfg.captureLog) {
            captureSidebar(sidebar, lobby, island, now);
        }
        if (cfg.readScoreboard) {
            readScoreboard(sidebar, now);
        }
        MiningEvent running = live.event();
        if (live.expire(now, history.estimatedDurationMs(running))) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningEvents] dropped {} - no end line after its countdown ran out",
                    MiningEvent.label(running, null));
        }
        alerts(cfg, now);
        if (dirty && now - lastSaveAt >= SAVE_MS) {
            save(now);
        }
    }

    /** World change / server hop: a new stay, nothing of the old lobby's live state survives. */
    public void onWorldChange() {
        live.reset();
        chat.reset();
        presence = System.currentTimeMillis();
        loggedSidebar.clear();
        endingAlerted = false;
        if (dirty) {
            save(presence);
        }
    }

    // ------------------------------------------------------------------ events

    private void started(MiningEvent event, String raw, long now, boolean exact, String source) {
        String lobby = live.lobby();
        if (exact && live.event() == event && !live.startExact() && now - live.startedAt() < CONFIRM_WINDOW_MS
                && history.confirmStart(lobby, presence, event, now)) {
            live.confirmStart(now);
        } else {
            live.start(event, raw, now, exact);
            history.add(new EventObservation(live.island(), lobby, event.name(), raw, now, 0L, exact, false,
                    presence, source));
        }
        dirty = true;
        endingAlerted = false;
        logUnknown(new MiningEventChat.Signal(MiningEventChat.Type.STARTED, event, raw, -1, null));
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningEvents] started {} lobby={} island={} exact={} source={}",
                MiningEvent.label(event, raw), lobby, live.island(), exact, source);
        MiningEventSettings cfg = cfg();
        if (exact && event != MiningEvent.UNKNOWN && cfg.startAlerts.contains(event.name())) {
            Alerts.send(Alerts.Alert.of(event.displayName() + " started",
                    event.displayName() + " is running in this lobby"), cfg.alertChannels);
        }
    }

    private void ended(MiningEvent event, String raw, long now, boolean exact) {
        String lobby = live.lobby();
        if (!history.close(lobby, presence, event, now, exact)) {
            history.add(new EventObservation(live.island(), lobby, event.name(), raw, 0L, now, false, exact,
                    presence, "chat"));
        }
        dirty = true;
        live.end();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningEvents] ended {} lobby={} island={}",
                MiningEvent.label(event, raw), lobby, live.island());
    }

    private void readScoreboard(List<String> sidebar, long now) {
        MiningEventScoreboard.Reading reading = MiningEventScoreboard.read(sidebar);
        if (!reading.hasEvent()) {
            if (live.scoreboardSilent(now)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningEvents] the sidebar stopped naming the event - ended");
            }
            return;
        }
        // Starting an event from the sidebar needs a remaining time as well as a name: a name alone
        // in an unverified sidebar is too weak to put a record in the history.
        if (live.event() != reading.event() && reading.remainingSeconds() >= 0) {
            started(reading.event(), reading.event().displayName(), now, false, "scoreboard");
        }
        live.scoreboard(reading.event(), reading.remainingSeconds(), now);
    }

    private void alerts(MiningEventSettings cfg, long now) {
        MiningEvent running = live.event();
        if (running != null && running != MiningEvent.UNKNOWN && !endingAlerted
                && cfg.endingAlerts.contains(running.name())) {
            long remaining = live.remainingMs(now, history.estimatedDurationMs(running));
            if (remaining > 0 && remaining <= ENDING_ALERT_MS) {
                endingAlerted = true;
                Alerts.send(Alerts.Alert.of(running.displayName() + " ends in 1 minute",
                        running.displayName() + " ends in about a minute"
                                + (live.remainingSource(history.estimatedDurationMs(running))
                                == LiveEventState.RemainingSource.ESTIMATED ? " (estimated)" : "")),
                        cfg.alertChannels);
            }
        }
        if (cfg.nextAlert && running == null) {
            NextEvent next = nextEvent(now);
            if (next.confidence() == NextEvent.Confidence.ESTIMATED && next.earliest() > now
                    && next.earliest() - now <= NEXT_ALERT_MS && nextAlertedFor != next.earliest()) {
                nextAlertedFor = next.earliest();
                Alerts.send(Alerts.Alert.of("Mining event soon",
                        "The next mining event is estimated to start within about 2 minutes"), cfg.alertChannels);
            }
        }
    }

    // ------------------------------------------------------------------ output

    public LiveEventState live() {
        return live;
    }

    public MiningEventHistory history() {
        ensureLoaded();
        return history;
    }

    /** The history if it has been loaded, else {@code null} - for the settings page, which must not load it. */
    public MiningEventHistory historyIfLoaded() {
        return loadedAccount == null ? null : history;
    }

    /** The next start: the game's announcement if there is one, else the local estimate. */
    public NextEvent nextEvent(long now) {
        if (live.announced() != null && now <= live.announcedStartAt() + LiveEventState.ANNOUNCE_GRACE_MS) {
            return new NextEvent(NextEvent.Confidence.KNOWN, live.announcedStartAt(), live.announcedStartAt(),
                    live.announced(), live.announcedRaw(), 0);
        }
        if (live.lobby().isEmpty()) {
            return NextEvent.unknown(0);
        }
        ensureLoaded();
        return history.next(live.island(), live.lobby(), now);
    }

    // ------------------------------------------------------------------ capture

    private void captureSidebar(List<String> sidebar, String lobby, String island, long now) {
        for (String line : sidebar) {
            String text = line == null ? "" : line.trim();
            if (text.isEmpty() || loggedSidebar.size() >= SIDEBAR_LOG_CAP) {
                continue;
            }
            // Digits normalised for the dedupe key only, so a ticking countdown is one line, not 1200.
            if (loggedSidebar.add(DIGITS.matcher(text).replaceAll("#"))) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningEvents] scoreboard lobby={} island={} at={} line=\"{}\"",
                        lobby.isEmpty() ? "?" : lobby, island, now, line);
            }
        }
    }

    private void logUnknown(MiningEventChat.Signal signal) {
        if (signal.event() == MiningEvent.UNKNOWN && loggedUnknown.add(signal.rawName())) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningEvents] unknown event name \"{}\" ({}) - shown by its raw name",
                    signal.rawName(), signal.type());
        }
    }

    private static String hoverText(Component message) {
        if (message == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        collectHover(message, out);
        return out.toString().replace('\n', ' ').trim();
    }

    private static void collectHover(Component component, StringBuilder out) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component text)) {
            out.append(text.getString()).append('\n');
        }
        for (Component sibling : component.getSiblings()) {
            collectHover(sibling, out);
        }
    }

    // ------------------------------------------------------------------ persistence

    /** Loads the account's history on first use and again whenever the account changes. */
    private void ensureLoaded() {
        String account = ProfileContext.getInstance().account();
        if (account == null || account.equals(loadedAccount)) {
            return;
        }
        if (dirty && loadedAccount != null) {
            save(System.currentTimeMillis());
        }
        loadedAccount = account;
        history.replaceAll(List.of());
        Path file = SBSFiles.miningEventsFile(account);
        try {
            if (Files.exists(file)) {
                List<EventObservation> stored = SBSFiles.GSON.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
                history.replaceAll(stored);
            }
            int pruned = history.prune(System.currentTimeMillis());
            dirty = pruned > 0;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningEvents] loaded {} observation(s), pruned {}",
                    history.size(), pruned);
        } catch (IOException | JsonParseException e) {
            // A corrupt history is the expected data-loading case: start empty and say so.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][MiningEvents] history unreadable, starting empty: {}",
                    e.toString());
        }
    }

    private void save(long now) {
        if (loadedAccount == null) {
            return;
        }
        history.prune(now);
        dirty = false;
        lastSaveAt = now;
        String json = SBSFiles.GSON.toJson(history.all(), LIST_TYPE);
        Path file = SBSFiles.miningEventsFile(loadedAccount);
        SbsExecutors.io().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][MiningEvents] could not write the event history", e);
            }
        });
    }
}
