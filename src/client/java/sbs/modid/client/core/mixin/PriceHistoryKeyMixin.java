/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

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
import sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager;
import sbs.modid.client.economy.pricehistory.logic.PriceLookup;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;

/**
 * The Item Price History hotkey <b>inside containers</b>: every press opens a <b>new</b> browser
 * window (up to the manager's limit). Each window has its own close ✕ button; the "Close All"
 * button in the bottom-left corner closes everything.
 *
 * <p>Guards mirror {@link RecipeLookupKeyMixin}: no text field (vanilla {@link EditBox}, the Recipe
 * Viewer search bar or any overlay's own search) may be focused, so the key is never stolen from
 * typing.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class PriceHistoryKeyMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$priceHistoryKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        var settings = ConfigManager.getInstance().get().priceHistory;
        boolean isOpenKey = settings.openKey != 0 && event.key() == settings.openKey;
        boolean isValueKey = settings.valueKey != 0 && event.key() == settings.valueKey;
        boolean isSimilarKey = settings.similarAuctionsKey != 0 && event.key() == settings.similarAuctionsKey;
        if (!isOpenKey && !isValueKey && !isSimilarKey) {
            return;
        }
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        PriceBrowserManager manager = PriceBrowserManager.getInstance();
        if (self.getFocused() instanceof EditBox
                || SearchHighlightState.getInstance().isSearchFocused()
                || manager.isAnyTypingFocused()) {
            return; // never steal keystrokes from a text field / the browser page
        }
        Slot slot = this.hoveredSlot;
        ItemStack stack = slot != null ? slot.getItem() : null;
        sbs.modid.client.economy.recipe.model.ItemRef panelItem = sbs.modid.client.economy.recipe.ui.RecipeOverlay
                .getInstance().hoveredItem();

        if (isOpenKey) {
            // Price-history key: always open a new browser window (no toggle).
            if (stack != null && !stack.isEmpty()) {
                manager.openNewItem(PriceLookup.candidatesFor(stack));
            } else if (panelItem != null) {
                manager.openNewItem(PriceLookup.candidatesFor(panelItem));
            } else {
                manager.openNewSearch();
            }
            cir.setReturnValue(true);
            return;
        }

        // Similar-auctions key: appraise the hovered item's exact variant via the cloud API and
        // show live + historic matching auctions; pressed over nothing it closes an open window.
        if (isSimilarKey) {
            var auctions = sbs.modid.client.economy.auctions.ui.SimilarAuctionsOverlay.getInstance();
            if (stack != null && !stack.isEmpty()) {
                auctions.openFor(stack);
            } else if (panelItem != null) {
                auctions.openPlain(PriceLookup.candidatesFor(panelItem), panelItem.name);
            } else if (auctions.isOpen()) {
                auctions.close();
            }
            cir.setReturnValue(true);
            return;
        }

        // Value key: price everything applied to the hovered item in the value table window;
        // pressed over nothing it closes an open value window.
        sbs.modid.client.economy.itemvalue.ItemValueService valueService =
                sbs.modid.client.economy.itemvalue.ItemValueService.getInstance();
        if (stack != null && !stack.isEmpty()) {
            valueService.check(stack);
        } else if (panelItem != null) {
            valueService.checkPlain(PriceLookup.candidatesFor(panelItem), panelItem.name);
        } else if (sbs.modid.client.economy.itemvalue.ItemValueOverlay.getInstance().isOpen()) {
            sbs.modid.client.economy.itemvalue.ItemValueOverlay.getInstance().close();
        }
        cir.setReturnValue(true);
    }
}
