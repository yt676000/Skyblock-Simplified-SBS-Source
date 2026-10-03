/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import sbs.modid.client.core.build.model.Schematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one edit will do, decided before anything is applied: a list of positions and the state
 * each one gets. The preview hologram is drawn from it and Enter applies exactly it - so what the
 * player approved is what happens.
 *
 * <p>Pure data (positions packed as {@link EditShapes#pack}, targets as indices into a small palette
 * of state strings), so plans are built and inspected in tests without a world.
 */
public final class EditPlan {

    private final String name;
    private final List<String> palette;
    private final long[] positions;
    private final char[] targets;
    private final Map<Integer, String> blockEntities;

    private EditPlan(String name, List<String> palette, long[] positions, char[] targets,
                     Map<Integer, String> blockEntities) {
        this.name = name;
        this.palette = palette;
        this.positions = positions;
        this.targets = targets;
        this.blockEntities = blockEntities;
    }

    /** What the timeline calls it: "set stone", "replace dirt with grass block". */
    public String name() {
        return name;
    }

    public List<String> palette() {
        return palette;
    }

    public int size() {
        return positions.length;
    }

    public long position(int i) {
        return positions[i];
    }

    public int target(int i) {
        return targets[i];
    }

    public String targetState(int i) {
        return palette.get(targets[i]);
    }

    /** Block-entity data (SNBT) to load after setting position {@code i}, by plan index. */
    public Map<Integer, String> blockEntities() {
        return blockEntities;
    }

    public long[] positionsCopy() {
        return positions.clone();
    }

    public char[] targetsCopy() {
        return targets.clone();
    }

    /** Min and max corner over every position: {@code {minX, minY, minZ, maxX, maxY, maxZ}}. */
    public int[] bounds() {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (long packed : positions) {
            int x = EditShapes.unpackX(packed);
            int y = EditShapes.unpackY(packed);
            int z = EditShapes.unpackZ(packed);
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.min(b[2], z);
            b[3] = Math.max(b[3], x);
            b[4] = Math.max(b[4], y);
            b[5] = Math.max(b[5], z);
        }
        return b;
    }

    /**
     * Builds a plan. With {@code dedupe} a position added twice keeps its last target (move and
     * stack overlap themselves); without it every add is a new entry and no lookup map is kept,
     * which is what a 4M-block {@code set} can afford.
     */
    public static final class Builder {

        private final String name;
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<Long, Integer> slot;
        private long[] positions = new long[256];
        private char[] targets = new char[256];
        private final Map<Integer, String> entities = new HashMap<>();
        private int size;

        public Builder(String name) {
            this(name, false);
        }

        public Builder(String name, boolean dedupe) {
            this.name = name;
            this.slot = dedupe ? new HashMap<>() : null;
            intern(Schematic.AIR);
        }

        public int intern(String state) {
            Integer existing = index.get(state);
            if (existing != null) {
                return existing;
            }
            if (palette.size() >= Schematic.MAX_PALETTE) {
                throw new IllegalStateException("more than " + Schematic.MAX_PALETTE + " different blocks");
            }
            palette.add(state);
            index.put(state, palette.size() - 1);
            return palette.size() - 1;
        }

        public Builder set(int x, int y, int z, String state) {
            return setIndex(x, y, z, intern(state), null);
        }

        /** Sets a position to an interned target, with optional block-entity data. */
        public Builder setIndex(int x, int y, int z, int target, String blockEntity) {
            long packed = EditShapes.pack(x, y, z);
            Integer at = slot == null ? null : slot.get(packed);
            if (at == null) {
                if (size == positions.length) {
                    positions = java.util.Arrays.copyOf(positions, size * 2);
                    targets = java.util.Arrays.copyOf(targets, size * 2);
                }
                at = size++;
                if (slot != null) {
                    slot.put(packed, at);
                }
                positions[at] = packed;
            }
            targets[at] = (char) target;
            if (blockEntity == null) {
                entities.remove(at);
            } else {
                entities.put(at, blockEntity);
            }
            return this;
        }

        public int size() {
            return size;
        }

        public EditPlan build() {
            return new EditPlan(name, Collections.unmodifiableList(new ArrayList<>(palette)),
                    java.util.Arrays.copyOf(positions, size), java.util.Arrays.copyOf(targets, size),
                    Collections.unmodifiableMap(new HashMap<>(entities)));
        }
    }
}
