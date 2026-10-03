/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.model.Selection;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Reads a box of the world into a {@link Schematic}.
 *
 * <p><b>Read-only, by construction.</b> It takes a {@link BlockGetter} - the read interface - and
 * calls nothing but {@code getBlockState} and {@code getBlockEntity}. On a server the getter is the
 * client's own level, so a capture reads exactly what the client already has loaded and sends
 * nothing; unloaded chunks read as air. In singleplayer the caller may pass the integrated server's
 * level instead (on the server thread), which is the only way to get full block-entity data.
 *
 * <p>Saving a client-side block entity is a read as well: it serialises what the client was sent. That
 * is partial - a chest's items never reach the client - but a head's profile does, because the client
 * renders the skin, so a server capture keeps its heads.
 */
public final class WorldCapture {

    private WorldCapture() {
    }

    /**
     * Captures the inclusive box of {@code selection}.
     *
     * @param registries block-entity data is saved when non-null - the level's own registry access,
     *                   client or server; {@code null} skips block entities (block counts only)
     */
    public static Schematic capture(BlockGetter level, Selection selection, SchematicHeader header,
                                    HolderLookup.Provider registries) {
        return capture(level, selection.minX(), selection.minY(), selection.minZ(),
                selection.maxX(), selection.maxY(), selection.maxZ(), header, registries);
    }

    public static Schematic capture(BlockGetter level, int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ, SchematicHeader header,
                                    HolderLookup.Provider registries) {
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int length = maxZ - minZ + 1;
        Schematic.Builder builder = Schematic.builder(width, height, length);
        // States are canonical instances, so identity is the right key - and serialising each
        // distinct state once, not once per cell, is what keeps a plot capture quick.
        Map<BlockState, Integer> interned = new IdentityHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cell = 0;
        int entityFailures = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++, cell++) {
                    pos.set(minX + x, minY + y, minZ + z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    Integer index = interned.get(state);
                    if (index == null) {
                        index = builder.intern(BlockStates.serialize(state));
                        interned.put(state, index);
                    }
                    builder.setIndex(cell, index);
                    if (registries != null && state.hasBlockEntity()) {
                        BlockEntity entity = level.getBlockEntity(pos);
                        if (entity != null) {
                            try {
                                builder.blockEntityAt(cell, entity.saveWithoutMetadata(registries).toString());
                            } catch (RuntimeException unsaveable) {
                                entityFailures++;
                            }
                        }
                    }
                }
            }
        }
        if (entityFailures > 0) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Build] {} block entities could not be saved; "
                    + "their blocks were copied without contents", entityFailures);
        }
        return builder.header(header).build();
    }
}
