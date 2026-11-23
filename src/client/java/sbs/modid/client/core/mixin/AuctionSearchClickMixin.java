/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.bazaar.logic.BazaarSearchHistory;

/**
 * Middle click on an item while an Auction House GUI is open: runs {@code /auctionsearch <name>}.
 * The click is swallowed so it never also reaches the server as a middle-click slot input.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AuctionSearchClickMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$auctionSearch(MouseButtonEvent event, boolean doubled,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || this.hoveredSlot == null
                || !this.hoveredSlot.hasItem()
                || !ConfigManager.getInstance().get().ahFlips.middleClickSearch) {
            return;
        }
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!BazaarSearchHistory.isAuctionGui(self.getTitle().getString())) {
            return;
        }
        String name = searchName(this.hoveredSlot.getItem().getHoverName().getString());
        var player = Minecraft.getInstance().player;
        if (name.isEmpty() || player == null) {
            return;
        }
        player.connection.sendCommand("auctionsearch " + name);
        cir.setReturnValue(true);
    }

    /** Display name minus colour codes, a pet's "[Lvl n]" prefix and trailing star glyphs. */
    private static String searchName(String raw) {
        String s = ChatFormatting.stripFormatting(raw);
        if (s == null) {
            return "";
        }
        s = s.replaceFirst("^\\[Lvl \\d+\\]\\s*", "");
        s = s.replaceAll("[✪➊➋➌➍➎⚚]", "");
        return s.trim().replaceAll("\\s+", " ");
    }
}
