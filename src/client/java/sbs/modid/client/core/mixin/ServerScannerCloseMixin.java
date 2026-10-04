/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.dev.scanner.ServerScanner;

/**
 * Server Scanner (developer tool): the player closed the open menu.
 *
 * <p>{@code closeContainer} is the client-initiated close (it sends the close packet); a server close
 * goes through {@code clientSideCloseContainer} only, so the two are told apart by which one ran. At
 * {@code HEAD} because the menu's container id is what identifies the menu, and it is reset by the
 * end of the method. Observational only - never cancels. One boolean read while dev mode is off.
 */
@Mixin(LocalPlayer.class)
public abstract class ServerScannerCloseMixin {

    @Inject(method = "closeContainer", at = @At("HEAD"))
    private void skyblockSimplified$scannerPlayerClose(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.containerMenu != null) {
            ServerScanner.onPlayerClose(self.containerMenu.containerId);
        }
    }
}
