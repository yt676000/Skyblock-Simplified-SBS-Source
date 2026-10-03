/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.client.core.build.logic.BlockStates;
import sbs.modid.client.core.build.model.BuildDir;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.Selection;
import sbs.modid.client.helper.build.model.BlockPattern;
import sbs.modid.client.helper.build.model.EditPlan;
import sbs.modid.client.helper.build.model.EditShapes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an edit command into an {@link EditPlan}: which positions get which state.
 *
 * <p>Plans are made on the client thread from the client's level. In singleplayer that is the same
 * world the integrated server holds, for every chunk the player has loaded - which covers anything
 * they could have selected. Positions that already hold the target are left out, so a plan (and the
 * undo record behind it) is the size of the change, not the size of the box.
 *
 * <p>Nothing here touches the world; applying is {@link EditEngine}'s job.
 */
public final class EditPlanner {

    private EditPlanner() {
    }

    /** A pattern whose entries have all been checked against the game's registry. */
    public record Resolved(BlockPattern pattern, Map<String, BlockState> states, Map<String, String> canonical) {

        public BlockState stateAt(int x, int y, int z) {
            return states.get(pattern.pick(x, y, z));
        }

        public String canonicalAt(int x, int y, int z) {
            return canonical.get(pattern.pick(x, y, z));
        }
    }

    /** Checks every entry of {@code pattern}; the first unknown block is the error. */
    public static Resolved resolve(BlockPattern pattern) throws BlockPattern.PatternException {
        Map<String, BlockState> states = new HashMap<>();
        Map<String, String> canonical = new HashMap<>();
        for (BlockPattern.Entry entry : pattern.entries()) {
            BlockState state = BlockStates.tryParse(entry.state());
            if (state == null) {
                throw new BlockPattern.PatternException("\"" + entry.state() + "\" is not a block");
            }
            states.put(entry.state(), state);
            canonical.put(entry.state(), BlockStates.serialize(state));
        }
        return new Resolved(pattern, states, canonical);
    }

    /** What {@code //replace} looks for: whole blocks by id, or exact states when properties are given. */
    public record Mask(Set<Block> blocks, Set<BlockState> states, String description) {

        public boolean matches(BlockState state) {
            return blocks.contains(state.getBlock()) || states.contains(state);
        }
    }

    /** Parses {@code stone,dirt,oak_log[axis=y]}. */
    public static Mask mask(String text) throws BlockPattern.PatternException {
        BlockPattern pattern = BlockPattern.parse(text, HeldBlocks.RESOLVER);
        Set<Block> blocks = new HashSet<>();
        Set<BlockState> exact = new HashSet<>();
        StringBuilder description = new StringBuilder();
        for (BlockPattern.Entry entry : pattern.entries()) {
            BlockState state = BlockStates.tryParse(entry.state());
            if (state == null) {
                throw new BlockPattern.PatternException("\"" + entry.state() + "\" is not a block");
            }
            if (entry.state().indexOf('[') >= 0) {
                exact.add(state);
            } else {
                blocks.add(state.getBlock());
            }
            if (description.length() > 0) {
                description.append(", ");
            }
            description.append(sbs.modid.client.core.build.model.StateStrings.displayName(entry.state()));
        }
        return new Mask(blocks, exact, description.toString());
    }

    /** Every cell of the box becomes the pattern. */
    public static EditPlan set(Selection box, Resolved pattern, ClientLevel level) {
        return shaped("set " + pattern.pattern().describe(), box, pattern, level, (x, y, z) -> true);
    }

    /** The four vertical sides become the pattern. */
    public static EditPlan walls(Selection box, Resolved pattern, ClientLevel level) {
        return shaped("walls of " + pattern.pattern().describe(), box, pattern, level,
                (x, y, z) -> EditShapes.isWall(box, x, y, z));
    }

    /** All six faces become the pattern. */
    public static EditPlan outline(Selection box, Resolved pattern, ClientLevel level) {
        return shaped("outline of " + pattern.pattern().describe(), box, pattern, level,
                (x, y, z) -> EditShapes.isOutline(box, x, y, z));
    }

    /** Cells matching {@code mask} become the pattern. */
    public static EditPlan replace(Selection box, Mask mask, Resolved pattern, ClientLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return shaped("replace " + mask.description() + " with " + pattern.pattern().describe(), box, pattern, level,
                (x, y, z) -> mask.matches(level.getBlockState(pos.set(x, y, z))));
    }

    /** Everything inside the shell becomes air. */
    public static EditPlan hollow(Selection box, ClientLevel level) {
        Resolved air = new Resolved(BlockPattern.of(Schematic.AIR), Map.of(Schematic.AIR, Blocks.AIR.defaultBlockState()),
                Map.of(Schematic.AIR, Schematic.AIR));
        return shaped("hollow", box, air, level, (x, y, z) -> EditShapes.isInterior(box, x, y, z));
    }

    /** Every non-air cell becomes air - the second half of a cut. */
    public static EditPlan clear(Selection box, ClientLevel level, String name) {
        Resolved air = new Resolved(BlockPattern.of(Schematic.AIR), Map.of(Schematic.AIR, Blocks.AIR.defaultBlockState()),
                Map.of(Schematic.AIR, Schematic.AIR));
        return shaped(name, box, air, level, (x, y, z) -> true);
    }

    private interface Shape {
        boolean includes(int x, int y, int z);
    }

    private static EditPlan shaped(String name, Selection box, Resolved pattern, ClientLevel level, Shape shape) {
        EditPlan.Builder plan = new EditPlan.Builder(name);
        Map<String, Integer> interned = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (!shape.includes(x, y, z)) {
                        continue;
                    }
                    BlockState target = pattern.stateAt(x, y, z);
                    if (level.getBlockState(pos.set(x, y, z)) == target) {
                        continue;   // already there: not a change, not worth an undo slot
                    }
                    String canonical = pattern.canonicalAt(x, y, z);
                    int index = interned.computeIfAbsent(canonical, plan::intern);
                    plan.setIndex(x, y, z, index, null);
                }
            }
        }
        return plan.build();
    }

    /** The outcome of planning a flood fill. */
    public record FillResult(EditPlan plan, String error) {
    }

    /**
     * Floods the air pocket at {@code start} inside {@code bounds} with the pattern. Refuses when the
     * pocket reaches the edge of the bounds - the area is not closed, and filling it would pour
     * blocks out into the open.
     */
    public static FillResult fill(BlockPos start, Selection bounds, Resolved pattern, ClientLevel level, int limit) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        EditShapes.Flood flood = EditShapes.flood(start.getX(), start.getY(), start.getZ(), bounds, limit,
                (x, y, z) -> level.getBlockState(pos.set(x, y, z)).isAir());
        if (flood.count() == 0) {
            return new FillResult(null, "there is no air there to fill");
        }
        if (flood.escaped()) {
            return new FillResult(null, "the area is not closed - the flood reached the edge of "
                    + (bounds.volume() > 0 ? "the selection" : "the search box") + ". Select the walls around it too.");
        }
        EditPlan.Builder plan = new EditPlan.Builder("fill with " + pattern.pattern().describe());
        Map<String, Integer> interned = new HashMap<>();
        for (int i = 0; i < flood.count(); i++) {
            long packed = flood.cells()[i];
            int x = EditShapes.unpackX(packed);
            int y = EditShapes.unpackY(packed);
            int z = EditShapes.unpackZ(packed);
            String canonical = pattern.canonicalAt(x, y, z);
            plan.setIndex(x, y, z, interned.computeIfAbsent(canonical, plan::intern), null);
        }
        return new FillResult(plan.build(), null);
    }

    /**
     * {@code schematic} (with its palette replaced by {@code palette}, for swaps) set down with its
     * min corner at {@code base}. Air cells are skipped unless {@code includeAir}; block-entity data
     * travels with its cell; cells that already hold their state are left out.
     */
    public static EditPlan place(String name, Schematic schematic, List<String> palette, BlockPos base,
                                 boolean includeAir, ClientLevel level) {
        EditPlan.Builder plan = new EditPlan.Builder(name);
        placeInto(plan, schematic, palette, base, includeAir, level, true);
        return plan.build();
    }

    /**
     * Adds a placement to an existing plan. {@code skipUnchanged} must be off when the plan already
     * holds an earlier entry for the same cells (a move clears its source first), or a cell that
     * happens to match would keep the earlier "becomes air" instead of its own block.
     */
    private static void placeInto(EditPlan.Builder plan, Schematic schematic, List<String> palette, BlockPos base,
                                  boolean includeAir, ClientLevel level, boolean skipUnchanged) {
        int[] remap = new int[palette.size()];
        BlockState[] states = BlockStates.resolve(palette);
        for (int i = 0; i < remap.length; i++) {
            remap[i] = plan.intern(i == 0 ? Schematic.AIR : palette.get(i));
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < schematic.height(); y++) {
            for (int z = 0; z < schematic.length(); z++) {
                for (int x = 0; x < schematic.width(); x++) {
                    int cell = schematic.index(x, y, z);
                    int paletteIndex = schematic.paletteAt(cell);
                    if (paletteIndex == 0 && !includeAir) {
                        continue;
                    }
                    int wx = base.getX() + x;
                    int wy = base.getY() + y;
                    int wz = base.getZ() + z;
                    String entity = schematic.blockEntities().get(cell);
                    if (skipUnchanged && entity == null
                            && level.getBlockState(pos.set(wx, wy, wz)) == states[paletteIndex]) {
                        continue;
                    }
                    plan.setIndex(wx, wy, wz, remap[paletteIndex], entity);
                }
            }
        }
    }

    /**
     * Moves the captured contents of {@code box} by {@code offset}: the source becomes air, then the
     * copy lands (air included, so the destination looks exactly like the source did).
     */
    public static EditPlan move(Schematic source, Selection box, int dx, int dy, int dz, ClientLevel level, String name) {
        EditPlan.Builder plan = new EditPlan.Builder(name, true);
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    plan.setIndex(x, y, z, 0, null);
                }
            }
        }
        placeInto(plan, source, source.palette(), new BlockPos(box.minX() + dx, box.minY() + dy, box.minZ() + dz),
                true, level, false);
        return plan.build();
    }

    /** {@code count} copies of the captured box, each flush against the last along {@code dir}. */
    public static EditPlan stack(Schematic source, Selection box, BuildDir dir, int count, ClientLevel level, String name) {
        EditPlan.Builder plan = new EditPlan.Builder(name, true);
        for (int i = 1; i <= count; i++) {
            int[] offset = EditShapes.stackOffset(box, dir, i);
            placeInto(plan, source, source.palette(),
                    new BlockPos(box.minX() + offset[0], box.minY() + offset[1], box.minZ() + offset[2]), true, level, false);
        }
        return plan.build();
    }
}
