/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.dev.RoomMapReader;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.model.DungeonBlessings;
import sbs.modid.client.dungeons.run.model.DungeonState;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single source of truth for run-wide dungeon state: which floor, which phase, the local player's
 * class and team, the live counters (secrets, crypts, deaths, cleared/total rooms, elapsed time) and
 * the blessings collected.
 * Every dungeon feature reads it instead of re-parsing the sidebar / tab list itself.
 *
 * <p><b>What is reliable vs best-effort.</b> The floor marker, the class/team ({@link DungeonTeamClasses})
 * and the cleared/total room counts (from the map snapshot) are read from stable sources. The numeric
 * counters (secrets / crypts / deaths) are scraped from the tab list, whose exact line wording is not
 * verifiable outside a live run - those are parsed with tolerant patterns and logged once per change
 * under {@code [SBS][DungeonState]} so they can be tuned in-game. Consumers must treat a counter of
 * {@code -1} as "unknown yet".
 *
 * <p><b>Blessings are the exception: chat, not the tab list,</b> because the tab list has no blessings
 * section (see {@link DungeonBlessings}). They are summed from the buff lines and are only a whole
 * run's count when the run's start was seen - {@link DungeonBlessings#complete()}.
 *
 * <p>Ticked from {@code GuiTrackingMixin}; the heavy tab scan is throttled. Cleared on leaving The
 * Catacombs. Fires {@link DungeonEvents#firePhaseChange} on transitions.
 */
public final class DungeonStateManager {

    private static final DungeonStateManager INSTANCE = new DungeonStateManager();

    private static final long TAB_SCAN_MS = 500;

    /**
     * How long the world room probe's answer is reused. It only runs while slot 9 is holding
     * something other than a map, which outside the boss room is momentary, so a second of lag on
     * the way in or out of the boss costs nothing and a per-tick void scan would cost plenty.
     */
    private static final long ROOM_PROBE_MS = 1_000;

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    /** "The Catacombs (F6)" / "(M7)" / "(E)". */
    private static final Pattern FLOOR = Pattern.compile("The Catacombs \\(([EFM])(\\d?)\\)");
    // Verified against a live F7 tab/scoreboard (2026-07-23). The tab shows BOTH "Secrets Found: 33"
    // (absolute, Player Stats) and "Secrets Found: 75%" (completion, Dungeon Stats); the lookaheads
    // keep SECRETS on the absolute one (no trailing % and no digit left over) and SECRET_PCT on the %.
    private static final Pattern SECRETS = Pattern.compile("(?i)Secrets?\\s*Found:?\\s*(\\d+)(?!\\d)(?!\\s*%)");
    private static final Pattern SECRET_PCT = Pattern.compile("(?i)Secrets?\\s*Found:?\\s*(\\d+)\\s*%");
    private static final Pattern CRYPTS = Pattern.compile("(?i)Crypts:?\\s*(\\d+)");
    private static final Pattern DEATHS = Pattern.compile("(?i)(?:Team\\s*)?Deaths:?\\s*(\\d+)");
    /** Sidebar "Cleared: 67% (163)" - the room-completion percent the score's room components use. */
    private static final Pattern CLEARED_PCT = Pattern.compile("(?i)Cleared:?\\s*(\\d+)\\s*%");

    private boolean inDungeon;
    private char floorType;      // 'E' / 'F' / 'M', 0 = unknown
    private int floorNumber;     // 0 for the Entrance / unknown
    private DungeonEvents.Phase phase = DungeonEvents.Phase.START;

    private int secretsFound = -1;
    private int secretsTotal = -1;
    private int secretPct = -1;
    private int roomClearPct = -1;
    private int crypts = -1;
    private int deaths = -1;
    private int clearedRooms = -1;
    private int totalRooms = -1;

    private long runStartMs;

    /** Summed per type from the buff lines; cleared on leaving and on the next run's start line. */
    private final DungeonBlessings blessings = new DungeonBlessings();
    private long lastTabScan;
    private String lastLoggedStats = "";

    /** The second boss signal's cached answer, and when it was taken - see {@link #standingInARoom}. */
    private long lastRoomProbeAt;
    private boolean lastRoomProbe;

    private DungeonStateManager() {
        // Registered here rather than in a listener of their own: the tally is run state, and the
        // registry is the one place dungeon chat is hooked. Neither is gated on inDungeon - both
        // lines only exist inside a run, and the gate flips a tick late on the way in.
        ChatPatternRegistry registry = ChatPatternRegistry.getInstance();
        registry.register(DungeonBlessings.RUN_START, (matcher, raw) -> blessings.startRun(),
                "dungeon state: run start");
        registry.register(DungeonBlessings.BUFF, (matcher, raw) -> onBlessing(DungeonBlessings.parse(matcher)),
                "dungeon state: blessing");
    }

    public static DungeonStateManager getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Accessors (read by every dungeon feature)
    // ------------------------------------------------------------------

    public boolean inDungeon() {
        return inDungeon;
    }

    /** 'E' / 'F' / 'M', or 0 when unknown. */
    public char floorType() {
        return floorType;
    }

    /** Floor number (0 for the Entrance / unknown). */
    public int floorNumber() {
        return floorNumber;
    }

    /** "F6" / "M7" / "E" / "?". */
    public String floorLabel() {
        if (floorType == 0) {
            return "?";
        }
        return floorType == 'E' ? "E" : (floorType + String.valueOf(floorNumber));
    }

    public DungeonEvents.Phase phase() {
        return phase;
    }

    /** The local player's class initial ('A','B','T','H','M') or 0. */
    public char ownClass() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? 0 : DungeonTeamClasses.classOf(minecraft.player.getGameProfile().name());
    }

    public int secretsFound() {
        return secretsFound;
    }

    /** Total secrets when the tab shows "found/total", else -1 (the game does not always give it). */
    public int secretsTotal() {
        return secretsTotal;
    }

    /** Secret completion percent from the tab ("Secrets Found: 75%"), or -1. Drives the Explore score. */
    public int secretPct() {
        return secretPct;
    }

    /**
     * Room completion percent: the sidebar's "Cleared: X%" when present (authoritative), else derived
     * from the map's cleared/total tile counts, else -1. Drives the Skill + Explore room components.
     */
    public int roomClearPct() {
        if (roomClearPct >= 0) {
            return roomClearPct;
        }
        if (totalRooms > 0 && clearedRooms >= 0) {
            return (int) Math.round(100.0 * clearedRooms / totalRooms);
        }
        return -1;
    }

    public int crypts() {
        return crypts;
    }

    public int deaths() {
        return deaths;
    }

    public int clearedRooms() {
        return clearedRooms;
    }

    public int totalRooms() {
        return totalRooms;
    }

    /** The run's blessings. Read {@link DungeonBlessings#display} rather than the raw levels. */
    public DungeonBlessings blessings() {
        return blessings;
    }

    /** Seconds since the run was first seen active (0 before it starts). */
    public int elapsedSeconds() {
        return runStartMs == 0 ? 0 : (int) ((System.currentTimeMillis() - runStartMs) / 1000);
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /** Called once per client tick from the tracking mixin. */
    public void tick(Minecraft minecraft) {
        boolean nowIn = DungeonScoreboard.isInCatacombs();
        if (nowIn != inDungeon) {
            inDungeon = nowIn;
            if (!nowIn) {
                reset();
                return;
            }
        }
        if (!inDungeon || minecraft.player == null) {
            return;
        }
        updateFloor();
        updatePhase();
        long now = System.currentTimeMillis();
        if (now - lastTabScan >= TAB_SCAN_MS) {
            lastTabScan = now;
            scanTab();
            updateRoomCounts();
        }
    }

    private void updateFloor() {
        int clearedPct = -1;
        for (String line : DungeonScoreboard.sidebarLines()) {
            Matcher floor = FLOOR.matcher(line);
            if (floor.find()) {
                floorType = floor.group(1).charAt(0);
                floorNumber = floor.group(2).isEmpty() ? 0 : Integer.parseInt(floor.group(2));
            }
            Matcher cleared = CLEARED_PCT.matcher(line);
            if (cleared.find()) {
                clearedPct = Integer.parseInt(cleared.group(1));
            }
        }
        roomClearPct = clearedPct;
    }

    /**
     * Phase inference. BOSS needs <b>two independent signals</b>, both true:
     *
     * <ol>
     *   <li><b>No dungeon map in hotbar slot 9.</b> Hypixel does not empty the slot in the boss
     *       room - it puts something else there (a Nether Star, the Spirit Bow, the F7/M7 kit), so
     *       the test is "the item in slot 9 is not a map", never "slot 9 is empty".</li>
     *   <li><b>The ground the player is standing on is not a legal dungeon room</b>, asked of the
     *       world blocks alone - see {@link #standingInARoom}.</li>
     * </ol>
     *
     * <p><b>Why two.</b> Signal 1 on its own answers "the map is not in slot 9", which is not the
     * same question: a player who moves the map elsewhere in their inventory, or who dies and is
     * relieved of it, reads as being in the boss for the rest of the run. Signal 2 is independent
     * of the map item - the boss room is a huge off-grid build that no room-shaped footprint fits,
     * while a player standing in a normal room is inside one whatever their hotbar holds. Neither
     * signal alone is trusted; both together have no known false positive.
     *
     * <p>The room tracker's own lock is deliberately <i>not</i> used as signal 2: it unlocks when
     * the map leaves slot 9, so it would agree with signal 1 by construction rather than
     * corroborating it.
     *
     * <p><b>Both signals arm it; one disarms it.</b> Once BOSS is entered it is held until the map
     * is back in slot 9, which inside a run means never - you cannot walk back onto the room grid.
     * Without that, the probe would have to keep being right for the whole fight, and parts of a
     * boss build are enclosed enough to pass for a room (F7's tower floors are the obvious case) -
     * the map would come back mid-Necron. Holding it also gives the loot room the right answer for
     * free, and the state is cleared on leaving the Catacombs like everything else here.
     *
     * <p>START↔RUN remains best-effort.
     */
    private void updatePhase() {
        DungeonEvents.Phase next;
        boolean mapInSlot = RoomMapReader.hasDungeonMap();
        if (mapInSlot) {
            // The map is back (or never left): drop the cached probe so the next disappearance is
            // answered by a fresh scan instead of by whatever this second happened to hold.
            lastRoomProbeAt = 0;
        }
        if (!mapInSlot && (phase == DungeonEvents.Phase.BOSS || !standingInARoom())) {
            next = DungeonEvents.Phase.BOSS;
        } else if (runStartMs != 0 || DungeonRoomTracker.getInstance().borders() != null) {
            // A locked room means the player has moved into the dungeon proper.
            if (runStartMs == 0) {
                runStartMs = System.currentTimeMillis();
            }
            next = DungeonEvents.Phase.RUN;
        } else {
            next = DungeonEvents.Phase.START;
        }
        if (next != phase) {
            DungeonEvents.Phase previous = phase;
            phase = next;
            DungeonEvents.firePhaseChange(previous, next);
        }
    }

    /**
     * Whether the player is standing inside something shaped like a real dungeon room, asked of the
     * world blocks only - no map item, no map snapshot, nothing the boss room takes away.
     *
     * <p>{@link DungeonRoomBorders#detectAround} walks the void until it finds walls and
     * {@link DungeonRoomLocator#isPlausibleRoom} applies the same legality rule the room tracker
     * uses before locking. The boss room fails it twice over: the rays run past the span cap in an
     * open build, and any footprint that does come back is not a legal shape.
     *
     * <p><b>Throttled to a second, and only asked when it can matter.</b> The scan is the same one
     * the tracker runs on room entry - cheap enough for that, far too costly per tick. It is only
     * reached once slot 9 has already stopped holding a map, which outside the boss room is rare,
     * and the answer is cached until the next second. The cache is dropped the moment the map is
     * back, so a fresh disappearance always re-probes rather than reusing a stale yes.
     */
    private boolean standingInARoom() {
        long now = System.currentTimeMillis();
        if (now - lastRoomProbeAt < ROOM_PROBE_MS) {
            return lastRoomProbe;
        }
        lastRoomProbeAt = now;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            lastRoomProbe = false;
            return false;
        }
        lastRoomProbe = DungeonRoomLocator.isPlausibleRoom(
                DungeonRoomBorders.detectAround(minecraft.level, minecraft.player.blockPosition()));
        return lastRoomProbe;
    }

    /** Counts cleared vs total painted room tiles from the map snapshot (drives the Explore score). */
    private void updateRoomCounts() {
        RoomMapReader.MapSnapshot snapshot = DungeonState.getInstance().snapshot();
        if (snapshot == null) {
            return;
        }
        int total = 0;
        int cleared = 0;
        for (RoomMapReader.MapTile tile : snapshot.tiles()) {
            if (tile.unexplored()) {
                total++;
                continue;
            }
            total++;
            RoomMapReader.RoomState state = tile.state();
            if (state == RoomMapReader.RoomState.CLEARED || state == RoomMapReader.RoomState.SECRETS_DONE) {
                cleared++;
            }
        }
        totalRooms = total;
        clearedRooms = cleared;
    }

    /** Scrapes the tab list for the run counters; logs them once per change for live tuning. */
    private void scanTab() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        int secrets = -1;
        int secretPercent = -1;
        int cryptCount = -1;
        int deathCount = -1;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String line = strip(display.getString());
            secrets = firstMatch(SECRETS, line, secrets);          // absolute count (Player Stats)
            secretPercent = firstMatch(SECRET_PCT, line, secretPercent); // completion % (Dungeon Stats)
            cryptCount = firstMatch(CRYPTS, line, cryptCount);
            deathCount = firstMatch(DEATHS, line, deathCount);
        }
        secretsFound = secrets;
        secretPct = secretPercent;
        crypts = cryptCount;
        deaths = deathCount;

        String stats = secretsFound + " " + secretPct + " " + crypts + " " + deaths + " " + roomClearPct;
        if (!stats.equals(lastLoggedStats)) {
            lastLoggedStats = stats;
            SkyblockSimplifiedSBS.LOGGER.debug(
                    "[SBS][DungeonState] {} secrets={} secret%={} crypts={} deaths={} cleared%={}",
                    floorLabel(), secretsFound, secretPct, crypts, deaths, roomClearPct());
        }
    }

    private void onBlessing(DungeonBlessings.Found found) {
        if (found == null) {
            return;
        }
        blessings.add(found);
        SkyblockSimplifiedSBS.LOGGER.debug("[SBS][DungeonState] blessing {} {} -> {} (whole run: {})",
                found.type().label(), found.level(), blessings.level(found.type()), blessings.complete());
    }

    private static int firstMatch(Pattern pattern, String line, int current) {
        if (current >= 0) {
            return current;
        }
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : current;
    }

    private void reset() {
        floorType = 0;
        floorNumber = 0;
        if (phase != DungeonEvents.Phase.START) {
            DungeonEvents.firePhaseChange(phase, DungeonEvents.Phase.START);
        }
        phase = DungeonEvents.Phase.START;
        secretsFound = -1;
        secretsTotal = -1;
        secretPct = -1;
        roomClearPct = -1;
        crypts = -1;
        deaths = -1;
        clearedRooms = -1;
        totalRooms = -1;
        runStartMs = 0;
        blessings.clear();
        lastLoggedStats = "";
        lastRoomProbeAt = 0;
        lastRoomProbe = false;
    }

    /** Diagnostic dump of the current tab list (for tuning the counter patterns in-game). */
    public List<String> debugTabLines() {
        List<String> out = new ArrayList<>();
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return out;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display != null) {
                out.add(strip(display.getString()));
            }
        }
        return out;
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "").trim();
    }
}
