/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.dev.scanner.ServerScanner;

/**
 * Server Scanner (developer tool): a click of the player's inside a menu, as sent.
 *
 * <p>At {@code RETURN}, like {@code LayoutOpenerSlotMixin}: a click another mixin cancelled at
 * {@code HEAD} (Item Protection, for one) never reaches it, so
 * only clicks that actually went to the server are recorded. Observational only - never cancels, never
 * changes an argument. One boolean read while dev mode is off.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class ServerScannerClickMixin {

    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void skyblockSimplified$scannerClick(int containerId, int slotId, int button, ContainerInput input,
                                                 Player player, CallbackInfo ci) {
        ServerScanner.onClick(containerId, slotId, button, input);
    }
}
