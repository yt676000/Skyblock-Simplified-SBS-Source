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
import sbs.modid.client.core.build.render.GhostStyle;

/**
 * The module a hologram belongs to: it decides whether the hologram may show right now, where it
 * sits when nobody pinned it, and how it looks.
 *
 * <p>This is the seam that keeps {@code core/build} free of feature code. Garden Blueprint's owner
 * knows the plot grid and the "only in Garden" gate; Build Tools' owner knows its own page. The shared
 * renderer asks the hologram's owner and never the features directly.
 */
public interface HologramOwner {

    /** Short name for chat and logs: "Garden Blueprint", "Build Tools". */
    String name();

    /** The draw style, read fresh each frame from the owner's settings. */
    GhostStyle style();

    /** Whether the hologram may be drawn at all right now (module on, location gates). */
    boolean visible(Hologram hologram, Player player);

    /**
     * World position of the hologram's min corner when it has no pin of its own
     * ({@link Hologram#anchor()} is null), or {@code null} when it then has nowhere to be.
     */
    BlockPos unpinnedBase(Hologram hologram, Player player);
}
