/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.dev.RoomMapReader;
import sbs.modid.client.dungeons.run.model.DungeonState;
import sbs.modid.client.dungeons.run.render.DungeonHighlight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Boxes the <b>next wither / blood door</b> of a Catacombs run from anywhere in the dungeon, in the
 * "locked" colour until anyone in the party has the matching key, then in the "key" colour.
 *
 * <p><b>Which door.</b> {@link NextDoor} reads the dungeon map: the closed key connectors on the
 * explored frontier, nearest first. Opened doors are remembered per run (see {@link #opened}) so the
 * highlight moves on as soon as a door is opened, whatever the map still paints.
 *
 * <p><b>Key state.</b> The key is shared by the whole party: a pickup by any member arms it, the next
 * door opened by anyone consumes it. Wither and blood keys are tracked separately; {@link DoorKeyChat}
 * classifies the lines.
 *
 * <p><b>Detection near the player.</b> Wither doors are solid {@link Blocks#COAL_BLOCK} plugs; the
 * blood door is red hardened clay (red terracotta). The scanner sweeps the current room's footprint
 * (or a radius around the player) along the 32-grid seams, clusters the hits per material and
 * verifies each cluster as a door position against the map connector. A scanned cluster gives the
 * exact box of a door and replaces the map-derived estimate; a door whose spot was swept with no
 * coal left counts as opened.
 *
 * <p>Runs off the client tick via {@code GuiTrackingMixin}, gated hard on the Catacombs scoreboard;
 * the boxes are drawn by {@link DungeonHighlight}. Nothing here scans in the render pass. The info is
 * the same the minimap already shows; the box only points at it.
 */
public final class WitherDoorTracker {

    private static final WitherDoorTracker INSTANCE = new WitherDoorTracker();

    private static final long SCAN_MS = 350L;
    /** Fallback horizontal search radius around the player when no room is locked (doorway / dev). */
    private static final int RADIUS = 16;
    /** Blocks past a room's painted edge to still sweep, so the seam doors just outside it are caught. */
    private static final int ROOM_EDGE_MARGIN = 4;
    /** Hard cap on how far from the player a room sweep may reach (guards a mis-detected footprint). */
    private static final int MAX_ROOM_REACH = 100;
    /** A cluster needs at least this many coal blocks to count as a door (rejects décor coal). */
    private static final int MIN_CLUSTER = 6;
    /** Coal blocks within this Chebyshev distance belong to the same door. */
    private static final int CLUSTER_GAP = 2;
    /** Safety net: forget a held key after this long (a key is normally used within minutes). */
    private static final long KEY_TIMEOUT_MS = 10 * 60_000L;
    /** Cap on collected coal positions, so a pathological area can never blow up the clustering. */
    private static final int MAX_COAL = 400;
    /** How close (map cells) a named player's marker must be to a door to be credited with opening it. */
    private static final double OPENER_REACH_CELLS = 1.0;

    /**
     * One boxed door: its bounding box (inclusive), whether it is drawn in the key colour, and whether
     * it is the next door (full strength, pointer line) or another known door (faint).
     */
    public record DoorBox(BlockPos min, BlockPos max, boolean keyColour, boolean next) {
    }

    /** A scanned, position-verified block cluster; {@code edge} is its map edge or {@code null} unanchored. */
    private record Cluster(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean blood, Long edge) {
    }

    private volatile List<DoorBox> doorBoxes = List.of();
    private long lastScanAt;
    private boolean witherKey;
    private long witherKeyAt;
    private boolean bloodKey;
    private long bloodKeyAt;
    /** Door edges ({@link NextDoor#edgeKey}) opened this run. Cleared when the Catacombs are left. */
    private final Set<Long> opened = new HashSet<>();
    /** The last ranked frontier, for crediting an "opened a WITHER door" line to a door. */
    private List<NextDoor.Door> lastRanked = List.of();
    private Long lastNextEdge;

    private WitherDoorTracker() {
    }

    public static WitherDoorTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** The doors to draw this frame (read by {@link DungeonHighlight}); empty when the feature is idle. */
    public List<DoorBox> doorBoxes() {
        return doorBoxes;
    }

    // ------------------------------------------------------------------ chat

    /** Every chat line: party-wide key pickups arm the key colour, door openings consume the key. */
    public void onChat(String text) {
        if (!cfg().witherDoors) {
            return;
        }
        DoorKeyChat.Event event = DoorKeyChat.parse(text);
        long now = System.currentTimeMillis();
        switch (event.kind()) {
            case WITHER_KEY -> {
                witherKey = true;
                witherKeyAt = now;
            }
            case BLOOD_KEY -> {
                bloodKey = true;
                bloodKeyAt = now;
            }
            case WITHER_OPENED -> {
                witherKey = false;
                creditOpener(event.player());
            }
            case BLOOD_OPENED -> {
                bloodKey = false;
                // One blood door per run: every blood connector is done with.
                for (NextDoor.Door door : lastRanked) {
                    if (door.type() == RoomMapReader.DoorType.BLOOD) {
                        opened.add(door.edge());
                    }
                }
            }
            default -> {
                return;
            }
        }
        lastScanAt = 0L;   // re-evaluate on the next tick rather than up to SCAN_MS later
    }

    /**
     * Marks the wither door nearest to the opener's map marker as opened, when the marker is within
     * {@value #OPENER_REACH_CELLS} cells of one. A line whose opener has no named marker credits
     * nothing; the coal scan or the map catches that door later.
     */
    private void creditOpener(String name) {
        RoomMapReader.MapSnapshot snapshot = DungeonState.getInstance().snapshot();
        if (name == null || snapshot == null || lastRanked.isEmpty()) {
            return;
        }
        List<NextDoor.Door> wither = new ArrayList<>();
        for (NextDoor.Door door : lastRanked) {
            if (door.type() == RoomMapReader.DoorType.WITHER) {
                wither.add(door);
            }
        }
        for (RoomMapReader.PlayerMarker marker : RoomMapReader.playerMarkers(snapshot)) {
            if (name.equalsIgnoreCase(marker.name())) {
                NextDoor.Door door = NextDoor.nearest(wither, marker.cellX(), marker.cellZ(), OPENER_REACH_CELLS);
                if (door != null) {
                    opened.add(door.edge());
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ tick

    /** Called once per client tick (throttled scan inside). */
    public void tick(Minecraft minecraft) {
        SBSConfig.DungeonsSettings cfg = cfg();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!cfg.witherDoors || player == null || level == null || !DungeonScoreboard.isInCatacombs()) {
            resetRun();
            return;
        }
        // The slot-9 map gate also stops the scan in the boss room: no doors exist there, only décor
        // coal that would get boxed. The run state survives a moment without the map.
        if (!RoomMapReader.hasDungeonMap()) {
            clearBoxes();
            return;
        }
        long now = System.currentTimeMillis();
        if (witherKey && now - witherKeyAt > KEY_TIMEOUT_MS) {
            witherKey = false;   // stale safety net
        }
        if (bloodKey && now - bloodKeyAt > KEY_TIMEOUT_MS) {
            bloodKey = false;
        }
        if (now - lastScanAt < SCAN_MS) {
            return;
        }
        lastScanAt = now;
        doorBoxes = build(level, player.blockPosition(), cfg.witherDoorsShowAll);
    }

    private void clearBoxes() {
        if (!doorBoxes.isEmpty()) {
            doorBoxes = List.of();
        }
    }

    /** Forgets everything about the run: boxes, keys, opened doors. */
    private void resetRun() {
        clearBoxes();
        witherKey = false;
        bloodKey = false;
        opened.clear();
        lastRanked = List.of();
        lastNextEdge = null;
    }

    private List<DoorBox> build(ClientLevel level, BlockPos player, boolean showAll) {
        Sweep sweep = sweepArea(player);
        Map<Long, RoomMapReader.MapTile> mapTiles = mapTilesByCell();
        List<Cluster> clusters = scan(level, sweep, mapTiles);

        RoomMapReader.MapSnapshot snapshot = RoomMapReader.isAnchored() ? DungeonState.getInstance().snapshot() : null;
        if (snapshot == null) {
            lastRanked = List.of();
            return fromScanOnly(clusters, player, showAll);
        }

        // A frontier door whose spot was just swept with no coal / red clay left has been opened.
        // The estimated box must lie well inside the sweep, so a door cut by the sweep's edge (and
        // therefore too small a cluster) is never mistaken for an opened one.
        if (!lastScanTruncated) {
            Set<Long> scannedEdges = new HashSet<>();
            for (Cluster cluster : clusters) {
                scannedEdges.add(cluster.edge());
            }
            for (NextDoor.Door door : NextDoor.closedFrontier(snapshot, opened)) {
                int[] box = NextDoor.worldBox(door);
                if (box == null || scannedEdges.contains(door.edge())) {
                    continue;
                }
                boolean swept = sweep.contains(box[0] - 2, box[2] - 2) && sweep.contains(box[3] + 2, box[5] + 2);
                if (swept && level.hasChunkAt(new BlockPos((box[0] + box[3]) / 2, box[1], (box[2] + box[5]) / 2))) {
                    opened.add(door.edge());
                }
            }
        }

        List<NextDoor.Door> ranked = NextDoor.ranked(snapshot, opened, bloodKey);
        lastRanked = List.copyOf(ranked);
        logNext(ranked.isEmpty() ? null : ranked.get(0));
        if (ranked.isEmpty()) {
            // Nothing on the map frontier: still box a closed door standing in front of the player,
            // so a map that does not paint the frontier the way NextDoor expects degrades to the
            // in-room boxes rather than to nothing.
            List<Cluster> closed = new ArrayList<>();
            for (Cluster cluster : clusters) {
                if (!opened.contains(cluster.edge())) {
                    closed.add(cluster);
                }
            }
            return fromScanOnly(closed, player, showAll);
        }

        Map<Long, Cluster> byEdge = new HashMap<>();
        for (Cluster cluster : clusters) {
            byEdge.put(cluster.edge(), cluster);
        }
        List<DoorBox> boxes = new ArrayList<>();
        for (int i = 0; i < ranked.size() && (i == 0 || showAll); i++) {
            NextDoor.Door door = ranked.get(i);
            boolean key = door.type() == RoomMapReader.DoorType.BLOOD ? bloodKey : witherKey;
            Cluster scanned = byEdge.remove(door.edge());
            if (scanned != null) {
                boxes.add(box(scanned, key, i == 0));
                continue;
            }
            int[] b = NextDoor.worldBox(door);
            if (b != null) {
                boxes.add(new DoorBox(new BlockPos(b[0], b[1], b[2]), new BlockPos(b[3], b[4], b[5]), key, i == 0));
            }
        }
        if (showAll) {
            // Closed doors in view that are not on the frontier (both sides already explored).
            for (Cluster cluster : byEdge.values()) {
                if (!opened.contains(cluster.edge())) {
                    boxes.add(box(cluster, keyFor(cluster), false));
                }
            }
        }
        return List.copyOf(boxes);
    }

    /** Before the map anchor exists: the nearest scanned door is the next one, the rest faint. */
    private List<DoorBox> fromScanOnly(List<Cluster> clusters, BlockPos player, boolean showAll) {
        Cluster nearest = null;
        double best = Double.MAX_VALUE;
        for (Cluster cluster : clusters) {
            double dx = (cluster.minX() + cluster.maxX()) / 2.0 - player.getX();
            double dz = (cluster.minZ() + cluster.maxZ()) / 2.0 - player.getZ();
            double d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                nearest = cluster;
            }
        }
        List<DoorBox> boxes = new ArrayList<>();
        for (Cluster cluster : clusters) {
            boolean next = cluster == nearest;
            if (next || showAll) {
                boxes.add(box(cluster, keyFor(cluster), next));
            }
        }
        return List.copyOf(boxes);
    }

    private boolean keyFor(Cluster cluster) {
        return cluster.blood() ? bloodKey : witherKey;
    }

    private static DoorBox box(Cluster c, boolean key, boolean next) {
        return new DoorBox(new BlockPos(c.minX(), c.minY(), c.minZ()), new BlockPos(c.maxX(), c.maxY(), c.maxZ()),
                key, next);
    }

    private void logNext(NextDoor.Door next) {
        Long edge = next == null ? null : next.edge();
        if (java.util.Objects.equals(edge, lastNextEdge)) {
            return;
        }
        lastNextEdge = edge;
        if (next == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dungeon] next door: none on the frontier ({} opened)",
                    opened.size());
        } else {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dungeon] next door: {} on map cell {},{} {} ({} opened)",
                    next.type(), next.cellX(), next.cellZ(), next.east() ? "east" : "south", opened.size());
        }
    }

    // ------------------------------------------------------------------ scan

    /** The blood door's material: red hardened clay (red terracotta since the flattening). */
    private static final Block BLOOD_DOOR_BLOCK = Blocks.DYED_TERRACOTTA.pick(DyeColor.RED);

    /** The horizontal rectangle swept this scan. */
    private record Sweep(int minX, int maxX, int minZ, int maxZ) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /**
     * The whole current room footprint (plus a margin for the seam doors just past its edge), so
     * every door on the room's perimeter is measured exactly. Without a locked room (doorway,
     * unpainted tile, dev mode without a map) a radius around the player.
     */
    private static Sweep sweepArea(BlockPos player) {
        int px = player.getX();
        int pz = player.getZ();
        DungeonRoomBorders.Borders room = DungeonRoomTracker.getInstance().borders();
        if (room != null) {
            return new Sweep(
                    Math.max(room.min().getX() - ROOM_EDGE_MARGIN, px - MAX_ROOM_REACH),
                    Math.min(room.max().getX() + ROOM_EDGE_MARGIN, px + MAX_ROOM_REACH),
                    Math.max(room.min().getZ() - ROOM_EDGE_MARGIN, pz - MAX_ROOM_REACH),
                    Math.min(room.max().getZ() + ROOM_EDGE_MARGIN, pz + MAX_ROOM_REACH));
        }
        return new Sweep(px - RADIUS, px + RADIUS, pz - RADIUS, pz + RADIUS);
    }

    /** Whether the last scan hit {@link #MAX_COAL}: its absence of coal then proves nothing. */
    private boolean lastScanTruncated;

    private List<Cluster> scan(ClientLevel level, Sweep sweep, Map<Long, RoomMapReader.MapTile> mapTiles) {
        // Collect the two door materials, but only in the band along the 32-grid seams where doors
        // live (see onSeam): sweeping a whole room stays cheap, and décor coal in the room interior
        // can never fill the position budget before a real door is reached. Kept apart per material
        // so coal and red clay never merge into one cluster.
        List<int[]> coal = new ArrayList<>();
        List<int[]> redClay = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = sweep.minX(); x <= sweep.maxX(); x++) {
            for (int z = sweep.minZ(); z <= sweep.maxZ(); z++) {
                if (!(onSeam(x) || onSeam(z))) {
                    continue;
                }
                for (int y = DOOR_BAND_MIN_Y; y <= DOOR_BAND_MAX_Y; y++) {
                    var state = level.getBlockState(cursor.set(x, y, z));
                    if (state.is(Blocks.COAL_BLOCK) && coal.size() < MAX_COAL) {
                        coal.add(new int[] {x, y, z});
                    } else if (state.is(BLOOD_DOOR_BLOCK) && redClay.size() < MAX_COAL) {
                        redClay.add(new int[] {x, y, z});
                    }
                }
            }
        }
        lastScanTruncated = coal.size() >= MAX_COAL || redClay.size() >= MAX_COAL;
        List<Cluster> clusters = new ArrayList<>();
        verify(cluster(coal), false, mapTiles, clusters);
        verify(cluster(redClay), true, mapTiles, clusters);
        return clusters;
    }

    /** Within 3 blocks of a 32-grid seam line on this axis - the band every wither/blood door sits in. */
    private static boolean onSeam(int c) {
        int l = Math.floorMod(c + DungeonRoomLocator.GRID_SHIFT, DungeonRoomLocator.GRID);
        return l <= 3 || l >= DungeonRoomLocator.GRID - 3;
    }

    /** Clusters block positions: each contiguous blob is one door. Greedy union by Chebyshev proximity. */
    private static List<List<int[]>> cluster(List<int[]> positions) {
        List<List<int[]>> clusters = new ArrayList<>();
        for (int[] pos : positions) {
            List<int[]> home = null;
            for (List<int[]> cluster : clusters) {
                for (int[] member : cluster) {
                    if (Math.abs(member[0] - pos[0]) <= CLUSTER_GAP
                            && Math.abs(member[1] - pos[1]) <= CLUSTER_GAP
                            && Math.abs(member[2] - pos[2]) <= CLUSTER_GAP) {
                        home = cluster;
                        break;
                    }
                }
                if (home != null) {
                    break;
                }
            }
            if (home == null) {
                home = new ArrayList<>();
                clusters.add(home);
            }
            home.add(pos);
        }
        return clusters;
    }

    /** Verifies each cluster as a door position (see {@link #doorEdge}) and keeps the real ones. */
    private static void verify(List<List<int[]>> clusters, boolean blood, Map<Long, RoomMapReader.MapTile> mapTiles,
                               List<Cluster> out) {
        for (List<int[]> cluster : clusters) {
            if (cluster.size() < MIN_CLUSTER) {
                continue;
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int[] pos : cluster) {
                minX = Math.min(minX, pos[0]);
                minY = Math.min(minY, pos[1]);
                minZ = Math.min(minZ, pos[2]);
                maxX = Math.max(maxX, pos[0]);
                maxY = Math.max(maxY, pos[1]);
                maxZ = Math.max(maxZ, pos[2]);
            }
            if (maxY < DOOR_BAND_MIN_Y || minY > DOOR_BAND_MAX_Y) {
                continue;
            }
            long edge = doorEdge((minX + maxX) / 2, (minZ + maxZ) / 2, mapTiles);
            if (edge == NOT_A_DOOR) {
                continue;   // décor coal: not on a grid edge, or the map paints no key door there
            }
            out.add(new Cluster(minX, minY, minZ, maxX, maxY, maxZ, blood, edge == UNANCHORED ? null : edge));
        }
    }

    // ------------------------------------------------------------------ door verification

    /** Hypixel doorways sit at the fixed Y 66..73 band; a little slack for the coal wall's frame. */
    private static final int DOOR_BAND_MIN_Y = 64;
    private static final int DOOR_BAND_MAX_Y = 76;
    /** {@link #doorEdge} results that are not an edge key: not a door / a door with no map anchor yet. */
    private static final long NOT_A_DOOR = Long.MIN_VALUE;
    private static final long UNANCHORED = Long.MIN_VALUE + 1;

    /**
     * The map edge ({@link NextDoor#edgeKey}) of a cluster centre, or {@link #NOT_A_DOOR} when the cluster is
     * not a door: its centre must lie on exactly one 32-grid gap line (doors bridge the 1-block void
     * seam between two room cells – décor coal sits inside a room) and, when the map already paints
     * that cell edge, the map must show a wither/blood connector there. {@link #UNANCHORED} while the
     * map is not anchored and only the geometric check is possible.
     */
    private static long doorEdge(int centerX, int centerZ, Map<Long, RoomMapReader.MapTile> mapTiles) {
        // Gap columns satisfy floorMod(c + 8, 32) == 31; ±2 tolerance because the coal plug is a
        // few blocks deep, so an asymmetric cluster centre can sit just beside the gap column.
        int lx = Math.floorMod(centerX + DungeonRoomLocator.GRID_SHIFT, DungeonRoomLocator.GRID);
        int lz = Math.floorMod(centerZ + DungeonRoomLocator.GRID_SHIFT, DungeonRoomLocator.GRID);
        boolean onGapX = lx >= 29 || lx <= 1;
        boolean onGapZ = lz >= 29 || lz <= 1;
        if (onGapX == onGapZ) {
            return NOT_A_DOOR;   // inside a cell, or on a cell corner - no door is ever there
        }
        // The connector is stored on the west (X doors) / north (Z doors) tile of the shared edge.
        // Sample 4 blocks to each side of the gap so the tolerance above cannot flip the cell.
        int[] cell = onGapX
                ? RoomMapReader.worldToMapCellIndex(centerX - 4, centerZ)
                : RoomMapReader.worldToMapCellIndex(centerX, centerZ - 4);
        if (cell == null) {
            return UNANCHORED;
        }
        if (mapTiles != null) {
            RoomMapReader.MapTile tile = mapTiles.get(cellKey(cell[0], cell[1]));
            if (tile != null && !(onGapX ? tile.doorEast() : tile.doorSouth()).keyDoor()) {
                return NOT_A_DOOR;
            }
        }
        return NextDoor.edgeKey(cell[0], cell[1], onGapX);
    }

    /** The current map snapshot's tiles by cell, or {@code null} while no anchored map exists. */
    private static Map<Long, RoomMapReader.MapTile> mapTilesByCell() {
        if (!RoomMapReader.isAnchored()) {
            return null;
        }
        RoomMapReader.MapSnapshot snapshot = DungeonState.getInstance().snapshot();
        if (snapshot == null) {
            return null;
        }
        Map<Long, RoomMapReader.MapTile> tiles = new HashMap<>();
        for (RoomMapReader.MapTile tile : snapshot.tiles()) {
            tiles.put(cellKey(tile.cellX(), tile.cellZ()), tile);
        }
        return tiles;
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }
}
