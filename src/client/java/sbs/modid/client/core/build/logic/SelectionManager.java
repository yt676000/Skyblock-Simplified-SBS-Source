/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.logic;

import net.minecraft.core.BlockPos;
import sbs.modid.client.core.build.model.Selection;

/**
 * The one two-corner selection, shared by every way of making one: the Magic Stick Thingy, the
 * corner keys, {@code /.. pos1}/{@code pos2}, smart select, and Garden Blueprint's custom area.
 *
 * <p>Either corner may be unset. {@link #selection()} is non-null only once both are, which is what
 * every command that acts on "the selection" checks.
 */
public final class SelectionManager {

    private static final SelectionManager INSTANCE = new SelectionManager();

    private volatile BlockPos corner1;
    private volatile BlockPos corner2;

    private SelectionManager() {
    }

    public static SelectionManager getInstance() {
        return INSTANCE;
    }

    public BlockPos corner1() {
        return corner1;
    }

    public BlockPos corner2() {
        return corner2;
    }

    public void setCorner1(BlockPos pos) {
        corner1 = pos == null ? null : pos.immutable();
    }

    public void setCorner2(BlockPos pos) {
        corner2 = pos == null ? null : pos.immutable();
    }

    /** The box between both corners, or {@code null} while either is unset. */
    public Selection selection() {
        BlockPos a = corner1;
        BlockPos b = corner2;
        if (a == null || b == null) {
            return null;
        }
        return new Selection(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    /** Replaces both corners with {@code value}'s. */
    public void set(Selection value) {
        if (value == null) {
            clear();
            return;
        }
        corner1 = new BlockPos(value.x1(), value.y1(), value.z1());
        corner2 = new BlockPos(value.x2(), value.y2(), value.z2());
    }

    public void clear() {
        corner1 = null;
        corner2 = null;
    }
}
