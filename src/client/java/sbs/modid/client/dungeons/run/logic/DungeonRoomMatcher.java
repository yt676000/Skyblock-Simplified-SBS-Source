/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import sbs.modid.client.core.dev.RoomRotation;
import sbs.modid.client.dungeons.rooms.DungeonRoom;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches the real world against the {@link DungeonRoom} database using the <b>corner scheme</b>:
 * rooms are stored once, canonical (NORTH-normalised, relative to the footprint's NW
 * corner, <b>Y absolute</b> – Hypixel dungeons always generate at the same height). At runtime the
 * room's void-detected bounding box provides four exact hypotheses, one per spawn rotation, each pairing
 * a facing with the bounding-box corner that becomes the canonical origin under that rotation:
 *
 * <pre>
 *   NORTH → NW corner (minX, minZ)     EAST → NE corner (maxX, minZ)
 *   SOUTH → SW corner (minX, maxZ)     WEST → SE corner (maxX, maxZ)
 * </pre>
 *
 * Rotating the room maps its bounding box onto itself corner-for-corner, so the pivots are exact – no
 * search radius, no ±1 centre rounding. The (room, facing) with the most matching blocks wins, so the
 * room is recognised no matter which entrance the player used or how the room is rotated.
 *
 * <p><b>Scoring is per 32x32 cell.</b> The stored signature is split across the room's cells, each
 * carrying its own ~30 blocks ({@code RoomScanner}), and a placement is scored one cell at a time –
 * never as one blended room-wide ratio. The cell the player is standing in is scored first and wins
 * outright when it passes, which is what lets a 1x4 identify the moment it is entered: that cell's
 * chunks are loaded by definition, while the far end of the room may not have streamed in yet.
 *
 * <p>Player heads are deliberately ignored: they change at wither doors and are placed dynamically, so
 * only fixed blocks (hoppers, chests, dispensers, …) are compared. World access goes through the
 * {@link BlockLookup} seam, keeping the matcher unit-testable without Minecraft.
 */
public final class DungeonRoomMatcher {

    /** Block ids never used for matching (unreliable / dynamic). */
    private static final Set<String> IGNORED_BLOCKS = Set.of("minecraft:player_head");

    /** Resolves the registry id (e.g. {@code "minecraft:chest"}) of the block at a world position. */
    @FunctionalInterface
    public interface BlockLookup {
        String idAt(BlockPos pos);
    }

    /**
     * A resolved match: the room, its rotation ({@code facing}), the world pivot the canonical frame
     * hangs on ({@code anchor} – the bounding-box corner for that facing, Y = 0), and the match
     * strength. {@code matched}/{@code total} are the counts of the <b>winning cell</b>, not of the
     * whole room, and {@code accepted} is that cell's verdict (see {@link #score}).
     */
    public record RoomMatch(String name, Direction facing, BlockPos anchor, int matched, int total,
                            boolean accepted) {
        public double ratio() {
            return total == 0 ? 0.0 : (double) matched / total;
        }
    }

    /** One scanned block resolved to a world position plus whether the expected block is really there. */
    public record BlockResult(BlockPos world, boolean matched) {
    }

    private static final Direction[] FACINGS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static final int MIN_MATCHED = 5;
    private static final double MIN_RATIO = 0.6;

    private DungeonRoomMatcher() {
    }

    // ---- identification ----------------------------------------------------------------------------

    /** Border-only identification (dev scanner path – no player-cell enumeration). */
    public static RoomMatch identify(BlockLookup lookup, DungeonRoomBorders.Borders borders,
                                     Map<String, DungeonRoom> database) {
        return identify(lookup, borders, null, null, false, database);
    }

    /**
     * Identifies the room in two passes. <b>Pass 1</b> is map-narrowed: only the database rooms whose
     * stored size matches the footprint's shape (orientation-free: a "1x4" footprint tries "1x4" and
     * "4x1" entries) <i>and</i> whose stored map colour matches the painted tile - a tiny, targeted
     * candidate set. Only when nothing there is accepted does <b>pass 2</b> widen the search – by
     * dropping the <i>colour</i>, never the size.
     *
     * <p><b>The footprint's shape is a hard filter in both passes</b>, whatever produced it. It is
     * what keeps a room from being labelled with its NEIGHBOUR's name: the void-gap fallback merges
     * across a doorway whenever the door breaks its "fully void gap line" probe, and such a footprint
     * reaches into the next cell, where another room's signature blocks are all present and score a
     * near-perfect match (a fairy room briefly locked as 1x2 matched the 1x1 room to its south 8/9).
     * Demanding that the candidate's own size equal the detected shape rules that out – a 1x1 entry
     * cannot answer for a 1x2 footprint – while a correctly sized void lock still identifies, which
     * the dev scanner depends on (rooms whose map tile is slow to paint are named from the void lock).
     * A stored size that is missing or blank never excludes an entry.
     *
     * <p>{@code footprintTrusted} – whether the dungeon map ruled on this footprint – decides the two
     * remaining freedoms. Trusted: the painted colour also stays in pass 2 (a pink tile is a fairy
     * room and can never be some brown database room, however many blocks line up; only the
     * <i>stored</i> colour may be unknown), and every hypothesis is one of the four border-corner
     * pivots, so the room must stand exactly on the footprint. Untrusted (void-gap fallback,
     * pre-anchor placement): there is no painted colour to trust, and the player-cell enumeration
     * ({@link #placements}) is added – every placement in which the player's own grid cell is one of
     * the room's cells, under all four rotations – so a footprint that is the right size but placed
     * one cell off cannot hide a correctly scanned room.
     *
     * <p>That gate is the fix for rooms being labelled with a <b>neighbour's</b> name: the free
     * enumeration lets a big room be placed up to three cells away from the player, and a 1x4 room
     * standing next door has all 30 of its signature blocks present, so it outscored (raw matched
     * count) the small room the player was really standing in – whose secrets were then drawn inside
     * that neighbour and whose name spread over every tile the wrong lock touched.
     */
    public static RoomMatch identify(BlockLookup lookup, DungeonRoomBorders.Borders borders,
                                     BlockPos player, String mapColor, boolean footprintTrusted,
                                     Map<String, DungeonRoom> database) {
        BlockPos enumerateFrom = footprintTrusted ? null : player;
        RoomMatch narrowed = bestMatch(lookup, borders, enumerateFrom, player, database, borders.shape(), mapColor);
        if (accept(narrowed)) {
            return narrowed;
        }
        RoomMatch brute = bestMatch(lookup, borders, enumerateFrom, player, database, borders.shape(),
                footprintTrusted ? mapColor : null);
        return accept(brute) ? brute : null;
    }

    /** Best (room, facing) across the whole database, ignoring the acceptance threshold (debug). */
    public static RoomMatch bestMatch(BlockLookup lookup, DungeonRoomBorders.Borders borders,
                                      Map<String, DungeonRoom> database) {
        return bestMatch(lookup, borders, null, null, database, null, null);
    }

    /**
     * Best (room, facing) over every hypothesis, optionally restricted to rooms whose stored size /
     * map colour are compatible with {@code shapeFilter} / {@code colorFilter} ({@code null} = no
     * restriction). Ignores the acceptance threshold.
     *
     * <p>The two player positions are deliberately separate: {@code enumerateFrom} drives the
     * <i>placement</i> enumeration and is withheld for trusted footprints (see {@link #identify}),
     * while {@code player} only says which cell to score first and is always passed.
     */
    private static RoomMatch bestMatch(BlockLookup lookup, DungeonRoomBorders.Borders borders,
                                       BlockPos enumerateFrom, BlockPos player,
                                       Map<String, DungeonRoom> database,
                                       String shapeFilter, String colorFilter) {
        RoomMatch best = null;
        CellScore bestScore = null;
        for (Map.Entry<String, DungeonRoom> entry : database.entrySet()) {
            DungeonRoom room = entry.getValue();
            if (room.blocks == null || room.blocks.isEmpty()) {
                continue;
            }
            if (shapeFilter != null && !shapeCompatible(shapeFilter, room.room_size)) {
                continue;
            }
            if (colorFilter != null && !colorCompatible(colorFilter, room.map_color)) {
                continue;
            }
            for (Placement placement : placements(borders, enumerateFrom, room)) {
                CellScore score = score(lookup, placement.pivot(), placement.facing(), room, player);
                if (score == null || !better(score, bestScore)) {
                    continue;
                }
                bestScore = score;
                best = new RoomMatch(entry.getKey(), placement.facing(), placement.pivot(),
                        score.matched(), score.total(), score.accepted());
            }
        }
        return best;
    }

    /** One placement hypothesis: a rotation plus the canonical-origin pivot it hangs on. */
    private record Placement(Direction facing, BlockPos pivot) {
    }

    /**
     * Every placement hypothesis for a room: the four border-corner pivots (exact when the detected
     * footprint is right), plus - when the player position is known - every placement in which the
     * player's own 32-grid cell is one of the room's cells, for all four rotations. The enumeration
     * needs only the room's stored cell size, not the detected borders, so it still finds the room
     * when the border detection went wrong. Both canonical orientations of the stored size are
     * enumerated because old exports do not guarantee the canonical frame's WxH order. Duplicate
     * hypotheses collapse via set semantics; with ≤4-cell rooms this stays a handful of cheap,
     * early-breaking score calls.
     */
    private static List<Placement> placements(DungeonRoomBorders.Borders borders, BlockPos player,
                                              DungeonRoom room) {
        LinkedHashSet<Placement> out = new LinkedHashSet<>();
        for (Direction facing : FACINGS) {
            out.add(new Placement(facing, pivotFor(facing, borders)));
        }
        if (player == null) {
            return List.copyOf(out);
        }
        int cellX = DungeonRoomLocator.cornerCoord(player.getX());
        int cellZ = DungeonRoomLocator.cornerCoord(player.getZ());
        int grid = DungeonRoomLocator.GRID;
        int span = DungeonRoomLocator.ROOM_SPAN;
        for (int[] size : canonicalSizes(room)) {
            for (Direction facing : FACINGS) {
                // World-axis extent of the rotated room: EAST/SOUTH are the 90° rotations of the
                // relativeToActual matrix, so canonical (W,H) swaps to (H,W) there.
                boolean swap = facing == Direction.EAST || facing == Direction.SOUTH;
                int cellsX = swap ? size[1] : size[0];
                int cellsZ = swap ? size[0] : size[1];
                for (int a = 0; a < cellsX; a++) {
                    for (int b = 0; b < cellsZ; b++) {
                        int nwX = cellX - a * grid;
                        int nwZ = cellZ - b * grid;
                        int maxX = nwX + (cellsX - 1) * grid + span;
                        int maxZ = nwZ + (cellsZ - 1) * grid + span;
                        BlockPos pivot = switch (facing) {
                            case EAST -> new BlockPos(maxX, 0, nwZ);   // NE
                            case SOUTH -> new BlockPos(nwX, 0, maxZ);  // SW
                            case WEST -> new BlockPos(maxX, 0, maxZ);  // SE
                            default -> new BlockPos(nwX, 0, nwZ);      // NORTH → NW
                        };
                        out.add(new Placement(facing, pivot));
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The room's canonical cell size(s) for the enumeration: parsed from {@code room_size} ("2x1"),
     * with the L classes reduced to their bounding box (3 cells → 2x2, 4 cells → 2x3) and a
     * blocks-extent fallback for entries without a usable size. Non-square sizes yield both
     * orientations - old exports do not pin the canonical WxH order.
     */
    private static List<int[]> canonicalSizes(DungeonRoom room) {
        int w = 0;
        int h = 0;
        String size = room.room_size == null ? "" : room.room_size.trim();
        var rect = java.util.regex.Pattern.compile("([0-9]+)x([0-9]+)").matcher(size);
        if (rect.find()) {
            w = Integer.parseInt(rect.group(1));
            h = Integer.parseInt(rect.group(2));
        } else if (size.startsWith("L")) {
            var cells = java.util.regex.Pattern.compile("([0-9]+)").matcher(size);
            int count = cells.find() ? Integer.parseInt(cells.group(1)) : 4;
            w = 2;
            h = count <= 3 ? 2 : 3;
        }
        if (w < 1 || h < 1) {
            int maxX = 0;
            int maxZ = 0;
            if (room.blocks != null) {
                for (DungeonRoom.RoomBlock block : room.blocks) {
                    maxX = Math.max(maxX, block.relative_x);
                    maxZ = Math.max(maxZ, block.relative_z);
                }
            }
            w = maxX / DungeonRoomLocator.GRID + 1;
            h = maxZ / DungeonRoomLocator.GRID + 1;
        }
        w = Math.max(1, Math.min(4, w));
        h = Math.max(1, Math.min(4, h));
        return w == h ? List.of(new int[] {w, h}) : List.of(new int[] {w, h}, new int[] {h, w});
    }

    /**
     * Whether a stored map colour is compatible with the painted tile. A stored colour that states
     * nothing – {@code null}, blank, or the literal {@code "unknown"} old exports wrote when the map
     * could not be read at scan time – never excludes an entry.
     */
    private static boolean colorCompatible(String mapColor, String storedColor) {
        return storedColor == null || storedColor.isBlank() || "unknown".equalsIgnoreCase(storedColor)
                || storedColor.equalsIgnoreCase(mapColor);
    }

    /**
     * Whether a stored room size can be the given footprint shape: "NxM" is orientation-free
     * ("1x4" == "4x1"), every "L (…)" flavour counts as one L class (old scans disagree on the cell
     * count), and a room without size info is never excluded.
     */
    private static boolean shapeCompatible(String footprintShape, String storedSize) {
        if (storedSize == null || storedSize.isBlank()) {
            return true;
        }
        return normalizeShape(storedSize).equals(normalizeShape(footprintShape));
    }

    /** "4x1" -> "1x4", "L (3 cells)" -> "L"; anything unparseable stays as trimmed text. */
    private static String normalizeShape(String shape) {
        String text = shape == null ? "" : shape.trim();
        if (text.startsWith("L")) {
            return "L";
        }
        var m = java.util.regex.Pattern.compile("([0-9]+)x([0-9]+)").matcher(text);
        if (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return Math.min(a, b) + "x" + Math.max(a, b);
        }
        return text;
    }

    /**
     * The bounding-box corner that is the canonical (NW) origin when the room spawned with the given
     * rotation. Y is 0 so relative Y stays absolute (dungeon height is fixed).
     */
    public static BlockPos pivotFor(Direction facing, DungeonRoomBorders.Borders borders) {
        int minX = borders.min().getX();
        int minZ = borders.min().getZ();
        int maxX = borders.max().getX();
        int maxZ = borders.max().getZ();
        return switch (facing) {
            case EAST -> new BlockPos(maxX, 0, minZ);  // NE
            case SOUTH -> new BlockPos(minX, 0, maxZ); // SW
            case WEST -> new BlockPos(maxX, 0, maxZ);  // SE
            default -> new BlockPos(minX, 0, minZ);    // NORTH → NW (canonical identity)
        };
    }

    // ---- scoring -----------------------------------------------------------------------------------

    /** One placement's verdict: the winning cell's counts, whether it passed, and whose cell it was. */
    private record CellScore(int matched, int total, boolean accepted, boolean playerCell) {
    }

    /**
     * Scores a placement <b>one 32x32 cell at a time</b> and returns the winning cell.
     *
     * <p>The player's own cell is scored first and returned the moment it passes: it is the one cell
     * whose chunks are loaded by definition, so a multi-cell room identifies immediately instead of
     * waiting for its far end to stream in. Only when that cell cannot rule are the room's other cells
     * scored, each judged on its own – counts are never blended across cells, so a dense cell can no
     * longer carry an empty one.
     *
     * <p>A cell is usable evidence only when the room stores at least {@value #MIN_MATCHED} blocks in
     * it: a cell holding one lone chest would otherwise "identify" half the database. Rooms whose
     * <b>entire</b> scan is smaller than that (quick dev scans of one to three blocks) cannot be split
     * meaningfully at all and keep the historical all-or-nothing rule over every block they have.
     */
    private static CellScore score(BlockLookup lookup, BlockPos anchor, Direction facing,
                                   DungeonRoom room, BlockPos player) {
        int roomTotal = comparableTotal(room);
        if (roomTotal == 0) {
            return null;
        }
        if (roomTotal < MIN_MATCHED) {
            int matched = countMatches(lookup, anchor, facing, room, null, roomTotal);
            return new CellScore(matched, roomTotal, matched == roomTotal, true);
        }
        Long playerCell = player == null ? null : cellOf(facing, anchor, player);
        CellScore best = null;
        for (long cell : cellsOf(room, playerCell)) {
            int total = countBlocks(room, cell);
            if (total < MIN_MATCHED) {
                continue;   // too little signature stored in this cell to rule on the room
            }
            int matched = countMatches(lookup, anchor, facing, room, cell, total);
            boolean isPlayerCell = playerCell != null && playerCell == cell;
            CellScore candidate = new CellScore(matched, total,
                    matched >= MIN_MATCHED && matched >= total * MIN_RATIO, isPlayerCell);
            if (candidate.accepted() && isPlayerCell) {
                return candidate;   // the cell the player is standing in ruled - nothing beats that
            }
            if (better(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The cells to score, the player's own first. A player cell the room stores nothing in counts
     * zero blocks and is skipped by the caller's usability gate, so a wrong placement costs one lookup.
     */
    private static List<Long> cellsOf(DungeonRoom room, Long playerCell) {
        LinkedHashSet<Long> cells = new LinkedHashSet<>();
        if (playerCell != null) {
            cells.add(playerCell);
        }
        for (DungeonRoom.RoomBlock block : room.blocks) {
            if (!IGNORED_BLOCKS.contains(block.id)) {
                cells.add(DungeonRoomLocator.relativeCellKey(block.relative_x, block.relative_z));
            }
        }
        return List.copyOf(cells);
    }

    /** The canonical cell a world position falls into under the given placement. */
    private static long cellOf(Direction facing, BlockPos anchor, BlockPos pos) {
        BlockPos relative = RoomRotation.actualToRelative(facing, anchor, pos);
        return DungeonRoomLocator.relativeCellKey(relative.getX(), relative.getZ());
    }

    /** Ranking between two scores: accepted first, then the player's own cell, then raw hits. */
    private static boolean better(CellScore candidate, CellScore best) {
        if (best == null) {
            return true;
        }
        if (candidate.accepted() != best.accepted()) {
            return candidate.accepted();
        }
        if (candidate.playerCell() != best.playerCell()) {
            return candidate.playerCell();
        }
        return candidate.matched() > best.matched();
    }

    /** Counts how many of one cell's signature blocks are really there ({@code cell} null = all of them). */
    private static int countMatches(BlockLookup lookup, BlockPos anchor, Direction facing,
                                    DungeonRoom room, Long cell, int total) {
        // Small scans cannot reach MIN_MATCHED at all - their requirement is "every block present".
        int needed = Math.min(MIN_MATCHED, total);
        int matched = 0;
        int index = 0;
        for (DungeonRoom.RoomBlock block : room.blocks) {
            if (!inCell(block, cell)) {
                continue;
            }
            index++;
            BlockPos world = RoomRotation.relativeToActual(facing, anchor, block.relative_x, block.relative_y, block.relative_z);
            if (block.id.equals(lookup.idAt(world))) {
                matched++;
            } else if (matched + (total - index) < needed) {
                break; // can no longer reach the acceptance threshold
            }
        }
        return matched;
    }

    /** How many comparable blocks the room stores in one cell ({@code cell} null = the whole room). */
    private static int countBlocks(DungeonRoom room, Long cell) {
        int total = 0;
        for (DungeonRoom.RoomBlock block : room.blocks) {
            if (inCell(block, cell)) {
                total++;
            }
        }
        return total;
    }

    /** Whether a stored block counts towards {@code cell} – comparable, and in that cell. */
    private static boolean inCell(DungeonRoom.RoomBlock block, Long cell) {
        return !IGNORED_BLOCKS.contains(block.id)
                && (cell == null
                || cell == DungeonRoomLocator.relativeCellKey(block.relative_x, block.relative_z));
    }

    private static int comparableTotal(DungeonRoom room) {
        return room.blocks == null ? 0 : countBlocks(room, null);
    }

    /** Whether a match may be used: the winning cell passed its own threshold (see {@link #score}). */
    private static boolean accept(RoomMatch match) {
        return match != null && match.accepted();
    }

    // ---- debug helpers -----------------------------------------------------------------------------

    /** Per-block results for a placement (fixed blocks only) – drives the green/red debug highlight. */
    public static List<BlockResult> blockResults(BlockLookup lookup, BlockPos anchor, Direction facing, DungeonRoom room) {
        List<BlockResult> results = new ArrayList<>();
        if (room.blocks == null) {
            return results;
        }
        for (DungeonRoom.RoomBlock block : room.blocks) {
            if (IGNORED_BLOCKS.contains(block.id)) {
                continue;
            }
            BlockPos world = RoomRotation.relativeToActual(facing, anchor, block.relative_x, block.relative_y, block.relative_z);
            results.add(new BlockResult(world, block.id.equals(lookup.idAt(world))));
        }
        return results;
    }
}
