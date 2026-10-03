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
import sbs.modid.client.helper.itemprotection.logic.ItemProtection;

/**
 * Stops the in-world drop key from throwing a protected item away.
 *
 * <p>This is the one way an item leaves the inventory without passing through a container screen, so
 * it needs its own veto beside {@code ItemProtectionGuardMixin}. Cancelling at HEAD is safe for the
 * same reason it is there: {@code drop} calls {@code getInventory().removeFromSelected(all)} before
 * {@code connection.send}, so refusing first removes nothing locally and sends nothing.
 *
 * <p><b>Never inside a dungeon.</b> Hypixel disables dropping in the Catacombs and reuses the press
 * as the class-ability trigger, and the ability travels on the very packet this method sends.
 * Cancelling there protects nothing - the server would refuse a real drop anyway - and silently eats
 * the ability instead. The same guard, for the same reason, opens {@code SlotLockDropMixin}; it is
 * repeated rather than shared because a veto that forgets it is a bug in the veto, not in a helper.
 */
@Mixin(value = LocalPlayer.class, priority = 800)
public abstract class ItemProtectionDropMixin {

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$blockProtectedDrop(boolean dropStack,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (DungeonStateManager.getInstance().inDungeon()) {
            return;   // the press is a class ability, not a drop - see the class comment
        }
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (ItemProtection.blocksDrop(self)) {
            cir.setReturnValue(false);
        }
    }
}
