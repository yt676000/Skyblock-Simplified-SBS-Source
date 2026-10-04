/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.dev.RoomMapReader;
import sbs.modid.client.core.dev.RoomRotation;
import sbs.modid.client.core.player.RealPlayers;
import sbs.modid.client.dungeons.run.logic.DungeonDoorScanner.DoorMatch;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher.BlockLookup;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher.BlockResult;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher.RoomMatch;
import sbs.modid.client.dungeons.rooms.DungeonRoom;
import sbs.modid.client.dungeons.rooms.DungeonRoomDatabase;
import sbs.modid.client.dungeons.run.render.DungeonHighlight;
import sbs.modid.client.dungeons.events.DungeonEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grid-scheme dungeon room tracking, driven from the <b>client tick</b> (never the render loop).
 *
 * <p>Flow: while no room is locked, every player block-move runs {@link DungeonRoomLocator#locate} –
 * fixed 32-grid + minimap segments, with the void-gap detection as map-less fallback.
 * The room anchor {@code (0,0,0)} is the footprint's <b>NW corner at Y 0</b> (corner scheme): the
 * matcher resolves the spawn rotation by pairing each facing with the corresponding bounding-box
 * corner. Everything – borders, anchor, dev border boxes, matched waypoints – is cached until the
 * player leaves the footprint, so the render pass stays lag-free. Map-located rooms are re-read every
 * second because minimap segments reveal progressively.
 *
 * <p>In developer mode the Catacombs scoreboard gate is bypassed, so room location, scanning and
 * waypoints can be verified in any world. The exact-geometry door sweep ({@link DungeonDoorScanner})
 * runs as a pure debug aid and reports found doorways in chat.
 */
public final class DungeonRoomTracker {

    private static final DungeonRoomTracker INSTANCE = new DungeonRoomTracker();

    private static final int LEAVE_MARGIN = 3;
    private static final double CHEST_HIDE_DIST_SQR = 30.0;

    /** A room waypoint resolved to absolute world coordinates, ready to render. */
    public record WorldWaypoint(String name, String type, BlockPos world) {
    }

    /** Ticks between map re-reads while locked (map segments reveal progressively). */
    private static final int MAP_RECHECK_TICKS = 20;

    private BlockPos lastScanPos;
    private BlockPos lastDoorChat;
    private DungeonRoomBorders.Borders borders;
    private BlockPos anchor;
    private boolean fromMap;
    /** Whether the world&lt;-&gt;map anchor existed when this lock was made (pre-anchor locks are drift-prone). */
    private boolean lockedAnchored;
    /** The painted map colour of the locked room ({@code null} when the map did not rule) – narrows the match. */
    private String mapColor;
    private RoomMapReader.RoomState roomState;
    private DungeonRunRegistry.RunRoom currentRun;
    private int recheckCounter;
    private RoomMatch active;
    private List<WorldWaypoint> waypoints = List.of();
    private boolean wasInCatacombs;

    private final Set<String> hiddenWaypoints = new HashSet<>();
    private boolean containerWasOpen;

    /**
     * Per-run footprint memory: once a room locked, every one of its cells maps to that footprint
     * for the rest of the run – the dungeon grid never changes mid-run, so later map weirdness
     * (edge clipping, marker drift, re-reads) can never move a room that was already established.
     * Cleared on leaving The Catacombs.
     */
    private record RunFootprint(DungeonRoomBorders.Borders borders, boolean fromMap, boolean anchored,
                                String mapColor) {
    }

    private final Map<Long, RunFootprint> runFootprints = new java.util.HashMap<>();

    /**
     * Every room locked this run, by its registry corner key – the source for rebuilding the map-cell
     * links after the world&lt;-&gt;map anchor moves.
     */
    private final Map<Long, DungeonRoomBorders.Borders> lockedRooms = new java.util.HashMap<>();

    /** The anchor generation the current map-cell links were built with (see {@link #relinkMapCells}). */
    private int linkedAnchorGeneration;

    /**
     * How long the scanner stays completely off after it found neither a room around the player nor
     * anything painted on the map. The boss room is the case this exists for: it is a huge off-grid
     * build with no map in slot 9, so every locate attempt fails - and since attempts are driven by
     * player movement, a boss fight produced one failed scan (and one "illegal shape" line) per
     * block stepped. Ten seconds is far below any room transition that matters and turns that spam
     * into at most one probe per 10s.
     */
    private static final long STAND_DOWN_MS = 10_000L;

    private long standDownUntil;
    /** True while the scanner is stood down – keeps the diagnostic to ONE line per episode. */
    private boolean standingDown;

    private DungeonRoomTracker() {
        CollectedSecrets.getInstance().addCandidateSource(this::uncollectedSecrets);
        // The end-of-run score summary is the other hard "past the room grid" signal: the run is
        // over, the boss room is all that is left, and nothing on the grid will be found again.
        sbs.modid.client.dungeons.events.ChatPatternRegistry.getInstance().register(
                "(?i)(Team Score:|>\\s*EXTRA STATS\\s*<|Dungeon Cleared:)",
                matcher -> standDown("dungeon score message"), "room scanner stand-down");
    }

    /**
     * Stops the room scanner for {@link #STAND_DOWN_MS}. Everything else keeps running (starred-mob
     * boxes, run memory, the map anchor) – this only silences the part that cannot work here.
     */
    private void standDown(String reason) {
        standDownUntil = System.currentTimeMillis() + STAND_DOWN_MS;
        if (!standingDown) {
            standingDown = true;
            DungeonDebug.chat("§7Room scanner off (" + reason + ") - probing every "
                    + STAND_DOWN_MS / 1000 + "s");
        }
    }

    /** Ends a stand-down episode (a room locked again, or the player left the dungeon). */
    private void resumeScanning() {
        standDownUntil = 0;
        if (standingDown) {
            standingDown = false;
            DungeonDebug.chat("§7Room scanner back on");
        }
    }

    public static DungeonRoomTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Whether any consumer needs the locked room <b>identified</b> against the database (its name /
     * {@link #activeMatch()}): room waypoints, the SBS Dungeon Map, Secret Routes, the puzzle solver
     * (finds its puzzle by room name) and the blood helper (finds the blood room by name). A consumer
     * that reads the tracker must be listed here, or it works only while something else is on.
     */
    static boolean needsIdentification(SBSConfig config) {
        return config.dungeons.roomWaypoints || config.dungeons.sbsDungeonMap
                || config.secretRoutes.enabled || config.dungeons.puzzleSolver || config.blood.enabled;
    }

    /** Whether any consumer needs at least the room footprint: the identifiers plus the wither doors. */
    static boolean needsFootprint(SBSConfig config) {
        return needsIdentification(config) || config.dungeons.witherDoors;
    }

    // ---- client-tick entry (from GuiTrackingMixin) -------------------------------------------------

    /** Called once per client tick. Heavy work runs only once per room entry; the rest is light. */
    public void tick(Minecraft minecraft) {
        SBSConfig config = ConfigManager.getInstance().get();
        boolean featureOn = config.dungeons.roomWaypoints;
        // Waypoint *rendering* stays gated on roomWaypoints alone (in DungeonHighlight), so the map
        // never draws boxes; identification and footprint locking follow every consumer's demand.
        boolean identifyOn = needsIdentification(config);
        // Developer mode only ADDS here: with no consumer on it keeps the locator running for the
        // diagnostics. It is never the reason a player feature gets its room.
        // DEV-ONLY: diagnostics only; consumers have their own demand
        boolean trackRooms = needsFootprint(config) || DevMode.ACTIVE;
        if (!trackRooms) {
            clear();
            return;
        }
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            clear();
            return;
        }

        // Hard gate on the Hypixel sidebar (where purse/date are shown): the whole scanner is off
        // unless a line contains "The Catacombs" – also in developer mode.
        boolean inCatacombs = DungeonScoreboard.isInCatacombs();
        if (inCatacombs != wasInCatacombs) {
            DungeonDebug.chat(inCatacombs ? "§aDungeon detected: The Catacombs" : "§7Left The Catacombs");
            wasInCatacombs = inCatacombs;
        }
        if (!inCatacombs) {
            unlock();
            resumeScanning();
            lastScanPos = null;
            DungeonRunRegistry.getInstance().clear(); // new run = fresh room memory
            CollectedSecrets.getInstance().clear();
            runFootprints.clear();
            lockedRooms.clear();
            RoomMapReader.resetAnchor();
            DungeonHighlight.getInstance().clearStarredMobs();
            clearStarredMobState();
            return;
        }

        // Stood down (boss room / score message / nothing findable): the scanner is OFF. Only the
        // starred-mob boxes keep running - they are what a boss fight actually needs.
        if (System.currentTimeMillis() < standDownUntil) {
            tickStarredMobs(player, level);
            return;
        }

        // No dungeon map in hotbar slot 9 = not in the dungeon proper: either outside a run or in
        // the boss room, which is not on the room grid at all (its huge open build is what produced
        // the impossible "3x4 span" retry spam). Stop the room scanner entirely - no locks, no
        // retries - but keep the starred-mob boxes running (boss fights are where they matter most).
        // Run memory and the map anchor stay untouched: the run itself is still going.
        if (!RoomMapReader.hasDungeonMap()) {
            unlock();
            lastScanPos = null;
            standDown("no map in slot 9");
            tickStarredMobs(player, level);
            return;
        }

        // The world<->map anchor was (re-)committed: every map-cell link in the run registry was
        // derived from the OLD correspondence and now points at the wrong tiles - which is how a
        // freshly entered room ends up wearing another room's name on the SBS map. Rebuild them all.
        int anchorGeneration = RoomMapReader.anchorGeneration();
        if (anchorGeneration != linkedAnchorGeneration) {
            linkedAnchorGeneration = anchorGeneration;
            relinkMapCells();
        }

        BlockPos current = player.blockPosition();

        // Locked: only the cheap leave check runs until the player exits the footprint. The room is
        // re-read periodically WHATEVER the lock's source: map-located rooms because the minimap
        // reveals their segments progressively (a 2x2 first shows up as 1x1), and fallback-located
        // rooms so the map can take over the moment it identifies the tile - the map is the size
        // authority, and a wrong void-fill lock must never survive it ("die Karte enforced Größen").
        if (borders != null) {
            if (isOutside(current)) {
                unlock();
            } else if (++recheckCounter >= MAP_RECHECK_TICKS) {
                recheckCounter = 0;
                DungeonRoomLocator.Located again = DungeonRoomLocator.locate(level, current);
                if (again != null && again.fromMap() && plausibleRoomSpan(again)) {
                    boolean nowAnchored = RoomMapReader.isAnchored();
                    // Untrusted locks are replaced outright by the map's verdict: fallback locks
                    // (the v6 takeover), and map locks made BEFORE the world<->map anchor existed -
                    // those placed the footprint from the drift-prone marker pixel, and the run
                    // memory used to cement them for the whole run.
                    boolean untrusted = !fromMap || (!lockedAnchored && nowAnchored);
                    if (untrusted && !sameFootprint(again.borders(), borders)) {
                        DungeonDebug.chat("§eMap takeover: §b" + again.borders().shape()
                                + " §7replaces " + (fromMap ? "pre-anchor" : "fallback") + " " + borders.shape());
                        forgetFootprint(borders);
                        lockRoom(level, again, identifyOn, true, current, nowAnchored, null);
                    } else if (untrusted) {
                        // Same footprint, higher trust: validate the lock in place (no re-lock churn).
                        fromMap = true;
                        lockedAnchored = nowAnchored;
                        if (again.mapRoom() != null) {
                            mapColor = again.mapRoom().colorName();
                            roomState = again.mapRoom().state();
                        }
                        rememberFootprint(borders);
                    } else if (again.borders().rects().size() > borders.rects().size()) {
                        // GROWTH only between two map reads: the map reveals progressively, so a
                        // bigger re-read refreshes the lock - but a smaller one is a map hiccup
                        // (edge clipping, marker drift) and must never shrink an established room.
                        lockRoom(level, again, identifyOn, true, current, nowAnchored, null);
                    } else if (again.mapRoom() != null && again.mapRoom().state() != roomState) {
                        roomState = again.mapRoom().state();
                        DungeonDebug.chat("§7Room state: " + stateText(roomState));
                    }
                }
                // A failed identification is retried while standing in the room: at lock time the
                // chunks holding distant signature blocks may not have streamed in yet, and the map
                // may not have revealed the full footprint - both heal within seconds.
                if (identifyOn && active == null && borders != null) {
                    attemptIdentify(level, current);
                }
            }
        }

        // Unlocked: locate the room around the player, but only when they moved to a new block.
        if (borders == null && !current.equals(lastScanPos)) {
            lastScanPos = current;
            // DEV-ONLY: diagnostics only; consumers have their own demand
            if (DevMode.ACTIVE) {
                debugDoorSweep(level, current);
            }
            // A cell that was part of an established room this run re-locks that footprint
            // directly - the grid never changes mid-run, whatever the map does at its edges now.
            RunFootprint remembered = runFootprints.get(cellKeyOf(current));
            if (remembered != null) {
                // The remembered colour comes along: it is what narrows the database on the
                // re-identification, and a re-lock carries no MapRoom of its own to read it from.
                lockRoom(level, new DungeonRoomLocator.Located(
                        remembered.borders(), remembered.fromMap(), null), identifyOn, false,
                        current, remembered.anchored(), remembered.mapColor());
            } else {
                DungeonRoomLocator.Located found = DungeonRoomLocator.locate(level, current);
                if (found != null && plausibleRoomSpan(found)) {
                    lockRoom(level, found, identifyOn, false, current, RoomMapReader.isAnchored(), null);
                } else {
                    // Nothing around us AND nothing on the map (locate consults the map first), or a
                    // footprint that cannot be a room: this place is not on the grid. Stand down
                    // instead of re-running the whole locate on the next block stepped.
                    standDown(found == null ? "no room, and the map paints none" : "illegal footprint");
                }
            }
        }

        tickSecrets(player, level);
        tickStarredMobs(player, level);
    }

    /** One-time heavy path per room entry: cache borders + anchor, push dev boxes, match the database. */
    /**
     * Sanity gate before a room lock: a cell-aligned footprint must be a shape from the exact
     * legal catalog ({@link RoomMapReader#isLegalShape}: 1x1..1x4, 2x2, corner, L – T/S layouts
     * and anything over 4 cells cannot exist). The void-gap fallback in particular can overshoot
     * on tall tower rooms – a door bridge breaks its "fully void line" probe and the border lands
     * one or two rooms further out, which then exported impossible sizes like "5x3". Irregular
     * (void-detected) rects carry no cell structure, so only the bounding-span heuristic can judge
     * them. An implausible result is simply not locked; the tracker retries on the next move.
     */
    private boolean plausibleRoomSpan(DungeonRoomLocator.Located located) {
        DungeonRoomBorders.Borders found = located.borders();
        if (DungeonRoomLocator.isPlausibleRoom(found)) {
            return true;
        }
        // The rule itself lives in the locator so the boss detector asks the same question; this
        // only names what was rejected, which is the half a diagnostic needs.
        List<int[]> offsets = DungeonRoomLocator.cellOffsets(found);
        String shape = offsets != null ? RoomMapReader.shapeOf(offsets)
                : (int) Math.ceil((found.max().getX() - found.min().getX() + 1)
                        / (double) DungeonRoomLocator.GRID)
                        + "x" + (int) Math.ceil((found.max().getZ() - found.min().getZ() + 1)
                        / (double) DungeonRoomLocator.GRID) + " span";
        chatImplausible(shape, located);
        return false;
    }

    /**
     * The "not a legal room" diagnostic. Silenced while the scanner is stood down: in the boss room
     * every single probe fails by definition, and one line per probe is exactly the spam the
     * stand-down exists to stop. The one line {@link #standDown} prints says it all.
     */
    private void chatImplausible(String shape, DungeonRoomLocator.Located located) {
        if (standingDown) {
            return;
        }
        String mapShape = located.mapRoom() != null
                ? RoomMapReader.shapeOf(located.mapRoom().offsets()) : null;
        DungeonDebug.chat("§cRoom shape " + shape + " is illegal - not locking (retry)"
                + (mapShape != null ? " §7- map paints §b" + mapShape : ""));
    }

    private void lockRoom(ClientLevel level, DungeonRoomLocator.Located located, boolean featureOn,
                          boolean keepHidden, BlockPos player, boolean anchoredAtLock, String colorHint) {
        DungeonRoomBorders.Borders found = located.borders();
        borders = found;
        fromMap = located.fromMap();
        lockedAnchored = anchoredAtLock;
        mapColor = located.mapRoom() != null ? located.mapRoom().colorName() : colorHint;
        recheckCounter = 0;
        // Canonical anchor = NW corner of the footprint at Y 0 (corner scheme): relative X/Z
        // are corner-based 0..30 values and relative Y stays absolute (dungeon height is fixed).
        anchor = new BlockPos(found.min().getX(), 0, found.min().getZ());
        // Remember the footprint under every grid-aligned cell it covers: re-entering any of them
        // this run re-locks it verbatim (the grid never changes mid-run).
        rememberFootprint(found);
        resumeScanning();   // a real lock means the grid is findable here again
        // Per-run room memory: keyed by the world NW corner, linked to the map cell for the map HUD.
        long cornerKey = DungeonRunRegistry.cornerKey(found.min().getX(), found.min().getZ());
        currentRun = DungeonRunRegistry.getInstance().getOrCreate(cornerKey);
        lockedRooms.put(cornerKey, found);
        linkMapCells(located, found, cornerKey);
        // Collected secrets survive re-entering: seed the hidden set from the run memory.
        hiddenWaypoints.clear();
        hiddenWaypoints.addAll(currentRun.foundSecrets());
        if (!keepHidden) {
            containerWasOpen = false;
        }
        pushDevBoxes();
        RoomMapReader.MapRoom mapRoom = located.mapRoom();
        roomState = mapRoom != null ? mapRoom.state() : null;
        DungeonDebug.chat(String.format("§a✔ Room: §b%s%s §7(%d,%d → %d,%d) NW anchor §b%d,%d §7src %s%s",
                mapRoom != null ? mapRoom.colorName() + " " : "", found.shape(),
                found.min().getX(), found.min().getZ(), found.max().getX(), found.max().getZ(),
                anchor.getX(), anchor.getZ(), fromMap ? "§amap+grid" : "§evoid",
                roomState != null ? " §7state " + stateText(roomState) : ""));
        // Grid fill vs map paint disagreeing means one of them mis-read this room - surface it so
        // the shape can be verified in-game instead of silently exporting the wrong size.
        if (mapRoom != null && !mapRoom.offsets().isEmpty()) {
            String mapShape = RoomMapReader.shapeOf(mapRoom.offsets());
            if (!mapShape.equals(found.shape())) {
                DungeonDebug.chat("§eShape mismatch: grid fill says §b" + found.shape()
                        + "§e, map paints §b" + mapShape + "§e - verify before scanning");
            }
        }

        active = null;
        waypoints = List.of();
        if (featureOn) {
            attemptIdentify(level, player);
        }
        // DEV-ONLY: diagnostics only; consumers have their own demand
        if (DevMode.ACTIVE) {
            BlockLookup lookup = pos -> BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            runDiagnostics(lookup);
        }
    }

    /**
     * Tries to identify the locked room against the database - map-narrowed (size + colour) with the
     * full-database brute force and the player-cell placement enumeration as fallbacks (see
     * {@link DungeonRoomMatcher#identify}). Runs at lock time and is <b>retried</b> on the periodic
     * recheck while no match exists: distant signature blocks may sit in chunks that had not
     * streamed in at lock time, and small-scan rooms need every block present.
     */
    private void attemptIdentify(ClientLevel level, BlockPos player) {
        Map<String, DungeonRoom> database = DungeonRoomDatabase.rooms();
        if (database.isEmpty() || borders == null) {
            return;
        }
        BlockLookup lookup = pos -> BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        // fromMap = the dungeon map ruled on this footprint, so it is the size authority and the room
        // must stand exactly on it (no roaming placements into the neighbour room - see identify).
        RoomMatch match = DungeonRoomMatcher.identify(lookup, borders, player, mapColor, fromMap, database);
        if (match == null) {
            return;
        }
        active = match;
        waypoints = computeWaypoints(match);
        DungeonRoom room = database.get(match.name());
        int secretsTotal = room != null && room.waypoints != null ? room.waypoints.size() : -1;
        if (currentRun != null) {
            currentRun.identify(match.name(), secretsTotal);
            // identify() drops the collected secrets when the name CHANGED (the earlier match was
            // wrong) - the hidden set was seeded from them at lock time, so it must follow.
            hiddenWaypoints.retainAll(currentRun.foundSecrets());
        }
        // matched/total are the WINNING CELL's counts, not the whole room's (per-cell scoring).
        DungeonDebug.chat(String.format("§a✔ Room match: §b%s §7facing §b%s §7(cell %d/%d) secrets %d/%s",
                match.name(), match.facing(), match.matched(), match.total(),
                currentRun != null ? currentRun.secretsFound() : 0,
                secretsTotal >= 0 ? secretsTotal : "?"));
    }

    /**
     * Links the run room to <b>every</b> map-grid cell it occupies, so the SBS map can label it from
     * any of its segments. Two reasons this is not just the NW cell:
     * <ul>
     *   <li>The map HUD groups painted tiles itself and looks the room up by the cells of that group.
     *       A room whose NW segment was still unrevealed at lock time (progressive reveal) grouped
     *       under a different NW cell than the one linked here, and its matched name never showed.</li>
     *   <li>Void-locked rooms carry no {@code mapRoom} at all, so they used to link nothing – no name
     *       on the map even when the database match succeeded. Their cells are derived from the world
     *       footprint through the run's world&lt;-&gt;map anchor instead.</li>
     * </ul>
     */
    private void linkMapCells(DungeonRoomLocator.Located located, DungeonRoomBorders.Borders found,
                              long cornerKey) {
        DungeonRunRegistry registry = DungeonRunRegistry.getInstance();
        RoomMapReader.MapRoom mapRoom = located.mapRoom();
        if (mapRoom != null) {
            registry.linkMapCell(mapRoom.nwCellX(), mapRoom.nwCellZ(), cornerKey);
            // MapRoom.offsets() are relative to the PLAYER's cell, while nwCell is the NW segment -
            // shifting them by their own minimum turns them into absolute cells.
            int minOffX = Integer.MAX_VALUE;
            int minOffZ = Integer.MAX_VALUE;
            for (int[] offset : mapRoom.offsets()) {
                minOffX = Math.min(minOffX, offset[0]);
                minOffZ = Math.min(minOffZ, offset[1]);
            }
            for (int[] offset : mapRoom.offsets()) {
                registry.linkMapCell(mapRoom.nwCellX() + offset[0] - minOffX,
                        mapRoom.nwCellZ() + offset[1] - minOffZ, cornerKey);
            }
        }
        linkFootprintCells(registry, found, cornerKey);
    }

    /** Every 32-block cell of the footprint, mapped through the anchor (silent until it is captured). */
    private static void linkFootprintCells(DungeonRunRegistry registry,
                                           DungeonRoomBorders.Borders found, long cornerKey) {
        for (DungeonRoomBorders.Rect rect : found.rects()) {
            for (int x = rect.minX(); x <= rect.maxX(); x += 32) {
                for (int z = rect.minZ(); z <= rect.maxZ(); z += 32) {
                    int[] cell = RoomMapReader.worldToMapCellIndex(x, z);
                    if (cell != null) {
                        registry.linkMapCell(cell[0], cell[1], cornerKey);
                    }
                }
            }
        }
    }

    /**
     * Rebuilds every map-cell link from the rooms locked this run, through the <b>current</b> anchor.
     * Room identities (name, secrets) survive – only which map tile shows them is recomputed. Rooms
     * whose footprint is not grid-aligned (the irregular void fallback) simply lose their label until
     * they are entered again, which is the harmless direction: no label beats a wrong one.
     *
     * <p>Without an anchor nothing can be rebuilt (world cells do not map to map cells yet), so the
     * existing links are left alone rather than wiped – the next anchor commit rebuilds them all.
     */
    private void relinkMapCells() {
        if (!RoomMapReader.isAnchored()) {
            return;
        }
        DungeonRunRegistry registry = DungeonRunRegistry.getInstance();
        registry.clearMapCells();
        for (Map.Entry<Long, DungeonRoomBorders.Borders> entry : lockedRooms.entrySet()) {
            linkFootprintCells(registry, entry.getValue(), entry.getKey());
        }
    }

    /**
     * Stores the footprint under every grid-aligned cell it covers, with the lock's current trust
     * flags - re-entering any of those cells this run re-locks it verbatim.
     */
    private void rememberFootprint(DungeonRoomBorders.Borders found) {
        RunFootprint memo = new RunFootprint(found, fromMap, lockedAnchored, mapColor);
        for (DungeonRoomBorders.Rect rect : found.rects()) {
            if (DungeonRoomLocator.cornerCoord(rect.minX()) == rect.minX()
                    && DungeonRoomLocator.cornerCoord(rect.minZ()) == rect.minZ()) {
                runFootprints.put(DungeonRunRegistry.cornerKey(rect.minX(), rect.minZ()), memo);
            }
        }
    }

    /** Whether two footprints cover exactly the same cells (XZ rects only – Y varies with the player). */
    private static boolean sameFootprint(DungeonRoomBorders.Borders a, DungeonRoomBorders.Borders b) {
        return new HashSet<>(a.rects()).equals(new HashSet<>(b.rects()));
    }

    private static String stateText(RoomMapReader.RoomState state) {
        return switch (state) {
            case CLEARED -> "§fcleared ✔ (white)";
            case SECRETS_DONE -> "§aall secrets ✔ (green)";
            case FAILED -> "§cfailed ✘";
            case UNCLEARED -> "§7uncleared";
        };
    }

    /** Dev-only doorway sweep: reports the exact-geometry door pattern in chat (no tracking role). */
    private void debugDoorSweep(ClientLevel level, BlockPos current) {
        DoorMatch door = DungeonDoorScanner.findDoorNear(level, current);
        if (door != null && !door.anchor().equals(lastDoorChat)) {
            lastDoorChat = door.anchor();
            // Hypixel doorways always sit inside Y 66..73 (known constant) – flag outliers.
            boolean hypixelY = door.anchor().getY() >= 66 && door.anchor().getY() <= 73;
            DungeonDebug.chat(String.format("§eDoor %s at %d,%d,%d §7(axis %s%s)",
                    door.variant(), door.anchor().getX(), door.anchor().getY(), door.anchor().getZ(),
                    door.axis(), hypixelY ? "" : ", §cY outside 66-73§7"));
        }
    }

    /**
     * Caches the dev border overlay. When every footprint rect is a plain grid cell (the normal
     * case for grid/map-located rooms) the border is pushed as ONE merged outline enclosing the
     * whole room – segments bridge the 1-block gap seams between cells, so a 1x4 or L room shows a
     * single connected border instead of four separate 32x32 boxes. Irregular (void-detected) rects
     * fall back to one box per rect.
     */
    private void pushDevBoxes() {
        List<DungeonHighlight.OutlineSegment> outline = cellOutline(borders.rects());
        if (outline != null) {
            DungeonHighlight.getInstance().setDebugOutline(outline, borders.min().getY());
            return;
        }
        List<BlockPos[]> boxes = new ArrayList<>();
        for (DungeonRoomBorders.Rect rect : borders.rects()) {
            boxes.add(new BlockPos[] {
                    new BlockPos(rect.minX(), borders.min().getY(), rect.minZ()),
                    new BlockPos(rect.maxX(), borders.max().getY(), rect.maxZ())});
        }
        DungeonHighlight.getInstance().setDebugComplex(boxes);
    }

    /**
     * Builds the merged border outline of a cell-aligned footprint, or {@code null} when a rect is
     * not a plain grid cell. Per cell, each side without a room neighbour contributes one segment
     * on the box hull ({@code corner .. corner+31}); collinear segments whose ends are one block
     * apart (the inter-cell gap seam) are merged, and perpendicular segments that stop one block
     * short of each other (the inner corners of L-shapes) are extended to meet.
     */
    private static List<DungeonHighlight.OutlineSegment> cellOutline(List<DungeonRoomBorders.Rect> rects) {
        int span = DungeonRoomLocator.ROOM_SPAN;
        int grid = DungeonRoomLocator.GRID;
        int edge = span + 1;   // box hull: corner .. corner+31 (the max+1 draw convention)
        Set<Long> cells = new HashSet<>();
        for (DungeonRoomBorders.Rect rect : rects) {
            if (rect.maxX() - rect.minX() != span || rect.maxZ() - rect.minZ() != span) {
                return null;
            }
            cells.add(packXZ(rect.minX(), rect.minZ()));
        }
        // Sides without a room neighbour, as {fixed, from, to} runs (fixed = the side's z or x).
        List<int[]> horizontal = new ArrayList<>();
        List<int[]> vertical = new ArrayList<>();
        for (DungeonRoomBorders.Rect rect : rects) {
            int cx = rect.minX();
            int cz = rect.minZ();
            if (!cells.contains(packXZ(cx, cz - grid))) {
                horizontal.add(new int[] {cz, cx, cx + edge});
            }
            if (!cells.contains(packXZ(cx, cz + grid))) {
                horizontal.add(new int[] {cz + edge, cx, cx + edge});
            }
            if (!cells.contains(packXZ(cx - grid, cz))) {
                vertical.add(new int[] {cx, cz, cz + edge});
            }
            if (!cells.contains(packXZ(cx + grid, cz))) {
                vertical.add(new int[] {cx + edge, cz, cz + edge});
            }
        }
        List<int[]> mergedH = mergeRuns(horizontal);
        List<int[]> mergedV = mergeRuns(vertical);
        closeInnerCorners(mergedH, mergedV);

        List<DungeonHighlight.OutlineSegment> segments = new ArrayList<>(mergedH.size() + mergedV.size());
        for (int[] run : mergedH) {
            segments.add(new DungeonHighlight.OutlineSegment(run[1], run[0], run[2], run[0]));
        }
        for (int[] run : mergedV) {
            segments.add(new DungeonHighlight.OutlineSegment(run[0], run[1], run[0], run[2]));
        }
        return segments;
    }

    /** Merges collinear {fixed, from, to} runs whose ends are at most one block apart (gap seams). */
    private static List<int[]> mergeRuns(List<int[]> runs) {
        Map<Integer, List<int[]>> byFixed = new java.util.HashMap<>();
        for (int[] run : runs) {
            byFixed.computeIfAbsent(run[0], k -> new ArrayList<>()).add(run);
        }
        List<int[]> merged = new ArrayList<>();
        for (List<int[]> line : byFixed.values()) {
            line.sort(java.util.Comparator.comparingInt(run -> run[1]));
            int[] current = null;
            for (int[] run : line) {
                if (current != null && run[1] - current[2] <= 1) {
                    current[2] = Math.max(current[2], run[2]);
                } else {
                    current = new int[] {run[0], run[1], run[2]};
                    merged.add(current);
                }
            }
        }
        return merged;
    }

    /**
     * Extends perpendicular runs that stop one block short of each other until they meet – the
     * inner corner of an L-shape sits across a gap seam, so the vertical wall ends at {@code z=31}
     * while the horizontal one starts at {@code z=32}, one block away on both axes.
     */
    private static void closeInnerCorners(List<int[]> horizontal, List<int[]> vertical) {
        for (int[] v : vertical) {                     // v = {x, z0, z1}
            for (int[] h : horizontal) {               // h = {z, x0, x1}
                for (int vEnd = 1; vEnd <= 2; vEnd++) {
                    for (int hEnd = 1; hEnd <= 2; hEnd++) {
                        if (Math.abs(v[0] - h[hEnd]) == 1 && Math.abs(h[0] - v[vEnd]) == 1) {
                            // Snap both loose ends onto the true corner (v.x, h.z).
                            h[hEnd] = v[0];
                            v[vEnd] = h[0];
                        }
                    }
                }
            }
        }
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private void unlock() {
        active = null;
        waypoints = List.of();
        borders = null;
        anchor = null;
        fromMap = false;
        lockedAnchored = false;
        mapColor = null;
        roomState = null;
        currentRun = null;
        recheckCounter = 0;
        hiddenWaypoints.clear();
        DungeonHighlight.getInstance().clearDebugScan();
        DungeonHighlight.getInstance().clearDebugComplex();
    }

    /**
     * Whether the player has left the room – tested against <b>every footprint rect</b>, not the
     * bounding box. An L / corner room's bounding box also covers the cell it does <i>not</i> occupy,
     * which belongs to another room: standing there kept the old lock alive, so that room's name and
     * its secret waypoints stayed on screen after the room change.
     */
    private boolean isOutside(BlockPos p) {
        for (DungeonRoomBorders.Rect rect : borders.rects()) {
            if (p.getX() >= rect.minX() - LEAVE_MARGIN && p.getX() <= rect.maxX() + LEAVE_MARGIN
                    && p.getZ() >= rect.minZ() - LEAVE_MARGIN && p.getZ() <= rect.maxZ() + LEAVE_MARGIN) {
                return false;
            }
        }
        return true;
    }

    /** The run-footprint key of the grid cell containing {@code pos}. */
    private static long cellKeyOf(BlockPos pos) {
        return DungeonRunRegistry.cornerKey(
                DungeonRoomLocator.cornerCoord(pos.getX()), DungeonRoomLocator.cornerCoord(pos.getZ()));
    }

    /**
     * Drops every run-memory entry still pointing at {@code old} (reference identity - the memo is
     * stored under all its cells). Called before a map takeover replaces a fallback lock: cells of
     * the wrong footprint that the new one does not cover would otherwise re-lock it verbatim.
     */
    private void forgetFootprint(DungeonRoomBorders.Borders old) {
        runFootprints.values().removeIf(memo -> memo.borders() == old);
        // The identification hung on this footprint too. A lock that reached into the neighbour cell
        // identifies as the NEIGHBOUR's room, and the run room is keyed by the NW corner - which a
        // takeover does not change - so the wrong name would outlive the footprint that produced it.
        // Dropping it lets the corrected footprint decide again from scratch.
        DungeonRunRegistry.getInstance().forgetIdentity(
                DungeonRunRegistry.cornerKey(old.min().getX(), old.min().getZ()));
        // Its map-cell links must go with it: cells the replacing footprint does not cover would
        // otherwise keep pointing at the discarded room and label a neighbour tile with its name.
        if (lockedRooms.values().removeIf(borders -> borders == old)) {
            relinkMapCells();
        }
    }

    /** Resolves every waypoint of the matched room (database + personal route file) to world coords. */
    private static List<WorldWaypoint> computeWaypoints(RoomMatch match) {
        List<WorldWaypoint> result = new ArrayList<>();
        DungeonRoom room = DungeonRoomDatabase.rooms().get(match.name());
        if (room != null && room.waypoints != null) {
            appendWaypoints(result, room.waypoints, match);
        }
        appendWaypoints(result, DungeonRouteStore.waypointsFor(match.name()), match);
        return result;
    }

    private static void appendWaypoints(List<WorldWaypoint> result,
                                        Map<String, DungeonRoom.RoomWaypoint> waypoints, RoomMatch match) {
        for (Map.Entry<String, DungeonRoom.RoomWaypoint> entry : waypoints.entrySet()) {
            DungeonRoom.RoomWaypoint wp = entry.getValue();
            BlockPos world = RoomRotation.relativeToActual(
                    match.facing(), match.anchor(), wp.relative_x, wp.relative_y, wp.relative_z);
            result.add(new WorldWaypoint(entry.getKey(), wp.type, world));
        }
    }

    // ---- per-tick starred-mob boxes ------------------------------------------------------------------

    /** The ✯ star Hypixel puts into the armor-stand nametag of starred dungeon mobs. */
    private static final String STARRED_MOB_MARK = "✯"; // "✯"

    /** Horizontal reach of the stand-to-mob search: a tag lags behind a moving mob, or hangs over a wide one. */
    private static final double STAR_SEARCH_RADIUS = 1.25;

    /** How far below the stand a body is searched for. */
    private static final double STAR_SEARCH_DEPTH = 3.0;

    /** Ticks a visibility answer is reused before the rays are cast again for that mob. */
    private static final int STAR_VISIBILITY_TICKS = 4;

    /** Beyond this the rays are not cast at all - the same reach the single-ray test had. */
    private static final double STAR_SIGHT_RANGE = 128.0;

    /** Per mob id: the tick its visibility was last tested, and the answer (1 visible, 0 not). */
    private final Map<Integer, long[]> starVisibility = new HashMap<>();

    /** Probe lines already written, keyed by stand id and outcome, so each is logged once. */
    private final Set<String> starProbeLogged = new HashSet<>();

    private long starTick;

    /**
     * Collects the starred mobs (✯ in the floating nametag) for the yellow boxes. <b>No highlight
     * through walls:</b> a mob is only boxed while some part of it is in the player's direct line of
     * sight ({@link StarredMobMatch#anyVisible}); a mob fully behind a wall or around a corner is
     * skipped, so only genuinely visible mobs are ever highlighted.
     */
    private void tickStarredMobs(LocalPlayer player, ClientLevel level) {
        if (!ConfigManager.getInstance().get().dungeons.boxStarredMobs) {
            DungeonHighlight.getInstance().clearStarredMobs();
            clearStarredMobState();
            return;
        }
        long tick = ++starTick;
        boolean probe = DungeonDebug.enabled();
        List<net.minecraft.world.entity.Entity> starred = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand)
                    || !stand.hasCustomName()) {
                continue;
            }
            var name = stand.getCustomName();
            if (name == null || !name.getString().contains(STARRED_MOB_MARK)) {
                continue;
            }
            StarredMobMatch.Pick<net.minecraft.world.entity.LivingEntity> pick = mobBelow(level, stand);
            net.minecraft.world.entity.LivingEntity mob = pick.mob();
            StarredMobMatch.Outcome outcome = pick.outcome();
            if (mob != null) {
                seen.add(mob.getId());
                if (isVisible(player, level, mob, tick)) {
                    starred.add(mob);
                } else {
                    outcome = StarredMobMatch.Outcome.NO_LINE_OF_SIGHT;
                }
            }
            if (probe) {
                probeStar(stand, name.getString(), mob, outcome);
            }
        }
        starVisibility.keySet().retainAll(seen);
        DungeonHighlight.getInstance().setStarredMobs(starred);
    }

    /** Throttled visibility: the rays for one mob are cast every few ticks, the answer reused between. */
    private boolean isVisible(LocalPlayer player, ClientLevel level,
                              net.minecraft.world.entity.LivingEntity mob, long tick) {
        long[] last = starVisibility.get(mob.getId());
        if (last != null && tick - last[0] < STAR_VISIBILITY_TICKS) {
            return last[1] != 0;
        }
        boolean visible = castSightRays(player, level, mob);
        starVisibility.put(mob.getId(), new long[] {tick, visible ? 1 : 0});
        return visible;
    }

    /** Whether any sample point of the mob is reached by an unobstructed ray from the player's eye. */
    private static boolean castSightRays(LocalPlayer player, ClientLevel level,
                                         net.minecraft.world.entity.LivingEntity mob) {
        if (mob.level() != level) {
            return false;
        }
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        if (eye.distanceTo(mob.position()) > STAR_SIGHT_RANGE) {
            return false;
        }
        return StarredMobMatch.anyVisible(StarredMobMatch.samplePoints(mob.getBoundingBox(), mob.getEyeY()),
                target -> level.clip(new net.minecraft.world.level.ClipContext(eye, target,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, player)).getType()
                        == net.minecraft.world.phys.HitResult.Type.MISS);
    }

    /**
     * The body a ✯ nametag stand belongs to. Candidates are every living entity in a box
     * {@link #STAR_SEARCH_RADIUS} out and {@link #STAR_SEARCH_DEPTH} down from the stand, real players
     * flagged rather than dropped so the probe can say so; {@link StarredMobMatch#pick} chooses the
     * one whose top is closest under the tag. Hypixel's player-model mobs are fake players and are
     * ordinary candidates.
     */
    private static StarredMobMatch.Pick<net.minecraft.world.entity.LivingEntity> mobBelow(
            ClientLevel level, net.minecraft.world.entity.decoration.ArmorStand stand) {
        List<net.minecraft.world.entity.LivingEntity> bodies = level.getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class,
                stand.getBoundingBox().inflate(STAR_SEARCH_RADIUS, 0, STAR_SEARCH_RADIUS)
                        .expandTowards(0, -STAR_SEARCH_DEPTH, 0),
                m -> m != stand && !(m instanceof net.minecraft.world.entity.decoration.ArmorStand)
                        && m.isAlive());
        // The tag is drawn at the top of the stand's box: the tops are measured from there.
        double tagY = stand.getBoundingBox().maxY;
        List<StarredMobMatch.Candidate<net.minecraft.world.entity.LivingEntity>> candidates =
                new ArrayList<>(bodies.size());
        for (net.minecraft.world.entity.LivingEntity body : bodies) {
            double dx = body.getX() - stand.getX();
            double dz = body.getZ() - stand.getZ();
            candidates.add(new StarredMobMatch.Candidate<>(body,
                    Math.abs(tagY - body.getBoundingBox().maxY), Math.sqrt(dx * dx + dz * dz),
                    RealPlayers.isRealPlayerEntity(body)));
        }
        return StarredMobMatch.pick(candidates);
    }

    /** Dev probe: one log line per stand and outcome, saying which body the tag matched and why. */
    private void probeStar(net.minecraft.world.entity.decoration.ArmorStand stand, String rawName,
                           net.minecraft.world.entity.LivingEntity mob, StarredMobMatch.Outcome outcome) {
        if (!starProbeLogged.add(stand.getId() + ":" + outcome.name())) {
            return;
        }
        String type = mob == null ? "none"
                : BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath()
                + (mob instanceof net.minecraft.world.entity.player.Player ? " (fake player)" : "");
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][StarMobs] ✯ '{}' → {} ({})",
                rawName.replaceAll(String.valueOf((char) 0x00A7) + ".", ""), type, outcome.reason());
    }

    private void clearStarredMobState() {
        starVisibility.clear();
        starProbeLogged.clear();
    }

    // ---- per-tick secret tracking ------------------------------------------------------------------

    /**
     * Detects levers and chests directly and reports them through {@link DungeonEvents#fireSecretFound};
     * then hides every waypoint {@link CollectedSecrets} holds, whoever detected it - Secret Routes,
     * the secret counters (items, bats, essences), or this method a tick ago.
     */
    private void tickSecrets(LocalPlayer player, ClientLevel level) {
        if (active == null) {
            return;
        }
        for (WorldWaypoint waypoint : waypoints) {
            if (hiddenWaypoints.contains(waypoint.name())) {
                continue;
            }
            BlockState state = level.getBlockState(waypoint.world());
            if (state.getBlock() == Blocks.LEVER && state.getValue(BlockStateProperties.POWERED)) {
                DungeonEvents.fireSecretFound(new DungeonEvents.Secret("lever", waypoint.world()));
            }
        }
        boolean containerOpen = GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen;
        if (containerOpen && !containerWasOpen) {
            WorldWaypoint nearest = null;
            double best = CHEST_HIDE_DIST_SQR;
            for (WorldWaypoint waypoint : waypoints) {
                if (hiddenWaypoints.contains(waypoint.name()) || !isChest(level.getBlockState(waypoint.world()))) {
                    continue;
                }
                double distSqr = player.blockPosition().distSqr(waypoint.world());
                if (distSqr <= best) {
                    best = distSqr;
                    nearest = waypoint;
                }
            }
            if (nearest != null) {
                DungeonEvents.fireSecretFound(new DungeonEvents.Secret("chest", nearest.world()));
            }
        }
        containerWasOpen = containerOpen;

        CollectedSecrets collected = CollectedSecrets.getInstance();
        for (WorldWaypoint waypoint : waypoints) {
            if (!hiddenWaypoints.contains(waypoint.name()) && collected.isCollected(active.name(), waypoint.world())) {
                hide(waypoint.name(), waypoint.type());
            }
        }
    }

    /** The current room's unhidden secret waypoints, for the counter path ("standing" is no secret). */
    private List<BlockPos> uncollectedSecrets() {
        List<BlockPos> out = new ArrayList<>();
        if (active == null || !ConfigManager.getInstance().get().dungeons.roomWaypoints) {
            return out;
        }
        for (WorldWaypoint waypoint : waypoints) {
            if (!hiddenWaypoints.contains(waypoint.name()) && !"standing".equals(waypoint.type())) {
                out.add(waypoint.world());
            }
        }
        return out;
    }

    private static boolean isChest(BlockState state) {
        return state.getBlock() == Blocks.CHEST || state.getBlock() == Blocks.TRAPPED_CHEST;
    }

    private void hide(String name, String kind) {
        if (hiddenWaypoints.add(name)) {
            if (currentRun != null) {
                currentRun.addSecret(name); // +1 on the map HUD's found/total counter
            }
            DungeonDebug.chat("§7Secret found: §f" + name + " §8(" + kind + ")"
                    + (currentRun != null ? " §7now " + currentRun.secretsFound()
                    + "/" + (currentRun.secretsTotal() >= 0 ? currentRun.secretsTotal() : "?") : ""));
        }
    }

    // ---- developer diagnostics (room-entry only, cached for render) ---------------------------------

    private void runDiagnostics(BlockLookup lookup) {
        Map<String, DungeonRoom> database = DungeonRoomDatabase.rooms();
        if (database.isEmpty()) {
            return;
        }
        RoomMatch best = DungeonRoomMatcher.bestMatch(lookup, borders, database);
        if (best != null) {
            DungeonRoom room = database.get(best.name());
            List<BlockResult> results = DungeonRoomMatcher.blockResults(lookup, best.anchor(), best.facing(), room);
            DungeonHighlight.getInstance().setDebugScan(anchor, best.facing(), results);
        } else {
            DungeonHighlight.getInstance().clearDebugScan();
        }
    }

    // ---- accessors / state -------------------------------------------------------------------------

    public String activeRoomName() {
        return active != null ? active.name() : null;
    }

    /** The active database match (name / facing / canonical anchor), or {@code null}. */
    public RoomMatch activeMatch() {
        return active;
    }

    /** Re-resolves the active room's waypoints (call after adding a route waypoint). */
    public void refreshWaypoints() {
        if (active != null) {
            waypoints = computeWaypoints(active);
        }
    }

    public List<WorldWaypoint> waypoints() {
        return waypoints;
    }

    public boolean isHidden(String name) {
        return hiddenWaypoints.contains(name);
    }

    /**
     * The locked room's canonical anchor – the footprint's NW corner at Y 0, so relative X/Z are
     * corner-based and relative Y is the absolute world height. {@code null} when no room is locked.
     */
    public BlockPos anchor() {
        return anchor;
    }

    /** The locked room's borders, or {@code null} when no room is locked. */
    public DungeonRoomBorders.Borders borders() {
        return borders;
    }

    /**
     * The locked room's painted map colour ({@code "red"}, {@code "brown"}, …), or {@code null} when
     * the map did not rule on it. Red is the blood room – the colour alone identifies it even before
     * the database match lands, which is what the blood helper keys on.
     */
    public String mapColor() {
        return borders != null ? mapColor : null;
    }

    /** Whether the locked room came from the map+grid scheme (Hypixel constants apply). */
    public boolean locatedFromMap() {
        return borders != null && fromMap;
    }

    private void clear() {
        unlock();
        lastScanPos = null;
        lastDoorChat = null;
        wasInCatacombs = false;
        runFootprints.clear();
        lockedRooms.clear();
        clearStarredMobState();
    }
}
