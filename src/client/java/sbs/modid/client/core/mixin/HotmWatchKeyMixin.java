/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.skills.mining.logic.HotmWatchKey;

/**
 * The HotM Upgrade Reminder's watch key inside the Heart of the Mountain menu. At the head of
 * {@code keyPressed}, so a key SBS acts on is consumed before the screen can turn it into a slot
 * interaction; every other key falls through untouched. All of the deciding is in
 * {@link HotmWatchKey}.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class HotmWatchKeyMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hotmWatchKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (HotmWatchKey.handle(self, event, this.hoveredSlot)) {
            cir.setReturnValue(true);
        }
    }
}
