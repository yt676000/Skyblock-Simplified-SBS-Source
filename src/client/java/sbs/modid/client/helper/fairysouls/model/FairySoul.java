/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.model;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One Fairy Soul from the data file.
 *
 * <p><b>{@link #id} is the identity, not the coordinates.</b> Collection state persists against the
 * id, so an id that gets renumbered between data versions silently wipes the player's progress -
 * hence the generator's contract that ids are assigned once and never reused. Coordinates, by
 * contrast, are free to be corrected: moving a soul by a block does not make it a different soul.
 *
 * <p>A plain mutable POJO because Gson builds it straight out of the data file.
 */
public final class FairySoul {

    /** Stable, permanent id ("hub-001"). The key every collection record uses. */
    public String id = "";

    public int x;
    public int y;
    public int z;

    /** The zone, for display only ("Village"). */
    public String area = "";

    /**
     * Whether the soul can simply be walked to, or needs a teleport to reach.
     *
     * <p>Defaults to {@code true} so the data file only ever states the exception - and so a file
     * written before this field existed does not turn every soul unreachable.
     */
    public boolean walkable = true;

    /** Optional hint shown on the routed marker ("inside the wall behind the crate"). */
    public String note = "";

    /**
     * The island this soul belongs to. Not read from the per-soul JSON - it is filled in from the
     * enclosing island block by {@link FairySoulData#link()}, so the file never repeats it.
     */
    public transient String island = "";

    /** Seen by the client's own scanner, not read from the curated file. Drawn distinctly. */
    public transient boolean learned;

    /** Gson needs a no-arg constructor. */
    public FairySoul() {
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** The soul's centre - what distances and the marker are measured against. */
    public Vec3 centre() {
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
    }

    /** Whether the entry is complete enough to use: an id-less soul cannot be tracked. */
    public boolean valid() {
        return id != null && !id.isBlank();
    }

    /** "hub-001 (10, 70, -20)" for chat and logs. */
    public String summary() {
        return id + " (" + x + ", " + y + ", " + z + ")";
    }
}
