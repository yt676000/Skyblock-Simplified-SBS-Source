/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Creeper Beams: boxes the lit lanterns that belong together, one colour and number per pair.
 * Reads blocks and the creeper; never clicks or aims.
 *
 * <p><b>What it reads.</b> On entry a bounded scan of the room's footprint (from
 * {@link DungeonRoomTracker#borders()}, a Y window above the door floor) collects every sea lantern
 * and prismarine block - split over ticks with a cursor, a few layers a tick. After that only those
 * positions are re-read, twice a second, and the pairing is recomputed only when one of them changed
 * or the creeper moved. The pairing itself is {@link BeamPairing}: pure geometry, tested.
 *
 * <p><b>Facts and their sources.</b> Lit lanterns are sea lanterns and a connected pair turns to
 * prismarine (wiki, unverified in 26.2). The centre is the
 * creeper entity standing in the middle of the room (wiki). Whether all three are still those blocks
 * and that entity in 26.2 has not been watched - the log line says what was found.
 *
 * <p><b>Stage: probe.</b> Off by default. Every change of what it read and what it would draw is
 * logged as one {@code [SBS][Puzzle] creeper beams:} line, relative to the room anchor and with the
 * matched facing, so a run can be checked against the room.
 */
public final class CreeperBeamsSolver implements PuzzleStrategy {

    /** Layers below / above the door-floor Y the scan covers. {@code ESTIMATED}; logged when hit. */
    private static final int SCAN_BELOW = 2;
    private static final int SCAN_ABOVE = 24;
    /** Y layers scanned per tick (a 1x1 room layer is 31x31 = 961 reads). */
    private static final int LAYERS_PER_TICK = 9;
    /** Ticks between re-reads of the known lantern positions and the creeper. */
    private static final int RECHECK_TICKS = 10;

    /** Pair colours after the first (which is the player's configured colour). Opaque ARGB. */
    /** Half size of the box a beam must cross: hitbox 0.3 + 0.2 sideways, 0.85 + 0.75 bob vertically. */
    private static final double HIT_HALF_WIDTH = 0.5;
    private static final double HIT_HALF_HEIGHT = 1.6;
    private static final int[] PAIR_COLORS = {
        0xFFFFAA00, 0xFF55FFFF, 0xFFFF55FF, 0xFFFFFF55, 0xFFFF5555, 0xFF5555FF, 0xFFFFFFFF, 0xFFAA00AA,
    };

    private final BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();
    /** Every sea lantern / prismarine position the scan found, as {@link BlockPos#asLong}. */
    private final LongArrayList beamBlocks = new LongArrayList();
    /** Per {@link #beamBlocks} entry: currently a sea lantern (lit, still to connect). */
    private boolean[] lit = new boolean[0];

    private int scanY = Integer.MIN_VALUE;
    private int scanTopY;
    private boolean scanned;
    private int ticks;
    private Vec3 creeper;
    private boolean dirty;
    private String lastLogged = "";

    private List<PuzzleHighlight> highlights = List.of();

    @Override
    public PuzzleType type() {
        return PuzzleType.CREEPER_BEAMS;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        ClientLevel level = minecraft.level;
        if (borders == null || level == null) {
            return;
        }
        if (!scanned) {
            scanStep(level, borders);
            if (!scanned) {
                return;
            }
            // The scan just finished: read and draw now, not a recheck interval later - the
            // answer appeared seconds after entering (2026-10-16).
        } else if (++ticks % RECHECK_TICKS != 0) {
            return;
        }
        recheck(level, borders);
        if (dirty) {
            dirty = false;
            recompute(borders);
        }
    }

    /** A few Y layers of the footprint's bounding box; finishes the scan when past the window. */
    private void scanStep(ClientLevel level, DungeonRoomBorders.Borders borders) {
        if (scanY == Integer.MIN_VALUE) {
            scanY = borders.min().getY() - SCAN_BELOW;
            scanTopY = borders.min().getY() + SCAN_ABOVE;
        }
        int end = Math.min(scanTopY, scanY + LAYERS_PER_TICK - 1);
        for (int y = scanY; y <= end; y++) {
            for (int x = borders.min().getX(); x <= borders.max().getX(); x++) {
                for (int z = borders.min().getZ(); z <= borders.max().getZ(); z++) {
                    cursorPos.set(x, y, z);
                    if (!borders.contains(cursorPos)) {
                        continue;
                    }
                    Block block = level.getBlockState(cursorPos).getBlock();
                    if (block == Blocks.SEA_LANTERN || block == Blocks.PRISMARINE) {
                        beamBlocks.add(cursorPos.asLong());
                    }
                }
            }
        }
        scanY = end + 1;
        if (scanY > scanTopY) {
            scanned = true;
            lit = new boolean[beamBlocks.size()];
            dirty = true;
        }
    }

    /** Re-reads the known positions and the creeper; marks dirty on any change. */
    private void recheck(ClientLevel level, DungeonRoomBorders.Borders borders) {
        for (int i = 0, n = beamBlocks.size(); i < n; i++) {
            cursorPos.set(beamBlocks.getLong(i));
            boolean now = level.getBlockState(cursorPos).getBlock() == Blocks.SEA_LANTERN;
            if (now != lit[i]) {
                lit[i] = now;
                dirty = true;
            }
        }
        Vec3 found = findCreeper(borders);
        // Horizontal only: the creeper bobs up and down by ~1.5 blocks (2026-10-16 log), and every
        // bob used to re-pair, re-draw and log. It stands on one spot; a real move is sideways.
        if (found == null ? creeper != null : creeper == null || horizontalSq(found, creeper) > 1.0) {
            creeper = found;
            dirty = true;
        }
    }

    private static double horizontalSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /** The centre of the one creeper inside the room, or {@code null} unless exactly one. */
    private static Vec3 findCreeper(DungeonRoomBorders.Borders borders) {
        List<Tracked> living = EntityIndex.bucket(EntityKind.LIVING);
        Vec3 found = null;
        for (int i = 0, n = living.size(); i < n; i++) {
            Entity entity = living.get(i).entity;
            if (entity instanceof Creeper && entity.isAlive() && borders.contains(entity.blockPosition())) {
                if (found != null) {
                    return null;
                }
                found = entity.getBoundingBox().getCenter();
            }
        }
        return found;
    }

    /** Runs only on a change: pairs the lit lanterns, rebuilds the highlight list, logs once. */
    private void recompute(DungeonRoomBorders.Borders borders) {
        List<BlockPos> lanterns = new ArrayList<>();
        int prismarine = 0;
        for (int i = 0; i < beamBlocks.size(); i++) {
            if (lit[i]) {
                lanterns.add(BlockPos.of(beamBlocks.getLong(i)));
            } else {
                prismarine++;
            }
        }
        List<BeamPairing.Pair> pairs = List.of();
        if (creeper != null && !lanterns.isEmpty()) {
            List<BeamPairing.Point> points = new ArrayList<>(lanterns.size());
            for (BlockPos pos : lanterns) {
                points.add(new BeamPairing.Point(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            }
            // The creeper's hitbox (0.6 wide, 1.7 tall) grown sideways and by its ~1.5-block bob,
            // so the pairs hold however high it floats at the moment of the read.
            pairs = BeamPairing.throughBox(points,
                    new BeamPairing.Point(creeper.x - HIT_HALF_WIDTH, creeper.y - HIT_HALF_HEIGHT, creeper.z - HIT_HALF_WIDTH),
                    new BeamPairing.Point(creeper.x + HIT_HALF_WIDTH, creeper.y + HIT_HALF_HEIGHT, creeper.z + HIT_HALF_WIDTH));
        }

        List<PuzzleHighlight> out = new ArrayList<>(pairs.size() * 3);
        for (int p = 0; p < pairs.size(); p++) {
            BeamPairing.Pair pair = pairs.get(p);
            int color = p == 0 ? PuzzleSettings.argb(PuzzleType.CREEPER_BEAMS)
                    : PAIR_COLORS[(p - 1) % PAIR_COLORS.length];
            BlockPos a = lanterns.get(pair.a());
            BlockPos b = lanterns.get(pair.b());
            // The pair is a line between the two lanterns (asked for 2026-10-16), unnumbered (the
            // numbers were asked away 2026-10-17); each lantern keeps a thin box for its end.
            out.add(PuzzleHighlight.line(Vec3.atCenterOf(a), Vec3.atCenterOf(b), color, null));
            out.add(PuzzleHighlight.context(new AABB(a), color, null));
            out.add(PuzzleHighlight.context(new AABB(b), color, null));
        }
        // Only lanterns in a matching pair are drawn; a lit lantern no beam through the creeper can
        // reach is not part of the answer (asked 2026-10-16, replacing the white "all others").
        highlights = List.copyOf(out);
        log(borders, lanterns, prismarine, pairs);
    }

    private void log(DungeonRoomBorders.Borders borders, List<BlockPos> lanterns, int prismarine,
                     List<BeamPairing.Pair> pairs) {
        DungeonRoomTracker tracker = DungeonRoomTracker.getInstance();
        BlockPos anchor = tracker.anchor() != null ? tracker.anchor() : borders.min();
        DungeonRoomMatcher.RoomMatch match = tracker.activeMatch();
        StringBuilder sb = new StringBuilder(256);
        sb.append("facing=").append(match == null ? "?" : match.facing())
                .append(" creeper=");
        if (creeper == null) {
            sb.append("NOT FOUND");
        } else {
            sb.append(String.format(java.util.Locale.ROOT, "(%.1f,%.1f,%.1f)", creeper.x - anchor.getX(),
                    creeper.y - anchor.getY(), creeper.z - anchor.getZ()));
        }
        sb.append(" lanterns=").append(lanterns.size()).append(' ');
        appendRel(sb, lanterns, anchor);
        sb.append(" prismarine=").append(prismarine).append(" pairs=[");
        for (int p = 0; p < pairs.size(); p++) {
            BeamPairing.Pair pair = pairs.get(p);
            if (p > 0) {
                sb.append(", ");
            }
            sb.append(p + 1).append(':');
            appendRel(sb, lanterns.get(pair.a()), anchor);
            sb.append("<->");
            appendRel(sb, lanterns.get(pair.b()), anchor);
        }
        sb.append(']');
        String line = sb.toString();
        if (!line.equals(lastLogged)) {
            lastLogged = line;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] creeper beams: {} (scan y {}..{}, rel to anchor)",
                    line, scanTopY - SCAN_ABOVE - SCAN_BELOW, scanTopY);
        }
    }

    private static void appendRel(StringBuilder sb, List<BlockPos> positions, BlockPos anchor) {
        sb.append('{');
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            appendRel(sb, positions.get(i), anchor);
        }
        sb.append('}');
    }

    private static void appendRel(StringBuilder sb, BlockPos pos, BlockPos anchor) {
        sb.append('(').append(pos.getX() - anchor.getX()).append(',').append(pos.getY() - anchor.getY())
                .append(',').append(pos.getZ() - anchor.getZ()).append(')');
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        return highlights;
    }

    @Override
    public void reset() {
        beamBlocks.clear();
        lit = new boolean[0];
        scanY = Integer.MIN_VALUE;
        scanTopY = 0;
        scanned = false;
        ticks = 0;
        creeper = null;
        dirty = false;
        lastLogged = "";
        highlights = List.of();
    }
}
