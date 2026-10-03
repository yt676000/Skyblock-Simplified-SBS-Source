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
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.dev.RoomMapReader;
import sbs.modid.client.dungeons.run.model.DungeonState;
import sbs.modid.client.dungeons.run.render.DungeonHighlight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Boxes the <b>wither / blood doors</b> in a Catacombs dungeon: normally in the "locked" colour,
 * but the door of the room you are in turns to the "key" colour the moment you pick a wither key up
 * ("… has obtained Wither Key!" / "RIGHT CLICK on a WITHER door to open it …"), so it is obvious
 * which door your key opens.
 *
 * <p><b>Detection.</b> Wither doors are solid {@link Blocks#COAL_BLOCK} plugs; the <b>blood door is
 * red hardened clay</b> (red terracotta – on the minimap its connector is painted red). The scanner
 * sweeps a box around the player for both materials, clusters the hits per material (each door is
 * one contiguous block), and boxes every cluster of at least {@link #MIN_CLUSTER} blocks. Because
 * décor coal / red clay exists too, every cluster is then <b>verified as a door position</b>: it
 * must sit on a 32-grid gap line inside the fixed doorway height band, and – once the dungeon map is
 * anchored – the map must paint a wither (black) or blood (red) connector on exactly that cell edge.
 * The colour of a cluster is decided per frame: green while you hold a key AND the cluster belongs
 * to your current room grid cell, else the locked colour.
 *
 * <p>Runs off the client tick via {@code GuiTrackingMixin}, gated hard on the Catacombs scoreboard;
 * the boxes are drawn by {@link DungeonHighlight}. Nothing here scans in the render pass.
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
    /** Safety net: forget a held key after this long (a key is normally used within seconds). */
    private static final long KEY_TIMEOUT_MS = 10 * 60_000L;
    /** Cap on collected coal positions, so a pathological area can never blow up the clustering. */
    private static final int MAX_COAL = 400;

    /** One boxed door: its coal bounding box (inclusive) and whether it is the key-coloured one. */
    public record DoorBox(BlockPos min, BlockPos max, boolean green) {
    }

    private volatile List<DoorBox> doorBoxes = List.of();
    private long lastScanAt;
    private boolean hasKey;
    private long keyAt;

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

    /** Every chat line; a wither-key pickup / prompt of MINE arms the green highlight. */
    public void onChat(String text) {
        if (!cfg().witherDoors || text == null) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        // "<player> opened a WITHER door!" - the key is consumed on the spot, whoever opened it, so
        // the highlight falls back to the locked colour until the next key pickup.
        if (lower.contains("opened a wither door") || lower.contains("opened a blood door")) {
            hasKey = false;
            return;
        }
        // The "RIGHT CLICK on a WITHER door" prompt is client-only (always mine). The "obtained
        // Wither Key" line names a player, so it must name ME - a party member's key is not mine.
        boolean mine = lower.contains("right click on a wither door")
                || ((lower.contains("obtained wither key") || lower.contains("obtained blood key"))
                        && namesLocalPlayer(text));
        if (mine) {
            hasKey = true;
            keyAt = System.currentTimeMillis();
        }
    }

    private static boolean namesLocalPlayer(String text) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && text.contains(player.getGameProfile().name());
    }

    // ------------------------------------------------------------------ tick

    /** Called once per client tick (throttled scan inside). */
    public void tick(Minecraft minecraft) {
        SBSConfig.DungeonsSettings cfg = cfg();
        if (!cfg.witherDoors) {
            reset();
            return;
        }
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        // The slot-9 map gate also stops the scan in the boss room: no doors exist there, only
        // décor coal that would get boxed.
        if (player == null || level == null || !DungeonScoreboard.isInCatacombs()
                || !RoomMapReader.hasDungeonMap()) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (hasKey && now - keyAt > KEY_TIMEOUT_MS) {
            hasKey = false;   // stale safety net
        }
        if (now - lastScanAt < SCAN_MS) {
            return;
        }
        lastScanAt = now;
        doorBoxes = scan(level, player.blockPosition());
    }

    private void reset() {
        if (!doorBoxes.isEmpty()) {
            doorBoxes = List.of();
        }
        hasKey = false;
    }

    // ------------------------------------------------------------------ scan

    /** The blood door's material: red hardened clay (red terracotta since the flattening). */
    private static final Block BLOOD_DOOR_BLOCK = Blocks.DYED_TERRACOTTA.pick(DyeColor.RED);

    private List<DoorBox> scan(ClientLevel level, BlockPos player) {
        int px = player.getX();
        int pz = player.getZ();

        // Sweep the whole current room footprint (plus a margin for the seam doors just past its
        // edge) so every wither / blood door on the room's perimeter is boxed from anywhere inside
        // it - they show through the walls exactly like they do on the minimap. Without a locked
        // room (doorway, unpainted tile, dev mode without a map) fall back to a player radius.
        DungeonRoomBorders.Borders room = DungeonRoomTracker.getInstance().borders();
        int minX, maxX, minZ, maxZ;
        if (room != null) {
            minX = Math.max(room.min().getX() - ROOM_EDGE_MARGIN, px - MAX_ROOM_REACH);
            maxX = Math.min(room.max().getX() + ROOM_EDGE_MARGIN, px + MAX_ROOM_REACH);
            minZ = Math.max(room.min().getZ() - ROOM_EDGE_MARGIN, pz - MAX_ROOM_REACH);
            maxZ = Math.min(room.max().getZ() + ROOM_EDGE_MARGIN, pz + MAX_ROOM_REACH);
        } else {
            minX = px - RADIUS;
            maxX = px + RADIUS;
            minZ = pz - RADIUS;
            maxZ = pz + RADIUS;
        }

        // Collect the two door materials, but only in the band along the 32-grid seams where doors
        // live (see onSeam): sweeping a whole room stays cheap, and décor coal in the room interior
        // can never fill the position budget before a real door is reached. Kept apart per material
        // so coal and red clay never merge into one cluster.
        List<int[]> coal = new ArrayList<>();
        List<int[]> redClay = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
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
        if (coal.isEmpty() && redClay.isEmpty()) {
            return List.of();
        }

        // The player's current room grid cell – the fallback when no footprint is locked.
        int cornerX = DungeonRoomLocator.cornerCoord(px);
        int cornerZ = DungeonRoomLocator.cornerCoord(pz);

        Map<Long, RoomMapReader.MapTile> mapTiles = mapTilesByCell();

        List<DoorBox> boxes = new ArrayList<>();
        boxClusters(cluster(coal), mapTiles, room, cornerX, cornerZ, boxes);
        boxClusters(cluster(redClay), mapTiles, room, cornerX, cornerZ, boxes);
        return List.copyOf(boxes);
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

    /** Verifies each cluster as a door position and appends its box (see {@link #plausibleDoor}). */
    private void boxClusters(List<List<int[]>> clusters, Map<Long, RoomMapReader.MapTile> mapTiles,
                             DungeonRoomBorders.Borders room, int cornerX, int cornerZ, List<DoorBox> boxes) {
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
            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;
            if (!plausibleDoor(centerX, centerZ, minY, maxY, mapTiles)) {
                continue;   // décor coal: not on a grid edge, or the map paints no key door there
            }
            boolean green = hasKey && adjacentToRoom(centerX, centerZ, room, cornerX, cornerZ);
            boxes.add(new DoorBox(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), green));
        }
    }

    // ------------------------------------------------------------------ door verification

    /** Hypixel doorways sit at the fixed Y 66..73 band; a little slack for the coal wall's frame. */
    private static final int DOOR_BAND_MIN_Y = 64;
    private static final int DOOR_BAND_MAX_Y = 76;

    /**
     * Whether a coal cluster really is a door: its centre must lie on exactly one 32-grid gap line
     * (doors bridge the 1-block void seam between two room cells – décor coal sits inside a room),
     * its blocks inside the fixed doorway height band, and – when the dungeon map already paints the
     * corresponding cell edge – the map must show a wither/blood connector there.
     */
    private static boolean plausibleDoor(int centerX, int centerZ, int minY, int maxY,
                                         Map<Long, RoomMapReader.MapTile> mapTiles) {
        if (maxY < DOOR_BAND_MIN_Y || minY > DOOR_BAND_MAX_Y) {
            return false;
        }
        // Gap columns satisfy floorMod(c + 8, 32) == 31; ±2 tolerance because the coal plug is a
        // few blocks deep, so an asymmetric cluster centre can sit just beside the gap column.
        int lx = Math.floorMod(centerX + DungeonRoomLocator.GRID_SHIFT, DungeonRoomLocator.GRID);
        int lz = Math.floorMod(centerZ + DungeonRoomLocator.GRID_SHIFT, DungeonRoomLocator.GRID);
        boolean onGapX = lx >= 29 || lx <= 1;
        boolean onGapZ = lz >= 29 || lz <= 1;
        if (onGapX == onGapZ) {
            return false;   // inside a cell, or on a cell corner - no door is ever there
        }
        if (mapTiles == null) {
            return true;    // no anchored map yet - the geometric check is all we have
        }
        // The connector is stored on the west (X doors) / north (Z doors) tile of the shared edge.
        // Sample 4 blocks to each side of the gap so the tolerance above cannot flip the cell.
        RoomMapReader.MapTile tile;
        if (onGapX) {
            int[] west = RoomMapReader.worldToMapCellIndex(centerX - 4, centerZ);
            tile = west == null ? null : mapTiles.get(cellKey(west[0], west[1]));
            return tile == null || tile.doorEast().keyDoor();
        }
        int[] north = RoomMapReader.worldToMapCellIndex(centerX, centerZ - 4);
        tile = north == null ? null : mapTiles.get(cellKey(north[0], north[1]));
        return tile == null || tile.doorSouth().keyDoor();
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

    /**
     * Whether a door centre belongs to the room the player is in – so a held key highlights it. With
     * a locked footprint that is "on or just outside the room's bounding box" (so every door of a
     * multi-cell room highlights together, not only the one on the player's own cell); without one
     * it falls back to the player's single grid cell.
     */
    private static boolean adjacentToRoom(int cx, int cz, DungeonRoomBorders.Borders room,
                                          int cornerX, int cornerZ) {
        if (room == null) {
            return adjacentToCell(cx, cz, cornerX, cornerZ);
        }
        int pad = 4;
        return cx >= room.min().getX() - pad && cx <= room.max().getX() + pad
                && cz >= room.min().getZ() - pad && cz <= room.max().getZ() + pad;
    }

    /** Whether a door centre lies on / just outside the room cell {@code corner..corner+ROOM_SPAN}. */
    private static boolean adjacentToCell(int cx, int cz, int cornerX, int cornerZ) {
        int pad = 4;
        int span = DungeonRoomLocator.ROOM_SPAN;
        return cx >= cornerX - pad && cx <= cornerX + span + pad
                && cz >= cornerZ - pad && cz <= cornerZ + span + pad;
    }
}
