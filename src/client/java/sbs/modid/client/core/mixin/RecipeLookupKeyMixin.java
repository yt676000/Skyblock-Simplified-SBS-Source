/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.recipe.ui.RecipeViewerScreen;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;

/**
 * Recipe Viewer lookup key: pressing <b>R</b> while hovering an item in any container opens the
 * Recipe Viewer pre-searched with that item, so recipes can be inspected from
 * inventories, chests, the Bazaar, the Auction House – anywhere items are shown.
 *
 * <p>Guards: the module must be enabled, a real item must be hovered, and no text field may be
 * focused (so typing an 'r' into an anvil / search box is never hijacked).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class RecipeLookupKeyMixin {

    /** GLFW key code for R. */
    private static final int KEY_R = 82;

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$recipeLookup(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() != KEY_R) {
            return;
        }
        if (!ConfigManager.getInstance().get().recipeViewer.enabled) {
            return;
        }
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (self.getFocused() instanceof EditBox || SearchHighlightState.getInstance().isSearchFocused()
                || sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager.getInstance().isAnyTypingFocused()) {
            return; // never steal keystrokes from a text field, the search bar or the browser page
        }
        Slot slot = this.hoveredSlot;
        if (slot == null) {
            return;
        }
        ItemStack stack = slot.getItem();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String name = stack.getHoverName().getString().replaceAll(SECTION_SIGN + ".", "").trim();
        Minecraft.getInstance().setScreenAndShow(new RecipeViewerScreen(name));
        cir.setReturnValue(true);
    }
}
