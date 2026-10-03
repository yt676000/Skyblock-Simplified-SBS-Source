/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicTransform;
import sbs.modid.client.core.build.model.StateStrings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A schematic shown in the world: which blocks, turned which way, pinned where, drawn how.
 *
 * <p>Immutable - nudging, turning or pinning returns a new instance, and {@link HologramManager}
 * swaps it in whole, so the renderers on the render thread never see a half-updated hologram.
 *
 * <p>{@link #source()} is the schematic as loaded; {@link #display()} is it after the turns and flips
 * the player applied. A palette swap is kept apart ({@link #swaps()}) and applied to the palette only
 * ({@link #palette()}), so swapping oak for spruce never rewrites the cells or the saved file.
 */
public final class Hologram {

    /** What the hologram is for, which changes how cells are classified and coloured. */
    public enum Mode {
        /** Compare the world with the build: missing / wrong / correct (Garden Blueprint, guide). */
        COMPARE,
        /** A paste waiting to be placed: a wrong cell is a collision. */
        PASTE,
        /** An edit's result before it is applied: added cells green, removed cells red. */
        PREVIEW
    }

    /** Palette entry an edit preview uses for "this cell becomes air". */
    public static final String REMOVE_MARKER = "sbs:remove";

    /** {@link #layer()} value meaning every layer is shown. */
    public static final int ALL_LAYERS = -1;

    private final Schematic source;
    private final Schematic display;
    private final List<String> palette;
    private final BlockState[] states;
    private final HologramOwner owner;
    private final BlockPos anchor;
    private final Mode mode;
    private final int layer;
    private final int quarterTurns;
    private final Map<String, String> swaps;

    private Hologram(Schematic source, Schematic display, Map<String, String> swaps, HologramOwner owner,
                     BlockPos anchor, Mode mode, int layer, int quarterTurns) {
        this.source = source;
        this.display = display;
        this.swaps = Collections.unmodifiableMap(new LinkedHashMap<>(swaps));
        this.palette = swappedPalette(display.palette(), this.swaps);
        this.states = BlockStates.resolve(this.palette);
        this.owner = owner;
        this.anchor = anchor;
        this.mode = mode;
        this.layer = layer;
        this.quarterTurns = quarterTurns;
    }

    /** A fresh hologram of {@code schematic}, untransformed, all layers. */
    public static Hologram of(Schematic schematic, HologramOwner owner, BlockPos anchor, Mode mode) {
        return new Hologram(schematic, schematic, Map.of(), owner, anchor, mode, ALL_LAYERS, 0);
    }

    public Schematic source() {
        return source;
    }

    public Schematic display() {
        return display;
    }

    /** The display palette after swaps - what would actually be placed. */
    public List<String> palette() {
        return palette;
    }

    /** {@link #palette()} resolved to game states, index for index. */
    public BlockState[] states() {
        return states;
    }

    public HologramOwner owner() {
        return owner;
    }

    /** The pinned min corner, or {@code null} to let the owner place it. */
    public BlockPos anchor() {
        return anchor;
    }

    public Mode mode() {
        return mode;
    }

    /** Relative Y of the only layer drawn, or {@link #ALL_LAYERS}. */
    public int layer() {
        return layer;
    }

    /** Clockwise quarter turns applied since loading, 0-3. */
    public int quarterTurns() {
        return quarterTurns;
    }

    /** Block-id swaps ({@code minecraft:oak_planks -> minecraft:spruce_planks}), in the order given. */
    public Map<String, String> swaps() {
        return swaps;
    }

    /** Where the min corner lands for {@code player} now, or {@code null} when it has nowhere to be. */
    public BlockPos base(Player player) {
        return anchor != null ? anchor : owner.unpinnedBase(this, player);
    }

    public Hologram withAnchor(BlockPos value) {
        return new Hologram(source, display, swaps, owner, value, mode, layer, quarterTurns);
    }

    public Hologram withOwner(HologramOwner value) {
        return new Hologram(source, display, swaps, value, anchor, mode, layer, quarterTurns);
    }

    public Hologram withMode(Mode value) {
        return new Hologram(source, display, swaps, owner, anchor, value, layer, quarterTurns);
    }

    public Hologram withLayer(int value) {
        int clamped = value == ALL_LAYERS ? ALL_LAYERS : Math.max(0, Math.min(display.height() - 1, value));
        return new Hologram(source, display, swaps, owner, anchor, mode, clamped, quarterTurns);
    }

    public Hologram withSwaps(Map<String, String> value) {
        return new Hologram(source, display, value, owner, anchor, mode, layer, quarterTurns);
    }

    /**
     * Turned a quarter clockwise about its own footprint centre, so it spins in place rather than
     * swinging around its corner.
     */
    public Hologram rotated(int turns) {
        int t = Math.floorMod(turns, 4);
        if (t == 0) {
            return this;
        }
        Schematic turned = SchematicTransform.rotate(display, t, BlockStates.MAPPER);
        BlockPos newAnchor = anchor == null ? null : recentre(anchor, display, turned);
        return new Hologram(source, turned, swaps, owner, newAnchor, mode, layer, (quarterTurns + t) % 4);
    }

    /** Mirrored across {@code axis}; the footprint stays where it is. */
    public Hologram flipped(SchematicTransform.Axis axis) {
        Schematic mirrored = SchematicTransform.flip(display, axis, BlockStates.MAPPER);
        return new Hologram(source, mirrored, swaps, owner, anchor, mode, layer, quarterTurns);
    }

    /** Keeps the footprint's centre fixed when width and length swap. */
    private static BlockPos recentre(BlockPos anchor, Schematic before, Schematic after) {
        int dx = (before.width() - after.width()) / 2;
        int dz = (before.length() - after.length()) / 2;
        return anchor.offset(dx, 0, dz);
    }

    private static List<String> swappedPalette(List<String> palette, Map<String, String> swaps) {
        if (swaps.isEmpty()) {
            return palette;
        }
        List<String> out = new ArrayList<>(palette.size());
        for (String state : palette) {
            String target = swaps.get(StateStrings.blockId(state));
            if (target == null) {
                out.add(state);
            } else {
                // Keep the properties (a stair stays a stair facing the same way); the resolver
                // falls back to air for a property the new block does not have, so resolve first
                // and keep the old state when the swap would not parse.
                String swapped = target + state.substring(StateStrings.blockId(state).length());
                out.add(BlockStates.tryParse(swapped) != null ? swapped
                        : BlockStates.tryParse(target) != null ? target : state);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
