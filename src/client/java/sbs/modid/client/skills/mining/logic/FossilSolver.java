/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which tile to chisel next in the Fossil Excavator, and which fossil is buried.
 *
 * <p><b>Deliberately free of every Minecraft type.</b> A board is a width, a height and an array of
 * three-valued tiles; a fossil is a list of offsets. That is the whole input. It means the part of
 * this feature that can be wrong in a way nobody notices - the search - is the part that can be
 * tested at a desk, and the part that needs a live menu is reduced to filling these structures in.
 *
 * <h2>What it does</h2>
 * Enumerates every placement of every known fossil shape - each variant (rotation and mirror) at
 * every offset that fits - and keeps the ones consistent with what has been dug so far. From the
 * survivors it names the tile worth chiselling next, and, when one placement is left, where the rest
 * of the fossil lies.
 *
 * <h2>Consistency</h2>
 * A placement survives when all three hold:
 * <ul>
 *   <li>no tile of the shape lands on a square already dug and known to be empty;</li>
 *   <li>every square already dug and known to be fossil is covered by the shape - a revealed fossil
 *       tile the placement does not explain kills it;</li>
 *   <li>if the progress percentage is known, the fraction of the shape already revealed matches it
 *       within {@link #PERCENT_TOLERANCE}.</li>
 * </ul>
 *
 * <p>The percentage is the strongest constraint there is, because it is a statement about the
 * fossil's <i>total</i> size: a shape of six tiles with two revealed can only ever read 33%, so a
 * board showing 50% rules that shape out entirely, whatever its geometry. Hypixel shows a rounded
 * integer, so the comparison is rounded the same way and then given a point of slack in each
 * direction - a solver that is confidently wrong about a rounding boundary is worse than one that
 * keeps two candidates for one more chisel.
 *
 * <h2>What it refuses to do</h2>
 * With no candidates left it reports a contradiction and recommends nothing. That state is
 * reachable in practice - a shape table that is wrong or incomplete produces it - and the honest
 * response is to say so rather than to point at a tile chosen by a rule that has already failed.
 */
public final class FossilSolver {

    /** How far the computed percentage may sit from the shown one, in percentage points. */
    public static final int PERCENT_TOLERANCE = 1;

    /** What is known about one square of the dig area. */
    public enum Tile {
        /** Not dug yet. */
        UNKNOWN,
        /** Dug, and part of the fossil. */
        FOSSIL,
        /** Dug, and nothing there. */
        EMPTY
    }

    /**
     * One fossil, as the set of squares it occupies.
     *
     * <p>Offsets are relative and need no particular origin: {@link #variants()} normalises every
     * rotation and mirror back to the top-left corner before use.
     */
    public record Shape(String name, List<int[]> tiles) {

        public int size() {
            return tiles.size();
        }

        /**
         * The distinct orientations of this shape: four rotations, each optionally mirrored, with
         * duplicates removed.
         *
         * <p>Symmetric shapes collapse - a 2x2 block has one orientation, not eight - which matters
         * for more than speed: a duplicated variant would be counted twice when tiles are ranked by
         * how many candidates cover them, quietly biasing the recommendation toward symmetric
         * fossils.
         *
         * <p><b>Whether Hypixel actually rotates and mirrors its fossils is UNVERIFIED.</b> Allowing
         * both is the conservative choice: it can only ever keep candidates that the truth would
         * have removed, so the solver stays slower to commit rather than wrong.
         */
        public List<List<int[]>> variants() {
            Set<String> seen = new LinkedHashSet<>();
            List<List<int[]>> out = new ArrayList<>();
            List<int[]> current = tiles;
            for (int mirror = 0; mirror < 2; mirror++) {
                List<int[]> work = mirror == 0 ? current : mirrored(current);
                for (int rotation = 0; rotation < 4; rotation++) {
                    work = rotation == 0 ? work : rotated(work);
                    List<int[]> normalised = normalise(work);
                    if (seen.add(key(normalised))) {
                        out.add(normalised);
                    }
                }
            }
            return out;
        }

        private static List<int[]> mirrored(List<int[]> tiles) {
            List<int[]> out = new ArrayList<>(tiles.size());
            for (int[] tile : tiles) {
                out.add(new int[]{-tile[0], tile[1]});
            }
            return out;
        }

        /** Quarter turn: (x, y) becomes (-y, x). */
        private static List<int[]> rotated(List<int[]> tiles) {
            List<int[]> out = new ArrayList<>(tiles.size());
            for (int[] tile : tiles) {
                out.add(new int[]{-tile[1], tile[0]});
            }
            return out;
        }

        private static List<int[]> normalise(List<int[]> tiles) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            for (int[] tile : tiles) {
                minX = Math.min(minX, tile[0]);
                minY = Math.min(minY, tile[1]);
            }
            List<int[]> out = new ArrayList<>(tiles.size());
            for (int[] tile : tiles) {
                out.add(new int[]{tile[0] - minX, tile[1] - minY});
            }
            out.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
            return out;
        }

        private static String key(List<int[]> tiles) {
            StringBuilder sb = new StringBuilder();
            for (int[] tile : tiles) {
                sb.append(tile[0]).append(',').append(tile[1]).append(';');
            }
            return sb.toString();
        }
    }

    /** The dig area and what has been learned about it. */
    public static final class Board {
        private final int width;
        private final int height;
        private final Tile[] tiles;
        /** The shown excavation percentage, or {@code null} while none is shown. */
        private Integer percent;

        public Board(int width, int height) {
            this.width = width;
            this.height = height;
            this.tiles = new Tile[width * height];
            java.util.Arrays.fill(this.tiles, Tile.UNKNOWN);
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public Tile at(int x, int y) {
            return inside(x, y) ? tiles[y * width + x] : Tile.EMPTY;
        }

        public Board set(int x, int y, Tile tile) {
            if (inside(x, y)) {
                tiles[y * width + x] = tile;
            }
            return this;
        }

        public Board percent(Integer value) {
            this.percent = value;
            return this;
        }

        public Integer percent() {
            return percent;
        }

        public boolean inside(int x, int y) {
            return x >= 0 && x < width && y >= 0 && y < height;
        }
    }

    /** One surviving explanation of the board. */
    public record Placement(String fossil, List<int[]> tiles) {
    }

    /**
     * What the solver concluded.
     *
     * @param candidates    every placement still consistent with the board
     * @param fossils       the distinct fossil names among them, in candidate order
     * @param recommendedX  the tile to chisel next, or -1 when there is nothing to recommend
     * @param contradiction true when no shape fits at all, which means the shape table is wrong
     */
    public record Result(List<Placement> candidates, List<String> fossils,
                         int recommendedX, int recommendedY, boolean contradiction) {

        /** True once exactly one placement remains: the fossil and all its tiles are then known. */
        public boolean solved() {
            return candidates.size() == 1;
        }

        public boolean hasRecommendation() {
            return recommendedX >= 0 && recommendedY >= 0;
        }
    }

    private FossilSolver() {
    }

    /**
     * Solves the board against a shape table.
     *
     * <p>An empty shape table yields a contradiction, which is the correct answer to "which of no
     * known fossils is this": the caller is expected to say that the table has not been filled in
     * rather than to draw anything.
     */
    public static Result solve(Board board, List<Shape> shapes) {
        List<Placement> candidates = new ArrayList<>();
        for (Shape shape : shapes) {
            for (List<int[]> variant : shape.variants()) {
                collectPlacements(board, shape, variant, candidates);
            }
        }
        if (candidates.isEmpty()) {
            return new Result(List.of(), List.of(), -1, -1, true);
        }
        List<String> fossils = new ArrayList<>(new LinkedHashSet<>(
                candidates.stream().map(Placement::fossil).toList()));

        // Rank every undug square by how many surviving placements cover it. The square covered by
        // the most candidates is both the best first dig on an empty board and the most informative
        // one later, so the rule does not need a special case for the opening move.
        int bestX = -1;
        int bestY = -1;
        int bestCount = 0;
        for (int y = 0; y < board.height(); y++) {
            for (int x = 0; x < board.width(); x++) {
                if (board.at(x, y) != Tile.UNKNOWN) {
                    continue;
                }
                int count = 0;
                for (Placement placement : candidates) {
                    if (covers(placement, x, y)) {
                        count++;
                    }
                }
                if (count > bestCount) {
                    bestCount = count;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new Result(List.copyOf(candidates), List.copyOf(fossils), bestX, bestY, false);
    }

    /** The undug tiles of the single remaining placement, or empty while more than one survives. */
    public static List<int[]> confirmedTiles(Board board, Result result) {
        if (!result.solved()) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        for (int[] tile : result.candidates().get(0).tiles()) {
            if (board.at(tile[0], tile[1]) == Tile.UNKNOWN) {
                out.add(tile);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static void collectPlacements(Board board, Shape shape, List<int[]> variant,
                                          List<Placement> into) {
        int spanX = 0;
        int spanY = 0;
        for (int[] tile : variant) {
            spanX = Math.max(spanX, tile[0]);
            spanY = Math.max(spanY, tile[1]);
        }
        for (int originY = 0; originY + spanY < board.height(); originY++) {
            for (int originX = 0; originX + spanX < board.width(); originX++) {
                List<int[]> placed = new ArrayList<>(variant.size());
                for (int[] tile : variant) {
                    placed.add(new int[]{originX + tile[0], originY + tile[1]});
                }
                if (consistent(board, placed)) {
                    into.add(new Placement(shape.name(), placed));
                }
            }
        }
    }

    private static boolean consistent(Board board, List<int[]> placed) {
        int revealed = 0;
        for (int[] tile : placed) {
            Tile state = board.at(tile[0], tile[1]);
            if (state == Tile.EMPTY) {
                return false;   // the shape would cover a square proven to hold nothing
            }
            if (state == Tile.FOSSIL) {
                revealed++;
            }
        }
        // Every revealed fossil square has to belong to this placement. One outside it means the
        // board is showing fossil where this shape says there is none, which no amount of further
        // digging can reconcile.
        for (int y = 0; y < board.height(); y++) {
            for (int x = 0; x < board.width(); x++) {
                if (board.at(x, y) == Tile.FOSSIL && !containsTile(placed, x, y)) {
                    return false;
                }
            }
        }
        Integer percent = board.percent();
        if (percent == null) {
            return true;
        }
        int computed = (int) Math.round(100.0 * revealed / placed.size());
        return Math.abs(computed - percent) <= PERCENT_TOLERANCE;
    }

    private static boolean containsTile(List<int[]> tiles, int x, int y) {
        for (int[] tile : tiles) {
            if (tile[0] == x && tile[1] == y) {
                return true;
            }
        }
        return false;
    }

    private static boolean covers(Placement placement, int x, int y) {
        return containsTile(placement.tiles(), x, y);
    }
}
