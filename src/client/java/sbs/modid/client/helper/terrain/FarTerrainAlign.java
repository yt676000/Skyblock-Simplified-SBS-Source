/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.keybind.Keys;

import java.util.HashMap;
import java.util.Map;

/**
 * The measuring tool for lining the neighbour islands up: aim at any island and press the mark
 * key, and one {@code [SBS][Align]} line goes to chat and the log saying what was hit and where it
 * truly lives.
 *
 * <p><b>Why marks.</b> The neighbour layout places each island's box in the nearest free spot,
 * which keeps the scenery visible but ignores the geography the islands actually present - the
 * player can see that the row is in the wrong place, but "wrong" only becomes fixable as numbers.
 * A mark turns one aimed click into those numbers. Marking the same landmark from two islands -
 * once as real terrain on each side of a seam - measures the true offset between their coordinate
 * spaces; marking a drawn neighbour island names the store and the true chunk a piece of scenery
 * came from.
 *
 * <p><b>It is also the mixed-file detector.</b> Every hit is looked up in the claim record of the
 * map it belongs to, so a mark that answers "Hub" inside the Park's scenery box says, in one line,
 * that {@code the_park.sbsr} still holds Hub chunks - the exact confusion a screenshot of a
 * misplaced castle cannot pin down.
 */
public final class FarTerrainAlign {

    /** Far enough to reach scenery at the live window's edge (128 chunks = 2048 blocks). */
    private static final double RANGE_BLOCKS = 2048;

    private static boolean wasDown;
    private static int marks;

    /**
     * Claim records of the neighbour maps, opened read-only as marks first need them. The active
     * map's record stays the manager's; these are only ever asked {@code ownerOf}, which does not
     * mark them dirty, so nothing here ever writes.
     */
    private static final Map<String, FarTerrainClaims> NEIGHBOR_CLAIMS = new HashMap<>();

    private FarTerrainAlign() {
    }

    private static SBSConfig.FarTerrainSettings cfg() {
        return ConfigManager.getInstance().get().farTerrain;
    }

    /** Driven every client tick from {@link FarTerrainManager#tick}, beside the toggle key. */
    static void tick(Minecraft minecraft) {
        int key = cfg().markKey;
        if (key == 0) {
            wasDown = false;
            return;
        }
        boolean down = Keys.isDown(key);
        if (down == wasDown) {
            return;
        }
        wasDown = down;
        if (!down) {
            return;   // act on the press, not on release
        }
        if (GuiStateManager.getInstance().getCurrentScreen() != null) {
            return;   // swallowed while typing, same contract as the toggle key
        }
        mark(minecraft);
    }

    private static void mark(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        marks++;
        FarTerrainManager manager = FarTerrainManager.getInstance();
        String island = manager.currentIsland();
        BlockPos feet = player.blockPosition();
        String standing = "on " + (island != null ? "'" + island + "'" : "unknown island")
                + " at " + feet.getX() + "," + feet.getY() + "," + feet.getZ()
                + " (ch " + (feet.getX() >> 4) + "," + (feet.getZ() >> 4) + ")";

        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0f);
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(view.scale(RANGE_BLOCKS)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            say("mark #" + marks + ": " + standing + ", facing " + compass(view)
                    + String.format(java.util.Locale.ROOT, " (%.2f,%.2f,%.2f)", view.x, view.y,
                            view.z)
                    + " - nothing hit within " + (int) RANGE_BLOCKS + " blocks");
            return;
        }

        BlockPos pos = hit.getBlockPos();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int distance = (int) Math.round(hit.getLocation().distanceTo(eye));
        String at = pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + " (ch " + chunkX + "," + chunkZ + "), " + distance + " blocks "
                + compass(view);

        if (FarTerrainNeighborView.isTranslated(chunkX, chunkZ)) {
            FarTerrainNeighborView.Source source =
                    FarTerrainNeighborView.getInstance().sourceOf(chunkX, chunkZ);
            if (source == null) {
                say("mark #" + marks + ": " + standing + " - hit SCENERY at " + at
                        + " that no laid-out neighbour accounts for");
                return;
            }
            // The true position: same block inside the chunk, chunk moved back by the offset.
            int trueX = (source.sourceX() << 4) + (pos.getX() & 15);
            int trueZ = (source.sourceZ() << 4) + (pos.getZ() & 15);
            String owner = claimsOf(source.map()).ownerOf(source.sourceX(), source.sourceZ());
            say("mark #" + marks + ": " + standing + " - hit SCENERY of '" + source.map()
                    + "' drawn at " + at + ", true position " + trueX + "," + pos.getY() + ","
                    + trueZ + " (ch " + source.sourceX() + "," + source.sourceZ() + ", claimed by "
                    + (owner != null ? "'" + owner + "'" : "nobody") + ")");
            return;
        }

        FarTerrainClaims claims = manager.claims();
        String owner = claims != null ? claims.ownerOf(chunkX, chunkZ) : null;
        say("mark #" + marks + ": " + standing + " - hit REAL terrain of "
                + (owner != null ? "'" + owner + "'" : "nobody (unclaimed)") + " at " + at);
    }

    /** The neighbour map's claim record, opened on first use and kept for the session. */
    private static FarTerrainClaims claimsOf(String map) {
        return NEIGHBOR_CLAIMS.computeIfAbsent(map, FarTerrainClaims::new);
    }

    /** The dominant horizontal direction, in the words a player lines islands up with. */
    private static String compass(Vec3 view) {
        if (Math.abs(view.x) >= Math.abs(view.z)) {
            return view.x >= 0 ? "east" : "west";
        }
        return view.z >= 0 ? "south" : "north";
    }

    private static void say(String message) {
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Align] {}", message);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(
                    Component.literal("§8[§bSBS§8]§r §7" + message));
        }
    }
}
