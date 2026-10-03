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
import sbs.modid.client.helper.itemprotection.logic.ItemProtection;

/**
 * Enforces Item Protection at the container funnel.
 *
 * <p>{@code handleContainerInput} is the single method every container interaction passes through -
 * pickup, shift-click, number swap, drag, clone and throw alike - and its own body is why vetoing it
 * at HEAD cannot desync anything: it calls {@code containerMenu.clicked(...)} first and builds
 * {@code ServerboundContainerClickPacket} from the slots that changed afterwards. Cancel before it
 * and the local menu is untouched and nothing is sent, so there is no divergence and no ghost item.
 *
 * <p><b>{@code priority = 800}</b>, so this runs before {@code SlotLockGuardMixin} (default 1000).
 * An explicitly protected item should be answered for by the feature the player marked it in, and
 * ordering it first also keeps the rarity drop protection's triple-press counter from advancing on
 * a press that was going to be refused here anyway.
 *
 * <p>All the reasoning lives in {@link ItemProtection#blocksContainerInput}; this hook only asks.
 */
@Mixin(value = MultiPlayerGameMode.class, priority = 800)
public abstract class ItemProtectionGuardMixin {

    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$guardProtectedItems(int containerId, int slotId, int button,
                                                        ContainerInput input, Player player,
                                                        CallbackInfo ci) {
        if (ItemProtection.blocksContainerInput(player, slotId, button, input)) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Stacking] ItemProtectionGuardMixin cancelled {} slot={} button={}", input, slotId, button);
            ci.cancel();
        }
    }
}
