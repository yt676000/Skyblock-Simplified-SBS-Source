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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.dev.RoomRotation;
import sbs.modid.client.dungeons.puzzle.logic.BoulderBoard.Move;
import sbs.modid.client.dungeons.puzzle.logic.BoulderBoard.Result;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.puzzle.model.SolverStage;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher.RoomMatch;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Boulder: a 7 x 6 grid of 3 x 3 boulders between the entrance and the chest; each push moves one
 * boulder one slot away from the player, and the room is done when a path to the chest is open.
 * This reads the grid (one block sampled per slot), searches the shortest push sequence
 * ({@link BoulderBoard}) and marks the button to press next, numbered, with the rest of the sequence
 * faint. Reads and draws; never clicks.
 *
 * <p><b>Drawing is gated on the stage.</b> While {@link PuzzleType#BOULDER} is {@link
 * SolverStage#PROBE_ONLY} this only logs. Promoted to {@code FINISHED} on 2026-10-15: in the
 * maintainer's run the first read solved to three pushes, and each later read (after the player
 * pushed) solved to exactly the remaining ones - the board is read right and the plan holds.
 *
 * <p>Re-reads the 42 slots every {@link #READ_INTERVAL} ticks and re-solves only when the board
 * changed, so a teammate's push resyncs the sequence.
 */
public final class BoulderSolver implements PuzzleStrategy {

    /**
     * Room-relative geometry (corner frame, Y absolute). Rows are fixed at z 6..21 - see {@link
     * #detectRows} for why.
     */
    static final int FIRST_X = 6;
    static final int PITCH = 3;
    /** The grid's chest-side row (room-relative z); rows run 6, 9, ... 21, the entry walkway is z 24. */
    static final int FIRST_Z = 6;
    /** Rows logged for the record: z = 0, 3, ... 30. */
    private static final int ROW_CANDIDATES = 11;
    static final int SAMPLE_Y = 66;
    static final int BUTTON_Y = 65;

    private static final int READ_INTERVAL = 10;
    private static final int OTHER_COLOR = 0x80FFC94D;

    /** World position of each slot's sample block, board order; built once per room. */
    private final BlockPos[] samples = new BlockPos[BoulderBoard.CELLS];
    private final boolean[] occupied = new boolean[BoulderBoard.CELLS];
    private Direction facing;
    private BlockPos anchor;
    /**
     * Whether board row 0 (entry) is the grid's low-Z row in the room frame. Always {@code false}:
     * in the rotation-normalised frame the walkway (z 21..24) and the door are at high z in every
     * room read on 2026-10-18; guessing it from where the player stood read one room backwards.
     */
    private static final boolean entryLowZ = false;
    /** Room-relative z of grid row k counted from the low-Z end; set by {@link #detectRows}. */
    private int firstZ;
    private boolean geometryReady;
    private boolean rowsLogged;
    private int ticks;
    private long lastBoard = -1;

    private List<PuzzleHighlight> highlights = List.of();

    @Override
    public PuzzleType type() {
        return PuzzleType.BOULDER;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (ticks++ % READ_INTERVAL != 0) {
            return;
        }
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        if (!geometryReady && !buildGeometry(minecraft)) {
            return;
        }
        for (int i = 0; i < BoulderBoard.CELLS; i++) {
            BlockPos pos = samples[i];
            if (!level.hasChunkAt(pos)) {
                return;   // half-loaded room: never solve a partial read
            }
            occupied[i] = !level.getBlockState(pos).isAir();
        }
        long board = BoulderBoard.fromSamples(occupied);
        if (board == lastBoard) {
            return;
        }
        lastBoard = board;
        Result result = BoulderBoard.solve(board);
        List<PuzzleHighlight> built = build(result);
        boolean draw = PuzzleType.BOULDER.stage() == SolverStage.FINISHED;
        highlights = draw ? built : List.of();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] Boulder facing={} entry={} board={} -> {} {} states={}{}",
                facing, entryLowZ ? "lowZ" : "highZ", BoulderBoard.format(board), result.status(),
                result.moves(), result.statesSearched(), draw ? "" : " (probe: not drawn)");
    }

    /**
     * Slot positions from the matched room's anchor and rotation. The entry side is whichever end
     * of the grid the player is nearer when first read - the player has just walked in.
     */
    private boolean buildGeometry(Minecraft minecraft) {
        RoomMatch match = DungeonRoomTracker.getInstance().activeMatch();
        if (match == null || match.facing() == null || match.anchor() == null) {
            return false;
        }
        facing = match.facing();
        anchor = match.anchor();
        Vec3 entered = RoomRotation.actualToRelative(facing, anchor,
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
        if (!detectRows(minecraft.level, entered.z > 15)) {
            return false;
        }
        for (int r = 0; r < BoulderBoard.ROWS; r++) {
            for (int c = 0; c < BoulderBoard.COLS; c++) {
                samples[r * BoulderBoard.COLS + c] =
                        RoomRotation.relativeToActual(facing, anchor, relX(c), SAMPLE_Y, relZ(r));
            }
        }
        geometryReady = true;
        return true;
    }

    private static int relX(int col) {
        return FIRST_X + PITCH * col;
    }

    private int relZ(int row) {
        int k = entryLowZ ? row : BoulderBoard.ROWS - 1 - row;
        return firstZ + PITCH * k;
    }

    /**
     * Sets the grid to the fixed frame z {@link #FIRST_Z}..{@code FIRST_Z + 15} and logs, once per
     * room, which candidate rows hold a boulder and the floor under each - the record any later
     * change to the frame has to be argued from. Returns {@code false} while the chunks still load.
     *
     * <p>The frame is fixed at z 6..21 (2026-10-18). z 9..24 was one row short on the chest side:
     * in the 2026-10-18 runs a boulder pushed off its far row vanished from the read instead of
     * being blocked, so a slot row exists at z 6, and z 24 was empty in all eight boards (walkway).
     * Without the z 6 row every opening board read NO_SOLUTION. Two attempts to find the rows
     * instead both failed on real rooms: "gravel under every open slot" matched no row, and "a row
     * with a boulder in it" broke on grids with empty rows.
     */
    private boolean detectRows(ClientLevel level, boolean entryHighZ) {
        StringBuilder seen = new StringBuilder();
        StringBuilder floors = new StringBuilder();
        for (int k = 0; k < ROW_CANDIDATES; k++) {
            int z = k * PITCH;
            boolean boulder = false;
            for (int c = 0; c < BoulderBoard.COLS; c++) {
                BlockPos pos = RoomRotation.relativeToActual(facing, anchor, relX(c), SAMPLE_Y, z);
                if (!level.hasChunkAt(pos)) {
                    return false;
                }
                boulder |= !level.getBlockState(pos).isAir();
            }
            seen.append(boulder ? '#' : '.');
            floors.append(k == 0 ? "" : " ").append(z).append('=').append(floorUnder(level,
                    RoomRotation.relativeToActual(facing, anchor, relX(BoulderBoard.COLS / 2), SAMPLE_Y, z)));
        }
        firstZ = FIRST_Z;
        if (!rowsLogged) {
            rowsLogged = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] Boulder grid rows at z={}..{} (fixed), entry {}; "
                            + "rows z=0..30 step 3 read {} (# = something solid in the row) floors {}",
                    firstZ, firstZ + PITCH * (BoulderBoard.ROWS - 1), entryHighZ ? "high z" : "low z", seen, floors);
        }
        return true;
    }

    /** The id of the first solid block within four below {@code pos}, for the log. */
    private static String floorUnder(ClientLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos below = pos.mutable();
        for (int dy = 0; dy <= 4; dy++) {
            below.setY(pos.getY() - dy);
            BlockState state = level.getBlockState(below);
            if (!state.isAir()) {
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                return dy == 0 ? "boulder:" + id : id + "@-" + dy;
            }
        }
        return "air";
    }

    /** The button for each push in the sequence (first strong), plus its boulder. Built on change only. */
    private List<PuzzleHighlight> build(Result result) {
        if (result.moves().isEmpty()) {
            return List.of();
        }
        int color = PuzzleSettings.argb(PuzzleType.BOULDER);
        List<PuzzleHighlight> out = new ArrayList<>(result.moves().size() + 1);
        for (int i = 0; i < result.moves().size(); i++) {
            Move move = result.moves().get(i);
            int dz = entryLowZ ? move.push().dRow : -move.push().dRow;
            int dx = move.push().dCol;
            // The button sits on the face opposite the push: two blocks back from the slot centre.
            int bx = relX(move.col()) - 2 * dx;
            int bz = relZ(move.row()) - 2 * dz;
            BlockPos button = RoomRotation.relativeToActual(facing, anchor, bx, BUTTON_Y, bz);
            String label = (i == 0 ? "§a" : "§7") + (i + 1);
            if (i == 0) {
                out.add(PuzzleHighlight.answer(new AABB(button), color, label));
                BlockPos centre = RoomRotation.relativeToActual(facing, anchor,
                        relX(move.col()), BUTTON_Y, relZ(move.row()));
                out.add(PuzzleHighlight.context(new AABB(centre).inflate(1.0), OTHER_COLOR, null));
            } else {
                out.add(PuzzleHighlight.context(new AABB(button), OTHER_COLOR, label));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        return highlights;
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(samples, null);
        java.util.Arrays.fill(occupied, false);
        facing = null;
        anchor = null;
        geometryReady = false;
        rowsLogged = false;
        ticks = 0;
        lastBoard = -1;
        highlights = List.of();
    }
}
