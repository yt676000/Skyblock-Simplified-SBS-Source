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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.inventory.logic.SlotLock;

/**
 * Enforces the inventory slot locks.
 *
 * <p>{@code handleContainerInput} is the single funnel every container interaction passes through on
 * its way to the server – pickup, shift-click, number-key swap, drop, clone and drag alike. Vetoing
 * it at HEAD means a refused action never becomes a packet, so the client and server can never end
 * up disagreeing about where an item is, and no vanilla inventory logic had to be reimplemented to
 * get there.
 *
 * <p>All the reasoning lives in {@link SlotLock#blocks}; this hook only asks and cancels.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class SlotLockGuardMixin {

    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$guardLockedSlots(int containerId, int slotId, int button,
                                                     ContainerInput input, Player player,
                                                     CallbackInfo ci) {
        if (SlotLock.blocks(player, slotId, button, input)) {
            SlotLock.denied();
            skyblockSimplified$logVeto("slot lock", input, slotId, button);
            ci.cancel();
            return;
        }
        // Rarity drop protection: the two container-side drop shapes are THROW on a slot (Q /
        // Ctrl+Q) and PICKUP outside the window (-999) with the item on the cursor. Everything
        // else (moving, selling, trading) is not a drop and passes untouched.
        if (player != null && player.containerMenu != null) {
            net.minecraft.world.item.ItemStack dropping = null;
            if (input == ContainerInput.THROW
                    && slotId >= 0 && slotId < player.containerMenu.slots.size()) {
                dropping = player.containerMenu.getSlot(slotId).getItem();
            } else if (input == ContainerInput.PICKUP && slotId == -999) {
                dropping = player.containerMenu.getCarried();
            }
            if (dropping != null && !sbs.modid.client.helper.inventory.logic.DropProtection.allowDrop(dropping)) {
                skyblockSimplified$logVeto("drop protection", input, slotId, button);
                ci.cancel();
            }
        }
        // Garden visitor guard: refusing a visitor with a valuable reward needs Ctrl+Shift.
        if (sbs.modid.client.skills.garden.logic.VisitorGuard.blocksClick(player, slotId, input)) {
            skyblockSimplified$logVeto("visitor guard", input, slotId, button);
            ci.cancel();
        }
    }

    /**
     * One line per refused click, so "my click did nothing" can be traced to the rule that ate it.
     * Only refusals are logged - they are rare by design - never the clicks that pass.
     */
    @Unique
    private static void skyblockSimplified$logVeto(String rule, ContainerInput input, int slotId, int button) {
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Stacking] SlotLockGuardMixin ({}) cancelled {} slot={} button={}",
                rule, input, slotId, button);
    }
}
