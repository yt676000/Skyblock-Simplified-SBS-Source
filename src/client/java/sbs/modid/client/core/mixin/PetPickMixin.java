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
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.ui.hud.logic.PetTracker;

/**
 * Notices which pet you summon, so the Active Pet card can capture its skin.
 *
 * <p>Hooked on {@code handleContainerInput} rather than on the screen's {@code mouseClicked} because
 * that is the single funnel <b>every</b> way of picking a pet passes through: a plain click on the
 * Hypixel menu, the SBS Pets overlay (which draws its own cards and dispatches a synthetic click at
 * the slot behind them), and the Loadouts overlay. Hooking the mouse would have caught only the first
 * - under either overlay the vanilla {@code hoveredSlot} is not the pet you clicked.
 *
 * <p>At RETURN, so a click another mixin vetoes (a slot lock, drop protection) never registers as a
 * pet pick: a HEAD cancellation returns before these injectors are reached. The menu itself is
 * untouched at this point - the server's reply lands later - so the clicked slot still holds the pet.
 *
 * <p>Left-click PICKUP only, which is what summons a pet; the tracker itself checks that this is the
 * Pets menu and that the slot really holds a pet entry.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class PetPickMixin {

    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void skyblockSimplified$capturePetPick(int containerId, int slotId, int button,
                                                   ContainerInput input, Player player,
                                                   CallbackInfo ci) {
        if (input != ContainerInput.PICKUP || button != 0 || slotId < 0 || player == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != containerId || slotId >= menu.slots.size()) {
            return;
        }
        ItemStack stack = menu.getSlot(slotId).getItem();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        var screen = GuiStateManager.getInstance().getCurrentScreen();
        String title = screen == null || screen.getTitle() == null ? "" : screen.getTitle().getString();
        PetTracker.getInstance().onMenuSlotClicked(title, stack);
    }
}
