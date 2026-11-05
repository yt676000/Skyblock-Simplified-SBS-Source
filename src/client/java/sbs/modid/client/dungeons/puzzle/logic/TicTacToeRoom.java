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
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.puzzle.model.SolverStage;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Tic Tac Toe: boxes the button for the player's best next mark.
 *
 * <p>Display only. The player clicks the button; nothing here clicks, aims or moves.
 *
 * <p>What the room is (from the wiki and the room database, see the spec): a 3x3 grid on a
 * wall, one stone button per open cell, and an item frame holding a map with an X or an O per played
 * cell. The computer opens. The state is public - it is the wall the player is looking at.
 *
 * <ol>
 *   <li><b>Buttons</b> - on entering, the room's footprint from {@link DungeonRoomTracker#borders()} is
 *       scanned for stone buttons, a fixed number of blocks per tick, once per visit.</li>
 *   <li><b>Frames</b> - every {@link #READ_INTERVAL} ticks, the item frames inside the footprint are
 *       listed. Each one holding a map becomes a played cell keyed by the map's centre pixel. A cheap
 *       signature of positions and keys decides whether anything changed; only then is the wall read.</li>
 *   <li><b>Board and move</b> - {@link TicTacToeGrid} turns buttons and frames into a board,
 *       {@link TicTacToeSearch} picks the best moves. One {@code [SBS][Puzzle] ttt:} line per change.</li>
 * </ol>
 *
 * <p>Drawing is gated on the stage: while {@link PuzzleType#TIC_TAC_TOE} is {@link SolverStage#PROBE_ONLY} the boxes are computed and
 * logged as "would box" but {@link #highlights()} returns nothing: the wall's shape in 26.2 has not
 * been seen yet. Promoted to FINISHED 2026-10-16: the maintainer's run read every board right and
 * proposed the optimal reply each time (centre after a corner opening, then the block).
 */
public final class TicTacToeRoom implements PuzzleStrategy {

    private static final int SCAN_BUDGET = 4096;
    /** Scanned layers relative to the room's door-floor Y; the wall is a few blocks above the floor. */
    private static final int SCAN_BELOW = 2;
    private static final int SCAN_ABOVE = 10;
    private static final int READ_INTERVAL = 5;
    /** Bound on buttons recorded; the grid has nine, a room with many more is not this room. */
    private static final int MAX_BUTTONS = 32;
    /** A map is 128x128; the pixel in its middle is inside the drawn symbol. */
    private static final int MAP_CENTRE = 64 * 128 + 64;

    private DungeonRoomBorders.Borders scanBorders;
    private int scanCursor;
    private final List<BlockPos> buttons = new ArrayList<>();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private int readCountdown;

    /** Signature of the last wall read; equal signatures skip the read. */
    private long lastSignature = Long.MIN_VALUE;
    private String lastLogged;
    private boolean finished;

    private volatile List<PuzzleHighlight> drawn = List.of();

    @Override
    public PuzzleType type() {
        return PuzzleType.TIC_TAC_TOE;
    }

    @Override
    public void onChat(String line) {
        if (line == null || finished || !line.startsWith("PUZZLE ") || !line.contains(" Tic Tac Toe!")) {
            return;
        }
        // CONFIRMED (maintainer's 1.8.9 logs): "PUZZLE SOLVED! <p> tied Tic Tac Toe! Good job!" and
        // "PUZZLE FAIL! <p> lost Tic Tac Toe! Yikes!".
        finished = true;
        drawn = List.of();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] ttt: result '{}' - last board {}", line, lastLogged);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (finished || minecraft.level == null) {
            return;
        }
        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        if (borders == null) {
            return;
        }
        if (borders != scanBorders) {
            scanBorders = borders;
            scanCursor = 0;
            buttons.clear();
            lastSignature = Long.MIN_VALUE;
        }
        if (scanCursor >= 0) {
            scanStep(minecraft.level, borders);
            return;
        }
        if (--readCountdown > 0) {
            return;
        }
        readCountdown = READ_INTERVAL;
        readWall(minecraft.level, borders);
    }

    private void scanStep(ClientLevel level, DungeonRoomBorders.Borders borders) {
        int minX = borders.min().getX();
        int minZ = borders.min().getZ();
        int sizeX = borders.max().getX() - minX + 1;
        int sizeZ = borders.max().getZ() - minZ + 1;
        int floor = borders.min().getY();
        int layers = SCAN_BELOW + SCAN_ABOVE + 1;
        int columns = sizeX * sizeZ;
        int budget = SCAN_BUDGET;
        while (scanCursor < columns && budget > 0) {
            int x = minX + scanCursor % sizeX;
            int z = minZ + scanCursor / sizeX;
            scanCursor++;
            probe.set(x, floor, z);
            if (!borders.contains(probe)) {
                continue;
            }
            for (int dy = -SCAN_BELOW; dy <= SCAN_ABOVE; dy++) {
                probe.set(x, floor + dy, z);
                if (buttons.size() < MAX_BUTTONS && level.getBlockState(probe).is(Blocks.STONE_BUTTON)) {
                    buttons.add(probe.immutable());
                }
            }
            budget -= layers;
        }
        if (scanCursor >= columns) {
            scanCursor = -1;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] ttt: room scan found {} stone button(s) {}",
                    buttons.size(), buttons);
        }
    }

    private void readWall(ClientLevel level, DungeonRoomBorders.Borders borders) {
        int floor = borders.min().getY();
        AABB room = new AABB(borders.min().getX(), floor - SCAN_BELOW, borders.min().getZ(),
                borders.max().getX() + 1, floor + SCAN_ABOVE + 1, borders.max().getZ() + 1);
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, room, f -> !f.isRemoved());

        long signature = buttons.size();
        int unreadable = 0;
        for (ItemFrame frame : frames) {
            int key = mapKey(level, frame);
            if (key == TicTacToeGrid.EMPTY) {
                unreadable++;
                continue;
            }
            signature = signature * 31 + frame.blockPosition().asLong() * 7 + key;
        }
        signature = signature * 31 + unreadable;
        if (signature == lastSignature) {
            return;
        }
        lastSignature = signature;

        List<TicTacToeGrid.Spot> spots = new ArrayList<>(buttons.size() + frames.size());
        for (BlockPos button : buttons) {
            spots.add(new TicTacToeGrid.Spot(button.getX(), button.getY(), button.getZ(), TicTacToeGrid.EMPTY));
        }
        StringBuilder seen = new StringBuilder();
        for (ItemFrame frame : frames) {
            int key = mapKey(level, frame);
            BlockPos at = frame.blockPosition();
            seen.append(' ').append(at.toShortString()).append('/').append(frame.getDirection())
                    .append('/').append(key == TicTacToeGrid.EMPTY ? "no-map" : "px" + key);
            if (key != TicTacToeGrid.EMPTY) {
                spots.add(new TicTacToeGrid.Spot(at.getX(), at.getY(), at.getZ(), key));
            }
        }
        TicTacToeGrid.Result result = TicTacToeGrid.read(spots);
        update(result, frames.size(), seen.toString());
    }

    /**
     * The map's centre pixel colour (0-255) for a frame holding a map whose data has arrived, else
     * {@link TicTacToeGrid#EMPTY}.
     */
    private static int mapKey(ClientLevel level, ItemFrame frame) {
        ItemStack item = frame.getItem();
        if (item.isEmpty()) {
            return TicTacToeGrid.EMPTY;
        }
        MapId id = item.get(DataComponents.MAP_ID);
        if (id == null) {
            return TicTacToeGrid.EMPTY;
        }
        MapItemSavedData data = level.getMapData(id);
        if (data == null) {
            return TicTacToeGrid.EMPTY;
        }
        return data.colors[MAP_CENTRE] & 0xFF;
    }

    private void update(TicTacToeGrid.Result result, int frameCount, String seen) {
        TicTacToeGrid.Board board = result.board();
        String shown;
        List<PuzzleHighlight> out = List.of();
        if (board == null) {
            shown = "unreadable (" + result.reason() + ")";
        } else if (!board.playerToMove()) {
            shown = board.render() + " " + result.reason() + " - nothing";
        } else {
            int mask = TicTacToeSearch.bestMoves(board.cells(), TicTacToeSearch.PLAYER);
            List<PuzzleHighlight> boxes = new ArrayList<>(Integer.bitCount(mask));
            StringBuilder cells = new StringBuilder();
            int color = PuzzleSettings.argb(type());
            for (int i = 0; i < 9; i++) {
                if ((mask & (1 << i)) == 0) {
                    continue;
                }
                cells.append(' ').append(i).append('@').append(board.x()[i]).append(',')
                        .append(board.y()[i]).append(',').append(board.z()[i]);
                boxes.add(PuzzleHighlight.answer(
                        new AABB(new BlockPos(board.x()[i], board.y()[i], board.z()[i])).inflate(0.02),
                        color, null));
            }
            shown = board.render() + " player to move - would box" + (mask == 0 ? " nothing (game over)" : cells);
            out = List.copyOf(boxes);
        }
        String line = buttons.size() + " button(s), " + frameCount + " frame(s):" + seen + " -> " + shown;
        if (!line.equals(lastLogged)) {
            lastLogged = line;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] ttt: {}", line);
        }
        drawn = out;
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        return type().stage() == SolverStage.FINISHED ? drawn : List.of();
    }

    @Override
    public void reset() {
        scanBorders = null;
        scanCursor = 0;
        buttons.clear();
        readCountdown = 0;
        lastSignature = Long.MIN_VALUE;
        lastLogged = null;
        finished = false;
        drawn = List.of();
    }
}
