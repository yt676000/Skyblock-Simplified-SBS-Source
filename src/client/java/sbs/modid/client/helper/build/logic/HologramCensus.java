/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.model.Schematic;

/**
 * Counts the whole hologram against the world - not just the cells near the player the renderer
 * draws: how many blocks are still missing, wrong (before a paste: would be overwritten) and right,
 * per layer too, and which missing block is the next one to place.
 *
 * <p>Spread over client ticks, a bounded number of cells per tick, and restarted whenever the
 * hologram or its position changes, so a million-block build is counted without a stall. The
 * published {@link Result} is always a complete pass; the build guide and the placing card read it.
 * Read-only: it looks at the client's loaded blocks and nothing else.
 */
public final class HologramCensus {

    /** Cells compared per client tick. */
    private static final int CELLS_PER_TICK = 150_000;

    /** A finished pass. */
    public record Result(Hologram hologram, BlockPos base, int missing, int wrong, int correct,
                         int[] missingPerLayer, int[] totalPerLayer, BlockPos next, String nextState) {
    }

    private static volatile Result last;

    // The pass in progress.
    private static Hologram scanning;
    private static BlockPos scanBase;
    private static int cursor;
    private static int missing;
    private static int wrong;
    private static int correct;
    private static int[] missingPerLayer;
    private static int[] totalPerLayer;
    private static BlockPos next;
    private static String nextState;
    private static long nextScore;

    static {
        BuildSession.onLeave(HologramCensus::reset);
    }

    private HologramCensus() {
    }

    /** The last complete pass for the hologram on screen now, or {@code null}. */
    public static Result result() {
        Result result = last;
        Hologram active = HologramManager.getInstance().active();
        return result != null && active != null && result.hologram() == active ? result : null;
    }

    public static void reset() {
        last = null;
        scanning = null;
    }

    /** Client tick. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        ClientLevel level = minecraft.level;
        Hologram hologram = HologramManager.getInstance().active();
        if (player == null || level == null || hologram == null || hologram.mode() == Hologram.Mode.PREVIEW
                || !hologram.owner().visible(hologram, player)) {
            reset();
            return;
        }
        BlockPos base = hologram.base(player);
        if (base == null) {
            reset();
            return;
        }
        if (hologram != scanning || !base.equals(scanBase)) {
            start(hologram, base);
        }
        Schematic schematic = hologram.display();
        int[] cells = schematic.nonAirCells();
        BlockState[] states = hologram.states();
        int layer = hologram.layer();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int end = Math.min(cells.length, cursor + CELLS_PER_TICK);
        for (; cursor < end; cursor++) {
            int cell = cells[cursor];
            BlockState wanted = states[schematic.paletteAt(cell)];
            if (wanted.isAir()) {
                continue;
            }
            int x = schematic.xOf(cell);
            int y = schematic.yOf(cell);
            int z = schematic.zOf(cell);
            totalPerLayer[y]++;
            BlockState actual = level.getBlockState(pos.set(base.getX() + x, base.getY() + y, base.getZ() + z));
            if (actual.getBlock() == wanted.getBlock()) {
                correct++;
            } else if (actual.isAir()) {
                missing++;
                missingPerLayer[y]++;
                if (layer == Hologram.ALL_LAYERS || layer == y) {
                    // Lowest layer first, then nearest to the player: build from the ground up.
                    long dx = pos.getX() - player.getBlockX();
                    long dy = pos.getY() - player.getBlockY();
                    long dz = pos.getZ() - player.getBlockZ();
                    long score = ((long) y << 40) + dx * dx + dy * dy + dz * dz;
                    if (next == null || score < nextScore) {
                        next = pos.immutable();
                        nextState = hologram.palette().get(schematic.paletteAt(cell));
                        nextScore = score;
                    }
                }
            } else {
                wrong++;
            }
        }
        if (cursor >= cells.length) {
            last = new Result(hologram, base, missing, wrong, correct, missingPerLayer, totalPerLayer, next, nextState);
            start(hologram, base);   // keep counting: the world changes as the player builds
        }
    }

    private static void start(Hologram hologram, BlockPos base) {
        scanning = hologram;
        scanBase = base;
        cursor = 0;
        missing = 0;
        wrong = 0;
        correct = 0;
        missingPerLayer = new int[hologram.display().height()];
        totalPerLayer = new int[hologram.display().height()];
        next = null;
        nextState = null;
        nextScore = Long.MAX_VALUE;
    }
}
