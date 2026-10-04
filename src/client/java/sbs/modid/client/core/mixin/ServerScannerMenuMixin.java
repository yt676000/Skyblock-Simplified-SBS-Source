/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.dev.scanner.ServerScanner;

/**
 * Server Scanner (developer tool): a menu opened or closed by the server.
 *
 * <p>At {@code TAIL}, never {@code HEAD}: each handler first runs on the Netty thread, where
 * {@code ensureRunningOnSameThread} throws and re-queues it onto the client thread. Only the client
 * thread reaches {@code TAIL}, so the scanner reads the packet where game objects are safe to read.
 * Observational only - nothing is cancelled or changed. One boolean read while dev mode is off.
 */
@Mixin(ClientPacketListener.class)
public class ServerScannerMenuMixin {

    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void skyblockSimplified$scannerOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        ServerScanner.onOpenScreen(packet);
    }

    @Inject(method = "handleContainerClose", at = @At("TAIL"))
    private void skyblockSimplified$scannerClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        ServerScanner.onServerClose(packet.getContainerId());
    }
}
