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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.dev.ScreenOpeners;

/**
 * Layout Recorder (developer tool): a slot click inside an open menu, as a candidate step of the
 * click path - kept only if the next screen opens from it (see {@code OpenerPath}).
 *
 * <p>At RETURN, so a click another mixin vetoed never counts, and observational only: it reads the
 * clicked slot (unchanged until the server answers) and the menu title, and never cancels.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class LayoutOpenerSlotMixin {

    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void skyblockSimplified$layoutOpener(int containerId, int slotId, int button,
                                                 ContainerInput input, Player player, CallbackInfo ci) {
        if (slotId < 0 || player == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != containerId || slotId >= menu.slots.size()) {
            return;
        }
        var screen = GuiStateManager.getInstance().getCurrentScreen();
        String title = screen == null || screen.getTitle() == null ? "" : screen.getTitle().getString();
        ScreenOpeners.getInstance().onSlotClick(title, slotId, menu.getSlot(slotId).getItem());
    }
}
