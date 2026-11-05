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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Ice Fill: draws the walk that steps on every ice tile of a floor exactly once and ends at the
 * floor's exit. Reads blocks and the player's position; never moves the player.
 *
 * <p>The room's blocks are scanned once on entry (retried every {@link #SCAN_EVERY} ticks until
 * floors are found). After that only the floors' own cells are re-read, every
 * {@link #READ_EVERY} ticks; ice turning into packed ice marks a walked tile. The route is
 * re-planned only when the player leaves it - a player walking it just advances the drawing.
 *
 * <p>Only the floor the player is on (else the lowest unfinished one) is drawn: the next straight
 * run strongly, the rest of the route faintly, one thin box per straight run.
 */
public final class IceFillSolver implements PuzzleStrategy {

    static final int SCAN_EVERY = 40;
    static final int READ_EVERY = 2;
    /** Heights scanned for ice. UNVERIFIED in 26.2: ice is estimated at y 68..72. */
    static final int MIN_Y = 66;
    static final int MAX_Y = 76;
    private static final int REST_COLOR = 0x80A0E8FF;
    private static final double LINE = 0.08;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private List<IceFillBoard.Floor> floors = List.of();
    private boolean[][] covered = new boolean[0][];
    private int[][] routes = new int[0][];
    /** Per floor: how far along its route the player is. */
    private int[] progress = new int[0];
    private int ticks;
    private int lastFloor = -2;
    private int lastCell = -2;
    private String lastLogged = "";
    private List<PuzzleHighlight> highlights = List.of();

    @Override
    public PuzzleType type() {
        return PuzzleType.ICE_FILL;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        int tick = ticks++;
        if (floors.isEmpty()) {
            if (tick % SCAN_EVERY == 0) {
                scan(level);
            }
            return;
        }
        if (tick % READ_EVERY != 0) {
            return;
        }
        boolean changed = readCovered(level);
        BlockPos feet = minecraft.player.blockPosition();
        int floorIndex = -1;
        int cell = -1;
        for (int f = 0; f < floors.size(); f++) {
            IceFillBoard.Floor floor = floors.get(f);
            if (floor.y() == feet.getY() - 1) {
                int i = floor.index(feet.getX(), feet.getZ());
                if (i >= 0) {
                    floorIndex = f;
                    cell = i;
                    break;
                }
            }
        }
        if (!changed && floorIndex == lastFloor && cell == lastCell) {
            return;
        }
        lastFloor = floorIndex;
        lastCell = cell;
        if (floorIndex < 0) {
            floorIndex = firstUnfinished();
            if (floorIndex >= 0 && (changed || routes[floorIndex] == null)) {
                replan(floorIndex, -1);
            }
        } else if (!followsRoute(floorIndex, cell)) {
            replan(floorIndex, cell);
        }
        build(floorIndex);
    }

    /** One pass over the room's footprint for ice with air above. */
    private void scan(ClientLevel level) {
        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        if (borders == null) {
            return;
        }
        List<IceFillBoard.Tile> tiles = new ArrayList<>();
        for (int x = borders.min().getX(); x <= borders.max().getX(); x++) {
            for (int z = borders.min().getZ(); z <= borders.max().getZ(); z++) {
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    cursor.set(x, y, z);
                    if (!isIce(level.getBlockState(cursor)) || !borders.contains(cursor)) {
                        continue;
                    }
                    cursor.set(x, y + 1, z);
                    if (level.getBlockState(cursor).isAir()) {
                        tiles.add(new IceFillBoard.Tile(x, y, z));
                    }
                }
            }
        }
        floors = IceFillBoard.floors(tiles);
        if (floors.isEmpty()) {
            log("scan: " + tiles.size() + " ice tiles, no floor yet");
            return;
        }
        covered = new boolean[floors.size()][];
        routes = new int[floors.size()][];
        progress = new int[floors.size()];
        StringBuilder sb = new StringBuilder("scan: ").append(floors.size()).append(" floors");
        for (int f = 0; f < floors.size(); f++) {
            IceFillBoard.Floor floor = floors.get(f);
            covered[f] = new boolean[floor.cells().length];
            sb.append(" | y=").append(floor.y()).append(' ').append(floor.width()).append('x')
                    .append(floor.depth()).append(" tiles=").append(floor.tiles())
                    .append(" entry=").append(floor.describe(floor.entry()))
                    .append(" exit=").append(floor.describe(floor.exit()));
        }
        log(sb.append(" - entry/exit inferred, verify").toString());
        readCovered(level);
        lastFloor = -2;
    }

    private static boolean isIce(BlockState state) {
        return state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE);
    }

    /** Re-reads every floor cell; packed ice means walked. Whether anything changed. */
    private boolean readCovered(ClientLevel level) {
        boolean changed = false;
        for (int f = 0; f < floors.size(); f++) {
            IceFillBoard.Floor floor = floors.get(f);
            boolean[] cells = floor.cells();
            boolean[] walked = covered[f];
            for (int i = 0; i < cells.length; i++) {
                if (!cells[i]) {
                    continue;
                }
                cursor.set(floor.xOf(i), floor.y(), floor.zOf(i));
                boolean now = level.getBlockState(cursor).is(Blocks.PACKED_ICE);
                if (now != walked[i]) {
                    walked[i] = now;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Advances along the current route when the player stands on its next tile. */
    private boolean followsRoute(int f, int cell) {
        int[] route = routes[f];
        if (route == null) {
            return false;
        }
        for (int k = progress[f]; k < Math.min(route.length, progress[f] + 2); k++) {
            if (route[k] == cell) {
                progress[f] = k;
                return true;
            }
        }
        return false;
    }

    private void replan(int f, int cell) {
        IceFillBoard.Floor floor = floors.get(f);
        IceFillBoard.Plan plan = IceFillBoard.plan(floor, covered[f], cell);
        routes[f] = plan.route() == null ? null : plan.route().path();
        progress[f] = 0;
        log(plan.route() == null
                ? "floor y=" + floor.y() + ": no route (" + plan.reason() + ")"
                : "floor y=" + floor.y() + ": route " + plan.route().path().length + " tiles from "
                + floor.describe(plan.route().path()[0]) + " to " + floor.describe(floor.exit())
                + ", " + plan.route().turns() + " turns, " + plan.route().nodes() + " nodes - would draw");
    }

    private int firstUnfinished() {
        for (int f = 0; f < floors.size(); f++) {
            boolean[] cells = floors.get(f).cells();
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] && !covered[f][i]) {
                    return f;
                }
            }
        }
        return -1;
    }

    /**
     * The remaining route: one line through every corner in the Ice Fill colour (setting "Ice Fill:
     * One Line", default, asked 2026-10-17), or one thin box per straight run with the first strong.
     */
    private void build(int f) {
        if (f < 0 || routes[f] == null) {
            highlights = List.of();
            return;
        }
        IceFillBoard.Floor floor = floors.get(f);
        int[] route = routes[f];
        int color = PuzzleSettings.argb(PuzzleType.ICE_FILL);
        double y = floor.y() + 1.02;
        if (ConfigManager.getInstance().get().dungeons.puzzleIceFillLine) {
            highlights = List.copyOf(asLine(floor, route, progress[f], y, color));
            return;
        }
        List<PuzzleHighlight> out = new ArrayList<>();
        int start = progress[f];
        while (start < route.length - 1) {
            int step = route[start + 1] - route[start];
            int end = start + 1;
            while (end + 1 < route.length && route[end + 1] - route[end] == step) {
                end++;
            }
            double ax = floor.xOf(route[start]) + 0.5;
            double az = floor.zOf(route[start]) + 0.5;
            double bx = floor.xOf(route[end]) + 0.5;
            double bz = floor.zOf(route[end]) + 0.5;
            AABB box = new AABB(Math.min(ax, bx) - LINE, y, Math.min(az, bz) - LINE,
                    Math.max(ax, bx) + LINE, y + LINE, Math.max(az, bz) + LINE);
            out.add(out.isEmpty() ? PuzzleHighlight.answer(box, color, null)
                    : PuzzleHighlight.context(box, REST_COLOR, null));
            start = end;
        }
        highlights = List.copyOf(out);
    }

    /** One segment per straight run from {@code start} on, corner to corner, all in {@code color}. */
    private static List<PuzzleHighlight> asLine(IceFillBoard.Floor floor, int[] route, int start, double y, int color) {
        List<PuzzleHighlight> out = new ArrayList<>();
        while (start < route.length - 1) {
            int step = route[start + 1] - route[start];
            int end = start + 1;
            while (end + 1 < route.length && route[end + 1] - route[end] == step) {
                end++;
            }
            net.minecraft.world.phys.Vec3 a = new net.minecraft.world.phys.Vec3(
                    floor.xOf(route[start]) + 0.5, y, floor.zOf(route[start]) + 0.5);
            net.minecraft.world.phys.Vec3 b = new net.minecraft.world.phys.Vec3(
                    floor.xOf(route[end]) + 0.5, y, floor.zOf(route[end]) + 0.5);
            out.add(PuzzleHighlight.line(a, b, color, null));
            start = end;
        }
        return out;
    }

    private void log(String line) {
        if (!line.equals(lastLogged)) {
            lastLogged = line;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] Ice Fill {}", line);
        }
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        return highlights;
    }

    @Override
    public void reset() {
        floors = List.of();
        covered = new boolean[0][];
        routes = new int[0][];
        progress = new int[0];
        ticks = 0;
        lastFloor = -2;
        lastCell = -2;
        lastLogged = "";
        highlights = List.of();
    }
}
