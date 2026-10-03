/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.util.HypixelServerEntry;

import java.util.List;

/**
 * Pins Hypixel to the top of the multiplayer server list after every load – see
 * {@link HypixelServerEntry}, which holds the rules.
 *
 * <p>The injection is at {@code RETURN} rather than {@code TAIL} on purpose: {@code load()} returns
 * early when {@code servers.dat} does not exist yet, and that is precisely the fresh install where
 * the entry has to be created. {@code RETURN} covers both exits.
 */
@Mixin(ServerList.class)
public abstract class HypixelServerPinMixin {

    @Shadow
    @Final
    private List<ServerData> serverList;

    @Inject(method = "load", at = @At("RETURN"))
    private void skyblockSimplified$pinHypixel(CallbackInfo ci) {
        HypixelServerEntry.pin(serverList);
    }
}
