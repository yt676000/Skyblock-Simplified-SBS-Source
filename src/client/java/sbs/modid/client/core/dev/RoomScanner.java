/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomLocator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans a dungeon room for a handful of markant "signature" blocks and records each one purely as a
 * <b>door-relative, rotation-normalised</b> coordinate ({@link RoomRotation#actualToRelative} – NORTH
 * canonical). No absolute world coordinates are kept.
 *
 * <p>The scan area is the room's real, void-detected footprint ({@link DungeonRoomBorders.Borders}), so
 * every recorded block is guaranteed to belong to this room – neighbouring rooms can never leak in, no
 * matter the room's shape. Columns are read section-wise ({@link LevelChunkSection#hasOnlyAir()}), so
 * empty sky above the room costs nothing. Only whitelisted blocks are collected.
 *
 * <p><b>The signature is split per 32x32 cell.</b> Every cell of the room gets its own bucket
 * ({@link DungeonRoomLocator#relativeCellKey}) with its own collect cap and its own
 * {@value #MAX_BLOCKS_PER_CELL}-block export, so each cell carries a full standalone signature and the
 * matcher can identify the room from the single cell the player is standing in – no waiting for the
 * far end of a 1x4 to stream in. A single room-wide budget could not do that: it was handed out in
 * sweep order, i.e. proportional to each cell's block <i>density</i>, so one dense cell ate most of the
 * quota (a 3x1 room stored 20 of its 30 blocks in one cell, and five multi-cell rooms ended up with a
 * cell holding no signature at all).
 */
public final class RoomScanner {

    /**
     * Only these fixed blocks are recorded. Player heads are intentionally excluded: they change at
     * wither doors and are placed dynamically, which makes them useless for reliable room matching.
     */
    private static final Set<Block> SIGNATURE_BLOCKS = Set.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.LEVER,
            Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.COAL_BLOCK);

    /** Hard cap on exported blocks <b>per 32x32 cell</b> (task: 5–30 signature blocks per cell). */
    private static final int MAX_BLOCKS_PER_CELL = 30;

    /**
     * Cap during the sweep, per cell and well above {@link #MAX_BLOCKS_PER_CELL}: a cell collects
     * everything first and exports an <b>evenly spaced subsample</b> of it, so its 30 blocks spread
     * over the whole cell instead of clustering in the first-scanned corner.
     */
    private static final int MAX_COLLECT_PER_CELL = 400;

    /** One recorded block: registry id + door-relative (rotation-normalised) coordinates only. */
    public record ScannedBlock(String id, int relativeX, int relativeY, int relativeZ) {
    }

    private RoomScanner() {
    }

    /**
     * Scans the room footprint for signature blocks.
     *
     * @param anchor          the room's canonical anchor (NW corner at Y 0 – the relative origin)
     * @param facing          the canonical facing the coordinates are normalised against (NORTH)
     * @param borders         the room footprint that bounds the scan
     * @param excludeDoorways skip Hypixel doorway zones (wither/blood door frames – e.g. coal blocks –
     *                        are dynamic and must never become signature blocks); only meaningful when
     *                        the room was located on the real dungeon grid
     */
    public static List<ScannedBlock> scan(BlockPos anchor, Direction facing, DungeonRoomBorders.Borders borders,
                                          boolean excludeDoorways) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || borders == null) {
            return List.of();
        }
        Map<Long, List<ScannedBlock>> byCell = new HashMap<>();
        for (DungeonRoomBorders.Rect rect : borders.rects()) {
            for (int x = rect.minX(); x <= rect.maxX(); x++) {
                for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
                    // The relative frame's X/Z do not depend on Y, so a whole column belongs to exactly
                    // one cell – resolve its bucket once instead of per block.
                    List<ScannedBlock> bucket = byCell.computeIfAbsent(cellKeyAt(anchor, facing, x, z),
                            key -> new ArrayList<>());
                    if (bucket.size() >= MAX_COLLECT_PER_CELL) {
                        continue;   // this cell has plenty; the other cells still get their full budget
                    }
                    scanColumn(level, anchor, facing, x, z, excludeDoorways, bucket);
                }
            }
        }
        // Export cell by cell, each subsampled on its own. Cell order is canonical (X then Z) so
        // re-scanning the same room produces the same file layout.
        List<Long> cells = new ArrayList<>(byCell.keySet());
        Collections.sort(cells);
        List<ScannedBlock> result = new ArrayList<>();
        for (Long cell : cells) {
            subsample(byCell.get(cell), result);
        }
        return result;
    }

    /** The canonical cell of the world column {@code (x,z)} under this scan's anchor / facing. */
    private static long cellKeyAt(BlockPos anchor, Direction facing, int x, int z) {
        BlockPos relative = RoomRotation.actualToRelative(facing, anchor, new BlockPos(x, anchor.getY(), z));
        return DungeonRoomLocator.relativeCellKey(relative.getX(), relative.getZ());
    }

    /** Appends one cell's export – all of it, or {@value #MAX_BLOCKS_PER_CELL} evenly spaced blocks. */
    private static void subsample(List<ScannedBlock> cell, List<ScannedBlock> out) {
        if (cell.size() <= MAX_BLOCKS_PER_CELL) {
            out.addAll(cell);
            return;
        }
        double step = cell.size() / (double) MAX_BLOCKS_PER_CELL;
        for (int i = 0; i < MAX_BLOCKS_PER_CELL; i++) {
            out.add(cell.get((int) (i * step)));
        }
    }

    /** Scans one column section-wise; empty sections (all sky above the room) are skipped wholesale. */
    private static void scanColumn(ClientLevel level, BlockPos anchor, Direction facing,
                                   int x, int z, boolean excludeDoorways, List<ScannedBlock> result) {
        if (!level.hasChunkAt(x, z)) {
            return;
        }
        LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
        int lx = x & 15;
        int lz = z & 15;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length && result.size() < MAX_COLLECT_PER_CELL; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) {
                continue;
            }
            int sectionBottom = (level.getMinSectionY() + i) << 4;
            for (int sy = 0; sy < 16 && result.size() < MAX_COLLECT_PER_CELL; sy++) {
                int y = sectionBottom + sy;
                if (excludeDoorways && inDoorway(x, y, z)) {
                    continue;
                }
                Block block = section.getBlockState(lx, sy, lz).getBlock();
                if (!SIGNATURE_BLOCKS.contains(block)) {
                    continue;
                }
                BlockPos relative = RoomRotation.actualToRelative(facing, anchor, new BlockPos(x, y, z));
                result.add(new ScannedBlock(registryId(block), relative.getX(), relative.getY(), relative.getZ()));
            }
        }
    }

    /**
     * The doorway zone: Hypixel doors always sit at Y 66–73, centred on the room-edge middles
     * of the fixed 32-grid ({@code floorMod(c - 8, 32)} local frame). Blocks in these zones are the
     * dynamic door frames (coal / blood blocks), never room signature.
     */
    private static boolean inDoorway(int x, int y, int z) {
        if (y < 66 || y > 73) {
            return false;
        }
        int lx = Math.floorMod(x - 8, 32);
        int lz = Math.floorMod(z - 8, 32);
        return !((lx < 13 || lx > 17 || lz > 2 && lz < 28) && (lz < 13 || lz > 17 || lx > 2 && lx < 28));
    }

    private static String registryId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
