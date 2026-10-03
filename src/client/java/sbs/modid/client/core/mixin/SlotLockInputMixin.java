/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.inventory.logic.SlotLock;

/**
 * Toggling a slot lock: the lock key held + left click on a player-inventory slot flips its lock and
 * swallows the click, so the click never becomes an item pickup. Without the key held the method
 * returns immediately and the click behaves exactly as vanilla.
 *
 * <p>Drawing the padlocks is {@code SlotLockDecorator}; enforcing the locks is
 * {@link SlotLockGuardMixin}. This class is only the toggle.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotLockInputMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$toggleSlotLock(MouseButtonEvent event, boolean doubled,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!SlotLock.enabled() || event.button() != 0 || !SlotLock.lockKeyDown()) {
            return;
        }
        int index = SlotLock.inventoryIndexOf(this.hoveredSlot);
        if (index < 0) {
            return; // not a player-inventory slot - nothing lockable here
        }
        SlotLock.toggle(index);
        cir.setReturnValue(true);
    }

}
