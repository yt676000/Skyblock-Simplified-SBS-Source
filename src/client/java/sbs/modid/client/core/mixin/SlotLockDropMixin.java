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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.helper.inventory.logic.SlotLock;

/**
 * Stops the in-world drop key (Q) from throwing an item out of a locked hotbar slot.
 *
 * <p>This is the one way an item can leave the inventory without going through a container screen,
 * so it needs its own veto next to {@code SlotLockGuardMixin}. Selecting and using the item is
 * untouched – only throwing it away is refused.
 *
 * <p><b>Never inside a dungeon.</b> There the drop key is not a drop key: Hypixel disables dropping
 * in the Catacombs and reuses the press as the class-ability trigger, and the ability travels on the
 * very packet this method sends. Cancelling it therefore protects nothing – the server would refuse
 * a real drop anyway – and silently eats the ability instead, which is why the guard runs before
 * either veto rather than inside them.
 */
@Mixin(LocalPlayer.class)
public abstract class SlotLockDropMixin {

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$blockLockedDrop(boolean dropStack, CallbackInfoReturnable<Boolean> cir) {
        if (DungeonStateManager.getInstance().inDungeon()) {
            return;   // the press is a class ability, not a drop - see the class comment
        }
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (SlotLock.blocksSelectedDrop(self)) {
            SlotLock.denied();
            cir.setReturnValue(false);
            return;
        }
        // Rarity drop protection: too-rare items don't leave the hotbar on a stray Q either.
        if (!sbs.modid.client.helper.inventory.logic.DropProtection.allowDrop(self.getInventory().getSelectedItem())) {
            cir.setReturnValue(false);
        }
    }
}
