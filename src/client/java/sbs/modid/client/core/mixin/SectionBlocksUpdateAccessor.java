/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The section a multi-block update targets. The packet only exposes it through {@code runUpdates},
 * which builds a {@code BlockPos} per changed block; the chunk revision needs the section alone.
 */
@Mixin(ClientboundSectionBlocksUpdatePacket.class)
public interface SectionBlocksUpdateAccessor {

    @Accessor("sectionPos")
    SectionPos skyblockSimplified$sectionPos();
}
