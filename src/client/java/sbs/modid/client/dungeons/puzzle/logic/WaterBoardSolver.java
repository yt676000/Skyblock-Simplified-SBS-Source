/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.puzzle.model.WaterGrid;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Water Board, <b>probe stage</b>: reads the room, logs what it found and what a solver would show,
 * and draws nothing.
 *
 * <p>What is read, all inside {@link DungeonRoomTracker#borders()} and found by block type rather
 * than by a coordinate table (the room frame of other mods is not ours, and a scan survives both
 * rotation and a frame mismatch):
 * <ul>
 *   <li>the six lever-group materials - gold, emerald, coal, diamond, quartz, terracotta - whose
 *       bounding box is taken as the board, a vertical plane;</li>
 *   <li>every lever, with its powered state;</li>
 *   <li>the wool blocks, whose colours are the gates; three of five present is the target
 *       (wiki and estimate, unverified in 26.2).</li>
 * </ul>
 * The board is then captured as a {@link WaterGrid} and handed to {@link WaterFlow#plan}; the result
 * is logged as "would show", never drawn. After that, a lever flip is logged once the board has been
 * still for {@link #SETTLE_TICKS}, with how many board cells changed between water and dry.
 *
 * <p>Work is on change only: the scan is budgeted per tick and runs once per visit, the levers are
 * polled every {@link #POLL_INTERVAL} ticks, and the board is re-read only after a flip.
 */
public final class WaterBoardSolver implements PuzzleStrategy {

    /** The lever-group materials, in the order of the {@link WaterGrid} group index. */
    private static final Block[] MATERIALS = {
            Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK, Blocks.COAL_BLOCK,
            Blocks.DIAMOND_BLOCK, Blocks.QUARTZ_BLOCK, Blocks.TERRACOTTA};
    private static final List<String> MATERIAL_NAMES =
            List.of("gold", "emerald", "coal", "diamond", "quartz", "terracotta");

    private static final int SCAN_BUDGET = 4096;
    /** Scanned layers around the door-floor Y. The board sits well above and the gates well below. */
    private static final int SCAN_BELOW = 20;
    private static final int SCAN_ABOVE = 20;
    private static final int POLL_INTERVAL = 5;
    private static final int SETTLE_TICKS = 40;
    /** A larger box than this is not a board - the scan caught materials elsewhere in the room. */
    private static final int MAX_BOARD_CELLS = 40 * 40 * 3;

    private DungeonRoomBorders.Borders scanBorders;
    private int scanCursor;
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    private final List<BlockPos> levers = new ArrayList<>();
    /** Every material block the scan found; the board box is built from those that are not labels. */
    private final List<BlockPos> materialBlocks = new ArrayList<>();
    /** How close to a lever a material block is that lever's label. */
    private static final int LABEL_REACH = 2;
    private boolean[] leverPowered = new boolean[0];
    private final List<String> woolColor = new ArrayList<>();
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;
    private boolean anyMaterial;
    /** Per material: how many blocks, and their box {minX,minY,minZ,maxX,maxY,maxZ}; for the layout log. */
    private final int[] materialCount = new int[MATERIALS.length];
    private final int[][] materialBox = new int[MATERIALS.length][6];

    /** The captured board, or {@code null} before the scan ends or when it found no board. */
    private WaterGrid board;
    /** The gate colours of the scan, kept for re-planning once the source is known. */
    private TreeSet<String> boardColors = new TreeSet<>();
    /** Wet cells of the last board read, parallel to {@code board.cells()}. */
    private boolean[] lastWater;
    private boolean alongX;
    private int pollCountdown;
    /** Ticks until the board is re-read after a flip; -1 while nothing is pending. */
    private int settleCountdown = -1;
    private String pendingFlip;

    @Override
    public PuzzleType type() {
        return PuzzleType.WATER_BOARD;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        if (level == null || borders == null) {
            return;
        }
        if (borders != scanBorders) {
            reset();
            scanBorders = borders;
        }
        if (scanCursor >= 0) {
            scanStep(level, borders);
            return;
        }
        if (--pollCountdown <= 0) {
            pollCountdown = POLL_INTERVAL;
            pollLevers(level);
        }
        if (settleCountdown > 0 && --settleCountdown == 0) {
            settleCountdown = -1;
            logBoardChange(level);
        }
    }

    private void scanStep(ClientLevel level, DungeonRoomBorders.Borders borders) {
        int bx = borders.min().getX();
        int bz = borders.min().getZ();
        int sizeX = borders.max().getX() - bx + 1;
        int sizeZ = borders.max().getZ() - bz + 1;
        int floor = borders.min().getY();
        int columns = sizeX * sizeZ;
        int budget = SCAN_BUDGET;
        while (scanCursor < columns && budget > 0) {
            int x = bx + scanCursor % sizeX;
            int z = bz + scanCursor / sizeX;
            scanCursor++;
            probe.set(x, floor, z);
            if (!borders.contains(probe)) {
                continue;
            }
            for (int y = floor - SCAN_BELOW; y <= floor + SCAN_ABOVE; y++) {
                probe.set(x, y, z);
                classify(level.getBlockState(probe));
            }
            budget -= SCAN_BELOW + SCAN_ABOVE + 1;
        }
        if (scanCursor >= columns) {
            scanCursor = -1;
            finishScan(level);
        }
    }

    private void classify(BlockState state) {
        if (state.is(Blocks.LEVER)) {
            levers.add(probe.immutable());
        } else if (state.is(BlockTags.WOOL)) {
            woolColor.add(colorName(state.getBlock()));
        } else if (material(state) >= 0) {
            int m = material(state);
            materialBlocks.add(probe.immutable());
            int[] b = materialBox[m];
            if (materialCount[m]++ == 0) {
                b[0] = b[3] = probe.getX();
                b[1] = b[4] = probe.getY();
                b[2] = b[5] = probe.getZ();
            } else {
                b[0] = Math.min(b[0], probe.getX());
                b[1] = Math.min(b[1], probe.getY());
                b[2] = Math.min(b[2], probe.getZ());
                b[3] = Math.max(b[3], probe.getX());
                b[4] = Math.max(b[4], probe.getY());
                b[5] = Math.max(b[5], probe.getZ());
            }
            if (!anyMaterial) {
                minX = maxX = probe.getX();
                minY = maxY = probe.getY();
                minZ = maxZ = probe.getZ();
                anyMaterial = true;
            }
            minX = Math.min(minX, probe.getX());
            maxX = Math.max(maxX, probe.getX());
            minY = Math.min(minY, probe.getY());
            maxY = Math.max(maxY, probe.getY());
            minZ = Math.min(minZ, probe.getZ());
            maxZ = Math.max(maxZ, probe.getZ());
        }
    }

    /**
     * The board box from the material blocks that are <b>not</b> lever labels. Each lever has a
     * block of its group's material next to it, so the box over every material spanned board and
     * lever row alike - an 18-block cube twice on 2026-10-16. The layout log of that day put the
     * levers at y 61 with every material reaching down to y 61 and across to the lever columns; a
     * material block within {@link #LABEL_REACH} of a lever is that lever's label.
     */
    private void boardBoxWithoutLabels() {
        int dropped = 0;
        boolean any = false;
        for (BlockPos pos : materialBlocks) {
            if (nearLever(pos)) {
                dropped++;
                continue;
            }
            if (!any) {
                minX = maxX = pos.getX();
                minY = maxY = pos.getY();
                minZ = maxZ = pos.getZ();
                any = true;
            }
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        anyMaterial = any;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] waterboard: {} lever label(s) left out, board box {}",
                dropped, any ? "[" + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ + "]"
                        : "empty");
    }

    private boolean nearLever(BlockPos pos) {
        for (BlockPos lever : levers) {
            if (Math.abs(pos.getX() - lever.getX()) <= LABEL_REACH && Math.abs(pos.getZ() - lever.getZ()) <= LABEL_REACH
                    && Math.abs(pos.getY() - lever.getY()) <= LABEL_REACH) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where each material and lever actually is. The board was assumed to be the bounding box of
     * all six materials, a vertical plane; on 2026-10-16 that box was an 18-block cube and no board
     * was found. This line is what the real board detection gets written from.
     */
    private void logLayout() {
        StringBuilder sb = new StringBuilder("waterboard layout:");
        for (int m = 0; m < MATERIALS.length; m++) {
            int[] b = materialBox[m];
            sb.append(' ').append(MATERIAL_NAMES.get(m)).append('=').append(materialCount[m]);
            if (materialCount[m] > 0) {
                sb.append("@[").append(b[0]).append(',').append(b[1]).append(',').append(b[2]).append("..")
                        .append(b[3]).append(',').append(b[4]).append(',').append(b[5]).append(']');
            }
        }
        sb.append(" levers=").append(levers);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] {}", sb);
    }

    private static int material(BlockState state) {
        for (int i = 0; i < MATERIALS.length; i++) {
            if (state.is(MATERIALS[i])) {
                return i;
            }
        }
        return -1;
    }

    /** {@code "block.minecraft.purple_wool"} -> {@code "purple"}. */
    private static String colorName(Block block) {
        String id = block.getDescriptionId();
        int dot = id.lastIndexOf('.');
        String path = dot >= 0 ? id.substring(dot + 1) : id;
        return path.endsWith("_wool") ? path.substring(0, path.length() - 5) : path;
    }

    private void finishScan(ClientLevel level) {
        logLayout();
        leverPowered = new boolean[levers.size()];
        for (int i = 0; i < levers.size(); i++) {
            leverPowered[i] = powered(level, levers.get(i));
        }
        TreeSet<String> colors = new TreeSet<>(woolColor);
        boardBoxWithoutLabels();
        if (anyMaterial && colors.size() <= 8) {
            alongX = maxX - minX >= maxZ - minZ;
            board = capture(level, List.copyOf(colors));
            lastWater = board == null ? null : readWater(level);
        }
        boardColors = colors;
        String plan = describePlan(colors);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] waterboard: levers={} powered={} gates={} board={} "
                        + "box=[{},{},{}..{},{},{}] layer={} layer1={} would show: {}",
                levers.size(), Arrays.toString(leverPowered), colors, board,
                minX, minY, minZ, maxX, maxY, maxZ, board == null ? "-" : board.dumpLayer(0),
                board == null || board.depth() < 2 ? "-" : board.dumpLayer(1), plan);
    }

    /**
     * The board as wet ({@code ~}), open ({@code .}) and solid ({@code #}) cells, top row first,
     * every layer - what the water did after a flip, for checking the flow model against the room.
     */
    private String waterMap(boolean[] wet) {
        StringBuilder out = new StringBuilder();
        for (int z = 0; z < board.depth(); z++) {
            out.append(z == 0 ? "" : " | ");
            for (int y = board.height() - 1; y >= 0; y--) {
                for (int x = 0; x < board.width(); x++) {
                    int i = board.index(x, y, z);
                    out.append(wet[i] ? '~' : board.cells()[i] == WaterGrid.OPEN ? '.' : '#');
                }
                out.append(y == 0 ? "" : "/");
            }
        }
        return out.toString();
    }

    /**
     * The source is unknown until water stands on the board - at entry there is none. The first
     * settled read with water takes its topmost wet cell as the source and plans again, so the
     * "would show" line finally has something to say (2026-10-17: the plan ran only once, dry).
     */
    private void learnSource(boolean[] wet) {
        if (board.source() >= 0) {
            return;
        }
        int found = -1;
        int foundY = -1;
        for (int i = 0; i < wet.length; i++) {
            int y = i / (board.width() * board.depth());
            if (wet[i] && y > foundY) {
                foundY = y;
                found = i;
            }
        }
        if (found < 0) {
            return;
        }
        board = new WaterGrid(board.width(), board.height(), board.depth(), board.cells(), board.groups(),
                board.colors(), board.outletCells(), board.outletColor(), found);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] waterboard: source learnt at cell {} (top wet row y={}) "
                + "- would show: {}", found, foundY, describePlan(boardColors));
    }

    /**
     * The board box as a grid: x along the plane, y up, z the plane's thickness. Air and water are
     * open, a material is its group, anything else solid. Outlets: open cells of the bottom row with
     * wool somewhere below them in the column, coloured by that wool (an unverified guess at how the
     * gates line up with the board). Source: the topmost water cell, if water already stands there.
     */
    private WaterGrid capture(ClientLevel level, List<String> colors) {
        int width = (alongX ? maxX - minX : maxZ - minZ) + 1;
        int depth = (alongX ? maxZ - minZ : maxX - minX) + 1;
        int height = maxY - minY + 1;
        if (width * depth * height > MAX_BOARD_CELLS) {
            return null;
        }
        byte[] cells = new byte[width * depth * height];
        int source = -1;
        int[] outlets = new int[width * depth];
        int[] outletColor = new int[width * depth];
        int outletCount = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    setWorld(x, y, z);
                    BlockState state = level.getBlockState(probe);
                    int i = x + width * (z + depth * y);
                    int m = material(state);
                    if (m >= 0) {
                        cells[i] = (byte) (WaterGrid.GROUP_BASE + m);
                    } else if (state.isAir() || state.is(Blocks.WATER)) {
                        cells[i] = WaterGrid.OPEN;
                        if (state.is(Blocks.WATER)) {
                            source = i;
                        }
                        if (y == 0) {
                            int color = woolBelow(level, colors);
                            if (color >= 0) {
                                outlets[outletCount] = i;
                                outletColor[outletCount++] = color;
                            }
                        }
                    } else {
                        cells[i] = WaterGrid.SOLID;
                    }
                }
            }
        }
        return new WaterGrid(width, height, depth, cells, MATERIAL_NAMES, colors,
                Arrays.copyOf(outlets, outletCount), Arrays.copyOf(outletColor, outletCount), source);
    }

    /** The colour index of the first wool under {@link #probe}, or -1. Leaves the probe moved. */
    private int woolBelow(ClientLevel level, List<String> colors) {
        for (int dy = 1; dy <= SCAN_BELOW + SCAN_ABOVE; dy++) {
            probe.move(0, -1, 0);
            BlockState state = level.getBlockState(probe);
            if (state.is(BlockTags.WOOL)) {
                return colors.indexOf(colorName(state.getBlock()));
            }
        }
        return -1;
    }

    private void setWorld(int x, int y, int z) {
        if (alongX) {
            probe.set(minX + x, minY + y, minZ + z);
        } else {
            probe.set(minX + z, minY + y, minZ + x);
        }
    }

    private boolean[] readWater(ClientLevel level) {
        boolean[] water = new boolean[board.cells().length];
        for (int y = 0; y < board.height(); y++) {
            for (int z = 0; z < board.depth(); z++) {
                for (int x = 0; x < board.width(); x++) {
                    setWorld(x, y, z);
                    water[board.index(x, y, z)] = level.getBlockState(probe).is(Blocks.WATER);
                }
            }
        }
        return water;
    }

    private String describePlan(TreeSet<String> colors) {
        if (board == null) {
            return "nothing (no board found)";
        }
        if (board.source() < 0) {
            return "nothing (no water on the board yet - source unknown)";
        }
        if (colors.size() != 3) {
            return "nothing (expected 3 gate colours, read " + colors.size() + ")";
        }
        int mask = WaterFlow.plan(board, (1 << board.colors().size()) - 1);
        if (mask < 0) {
            return "nothing (no unique steady-state plan)";
        }
        List<String> groups = new ArrayList<>();
        for (int g = 0; g < MATERIAL_NAMES.size(); g++) {
            if ((mask & 1 << g) != 0) {
                groups.add(MATERIAL_NAMES.get(g));
            }
        }
        return "levers for " + groups + " (steady-state model, untimed)";
    }

    private void pollLevers(ClientLevel level) {
        for (int i = 0; i < levers.size(); i++) {
            boolean now = powered(level, levers.get(i));
            if (now != leverPowered[i]) {
                leverPowered[i] = now;
                pendingFlip = "lever " + levers.get(i).toShortString() + " -> " + (now ? "on" : "off");
                settleCountdown = SETTLE_TICKS;
            }
        }
    }

    private void logBoardChange(ClientLevel level) {
        if (board == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] waterboard: {} (no board to diff)", pendingFlip);
            return;
        }
        boolean[] now = readWater(level);
        int wetted = 0;
        int dried = 0;
        for (int i = 0; i < now.length; i++) {
            if (now[i] && !lastWater[i]) {
                wetted++;
            } else if (!now[i] && lastWater[i]) {
                dried++;
            }
        }
        lastWater = now;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] waterboard: {} settled, +{} wet -{} dry, powered={} water={}",
                pendingFlip, wetted, dried, Arrays.toString(leverPowered), waterMap(now));
        learnSource(now);
    }

    private static boolean powered(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.LEVER) && state.getValue(LeverBlock.POWERED);
    }

    /** Probe stage: never draws. */
    @Override
    public List<PuzzleHighlight> highlights() {
        return List.of();
    }

    @Override
    public void reset() {
        scanBorders = null;
        scanCursor = 0;
        levers.clear();
        materialBlocks.clear();
        leverPowered = new boolean[0];
        woolColor.clear();
        anyMaterial = false;
        Arrays.fill(materialCount, 0);
        board = null;
        lastWater = null;
        pollCountdown = 0;
        settleCountdown = -1;
        pendingFlip = null;
    }
}
