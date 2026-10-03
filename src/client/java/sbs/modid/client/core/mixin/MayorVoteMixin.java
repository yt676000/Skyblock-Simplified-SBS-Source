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
import sbs.modid.client.economy.mayor.MayorVoteTracker;

import java.util.Locale;

/**
 * Notices the vote you cast at the election booth.
 *
 * <p>Same funnel and the same reasoning as {@code PetPickMixin}: {@code handleContainerInput} is
 * what every way of clicking a menu slot goes through, including the SBS overlays that draw their
 * own cards and dispatch a synthetic click at the slot behind them. At RETURN, so a click another
 * mixin vetoes never registers as a vote, and because the menu is unchanged there the clicked slot
 * still holds the candidate.
 *
 * <p>The tracker decides what a candidate looks like; this only routes the clicked stack to it and,
 * while the booth is open, walks the rest of the menu so a vote cast on another device or in an
 * earlier session is recognised from the booth's own lore.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MayorVoteMixin {

    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void skyblockSimplified$captureVote(int containerId, int slotId, int button,
                                                ContainerInput input, Player player,
                                                CallbackInfo ci) {
        if (input != ContainerInput.PICKUP || button != 0 || slotId < 0 || player == null) {
            return;
        }
        if (!skyblockSimplified$atBooth()) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != containerId || slotId >= menu.slots.size()) {
            return;
        }
        ItemStack stack = menu.getSlot(slotId).getItem();
        if (stack != null && !stack.isEmpty()) {
            MayorVoteTracker.getInstance().readBoothSlot(stack, true);
        }
    }

    /** True while the open screen is the election booth, by its title. */
    private static boolean skyblockSimplified$atBooth() {
        var screen = GuiStateManager.getInstance().getCurrentScreen();
        if (screen == null || screen.getTitle() == null) {
            return false;
        }
        String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
        return title.contains("election") || title.contains("mayor");
    }
}
