/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.model.Schematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-frame list of hologram cells worth drawing, shared by the two renderers.
 *
 * <p>The hologram is compared against the world here - once - and both the world-space block models
 * ({@link GhostModels}, drawn during the level render) and the HUD-space outlines
 * ({@link HologramRenderer}, drawn during the HUD render) read the same {@link Frame}. Both run on the
 * render thread in the same frame, so a short time-based memo keeps the scan at one pass per frame
 * instead of two.
 *
 * <p>Only cells near the player are visited: when the box around the player is smaller than the
 * hologram's non-air cell list, the box is walked; otherwise the list is. Either way the cost is
 * bounded by the render radius, not by the size of the build.
 */
public final class GhostCollector {

    /** Hard cap on ghosts per frame; the nearest ones win. */
    public static final int MAX_RENDER = 600;

    /**
     * How long a computed frame stays valid: long enough to survive from the level pass to the HUD
     * pass of the same frame, short enough that a block you place flips colour at once (~40 Hz).
     */
    private static final long CACHE_NANOS = 25_000_000L;

    /** How the world compares with the hologram at one position. */
    public enum Status {
        /** World is air - the block still to be placed (in a preview: a block the edit adds). */
        MISSING,
        /** A different block stands here (before a paste: a collision; in a preview: a change). */
        WRONG,
        /** The right block is already there. */
        CORRECT,
        /** An edit preview removes the block standing here. */
        REMOVE
    }

    /**
     * One cell to draw: squared distance (for sorting), world position, the wanted state, status, and
     * the cell's block-entity SNBT from the schematic ({@code null} when it has none) - which is where
     * a head's skin lives.
     */
    public record Ghost(double distSq, int x, int y, int z, BlockState wanted, Status status, String entity) {
    }

    /** Everything one frame draws, with the style it was classified under. */
    public record Frame(List<Ghost> ghosts, GhostStyle style, Hologram hologram, BlockPos base) {

        public boolean isEmpty() {
            return ghosts.isEmpty();
        }
    }

    public static final Frame EMPTY = new Frame(List.of(), null, null, null);

    private static Frame cached = EMPTY;
    private static long cachedNanos;

    /**
     * Whether {@link #cachedNanos} holds a real reading yet. Never a sentinel timestamp: seeding it
     * with {@code Long.MIN_VALUE} overflows {@code now - cachedNanos}, the memo then never expires,
     * and the empty first frame is served forever - which once switched Garden Blueprint's whole
     * preview off without an error anywhere.
     */
    private static boolean cacheFilled;

    private GhostCollector() {
    }

    /** The frame to draw now, nearest ghost first; {@link #EMPTY} when nothing should be drawn. */
    public static Frame collect() {
        long now = System.nanoTime();
        if (cacheFilled && now - cachedNanos < CACHE_NANOS) {
            return cached;
        }
        cachedNanos = now;
        cacheFilled = true;
        cached = compute();
        return cached;
    }

    /** Drops the memo so the next call recomputes - after a nudge, so the move shows this frame. */
    public static void invalidate() {
        cacheFilled = false;
    }

    private static Frame compute() {
        Hologram hologram = HologramManager.getInstance().active();
        if (hologram == null) {
            return EMPTY;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return EMPTY;
        }
        if (!hologram.owner().visible(hologram, player)) {
            return EMPTY;
        }
        BlockPos base = hologram.base(player);
        if (base == null) {
            return EMPTY;
        }
        GhostStyle style = hologram.owner().style();
        List<Ghost> ghosts = scan(hologram, style, base, level, player);
        return new Frame(ghosts, style, hologram, base);
    }

    private static List<Ghost> scan(Hologram hologram, GhostStyle style, BlockPos base,
                                    ClientLevel level, Player player) {
        Schematic schematic = hologram.display();
        BlockState[] states = hologram.states();
        int removeIndex = hologram.palette().indexOf(Hologram.REMOVE_MARKER);
        int radius = Math.max(1, style.radius());
        double radiusSq = (double) radius * radius;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        int w = schematic.width();
        int h = schematic.height();
        int l = schematic.length();
        // The player's radius box, in the schematic's own coordinates, clipped to the schematic.
        int x0 = Math.max(0, (int) Math.floor(px - radius) - base.getX());
        int x1 = Math.min(w - 1, (int) Math.ceil(px + radius) - base.getX());
        int y0 = Math.max(0, (int) Math.floor(py - radius) - base.getY());
        int y1 = Math.min(h - 1, (int) Math.ceil(py + radius) - base.getY());
        int z0 = Math.max(0, (int) Math.floor(pz - radius) - base.getZ());
        int z1 = Math.min(l - 1, (int) Math.ceil(pz + radius) - base.getZ());
        int layer = hologram.layer();
        if (layer != Hologram.ALL_LAYERS) {
            y0 = Math.max(y0, layer);
            y1 = Math.min(y1, layer);
        }
        if (x0 > x1 || y0 > y1 || z0 > z1) {
            return List.of();
        }

        Classifier classifier = new Classifier(hologram.mode(), style.showCorrect(), states, removeIndex,
                schematic.blockEntities(), level, base, px, py, pz, radiusSq);
        long boxVolume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        int[] nonAir = schematic.nonAirCells();
        if (boxVolume <= nonAir.length) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        int cell = schematic.index(x, y, z);
                        int palette = schematic.paletteAt(cell);
                        if (palette != 0) {
                            classifier.visit(x, y, z, cell, palette);
                        }
                    }
                }
            }
        } else {
            for (int cell : nonAir) {
                int y = schematic.yOf(cell);
                if (y < y0 || y > y1) {
                    continue;
                }
                int x = schematic.xOf(cell);
                int z = schematic.zOf(cell);
                if (x < x0 || x > x1 || z < z0 || z > z1) {
                    continue;
                }
                classifier.visit(x, y, z, cell, schematic.paletteAt(cell));
            }
        }
        List<Ghost> ghosts = classifier.ghosts;
        if (ghosts.isEmpty()) {
            return List.of();
        }
        ghosts.sort(Comparator.comparingDouble(Ghost::distSq));
        return ghosts.size() > MAX_RENDER
                ? Collections.unmodifiableList(new ArrayList<>(ghosts.subList(0, MAX_RENDER)))
                : Collections.unmodifiableList(ghosts);
    }

    /** Compares one cell with the world and keeps it when it is worth drawing. */
    private static final class Classifier {

        private final Hologram.Mode mode;
        private final boolean showCorrect;
        private final BlockState[] states;
        private final int removeIndex;
        private final Map<Integer, String> entities;
        private final ClientLevel level;
        private final BlockPos base;
        private final double px;
        private final double py;
        private final double pz;
        private final double radiusSq;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final List<Ghost> ghosts = new ArrayList<>();

        Classifier(Hologram.Mode mode, boolean showCorrect, BlockState[] states, int removeIndex,
                   Map<Integer, String> entities, ClientLevel level, BlockPos base,
                   double px, double py, double pz, double radiusSq) {
            this.mode = mode;
            this.showCorrect = showCorrect;
            this.states = states;
            this.removeIndex = removeIndex;
            this.entities = entities;
            this.level = level;
            this.base = base;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.radiusSq = radiusSq;
        }

        void visit(int x, int y, int z, int cell, int palette) {
            int wx = base.getX() + x;
            int wy = base.getY() + y;
            int wz = base.getZ() + z;
            double cx = wx + 0.5 - px;
            double cy = wy + 0.5 - py;
            double cz = wz + 0.5 - pz;
            double distSq = cx * cx + cy * cy + cz * cz;
            if (distSq > radiusSq) {
                return;
            }
            BlockState actual = level.getBlockState(pos.set(wx, wy, wz));
            if (palette == removeIndex) {
                if (!actual.isAir()) {
                    ghosts.add(new Ghost(distSq, wx, wy, wz, actual, Status.REMOVE, null));
                }
                return;
            }
            BlockState wanted = states[palette];
            if (wanted.isAir()) {
                return;   // a state this game does not know: nothing to show
            }
            String entity = entities.get(cell);
            Status status;
            if (mode == Hologram.Mode.PREVIEW) {
                // A preview shows what the edit changes; a cell that already holds the result is not
                // a change and is not drawn.
                if (actual == wanted && sameSkin(wanted, entity)) {
                    return;
                }
                status = actual.isAir() ? Status.MISSING : Status.WRONG;
            } else if (matches(wanted, actual, entity)) {
                if (!showCorrect) {
                    return;
                }
                status = Status.CORRECT;
            } else if (actual.isAir()) {
                status = Status.MISSING;
            } else {
                status = Status.WRONG;
            }
            ghosts.add(new Ghost(distSq, wx, wy, wz, wanted, status, entity));
        }

        /**
         * Whether the block standing here counts as the hologram's. Same block is the rule - rotation
         * and other properties are the player's to get right - except where the property is the block:
         * a liquid's level (source vs flowing), whether a block is waterlogged, and a head's skin.
         */
        private boolean matches(BlockState wanted, BlockState actual, String entity) {
            if (actual.getBlock() != wanted.getBlock()) {
                return false;
            }
            if (wanted.getBlock() instanceof LiquidBlock
                    && !GhostMatch.sameFluidLevel(wanted.getValue(LiquidBlock.LEVEL),
                            actual.getValue(LiquidBlock.LEVEL))) {
                return false;
            }
            if (wanted.hasProperty(BlockStateProperties.WATERLOGGED) && actual.hasProperty(BlockStateProperties.WATERLOGGED)
                    && !wanted.getValue(BlockStateProperties.WATERLOGGED)
                            .equals(actual.getValue(BlockStateProperties.WATERLOGGED))) {
                return false;
            }
            return sameSkin(wanted, entity);
        }

        /** A head's skin against the one in the world; anything that is not a head always passes. */
        private boolean sameSkin(BlockState wanted, String entity) {
            if (entity == null || !(wanted.getBlock() instanceof AbstractSkullBlock)) {
                return true;
            }
            String wantedKey = wantedSkin(entity);
            if (wantedKey == null) {
                return true;
            }
            String actual = null;
            BlockEntity present = level.getBlockEntity(pos);
            if (present != null) {
                try {
                    actual = present.saveWithoutMetadata(level.registryAccess()).toString();
                } catch (RuntimeException unsaveable) {
                    return false; // a head whose skin cannot be read is not shown as correct
                }
            }
            return wantedKey.equals(GhostMatch.skinKey(actual));
        }
    }

    /** Skin keys of hologram cells, by SNBT: the same few heads are re-checked every frame. */
    private static final Map<String, Optional<String>> WANTED_SKINS = new HashMap<>();

    private static String wantedSkin(String snbt) {
        if (WANTED_SKINS.size() > 512) {
            WANTED_SKINS.clear();
        }
        return WANTED_SKINS.computeIfAbsent(snbt, key -> Optional.ofNullable(GhostMatch.skinKey(key)))
                .orElse(null);
    }
}
