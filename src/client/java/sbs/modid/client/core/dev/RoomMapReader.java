/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Reads the Hypixel dungeon map from hotbar slot 9 (index 8) – pure Vanilla 1.26.2, no Fabric API.
 *
 * <p><b>Map layout</b> (verified in-game): rooms are painted as solid colour tiles of
 * 18x18 px (Entrance–F3) or 16x16 px (F4–F7 / Master) separated by a 4 px lane; door connectors are
 * thin ~4 px bridges in the middle of a shared edge, while the segments of one multi-slot room are
 * painted <b>contiguously across the full edge</b>. The dungeon world grid is up to 6x6 slots of
 * 32 blocks (F7/M7 6x6; lower floors smaller, e.g. Entrance ≈4x4, F2/F3 ≈5x5).
 *
 * <p><b>Calibration</b> is dynamic: the always-1x1 green entrance tile gives the tile size (16/18)
 * and the pixel phase of the room grid. The player's own pixel comes from the server-set map
 * decoration (the marker Hypixel renders), with the vanilla projection only used to pick the right
 * marker when several players are on the map.
 *
 * <p><b>Room shape</b> is a cell flood fill that only crosses a boundary when the colour spans the
 * <i>full shared edge</i> (same room) – a thin centred door connector does not connect, so two
 * adjacent same-colour rooms are never merged (the old hop-only fill got this wrong).
 *
 * <p><b>Room state</b> comes from the tile centre pixels: white (34) = cleared, green (30) on a
 * non-entrance room = all secrets, red (18) on a non-blood room = failed, gray (85) / dark "?" (119)
 * = unexplored.
 */
public final class RoomMapReader {

    // --- Hypixel dungeon-map colour bytes ------------------------------------------------------
    /** Green – entrance room, and the "all secrets done" checkmark. */
    public static final byte COLOR_ENTRANCE = 30;
    /** Red – blood room, and the "failed" cross. */
    public static final byte COLOR_BLOOD = 18;
    /** Yellow – miniboss room. */
    public static final byte COLOR_MINIBOSS = 74;
    /** Purple – puzzle/quest room. */
    public static final byte COLOR_PUZZLE = 66;
    /** Orange – trap room. */
    public static final byte COLOR_TRAP = 62;
    /** Pink – fairy room. */
    public static final byte COLOR_FAIRY = 82;
    /** Brown – normal room. */
    public static final byte COLOR_NORMAL = 63;
    /** White – the "all star mobs killed" checkmark. */
    public static final byte COLOR_WHITE_CHECK = 34;
    /** Gray – unexplored room tile. */
    public static final byte COLOR_UNEXPLORED = 85;
    /** Dark gray – the "?" glyph on unexplored tiles. */
    public static final byte COLOR_QUESTION = 119;

    private static final int MAP_HOTBAR_SLOT = 8; // hotbar slot 9
    private static final int MAP_SIZE = 128;
    /** Pixel width of the lane between room tiles (door connectors are this thin). */
    private static final int CONNECTOR = 4;
    /** Dungeon grids never exceed 6x6 slots. */
    private static final int MAX_CELL_RADIUS = 5;

    /**
     * Plausible entrance-tile pixel widths across every floor's map zoom. Hypixel scales the map so
     * the whole grid fits 128px, so bigger grids (up to F7/M7 6x6) use smaller tiles: 18px on the
     * lower floors (Entrance–F3), 16px on F4+/Master were verified, but the calibration must not be
     * hard-locked to {16,18} — a floor whose zoom differs would then read as "no dungeon map". This
     * range accepts any real tile size while {@link #isEntranceSquare} still rejects glyphs.
     */
    private static final int MIN_TILE = 10;
    private static final int MAX_TILE = 20;

    /** Clear state of the room, read from the checkmark pixels on the map. */
    public enum RoomState {
        /** Entered but not cleared (no checkmark). */
        UNCLEARED,
        /** White checkmark – all star mobs killed. */
        CLEARED,
        /** Green checkmark – all secrets collected. */
        SECRETS_DONE,
        /** Red cross – puzzle failed. */
        FAILED
    }

    /**
     * The player's current room as painted on the map: segment offsets, colour, clear state, and the
     * absolute map-grid cell indices of its NW segment (stable for the whole run – used to link the
     * room to the map HUD).
     */
    public record MapRoom(List<int[]> offsets, String colorName, RoomState state, int nwCellX, int nwCellZ) {
    }

    /**
     * Kind of door connector painted on a lane between two tiles. Hypixel paints the blood-room door
     * red and wither doors black; every other painted connector is a normal open doorway.
     */
    public enum DoorType {
        /** No paint in the lane – no door on this edge. */
        NONE,
        /** A painted connector in a normal colour – an open doorway. */
        NORMAL,
        /** Black connector – a coal wither door. */
        WITHER,
        /** Red connector – the blood door. */
        BLOOD;

        public boolean present() {
            return this != NONE;
        }

        /** A key door: the coal-walled wither / blood doors (the ones the door boxes highlight). */
        public boolean keyDoor() {
            return this == WITHER || this == BLOOD;
        }
    }

    /** One painted tile on the dungeon map (absolute map-grid cell indices). */
    public record MapTile(int cellX, int cellZ, byte colour, RoomState state,
                          boolean joinEast, boolean joinSouth, DoorType doorEast, DoorType doorSouth) {

        public boolean unexplored() {
            return colour == COLOR_UNEXPLORED || colour == COLOR_QUESTION;
        }
    }

    /**
     * The whole painted dungeon map, cell-resolved, plus the player's fractional cell position and
     * the grid calibration ({@code phaseX/phaseZ/step}) so the HUD can place the live player-marker
     * decorations in the same cell frame as the tiles without re-scanning the 128x128 map per frame.
     */
    public record MapSnapshot(List<MapTile> tiles, int minCellX, int minCellZ, int maxCellX, int maxCellZ,
                              float playerCellX, float playerCellZ, int phaseX, int phaseZ, int step) {
    }

    /**
     * One player marker on the dungeon map, in the {@link MapSnapshot} cell frame: Hypixel paints a
     * decoration per party member, so this is how the map "knows" about every player – including ones
     * too far to be loaded as world entities. {@code self} is the marker nearest the local player's
     * own cell.
     */
    public record PlayerMarker(float cellX, float cellZ, float yawDeg, boolean self, String name) {
    }

    /**
     * Every player marker currently on the dungeon map, converted into the given snapshot's cell
     * frame. Reads only the (small) decoration list – no 128x128 scan – so it is cheap to call every
     * frame; the expensive calibration is reused from the cached snapshot.
     *
     * <p><b>Self is never guessed from decorations once the anchor is captured.</b> The decorations
     * are the server's laggy copy of everyone's position: after a Spirit Leap (or with a teammate
     * standing on your head) the old nearest-decoration pick tagged a TEAMMATE as "me" – green
     * marker on them, own arrow white – until the server caught up. Anchored, the local player's
     * marker is synthesised from live world math (exact, frame-smooth, lag-free) and their own
     * lagging decoration is skipped; only before the anchor exists does the nearest-pick fallback
     * run.
     */
    public static List<PlayerMarker> playerMarkers(MapSnapshot snapshot) {
        if (snapshot == null || snapshot.step() <= 0) {
            return List.of();
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return List.of();
        }
        MapItemSavedData map = dungeonMap(minecraft, player);
        if (map == null) {
            return List.of();
        }

        float[] selfCell = worldToMapCell(player.getX(), player.getZ());
        if (selfCell != null) {
            // Anchored: teammates from the decorations, minus our own lagging copy. Preferably drop
            // it by NAME (exact); only when the decorations are unnamed fall back to dropping the one
            // nearest our true cell. After a long leap ours may briefly show as a white ghost until
            // the server catches up - the green arrow (synthesised from world math) stays correct.
            String me = player.getGameProfile().name();
            java.util.Set<String> roster = sbs.modid.client.dungeons.run.model.DungeonTeamClasses.runRoster();
            List<PlayerMarker> markers = new ArrayList<>();
            boolean removedByIdentity = false;
            int ownIndex = -1;
            float ownDistance = Float.MAX_VALUE;
            for (MapDecoration decoration : map.getDecorations()) {
                if (isSelfDecoration(decoration, me)) {
                    removedByIdentity = true;
                    continue;
                }
                int dx = (decoration.x() + 128) >> 1;
                int dz = (decoration.y() + 128) >> 1;
                float cellX = (dx - snapshot.phaseX()) / (float) snapshot.step();
                float cellZ = (dz - snapshot.phaseZ()) / (float) snapshot.step();
                markers.add(new PlayerMarker(cellX, cellZ, decoration.rot() * 22.5f, false, decoIgn(decoration)));
                float distance = Math.abs(cellX - selfCell[0]) + Math.abs(cellZ - selfCell[1]);
                if (distance < ownDistance) {
                    ownDistance = distance;
                    ownIndex = markers.size() - 1;
                }
            }
            // Our own unnamed decoration: normally the one within ~a cell of our true spot - but when
            // the tab roster says every run player is accounted for among the decorations, one of
            // them MUST be us, so the nearest is dropped regardless of distance. That kills the
            // "3 markers with 2 players in the run" ghost that a lagging own decoration produced.
            boolean allAccounted = !roster.isEmpty() && markers.size() >= roster.size();
            if (!removedByIdentity && ownIndex >= 0 && (ownDistance <= 1.2f || allAccounted)) {
                markers.remove(ownIndex);
            }
            bindUnnamedMate(markers, roster, me);
            markers.add(new PlayerMarker(selfCell[0], selfCell[1], player.getYRot(), true, me));
            return List.copyOf(markers);
        }

        // Not anchored yet: OUR marker is the green one; only if none is green (unexpected map build)
        // does the old "nearest to the snapshot's player cell" guess decide.
        String me = player.getGameProfile().name();
        List<PlayerMarker> markers = new ArrayList<>();
        int bestIndex = -1;
        int selfIndex = -1;
        float bestDistance = Float.MAX_VALUE;
        for (MapDecoration decoration : map.getDecorations()) {
            int dx = (decoration.x() + 128) >> 1;
            int dz = (decoration.y() + 128) >> 1;
            float cellX = (dx - snapshot.phaseX()) / (float) snapshot.step();
            float cellZ = (dz - snapshot.phaseZ()) / (float) snapshot.step();
            markers.add(new PlayerMarker(cellX, cellZ, decoration.rot() * 22.5f, false, decoIgn(decoration)));
            if (isSelfDecoration(decoration, me)) {
                selfIndex = markers.size() - 1;
            }
            float distance = Math.abs(cellX - snapshot.playerCellX())
                    + Math.abs(cellZ - snapshot.playerCellZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = markers.size() - 1;
            }
        }
        if (selfIndex >= 0) {
            bestIndex = selfIndex;
        }
        if (bestIndex >= 0) {
            PlayerMarker mine = markers.get(bestIndex);
            // Unnamed decoration: now that it is positively ours, carry OUR ign so the HUD can
            // resolve our head from the tab list instead of drawing the fallback square.
            String name = mine.name() == null || mine.name().isEmpty() ? me : mine.name();
            markers.set(bestIndex, new PlayerMarker(mine.cellX(), mine.cellZ(), mine.yawDeg(), true, name));
        }
        return List.copyOf(markers);
    }

    /**
     * Names THE unnamed teammate marker by elimination from the tab-list run roster: when exactly one
     * marker carries no name and exactly one roster member (self excluded) is not already named by
     * some marker, they must be each other – so the marker gets that IGN, which is what lets the HUD
     * resolve the teammate's head (skin) from the tab list. Anything ambiguous is left unnamed.
     */
    private static void bindUnnamedMate(List<PlayerMarker> markers, java.util.Set<String> roster,
                                        String me) {
        if (roster.isEmpty()) {
            return;
        }
        int unnamedIndex = -1;
        int unnamedCount = 0;
        List<String> unclaimed = new ArrayList<>();
        for (String name : roster) {
            if (!name.equalsIgnoreCase(me)) {
                unclaimed.add(name);
            }
        }
        for (int i = 0; i < markers.size(); i++) {
            PlayerMarker marker = markers.get(i);
            if (marker.name() == null || marker.name().isEmpty()) {
                unnamedCount++;
                unnamedIndex = i;
            } else {
                unclaimed.removeIf(name -> name.equalsIgnoreCase(marker.name()));
            }
        }
        if (unnamedCount == 1 && unclaimed.size() == 1 && unnamedIndex >= 0) {
            PlayerMarker marker = markers.get(unnamedIndex);
            markers.set(unnamedIndex, new PlayerMarker(marker.cellX(), marker.cellZ(),
                    marker.yawDeg(), false, unclaimed.get(0)));
        }
    }

    /** Legacy result for the dev export: colour name + size string ("1x1" / "2x2" / "L (3 cells)"). */
    public record RoomInfo(String colorName, String size) {
    }

    private static final RoomInfo DEFAULT = new RoomInfo("unknown", "1x1");

    /** Grid calibration: tile size, pitch (tile + lane), grid pixel phase, and the green entrance cell. */
    private record MapGrid(MapItemSavedData map, int roomSize, int step, int phaseX, int phaseZ,
                           int greenCellX, int greenCellZ) {
    }

    // ---- world <-> map anchor (physical entrance pos idea) --------------------------------
    // The player pixel from the map decoration can drift into a neighbouring cell near tile edges,
    // which mislabels rooms. Every run starts in the entrance, so the moment the marker sits in the
    // green tile we capture the exact world<->map cell anchor – from then on the player's map cell is
    // derived purely from world coordinates (32-grid math), immune to marker drift.
    private static boolean anchored;
    private static int anchorWorldCellX;
    private static int anchorWorldCellZ;
    private static int anchorMapCellX;
    private static int anchorMapCellZ;

    // Anchor voting: a candidate correspondence must repeat over several spaced samples before it is
    // committed. A single sample can be one cell off purely from decoration lag (the marker still
    // painted in the previous room while the player already stands in the next), which used to anchor
    // the whole run 32 blocks off - and the old one-shot capture could never correct itself.
    private static final int ANCHOR_VOTES_TO_COMMIT = 3;
    private static final long ANCHOR_VOTE_GAP_MS = 300L;
    private static int anchorVoteDeltaX;
    private static int anchorVoteDeltaZ;
    private static int anchorVotes;
    private static long lastAnchorVoteAt;

    /**
     * Bumped on every committed anchor – the first capture and every self-heal correction. Anything
     * that cached a world&lt;-&gt;map correspondence (the run registry's map-cell links) must rebuild
     * it when this changes, or it keeps labelling tiles by the OLD correspondence. Monotonic for the
     * whole session, so a fresh run can never collide with a generation an observer already saw.
     */
    private static int anchorGeneration;

    private RoomMapReader() {
    }

    /** Forgets the world&lt;-&gt;map anchor (call when leaving The Catacombs). */
    public static void resetAnchor() {
        anchored = false;
        anchorVotes = 0;
    }

    /** Whether the world&lt;-&gt;map anchor has been captured (world coords map to map cells). */
    public static boolean isAnchored() {
        return anchored;
    }

    /** Changes whenever the anchor is (re-)committed – see {@link #anchorGeneration}. */
    public static int anchorGeneration() {
        return anchorGeneration;
    }

    /**
     * Converts any world position to fractional map-cell coordinates (for drawing teammates on the
     * SBS map). {@code null} until the entrance anchor has been captured.
     */
    public static float[] worldToMapCell(double x, double z) {
        if (!anchored) {
            return null;
        }
        return new float[] {
                anchorMapCellX + (float) ((x + 8.0) / 32.0 - anchorWorldCellX),
                anchorMapCellZ + (float) ((z + 8.0) / 32.0 - anchorWorldCellZ)};
    }

    /** The world grid cell (32-block slots, NW corners ≡ -8 mod 32) containing the coordinate. */
    private static int worldCell(int c) {
        return Math.floorDiv(c + 8, 32);
    }

    /**
     * Converts a world position to the <b>integer</b> map-grid cell it lies in (the frame
     * {@link MapTile} uses). {@code null} until the entrance anchor has been captured.
     */
    public static int[] worldToMapCellIndex(int x, int z) {
        if (!anchored) {
            return null;
        }
        return new int[] {
                anchorMapCellX + worldCell(x) - anchorWorldCellX,
                anchorMapCellZ + worldCell(z) - anchorWorldCellZ};
    }

    /**
     * The inverse of {@link #worldToMapCellIndex}: the world NW corner {@code {x, z}} of a map-grid
     * cell (the room interior then spans {@code corner .. corner + 30}, the seam is {@code corner + 31}).
     * {@code null} until the entrance anchor has been captured.
     */
    public static int[] cellToWorld(int cellX, int cellZ) {
        if (!anchored) {
            return null;
        }
        return new int[] {
                (cellX - anchorMapCellX + anchorWorldCellX) * 32 - 8,
                (cellZ - anchorMapCellZ + anchorWorldCellZ) * 32 - 8};
    }

    /**
     * Commits an anchor directly: the world cell containing {@code (worldX, worldZ)} is map cell
     * {@code (mapCellX, mapCellZ)}. Test seam for the conversion functions; the live anchor is only
     * ever committed by the sampler.
     */
    static void anchorAt(int worldX, int worldZ, int mapCellX, int mapCellZ) {
        anchored = true;
        anchorWorldCellX = worldCell(worldX);
        anchorWorldCellZ = worldCell(worldZ);
        anchorMapCellX = mapCellX;
        anchorMapCellZ = mapCellZ;
    }

    /** Safe colour lookup from the map byte array (returns {@code -1} out of bounds). */
    public static byte getColor(MapItemSavedData map, int x, int z) {
        if (x < 0 || z < 0 || x >= MAP_SIZE || z >= MAP_SIZE) {
            return -1;
        }
        return map.colors[x + (z << 7)];
    }

    /** Legacy wrapper for the dev export: colour + size of the current room. */
    public static RoomInfo read() {
        MapRoom room = readRoom();
        if (room == null) {
            return DEFAULT;
        }
        return new RoomInfo(room.colorName(), shapeOf(room.offsets()));
    }

    /**
     * Reads the player's current room from the dungeon map.
     *
     * @return the room (segments, colour, clear state), or {@code null} when no usable map exists or
     *         the player's tile is not painted yet (caller retries on the next move)
     */
    public static MapRoom readRoom() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return null;
        }
        MapItemSavedData map = dungeonMap(minecraft, player);
        if (map == null) {
            return null;
        }
        MapGrid grid = calibrate(map);
        if (grid == null) {
            return null;
        }
        int[] startCell = playerCell(grid, map, player);
        int ix = startCell[0];
        int iz = startCell[1];

        byte colour = tileColor(grid, ix, iz);
        if (!isRoomColor(colour) && !anchored) {
            // Before the anchor exists the cell comes from the marker pixel, which can drift into
            // the lane or a neighbour tile. Rather than reporting nothing (and leaving the room
            // size to the physical fallback), snap to the PAINTED room tile nearest the marker's
            // fractional position - the grid placement that most probably fits. Once anchored the
            // cell is exact world math, and an unpainted tile is genuinely unpainted: no snapping.
            int[] pixel = playerPixel(map, player);
            int[] snapped = nearestPaintedCell(grid,
                    (pixel[0] - grid.phaseX()) / (float) grid.step(),
                    (pixel[1] - grid.phaseZ()) / (float) grid.step(), ix, iz);
            if (snapped != null) {
                ix = snapped[0];
                iz = snapped[1];
                colour = tileColor(grid, ix, iz);
            }
        }
        if (!isRoomColor(colour)) {
            return null; // gap, unexplored or not painted yet
        }

        // Cell flood fill: cross a boundary only when the colour spans the full shared edge.
        Set<Long> visited = new HashSet<>();
        Queue<int[]> queue = new ArrayDeque<>();
        List<int[]> offsets = new ArrayList<>();
        visited.add(cellKey(ix, iz));
        queue.add(new int[] {ix, iz});
        offsets.add(new int[] {0, 0});
        RoomState state = RoomState.UNCLEARED;
        int minOffX = 0;
        int minOffZ = 0;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            state = strongest(state, tileState(grid, cell[0], cell[1], colour));
            for (int[] dir : directions) {
                int nx = cell[0] + dir[0];
                int nz = cell[1] + dir[1];
                if (Math.abs(nx - ix) > MAX_CELL_RADIUS || Math.abs(nz - iz) > MAX_CELL_RADIUS) {
                    continue;
                }
                if (tileColor(grid, nx, nz) == colour && fullEdge(grid, cell[0], cell[1], dir[0], dir[1], colour)
                        && visited.add(cellKey(nx, nz))) {
                    queue.add(new int[] {nx, nz});
                    offsets.add(new int[] {nx - ix, nz - iz});
                    minOffX = Math.min(minOffX, nx - ix);
                    minOffZ = Math.min(minOffZ, nz - iz);
                }
            }
        }
        // Exact-catalog gate: the painted blob must be a legal Hypixel shape (1x1..1x4, 2x2,
        // 3-cell corner, 4-cell L). Anything else means the fill crossed door connectors (bad grid
        // phase, glyph noise) - deliver nothing so the caller retries, rather than poisoning the
        // tracker / DB export with a merged "5x3 room" that cannot exist. Partially revealed rooms
        // pass: every connected subset of a legal shape is itself legal.
        if (!isLegalShape(offsets)) {
            logImplausibleRoom(offsets, colour);
            return null;
        }
        return new MapRoom(offsets, colorName(colour), state, ix + minOffX, iz + minOffZ);
    }

    private static long lastImplausibleLogAt;

    /** Throttled diagnostic: what the fill produced when it matched no legal room shape. */
    private static void logImplausibleRoom(List<int[]> offsets, byte colour) {
        long now = System.currentTimeMillis();
        if (now - lastImplausibleLogAt < 5_000L) {
            return;
        }
        lastImplausibleLogAt = now;
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                "[SBS][DungeonMap] illegal room shape rejected: {} ({} cells, colour {}) - "
                        + "grid phase suspect, retrying", shapeOf(offsets), offsets.size(), colour & 0xFF);
    }

    /**
     * Reads the <b>whole</b> painted dungeon map into cell tiles for the SBS map HUD: every explored /
     * unexplored room tile with its colour, checkmark state, same-room joins (full-edge fills) and door
     * connectors (thin centred bridges). {@code null} when no usable dungeon map exists.
     */
    public static MapSnapshot readSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return null;
        }
        MapItemSavedData map = dungeonMap(minecraft, player);
        if (map == null) {
            return null;
        }
        MapGrid grid = calibrate(map);
        if (grid == null) {
            return null;
        }
        List<MapTile> tiles = new ArrayList<>();
        int minCx = Integer.MAX_VALUE, minCz = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;
        for (int iz = 0; cellStartZ(grid, iz) + grid.roomSize() <= MAP_SIZE; iz++) {
            for (int ix = 0; cellStartX(grid, ix) + grid.roomSize() <= MAP_SIZE; ix++) {
                byte colour = tileColor(grid, ix, iz);
                boolean unexplored = colour == COLOR_UNEXPLORED || colour == COLOR_QUESTION;
                if (!isRoomColor(colour) && !unexplored) {
                    continue;
                }
                RoomState state = isRoomColor(colour) ? tileState(grid, ix, iz, colour) : RoomState.UNCLEARED;
                boolean joinEast = joins(grid, ix, iz, 1, 0, colour);
                boolean joinSouth = joins(grid, ix, iz, 0, 1, colour);
                DoorType doorEast = joinEast ? DoorType.NONE : doorType(laneColour(grid, ix, iz, 1, 0));
                DoorType doorSouth = joinSouth ? DoorType.NONE : doorType(laneColour(grid, ix, iz, 0, 1));
                tiles.add(new MapTile(ix, iz, colour, state, joinEast, joinSouth, doorEast, doorSouth));
                minCx = Math.min(minCx, ix);
                minCz = Math.min(minCz, iz);
                maxCx = Math.max(maxCx, ix);
                maxCz = Math.max(maxCz, iz);
            }
        }
        if (tiles.isEmpty()) {
            return null;
        }
        // Feed the anchor sampler from the snapshot path too, so the capture / self-heal also runs
        // while only the map HUD is active (readRoom is not the only anchor driver any more).
        sampleAnchor(grid, map, player);
        float playerCellX;
        float playerCellZ;
        if (anchored) {
            playerCellX = anchorMapCellX + (float) ((player.getX() + 8.0) / 32.0 - anchorWorldCellX);
            playerCellZ = anchorMapCellZ + (float) ((player.getZ() + 8.0) / 32.0 - anchorWorldCellZ);
        } else {
            int[] pixel = playerPixel(map, player);
            playerCellX = (pixel[0] - grid.phaseX()) / (float) grid.step();
            playerCellZ = (pixel[1] - grid.phaseZ()) / (float) grid.step();
        }
        return new MapSnapshot(List.copyOf(tiles), minCx, minCz, maxCx, maxCz, playerCellX, playerCellZ,
                grid.phaseX(), grid.phaseZ(), grid.step());
    }

    /**
     * The painted room tile nearest to the fractional marker position {@code (fx, fz)} among the
     * 3x3 cells around {@code (ix, iz)}, or {@code null} when none is close enough (within one cell
     * pitch of the marker). Used only before the anchor exists, to recover from marker drift into
     * the lane / a neighbouring tile.
     */
    private static int[] nearestPaintedCell(MapGrid grid, float fx, float fz, int ix, int iz) {
        // A tile occupies [i .. i + roomSize/step] in fractional cell coordinates; its centre is
        // therefore i + half the tile's fraction of the pitch (the lane makes up the rest).
        float half = grid.roomSize() / (2.0f * grid.step());
        int[] best = null;
        float bestDistance = 1.0f;   // more than a full cell away = drift cannot explain it
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = ix + dx;
                int cz = iz + dz;
                if (!isRoomColor(tileColor(grid, cx, cz))) {
                    continue;
                }
                float distance = Math.abs(cx + half - fx) + Math.abs(cz + half - fz);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new int[] {cx, cz};
                }
            }
        }
        return best;
    }

    /**
     * The player's map cell: pure world math once the anchor is captured, marker-based until then.
     * Every call also feeds the anchor sampler, so a not-yet-captured anchor can be won and – the
     * crucial part – a <b>wrongly</b> captured one heals itself instead of mislabelling every room
     * (and offsetting the player marker) by 32 blocks for the rest of the run.
     */
    private static int[] playerCell(MapGrid grid, MapItemSavedData map, LocalPlayer player) {
        BlockPos pos = player.blockPosition();
        logDecorations(map, player);
        sampleAnchor(grid, map, player);
        if (anchored) {
            return new int[] {
                    anchorMapCellX + worldCell(pos.getX()) - anchorWorldCellX,
                    anchorMapCellZ + worldCell(pos.getZ()) - anchorWorldCellZ};
        }
        SelfPixel self = selfPixel(map, player);
        int ix = Math.floorDiv(self.x() - grid.phaseX(), grid.step());
        int iz = Math.floorDiv(self.z() - grid.phaseZ(), grid.step());
        return new int[] {ix, iz};
    }

    /**
     * Samples the world&lt;-&gt;map correspondence and commits it as the anchor only after
     * {@value ANCHOR_VOTES_TO_COMMIT} consistent, spaced samples – both for the initial capture and,
     * with the same rule, to <b>correct</b> an anchor that disagrees with what the map now clearly
     * shows (the self-heal for "my marker and every room label sit one cell / 32 blocks off").
     *
     * <p>A sample is only taken when the correspondence is unambiguous: the (preferably
     * name-identified) self marker sits well inside a painted room tile – not clamped against the map
     * border – while the player stands well inside their 32-block world cell. The vote requirement is
     * what makes decoration lag harmless: a marker still catching up after a sprint or Spirit Leap
     * yields changing deltas that never accumulate votes, while a settled marker repeats the same
     * delta and wins.
     */
    private static void sampleAnchor(MapGrid grid, MapItemSavedData map, LocalPlayer player) {
        BlockPos pos = player.blockPosition();
        SelfPixel self = selfPixel(map, player);
        int px = self.x();
        int pz = self.z();
        if (px < 2 || px > MAP_SIZE - 3 || pz < 2 || pz > MAP_SIZE - 3) {
            return;   // clamped against the map border - the pixel no longer states a cell
        }
        // When self was only GUESSED from the projection (unnamed decorations), a teammate standing
        // near us could be the picked pixel - so only sample while we visibly stand alone on the map.
        // A name-identified marker is ours for certain, so it samples even in a full party.
        if (!self.identified()) {
            int nearby = 0;
            for (MapDecoration decoration : map.getDecorations()) {
                int dx = (decoration.x() + 128) >> 1;
                int dz = (decoration.y() + 128) >> 1;
                if (Math.abs(dx - px) <= 6 && Math.abs(dz - pz) <= 6) {
                    nearby++;
                }
            }
            if (nearby > 1) {
                return;   // ambiguous pick - wait for a moment without a teammate next to us
            }
        }
        int ix = Math.floorDiv(px - grid.phaseX(), grid.step());
        int iz = Math.floorDiv(pz - grid.phaseZ(), grid.step());
        int inX = px - (grid.phaseX() + ix * grid.step());
        int inZ = pz - (grid.phaseZ() + iz * grid.step());
        int margin = 3;
        if (inX < margin || inX > grid.roomSize() - margin
                || inZ < margin || inZ > grid.roomSize() - margin) {
            return;   // on a tile edge / in the lane - could belong to either neighbour
        }
        int lx = Math.floorMod(pos.getX() + 8, 32);
        int lz = Math.floorMod(pos.getZ() + 8, 32);
        if (lx < 6 || lx > 25 || lz < 6 || lz > 25) {
            return;   // near a world cell boundary - marker lag of one block could flip the cell
        }
        if (!isRoomColor(tileColor(grid, ix, iz))) {
            return;   // not standing on a painted room tile - nothing trustworthy to anchor on
        }

        int deltaX = ix - worldCell(pos.getX());
        int deltaZ = iz - worldCell(pos.getZ());
        if (anchored && deltaX == anchorMapCellX - anchorWorldCellX
                && deltaZ == anchorMapCellZ - anchorWorldCellZ) {
            anchorVotes = 0;   // the map agrees with the anchor - drop any pending correction
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAnchorVoteAt < ANCHOR_VOTE_GAP_MS) {
            return;   // spaced samples only - a lagging marker repeats itself within a burst
        }
        lastAnchorVoteAt = now;
        if (anchorVotes > 0 && deltaX == anchorVoteDeltaX && deltaZ == anchorVoteDeltaZ) {
            anchorVotes++;
        } else {
            anchorVoteDeltaX = deltaX;
            anchorVoteDeltaZ = deltaZ;
            anchorVotes = 1;
        }
        if (anchorVotes < ANCHOR_VOTES_TO_COMMIT) {
            return;
        }
        anchorVotes = 0;
        boolean corrected = anchored;
        anchored = true;
        anchorWorldCellX = worldCell(pos.getX());
        anchorWorldCellZ = worldCell(pos.getZ());
        anchorMapCellX = ix;
        anchorMapCellZ = iz;
        anchorGeneration++;
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][DungeonMap] anchor {}: world cell {},{} <-> map cell {},{}",
                corrected ? "CORRECTED" : "captured", anchorWorldCellX, anchorWorldCellZ, ix, iz);
    }

    /** Same room across this edge: same colour on both tiles plus a full-edge fill between them. */
    private static boolean joins(MapGrid grid, int ix, int iz, int dx, int dz, byte colour) {
        return tileColor(grid, ix + dx, iz + dz) == colour && fullEdge(grid, ix, iz, dx, dz, colour);
    }

    /** The paint byte in the middle of the lane between two tiles ({@code 0}/{@code -1} = no door). */
    private static byte laneColour(MapGrid grid, int ix, int iz, int dx, int dz) {
        int cx = cellStartX(grid, ix);
        int cz = cellStartZ(grid, iz);
        int mid = grid.roomSize() / 2;
        return dx != 0
                ? getColor(grid.map(), cx + grid.roomSize() + 1, cz + mid)
                : getColor(grid.map(), cx + mid, cz + grid.roomSize() + 1);
    }

    /**
     * Classifies a painted lane connector by its map colour <b>base</b> (byte / 4, robust against the
     * per-pixel shade Hypixel uses): black base 29 = wither door, red base 4 = the blood door
     * (Hypixel paints exactly these two on the vanilla dungeon map), anything else painted = a
     * normal open doorway.
     */
    private static DoorType doorType(byte lane) {
        if (lane == 0 || lane == -1) {
            return DoorType.NONE;
        }
        int base = (lane & 0xFF) >> 2;
        if (base == 29) {
            return DoorType.WITHER;
        }
        if (base == 4) {
            return DoorType.BLOOD;
        }
        return DoorType.NORMAL;
    }

    /**
     * The complete legal Hypixel room-shape catalog, applied to segment offsets: the straight runs
     * 1x1..1x4, the filled 2x2, the 3-cell corner (a 2x2 missing one cell) and the 4-cell L (a 2x3
     * whose full row of three carries the fourth cell at an <b>end</b> of the other row – a T or
     * S/Z layout is no Hypixel room). Anything else is a mis-read (bad grid phase, merged
     * neighbours) and must be rejected. Progressive map reveal is safe against this check: every
     * connected subset of a legal shape is again a legal shape.
     */
    public static boolean isLegalShape(List<int[]> offsets) {
        int n = offsets.size();
        if (n < 1 || n > 4) {
            return false;
        }
        int minIx = Integer.MAX_VALUE, minIz = Integer.MAX_VALUE, maxIx = Integer.MIN_VALUE, maxIz = Integer.MIN_VALUE;
        for (int[] off : offsets) {
            minIx = Math.min(minIx, off[0]);
            minIz = Math.min(minIz, off[1]);
            maxIx = Math.max(maxIx, off[0]);
            maxIz = Math.max(maxIz, off[1]);
        }
        int spanX = maxIx - minIx + 1;
        int spanZ = maxIz - minIz + 1;
        if (n == spanX * spanZ) {
            // Filled rectangle: any straight 1xN up to 4, or the 2x2 block.
            return (Math.min(spanX, spanZ) == 1 && Math.max(spanX, spanZ) <= 4)
                    || (spanX == 2 && spanZ == 2);
        }
        if (n == 3 && spanX == 2 && spanZ == 2) {
            return true;   // any 3 cells in a 2x2 box form the corner shape
        }
        if (n == 4 && Math.min(spanX, spanZ) == 2 && Math.max(spanX, spanZ) == 3) {
            // L: the long axis must hold a full row of three; the single leftover cell must sit at
            // one END of that run (offset 0 or 2 along the long axis), never in the middle (T).
            boolean longIsX = spanX == 3;
            int[] lineCells = new int[2];
            int lonePosition = -1;
            for (int[] off : offsets) {
                int line = longIsX ? off[1] - minIz : off[0] - minIx;
                lineCells[line]++;
            }
            int loneLine = lineCells[0] == 1 ? 0 : lineCells[1] == 1 ? 1 : -1;
            if (loneLine < 0) {
                return false;   // 2+2 split = an S/Z zigzag, not an L
            }
            for (int[] off : offsets) {
                int line = longIsX ? off[1] - minIz : off[0] - minIx;
                if (line == loneLine) {
                    lonePosition = longIsX ? off[0] - minIx : off[1] - minIz;
                }
            }
            return lonePosition == 0 || lonePosition == 2;
        }
        return false;
    }

    /** Renders segment offsets as "1x1" / "1x3" / "2x2", or "L (n cells)" when the box is not filled. */
    public static String shapeOf(List<int[]> offsets) {
        int minIx = 0, minIz = 0, maxIx = 0, maxIz = 0;
        for (int[] off : offsets) {
            minIx = Math.min(minIx, off[0]);
            minIz = Math.min(minIz, off[1]);
            maxIx = Math.max(maxIx, off[0]);
            maxIz = Math.max(maxIz, off[1]);
        }
        int cellsX = maxIx - minIx + 1;
        int cellsZ = maxIz - minIz + 1;
        if (offsets.size() >= cellsX * cellsZ) {
            return cellsX + "x" + cellsZ;
        }
        return "L (" + offsets.size() + " cells)";
    }

    // ---- map access ---------------------------------------------------------------------------------

    /**
     * Whether hotbar slot 9 holds a map item at all. Hypixel keeps the dungeon map there for the
     * whole exploration phase and takes it away in the <b>boss room</b> – so "no map in slot 9"
     * means the player is either outside a dungeon or past the room grid entirely.
     *
     * <p><b>The slot is not emptied, it is re-used</b> (confirmed by the maintainer, 2026-08-10):
     * the boss room puts a Nether Star, the Spirit Bow or the F7/M7 kit there instead. So the test
     * is "the item in slot 9 carries no map id", never "slot 9 is empty" – the second one would be
     * false in every boss room and the signal would never fire.
     *
     * <p>It is a <b>necessary but not sufficient</b> condition for the boss room on its own: the
     * map also leaves slot 9 when a player rearranges their hotbar. Callers that need "is this the
     * boss" ask {@code DungeonStateManager.phase()}, which corroborates this with a second signal.
     */
    public static boolean hasDungeonMap() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        ItemStack stack = player.getInventory().getItem(MAP_HOTBAR_SLOT);
        return stack != null && !stack.isEmpty() && stack.get(DataComponents.MAP_ID) != null;
    }

    private static MapItemSavedData dungeonMap(Minecraft minecraft, LocalPlayer player) {
        ItemStack mapItem = player.getInventory().getItem(MAP_HOTBAR_SLOT);
        if (mapItem == null || mapItem.isEmpty()) {
            return null;
        }
        MapId mapId = mapItem.get(DataComponents.MAP_ID);
        if (mapId == null) {
            return null;
        }
        MapItemSavedData map = minecraft.level.getMapData(mapId);
        if (map == null || map.colors == null || map.colors.length < MAP_SIZE * MAP_SIZE) {
            return null;
        }
        return map;
    }

    /**
     * Finds the green entrance tile (always 1x1) to calibrate tile size (16 or 18 px, in-game
     * validation) and the pixel phase of the room grid. Green checkmarks are much smaller than a room
     * tile, so their runs fail the 16/18 width check and are skipped.
     */
    private static MapGrid calibrate(MapItemSavedData map) {
        for (int z = 0; z < MAP_SIZE; z++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                if (getColor(map, x, z) != COLOR_ENTRANCE) {
                    continue;
                }
                int left = x;
                while (getColor(map, left - 1, z) == COLOR_ENTRANCE) {
                    left--;
                }
                int right = x;
                while (getColor(map, right + 1, z) == COLOR_ENTRANCE) {
                    right++;
                }
                int width = right - left + 1;
                if (isEntranceSquare(map, left, z, width)) {
                    if (width < MIN_TILE || width > MAX_TILE) {
                        logUnexpectedTile(width);   // a filled green tile of an unforeseen zoom
                    } else {
                        int top = z;
                        while (getColor(map, left, top - 1) == COLOR_ENTRANCE) {
                            top--;
                        }
                        int step = width + CONNECTOR;
                        int phaseX = Math.floorMod(left, step);
                        int phaseZ = Math.floorMod(top, step);
                        return new MapGrid(map, width, step, phaseX, phaseZ,
                                Math.floorDiv(left - phaseX, step), Math.floorDiv(top - phaseZ, step));
                    }
                }
                x = right; // a green checkmark or noise – skip this run and keep scanning
            }
        }
        return null;
    }

    private static long lastTileLogAt;

    /** Surfaces an entrance tile whose zoom is outside the accepted range, so a new floor size shows up. */
    private static void logUnexpectedTile(int width) {
        long now = System.currentTimeMillis();
        if (now - lastTileLogAt < 10_000L) {
            return;
        }
        lastTileLogAt = now;
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                "[SBS][DungeonMap] entrance tile {}px is outside the accepted {}-{}px range - "
                        + "map calibration skipped; widen MIN_TILE/MAX_TILE if this is a real floor zoom",
                width, MIN_TILE, MAX_TILE);
    }

    /**
     * Whether the green run at {@code (left, top)} really is the entrance <b>tile</b> and
     * not some other green paint (a big secrets checkmark, or glyph noise). A wrong lock here shifts
     * the whole grid phase, after which the corner/lane sampling reads the wrong pixels everywhere
     * and the room flood fill merges whole blocks of separate rooms – the "1x3 room detected as 5x3"
     * failure. The tile must be a filled square: its bottom row spans the same width, and the pixels
     * just outside all four sides – probed at 1px-inset corner rows/columns, which a centred green
     * door connector never touches – are NOT entrance-green, pinning the exact bounds.
     */
    private static boolean isEntranceSquare(MapItemSavedData map, int left, int top, int width) {
        int right = left + width - 1;
        int bottom = top + width - 1;
        // Bottom row of the square exists at both ends (a checkmark glyph is never a filled square).
        if (getColor(map, left, bottom) != COLOR_ENTRANCE
                || getColor(map, right, bottom) != COLOR_ENTRANCE) {
            return false;
        }
        // Exact bounds: just outside each side must NOT be green. Probed at +1-inset corner rows /
        // columns so the (centred, ~4px) green door connectors of the entrance never interfere.
        return getColor(map, left - 1, top + 1) != COLOR_ENTRANCE
                && getColor(map, right + 1, top + 1) != COLOR_ENTRANCE
                && getColor(map, left + 1, top - 1) != COLOR_ENTRANCE
                && getColor(map, left + 1, bottom + 1) != COLOR_ENTRANCE;
    }

    /** The self marker's pixel plus whether it was positively identified (by IGN) vs projection-guessed. */
    private record SelfPixel(int x, int z, boolean identified) {
    }

    /**
     * The player's pixel on the map, from the server-set decoration Hypixel renders. Decoration bytes
     * are half-pixels: {@code pixel = (b + 128) / 2}.
     */
    private static int[] playerPixel(MapItemSavedData map, LocalPlayer player) {
        SelfPixel self = selfPixel(map, player);
        return new int[] {self.x(), self.z()};
    }

    /**
     * Locates the LOCAL player's marker among the dungeon-map decorations: the one Hypixel paints
     * with the green {@code frame} sprite (or, on builds that name their decorations, the one named
     * with our IGN) – the exact green marker, no matter how many blue teammate markers crowd around
     * it. That is the whole fix for "on a full party the map thinks a teammate is me": the old code
     * fell back to the decoration nearest the vanilla projection, which grabs a teammate standing
     * closer (or after a Spirit Leap) and then anchors the entire run off that wrong pixel. Only
     * when nothing identifies us does the nearest-projection guess still run.
     */
    private static SelfPixel selfPixel(MapItemSavedData map, LocalPlayer player) {
        String me = player.getGameProfile().name();
        MapDecoration solo = null;
        int count = 0;
        for (MapDecoration decoration : map.getDecorations()) {
            count++;
            solo = decoration;
            if (isSelfDecoration(decoration, me)) {
                return new SelfPixel((decoration.x() + 128) >> 1, (decoration.y() + 128) >> 1, true);
            }
        }
        if (count == 1 && solo != null) {
            return new SelfPixel((solo.x() + 128) >> 1, (solo.y() + 128) >> 1, false);
        }
        // Nothing identified us: the old nearest-to-projection fallback (kept for unnamed, non-green
        // decorations - it stays "unidentified", so the anchor sampler keeps its lone-player guard).
        BlockPos pos = player.blockPosition();
        int scaleFactor = 1 << map.scale;
        int approxX = clampPixel((pos.getX() - map.centerX) / scaleFactor + 64);
        int approxZ = clampPixel((pos.getZ() - map.centerZ) / scaleFactor + 64);
        int bestX = approxX;
        int bestZ = approxZ;
        int bestDistance = Integer.MAX_VALUE;
        for (MapDecoration decoration : map.getDecorations()) {
            int dx = (decoration.x() + 128) >> 1;
            int dz = (decoration.y() + 128) >> 1;
            int distance = Math.abs(dx - approxX) + Math.abs(dz - approxZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestX = dx;
                bestZ = dz;
            }
        }
        return new SelfPixel(bestX, bestZ, false);
    }

    /** Exactly the characters a Minecraft IGN may contain, bounded to its 2..16 length. */
    private static final java.util.regex.Pattern IGN_TOKEN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]{2,16}");

    /**
     * A decoration's player IGN, or {@code ""}. Colour codes and a leading rank/level tag
     * ("[MVP+] " / "[441] ") are stripped, then the <b>first IGN-shaped token</b> is taken – which
     * also drops the decorations Hypixel names with the full tab string ("Name ✦ (Tank XLIV)"):
     * glyphs and the class suffix are not IGN characters, so the token is exactly the name.
     */
    static String decoIgn(MapDecoration decoration) {
        return decoration.name()
                .map(component -> {
                    String raw = component.getString()
                            .replaceAll("§.", "")
                            .replaceAll("^\\[[^\\]]*\\]\\s*", "");
                    java.util.regex.Matcher token = IGN_TOKEN.matcher(raw);
                    return token.find() ? token.group() : "";
                })
                .orElse("");
    }

    /**
     * Whether a decoration's name is the given IGN. Colour codes and a leading rank tag ("[MVP+] ")
     * are stripped first, so a decoration named with the player's full chat name still matches.
     */
    private static boolean nameMatches(MapDecoration decoration, String ign) {
        String name = decoIgn(decoration);
        return !name.isEmpty() && name.equalsIgnoreCase(ign);
    }

    /**
     * The sprite Hypixel gives the <b>local</b> player's dungeon-map marker: the vanilla green
     * {@code frame} icon (centre pixels #00FF4C), while every teammate gets {@code blue_marker}
     * (#5775E0). Map data is per-client, so exactly one decoration is ever green. Looked up per call
     * rather than held in a static, so loading this class never touches the decoration registry.
     */
    private static Identifier selfSprite() {
        return MapDecorationTypes.FRAME.value().assetId();
    }

    /**
     * Whether a decoration is the local player's marker. The <b>sprite</b> is the definitive signal
     * and is checked first: Hypixel leaves the dungeon-map decorations unnamed, so the old
     * name-then-nearest logic fell straight through to "whichever marker is closest" and happily
     * declared a teammate to be us (green ring on them, our own marker white) whenever one stood
     * nearby or we had just leaped. The name check stays as a second signal for the map builds that
     * do name their decorations.
     */
    static boolean isSelfDecoration(MapDecoration decoration, String ign) {
        return selfSprite().equals(decoration.getSpriteLocation()) || nameMatches(decoration, ign);
    }

    private static long lastMarkerLogAt;

    /**
     * Throttled diagnostic (~10s) while several markers are on the map: every decoration's sprite id,
     * name and rotation, and whether it matched our IGN. Confirms how Hypixel tags the self marker so
     * the name match can be pinned (or moved to the sprite/type) from a live party run.
     */
    private static void logDecorations(MapItemSavedData map, LocalPlayer player) {
        long now = System.currentTimeMillis();
        if (now - lastMarkerLogAt < 10_000L) {
            return;
        }
        String me = player.getGameProfile().name();
        int count = 0;
        StringBuilder sb = new StringBuilder();
        for (MapDecoration decoration : map.getDecorations()) {
            count++;
            String name = decoration.name().map(c -> c.getString().replaceAll("§.", "").trim()).orElse("");
            sb.append('[').append(decoration.getSpriteLocation()).append(" name=\"").append(name)
                    .append("\" rot=").append(decoration.rot())
                    .append(selfSprite().equals(decoration.getSpriteLocation()) ? " <-SELF(green)"
                            : nameMatches(decoration, me) ? " <-SELF(name)" : "")
                    .append("] ");
        }
        if (count > 1) {
            lastMarkerLogAt = now;
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonMarker] {} markers, me=\"{}\": {}",
                    count, me, sb.toString().trim());
        }
    }

    // ---- tile sampling --------------------------------------------------------------------------------

    private static int cellStartX(MapGrid grid, int ix) {
        return grid.phaseX() + ix * grid.step();
    }

    private static int cellStartZ(MapGrid grid, int iz) {
        return grid.phaseZ() + iz * grid.step();
    }

    /**
     * The room colour of a tile, decided by <b>majority over all four inset corners</b>. The centred
     * glyphs (white/green checkmarks, the failed red X, the "?") can reach any single sample point
     * on a 16px tile, and one glyph pixel then mis-colours the whole room – a puzzle whose sampled
     * corner caught a red X pixel read as a blood room. A glyph is never big enough to cover all
     * four corners, so the most frequent <i>meaningful</i> colour (room or unexplored – glyph-only
     * bytes never name a tile) wins; with no meaningful sample at all the NW probe decides as before.
     */
    private static byte tileColor(MapGrid grid, int ix, int iz) {
        int cx = cellStartX(grid, ix);
        int cz = cellStartZ(grid, iz);
        int near = 2;
        int far = grid.roomSize() - 3;
        byte[] samples = {
                getColor(grid.map(), cx + near, cz + near),
                getColor(grid.map(), cx + far, cz + near),
                getColor(grid.map(), cx + near, cz + far),
                getColor(grid.map(), cx + far, cz + far)};
        byte best = samples[0];
        int bestCount = -1;
        for (byte candidate : samples) {
            if (!isRoomColor(candidate) && candidate != COLOR_UNEXPLORED && candidate != COLOR_QUESTION) {
                continue;
            }
            int count = 0;
            for (byte sample : samples) {
                if (sample == candidate) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }

    /** The clear state of a tile, read from its centre pixel where checkmarks are drawn. */
    private static RoomState tileState(MapGrid grid, int ix, int iz, byte roomColour) {
        byte centre = getColor(grid.map(), cellStartX(grid, ix) + grid.roomSize() / 2,
                cellStartZ(grid, iz) + grid.roomSize() / 2);
        if (centre == COLOR_WHITE_CHECK) {
            return RoomState.CLEARED;
        }
        if (centre == COLOR_ENTRANCE && roomColour != COLOR_ENTRANCE) {
            return RoomState.SECRETS_DONE;
        }
        if (centre == COLOR_BLOOD && roomColour != COLOR_BLOOD) {
            return RoomState.FAILED;
        }
        return RoomState.UNCLEARED;
    }

    /**
     * Does the room colour span the <b>full shared edge</b> between a cell and its neighbour? One
     * room's segments are painted contiguously across the whole edge, while a door between two rooms
     * is only a thin centred connector – so sampling the lane near both edge ends tells them apart.
     */
    private static boolean fullEdge(MapGrid grid, int ix, int iz, int dx, int dz, byte colour) {
        int cx = cellStartX(grid, ix);
        int cz = cellStartZ(grid, iz);
        int nearEnd = 1;
        int farEnd = grid.roomSize() - 2;
        if (dx != 0) {
            int gapX = dx > 0 ? cx + grid.roomSize() + 1 : cx - 3;
            return getColor(grid.map(), gapX, cz + nearEnd) == colour
                    && getColor(grid.map(), gapX, cz + farEnd) == colour;
        }
        int gapZ = dz > 0 ? cz + grid.roomSize() + 1 : cz - 3;
        return getColor(grid.map(), cx + nearEnd, gapZ) == colour
                && getColor(grid.map(), cx + farEnd, gapZ) == colour;
    }

    private static boolean isRoomColor(byte colour) {
        return switch (colour) {
            case COLOR_ENTRANCE, COLOR_BLOOD, COLOR_MINIBOSS, COLOR_PUZZLE,
                 COLOR_TRAP, COLOR_FAIRY, COLOR_NORMAL -> true;
            default -> false;
        };
    }

    private static RoomState strongest(RoomState a, RoomState b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static long cellKey(int ix, int iz) {
        return ((long) ix << 32) ^ (iz & 0xFFFFFFFFL);
    }

    private static int clampPixel(int value) {
        return Math.max(0, Math.min(MAP_SIZE - 1, value));
    }

    /** Maps a Hypixel dungeon-map colour byte to a readable colour word for the JSON export. */
    private static String colorName(byte colour) {
        return switch (colour) {
            case COLOR_ENTRANCE -> "green";
            case COLOR_BLOOD -> "red";
            case COLOR_MINIBOSS -> "yellow";
            case COLOR_PUZZLE -> "purple";
            case COLOR_TRAP -> "orange";
            case COLOR_FAIRY -> "pink";
            case COLOR_NORMAL -> "brown";
            default -> "color_" + (colour & 0xFF);
        };
    }
}
