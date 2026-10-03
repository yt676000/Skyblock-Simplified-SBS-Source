/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.model;

import net.minecraft.core.BlockPos;

/**
 * One place on an island map: what it is called, what kind of place it is, and where it stands.
 *
 * <p>A plain mutable POJO – Gson builds these straight out of the island's JSON file, which is the
 * whole point of the module: a new island, or a place Hypixel added last week, is a data edit and
 * never a code change.
 *
 * <p>{@link #warp} is an <b>optional</b> override. Left out, the nearest warp is computed from the
 * arrival coordinates in {@link IslandMap#warps} – which is normally right and stays right when a
 * warp is added. It is worth setting when geometry lies: a spot that is close to a warp as the crow
 * flies but walled off from it (the Crypts under the Graveyard, anything down a lift) wants the warp
 * you can actually walk from, not the one with the smaller number.
 */
public final class MapLocation {

    /** Shown on the map and in the tooltip. */
    public String name = "";

    /**
     * Category <b>name</b>, resolved through {@link MapCategory#of}. A string rather than the enum
     * so an unknown value degrades to a generic marker instead of deserialising to {@code null} –
     * see {@link MapCategory}.
     */
    public String category = "";

    public int x;
    public int y;
    public int z;

    /** Id of the warp to use, overriding the computed nearest one. Optional; see the class docs. */
    public String warp = "";

    /** Optional second line in the tooltip ("Banker", "Slayer bosses"). */
    public String note = "";

    /**
     * The zone this sits in ({@code ⏣ Graveyard}), for the tooltip. Purely informational - the
     * routing gate is island-level, because that is the level at which coordinates mean anything.
     */
    public String area = "";

    /**
     * Where a commission naming this place sends the player, when that is not the marker itself.
     * Optional. The marker is an NPC or warp spot, which can sit a hundred blocks from where the
     * commission is actually done; moving it would move the map marker and click-to-warp as well.
     */
    public Anchor commission;

    /**
     * The map this location was loaded from. Set by {@link IslandMap#link()} after parsing, not by
     * Gson - {@code transient} keeps it out of serialisation and out of the data files.
     */
    public transient IslandMap map;

    /** Gson needs a no-arg constructor. */
    public MapLocation() {
    }

    public MapCategory categoryOrDefault() {
        return MapCategory.of(category);
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** "Bank (-29, 72, -38)" – the tooltip's coordinate line and the chat confirmation. */
    public String summary() {
        return name + " (" + x + ", " + y + ", " + z + ")";
    }

    /** The commission destination: {@link #commission} when set, else the marker position. */
    public BlockPos commissionPos() {
        return commission == null ? pos() : new BlockPos(commission.x, commission.y, commission.z);
    }

    /** Whether the entry is complete enough to show: a nameless marker is only confusing. */
    public boolean valid() {
        return name != null && !name.isBlank();
    }

    /** A block position inside a location entry ({@code "commission": {"x":..,"y":..,"z":..}}). */
    public static final class Anchor {
        public int x;
        public int y;
        public int z;
    }
}
