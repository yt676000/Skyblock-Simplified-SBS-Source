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
import net.minecraft.world.level.Level;
import sbs.modid.client.core.build.model.Schematic;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Placement presets a feature offers to the shared hologram - "snap this to the plot grid".
 *
 * <p>A registry rather than a direct call so the dependency points the right way: Garden Blueprint
 * registers its plot-grid snap here, and Build Tools' nudge key uses whatever is registered, without
 * either feature package naming the other.
 */
public final class PlacementSnaps {

    /** One way of snapping a hologram's min corner to a grid. */
    public interface Snap {

        /** Stable id: "plot". */
        String id();

        /** What the hint and chat call it: "plot grid". */
        String label();

        /** Where {@code schematic}'s min corner goes for {@code player}, or {@code null} if nowhere. */
        BlockPos snap(Player player, Level level, Schematic schematic);
    }

    private static final List<Snap> SNAPS = new CopyOnWriteArrayList<>();

    private PlacementSnaps() {
    }

    /** Adds a snap once; a second registration of the same id is ignored. */
    public static void register(Snap snap) {
        for (Snap existing : SNAPS) {
            if (existing.id().equals(snap.id())) {
                return;
            }
        }
        SNAPS.add(snap);
    }

    public static List<Snap> all() {
        return List.copyOf(SNAPS);
    }

    public static Snap get(String id) {
        for (Snap snap : SNAPS) {
            if (snap.id().equals(id)) {
                return snap;
            }
        }
        return null;
    }
}
