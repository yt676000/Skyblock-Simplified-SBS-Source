/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The single definition of "can the player be here": shared by the search ({@link PathfinderTask})
 * and by whoever decides where a search should start ({@link PathfindingManager}).
 *
 * <p>Split out because the two disagreeing is exactly how a path ends up starting somewhere the
 * search itself considers impossible.
 *
 * <p><b>Blocks are measured by height, not treated as solid-or-not.</b> A naive "does this block
 * have a collision shape" test gets partial blocks exactly backwards: a bottom slab has a shape, so
 * it reads as a wall and the router refuses to walk on slabs, stairs or carpet at all – even though
 * they are the easiest terrain there is. So every query goes through {@link #surfaceHeight}, and the
 * rules are written in terms of how high a block's top surface actually is:
 *
 * <ul>
 *   <li>{@code 0.0} – empty, the player passes through.</li>
 *   <li>{@code <= 0.5} – a low floor (bottom slab, carpet): the player stands <i>on top of it</i>,
 *       inside the same block, half a block higher than the block's base.</li>
 *   <li>{@code 1.0} – full height (a normal block, or a <i>top</i> slab, whose shape reaches the
 *       block's ceiling): cannot be stood in, but supports whatever is above it.</li>
 * </ul>
 *
 * <p>A node's {@code y} therefore means "the player's feet are in block {@code y}", at that block's
 * floor – which is its base for empty blocks and half a block up for a slab. Standing on a slab
 * lifts the player, so those positions need one more block of headroom.
 *
 * <p>Every method takes a caller-owned {@link BlockPos.MutableBlockPos} cursor: the search calls
 * these tens of thousands of times per path, and allocating a {@code BlockPos} per block read would
 * dominate the cost.
 */
final class Walkability {

    /** The tallest floor a player can simply stand on top of within a block (slab, carpet, ...). */
    private static final double MAX_STEP_IN = 0.5;

    /** How high a block must reach to support a player standing in the block above it. */
    private static final double FULL_SUPPORT = 1.0;

    /** Unloaded terrain reads as a full block, so a route is never planned through the unknown. */
    private static final double UNKNOWN = 1.0;

    /**
     * Headroom a standing player needs: the player's standing hitbox height (Avatar's
     * STANDING_DIMENSIONS, 0.6 x 1.8 in 26.2). Not readable from outside the class, hence the value.
     */
    static final double STAND_HEIGHT = 1.8;

    /** Headroom a sneaking player needs: Avatar.CROUCH_BB_HEIGHT, 1.5 in 26.2 (checked with javap). */
    static final double SNEAK_HEIGHT = 1.5;

    /** {@link #standKind} results. */
    static final int NO_STAND = 0;
    static final int STAND = 1;
    static final int SNEAK = 2;

    private Walkability() {
    }

    /**
     * How high the collision shape reaches inside a block, {@code 0} (empty) to {@code 1} (full).
     *
     * <p>This is the one primitive everything else is built on – see the class docs for why the
     * height matters rather than mere presence.
     */
    static double surfaceHeight(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
        if (!level.hasChunkAt(x, z)) {
            return UNKNOWN;
        }
        cursor.set(x, y, z);
        VoxelShape shape = level.getBlockState(cursor).getCollisionShape(level, cursor);
        return shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
    }

    /** Nothing at all in the way – what a player's head and torso need. */
    static boolean isPassable(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
        return surfaceHeight(level, cursor, x, y, z) <= 0.0;
    }

    /** Free, or floored low enough that the player simply stands on it (a slab underfoot is fine). */
    static boolean isSteppable(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
        return surfaceHeight(level, cursor, x, y, z) <= MAX_STEP_IN;
    }

    /** Whether the player's body fits at {@code (x, y, z)} – feet may rest on a low floor. */
    static boolean bodyClear(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
        return isSteppable(level, cursor, x, y, z) && isPassable(level, cursor, x, y + 1, z);
    }

    /**
     * Where the lowest thing overhead starts inside a block, {@code 0} (the block's base) to
     * {@code 1}, or {@code Double.NaN} when nothing is there. The other half of the height primitive:
     * a top slab's shape starts at {@code 0.5}, so a player can still fit under it while sneaking.
     */
    static double ceilingBottom(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
        if (!level.hasChunkAt(x, z)) {
            return 0.0;   // the unknown is a wall, overhead too
        }
        cursor.set(x, y, z);
        VoxelShape shape = level.getBlockState(cursor).getCollisionShape(level, cursor);
        return shape.isEmpty() ? Double.NaN : shape.min(Direction.Axis.Y);
    }

    /**
     * Whether a player standing at {@code (x, y, z)} would be supported and fit, standing or (when
     * {@code allowSneak}) sneaking.
     */
    static boolean canStand(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
        return standKind(level, cursor, x, y, z, allowSneak()) != NO_STAND;
    }

    /** The "Allow sneak gaps" pathfinding setting. */
    static boolean allowSneak() {
        return sbs.modid.client.core.config.ConfigManager.getInstance().get().pathfinding.allowSneakGaps;
    }

    /**
     * {@link #STAND}, {@link #SNEAK} or {@link #NO_STAND} for a player at {@code (x, y, z)}.
     *
     * <p>Headroom is measured in real height, not whole blocks: from the feet (half a block up on a
     * bottom slab) to the lowest collision bottom in the two blocks above. A floor plus a top slab
     * overhead leaves 1.5 - room to sneak, not to stand.
     */
    static int standKind(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z, boolean allowSneak) {
        double floor = surfaceHeight(level, cursor, x, y, z);
        if (floor > MAX_STEP_IN) {
            return NO_STAND; // a full block or a top slab - the player cannot be inside it
        }
        double below = floor > 0.0 ? 0.0 : surfaceHeight(level, cursor, x, y - 1, z);
        double above1 = ceilingBottom(level, cursor, x, y + 1, z);
        double above2 = Double.isNaN(above1) ? ceilingBottom(level, cursor, x, y + 2, z) : Double.NaN;
        return column(floor, below, above1, above2, allowSneak);
    }

    /**
     * The stand/sneak rule on one column, from four heights - pure, so the tests run the real rule.
     *
     * @param floor  the feet block's surface height (0 empty, up to 0.5 a low floor)
     * @param below  the block below's surface height (only read when {@code floor} is 0)
     * @param above1 where the block above the feet starts ({@link #ceilingBottom}), NaN if empty
     * @param above2 the same two above, NaN if empty (only read when {@code above1} is empty)
     */
    static int column(double floor, double below, double above1, double above2, boolean allowSneak) {
        if (floor > MAX_STEP_IN) {
            return NO_STAND;
        }
        // Either this block has its own low floor to stand on, or the block below holds them up.
        if (floor <= 0.0 && below < FULL_SUPPORT) {
            return NO_STAND;
        }
        double ceiling;
        if (!Double.isNaN(above1)) {
            ceiling = 1 + above1;
        } else if (!Double.isNaN(above2)) {
            ceiling = 2 + above2;
        } else {
            ceiling = 3.0;
        }
        return classify(ceiling - floor, allowSneak);
    }

    /** Headroom in blocks -> {@link #STAND} / {@link #SNEAK} / {@link #NO_STAND}. Pure, for the tests. */
    static int classify(double clearance, boolean allowSneak) {
        if (clearance >= STAND_HEIGHT - 1e-9) {
            return STAND;
        }
        return allowSneak && clearance >= SNEAK_HEIGHT - 1e-9 ? SNEAK : NO_STAND;
    }

    /**
     * The block below {@code from} the player would come to rest on, or {@code null} if there is no
     * ground beneath them at all.
     *
     * <p>This is what makes a route survive being in the air: mid-jump the player's own position has
     * no floor, so a walking search rooted there has nowhere to go and fails instantly. Rooting it
     * at the landing spot instead keeps the route both valid and stable while they are airborne.
     */
    static BlockPos groundBelow(Level level, BlockPos from) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        if (canStand(level, cursor, from.getX(), from.getY(), from.getZ())) {
            return from;
        }
        for (int y = from.getY(); y > level.getMinY(); y--) {
            if (canStand(level, cursor, from.getX(), y, from.getZ())) {
                return new BlockPos(from.getX(), y, from.getZ());
            }
        }
        return null;
    }
}
