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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.economy.bazaar.prerender.BazaarPrerender;

/**
 * Tells the Bazaar pre-render cache which slot the player clicked, so an arriving container can be
 * attributed to the click that asked for it.
 *
 * <p><b>Observation only.</b> Not cancellable, returns nothing, consumes nothing: the click carries
 * on to the container's own handling exactly as it would without this. The two things it produces
 * are the click-to-arrival latency — the amount of waiting a preview would be hiding — and the
 * record of which screen a given slot opens, which is the only way a cache can be looked up at click
 * time, since the arriving screen does not exist yet.
 *
 * <p>No priority is set, and none is wanted: this must run whatever else claims the click, and it
 * changes nothing if another handler cancels afterwards. A click that is consumed by an overlay and
 * never reaches the server simply produces no arrival, and the pending record times out.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class BazaarPrerenderInputMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void skyblockSimplified$bazaarPrerenderClick(MouseButtonEvent event, boolean doubled,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (!BazaarPrerender.enabled()) {
            return;
        }
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        // The clicked slot is whatever the screen already knows is under the cursor - resolving it
        // again here would be a second, disagreeing answer to a question the screen has answered.
        Slot hovered = ((AbstractContainerScreenAccessor) self).skyblockSimplified$hoveredSlot();
        if (hovered != null) {
            BazaarPrerender.getInstance().onSlotClick(self, hovered.index);
        }
    }
}
