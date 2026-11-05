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
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.ItemAnalysis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.recipe.logic.EnchantedForm;
import sbs.modid.client.economy.recipe.logic.RecipeLookup;
import sbs.modid.client.economy.recipe.logic.RecipeRegistry;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.ui.RecipeViewerScreen;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;

/**
 * Recipe Viewer lookup keys. Pressing the lookup key (default <b>R</b>) while hovering an item in any container opens the
 * Recipe Viewer directly on that item's recipes (Shift: its usages), resolved by SkyBlock id so
 * reforges and stars never matter; items without a recipe fall back to a search on the cleaned
 * name. Recipes can be inspected from
 * inventories, chests, the Bazaar, the Auction House – anywhere items are shown.
 *
 * <p>The enchanted-form key (unbound by default) opens the recipe of the hovered item's compacted
 * form instead - Wheat → Enchanted Wheat ({@link EnchantedForm}).
 *
 * <p>Guards: the module must be enabled, a real item must be hovered, and no text field may be
 * focused (so typing an 'r' into an anvil / search box is never hijacked).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class RecipeLookupKeyMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$recipeLookup(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        SBSConfig.RecipeViewerSettings settings = ConfigManager.getInstance().get().recipeViewer;
        boolean lookup = settings.lookupKey > 0 && event.key() == settings.lookupKey;
        boolean enchanted = settings.enchantedFormKey > 0 && event.key() == settings.enchantedFormKey;
        if (!lookup && !enchanted) {
            return;
        }
        if (!settings.enabled) {
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
        char hotkeyChar = RecipeLookup.hotkeyChar(event.key());
        RecipeViewerScreen screen = lookup
                ? lookupScreen(stack, event.hasShiftDown(), hotkeyChar)
                : enchantedFormScreen(stack, hotkeyChar);
        Minecraft.getInstance().setScreenAndShow(screen);
        cir.setReturnValue(true);
    }

    @Unique
    private static RecipeViewerScreen lookupScreen(ItemStack stack, boolean usages, char hotkeyChar) {
        // usages = Shift: like the overlay's right-click
        RecipeRegistry registry = RecipeRegistry.getInstance();
        String id = RecipeLookup.directId(ItemAnalysis.of(stack).id(),
                lookup -> !(usages ? registry.usagesFor(lookup) : registry.recipesFor(lookup)).isEmpty());
        SkyBlockItemCatalog catalog = SkyBlockItemCatalog.getInstance();
        String name = RecipeLookup.cleanName(stack.getHoverName().getString(),
                known -> catalog.byName(known) != null);
        if (id == null) {
            return RecipeViewerScreen.forHotkey(name, hotkeyChar);
        }
        return RecipeViewerScreen.forHotkey(refOf(id, name), usages, hotkeyChar);
    }

    /**
     * The enchanted form's recipe; without one, the item's usages and a short action-bar note -
     * other compactions of the same item are among those usages.
     */
    @Unique
    private static RecipeViewerScreen enchantedFormScreen(ItemStack stack, char hotkeyChar) {
        String id = ItemAnalysis.of(stack).id();
        String name = RecipeLookup.cleanName(stack.getHoverName().getString(),
                known -> SkyBlockItemCatalog.getInstance().byName(known) != null);
        if (id == null || id.isEmpty()) {
            return RecipeViewerScreen.forHotkey(name, hotkeyChar);
        }
        ItemRef form = EnchantedForm.best(id, RecipeRegistry.getInstance().usagesFor(id));
        if (form != null) {
            return RecipeViewerScreen.forHotkey(refOf(form.lookupId(), form.name), false, hotkeyChar);
        }
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendOverlayMessage(
                    Component.literal("No enchanted form for " + name));
        }
        return RecipeViewerScreen.forHotkey(refOf(id, name), true, hotkeyChar);
    }

    @Unique
    private static ItemRef refOf(String id, String name) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(id);
        return entry != null ? entry.toRef() : new ItemRef(id, null, name, 1);
    }
}
