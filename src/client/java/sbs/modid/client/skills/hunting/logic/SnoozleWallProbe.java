/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.skills.foraging.logic.WaypointPresetDatabase;
import sbs.modid.client.skills.foraging.logic.WaypointPresetOverrides;
import sbs.modid.client.skills.foraging.model.WaypointPresetData;
import sbs.modid.client.skills.hunting.CritterFinderModule;

import java.util.HashSet;
import java.util.Set;

/**
 * Writes down which block is really standing at each shipped Snoozle spot, once per spot per visit.
 *
 * <p><b>Evidence, never a gate.</b> It is tempting to check for Cobbled Deepslate or Tuff at the
 * coordinate and hide the marker when it is missing, and that would be wrong in both directions. The
 * wall is <i>supposed</i> to disappear - it is broken by running into it, by you or by anyone else in
 * what is a shared instance - so an absent wall means "this spot is open", which is when the marker is
 * most worth having, not least. And a chunk 100 blocks away is often not loaded on the client at all,
 * where {@code getBlockState} answers air: a gate built on that would go dark for reasons that have
 * nothing to do with the Safari. So nothing here changes what is drawn.
 *
 * <p>What it does do is settle the one question the client cannot answer from outside the game -
 * whether these five coordinates are the walls at all - by logging the block actually found, at the
 * spot and one block along each horizontal face, the first time the player gets close enough for the
 * chunk to be real. One trip through the Cavern produces five lines, and a wrong list becomes a data
 * fix instead of a mystery. Exactly the argument {@link HidingCritterTracker}'s nametag log makes.
 *
 * <p>Inert unless the marker group is switched on: a player who never turned the feature on has not
 * asked for anything, including log lines.
 */
public final class SnoozleWallProbe {

    private static final SnoozleWallProbe INSTANCE = new SnoozleWallProbe();

    /** Ticks between passes. The spots do not move; this only has to catch the player walking past. */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /**
     * How close the player has to be before a spot is read. Well inside any render distance, so the
     * chunk is loaded and the answer means something.
     */
    private static final double PROBE_RANGE = 24.0;

    /** Point ids already written down this visit, so each spot costs exactly one line. */
    private final Set<String> logged = new HashSet<>();

    private int tickCounter;

    private SnoozleWallProbe() {
    }

    public static SnoozleWallProbe getInstance() {
        return INSTANCE;
    }

    /** Called every client tick; the pass itself runs on the {@value #SCAN_INTERVAL_TICKS} throttle. */
    public void onClientTick() {
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        WaypointPresetData.Group group =
                WaypointPresetDatabase.group(CritterFinderModule.SNOOZLE_GROUP);
        if (group == null || !WaypointPresetOverrides.getInstance()
                .groupEnabled(group.id, group.enabledByDefault)) {
            return;
        }
        if (!SafariTracker.inSafariArea()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        for (WaypointPresetData.Point point : group.points) {
            probe(level, player, point);
        }
    }

    /** A new instance is a new set of chunks; nothing read on the last one describes this one. */
    public void onWorldChange() {
        logged.clear();
    }

    private void probe(ClientLevel level, LocalPlayer player, WaypointPresetData.Point point) {
        if (logged.contains(point.id)) {
            return;
        }
        BlockPos pos = new BlockPos(point.x, point.y, point.z);
        if (player.blockPosition().distSqr(pos) > PROBE_RANGE * PROBE_RANGE) {
            return;
        }
        if (!level.hasChunkAt(pos)) {
            return;   // not loaded yet: "air" here would be an answer about the client, not the world
        }
        logged.add(point.id);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Snoozle] {} at {}: {} (north {}, east {}, south {}, west {})",
                point.name, pos.toShortString(), name(level, pos),
                name(level, pos.north()), name(level, pos.east()),
                name(level, pos.south()), name(level, pos.west()));
    }

    /** The block's registry name, which is what a coordinate correction is actually judged on. */
    private static String name(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
