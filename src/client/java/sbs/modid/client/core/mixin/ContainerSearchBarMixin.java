/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager;
import sbs.modid.client.economy.recipe.ui.RecipeOverlay;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;

/**
 * <b>Input handling</b> for the Recipe Viewer overlay on container screens. All layout, rendering
 * and click semantics live in {@link RecipeOverlay}; this mixin only forwards the events:
 * <ul>
 *   <li>mouse clicks / scrolling → {@link RecipeOverlay#handleClick} / {@link RecipeOverlay#handleScroll}
 *       (search-bar focus, double-click highlight toggle, item clicks with Shift/Ctrl shortcuts),</li>
 *   <li>keyboard → search-bar editing while focused (every key consumed so 'E' can't close the
 *       inventory mid-typing; Escape / Enter release focus),</li>
 *   <li>{@code charTyped} → an added override (the method only exists as an interface default in
 *       this version) feeding typed characters into the query.</li>
 * </ul>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSearchBarMixin implements ContainerEventHandler {

    // GLFW key codes.
    @Unique
    private static final int KEY_ESCAPE = 256;
    /** GLFW_KEY_TAB: cycles the Recipe Viewer's kind chips while the bar is focused. */
    private static final int KEY_TAB = 258;
    @Unique
    private static final int KEY_ENTER = 257;
    @Unique
    private static final int KEY_NUMPAD_ENTER = 335;
    @Unique
    private static final int KEY_BACKSPACE = 259;
    @Unique
    private static final int KEY_DELETE = 261;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$overlayClick(MouseButtonEvent event, boolean doubled,
                                                 CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        // The calculator only types while it is being clicked into, so its focus is re-decided on
        // EVERY click – before anything can consume the event and skip its own handler.
        sbs.modid.client.economy.calculator.CalculatorOverlay.getInstance().updateFocus(self, event);
        // The AH flip popup cards render above everything (OverlayRenderMixin TAIL), so they get
        // the very first pick of every click – before the floating windows and the container.
        if (sbs.modid.client.economy.auctions.ui.AhFlipPopups.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // Melody's Harp timing log: records the click, never consumes it.
        sbs.modid.client.helper.experiment.logic.HarpHelper.getInstance().onClick(self, event);
        // Experimentation Table misclick guard: veto an out-of-order click on the board (never sends
        // it to the server), otherwise let it fall through to the game as normal.
        if (sbs.modid.client.helper.experiment.logic.ExperimentationTable.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // The big terminal board covers the menu whole, so it takes every click first and forwards
        // the ones that land on a cell to the real slot behind it (guard applied on the way).
        if (sbs.modid.client.dungeons.terminal.TerminalBoardOverlay.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // Dungeon terminal misclick guard: veto a click the solution does not contain (never sends it
        // to the server); everything the solver marked, and every terminal it does not guard, falls
        // through to the game untouched.
        if (sbs.modid.client.dungeons.terminal.TerminalSolver.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // Fishing tracker title bar: click to open / collapse its scrollable drop list.
        if (sbs.modid.client.skills.fishing.render.FishingHud.handleClick(event.x(), event.y())) {
            cir.setReturnValue(true);
            return;
        }
        // The storage workspace is exclusive: it takes every click before any floating window, so
        // no half-usable window can sit on top of the full UI.
        if (sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().isWorkspaceActive(self)) {
            sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().handleClick(self, event);
            cir.setReturnValue(true);
            return;
        }
        // Input order has to be the frame order read backwards, and the frame order FLIPS: while the
        // container is raised the windows are drawn first of all (behind everything), otherwise they
        // are drawn at TAIL, above the menu and above every overlay painted over it.
        boolean windowsOnTop = !sbs.modid.client.ui.window.FloatingWindows.isContainerRaised();
        if (windowsOnTop && sbs$windowClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // Inventory Window title bar: it sits above the panel, never over a slot, so taking it here
        // costs the menu nothing; only a floating window on top of it gets the click first.
        if (sbs.modid.client.helper.inventory.ui.InventoryWindow.handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // The overlays painted over the menu: Loadouts / Pets cover it whole (their grids map clicks
        // onto the real slots), the storage overview does the same for Hypixel's Storage menu, and
        // the Recipe Viewer's static panel sits beside it.
        if (sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // The Accessory Bag chip sits outside the container bounds, so it can only ever take a click
        // no slot wanted - but it is checked before the big overlays so a raised window cannot bury it.
        if (sbs.modid.client.helper.inventory.render.AccessoryBagButton.handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // The equipment column sits outside the inventory's left edge: display only, a click on it
        // runs /equipment and never reaches a slot.
        if (sbs.modid.client.helper.inventory.render.EquipmentColumn.handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // The Chocolate Factory button on a filler slot of the SkyBlock Menu: a click on it sends one
        // command and never reaches the pane underneath.
        if (sbs.modid.client.helper.chocolate.ui.ChocolateMenuShortcut.handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.helper.loadouts.LoadoutsOverlay.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.helper.wardrobe.ArmorSetsOverlay.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.helper.pets.PetsOverlay.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // The Sack Overlay sits BESIDE the sack, so it only ever takes a click that landed on its
        // own panel - every click on the sack's slots falls through and withdraws as it always did.
        if (sbs.modid.client.helper.sacks.SackOverlay.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (RecipeOverlay.getInstance().handleClick(self, event, doubled)) {
            cir.setReturnValue(true);
            return;
        }
        // The Bazaar Order History panel sits beside the container, never over it, so it only ever
        // takes a click the menu itself was never going to see.
        if (sbs.modid.client.economy.bazaar.ui.OrderHistoryPanel.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // Windows are behind the container now, so they only get what neither the overlays above nor
        // the container itself wanted. Skipping them entirely for points inside the container is what
        // keeps a window sticking out beyond the menu clickable while the menu stays on top.
        if (!windowsOnTop && !sbs$inContainer(event.x(), event.y()) && sbs$windowClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        // A folded Inventory Window has its panel off-screen: vanilla would read this click as
        // "outside" and throw, so nothing no overlay wanted reaches it.
        if (sbs.modid.client.helper.inventory.ui.InventoryWindow.swallowWhileFolded(self)) {
            cir.setReturnValue(true);
            return;
        }
        // Nothing of ours consumed the click: clicking the container body pulls it in front of
        // the floating windows (the click itself still goes to the container as usual).
        if (sbs$inContainer(event.x(), event.y())) {
            sbs.modid.client.ui.window.FloatingWindows.raiseContainer();
        }
    }

    /**
     * Offers the click to the floating windows in their current z-order (topmost first); a consumed
     * click raises that layer above the others, like a desktop window.
     */
    @Unique
    private boolean sbs$windowClick(AbstractContainerScreen<?> self, MouseButtonEvent event) {
        for (sbs.modid.client.ui.window.FloatingWindows.Layer layer
                : sbs.modid.client.ui.window.FloatingWindows.clickOrder()) {
            boolean consumed = switch (layer) {
                case VALUE -> sbs.modid.client.economy.itemvalue.ItemValueOverlay.getInstance().handleClick(self, event);
                case BROWSERS -> PriceBrowserManager.getInstance().handleClick(self, event);
                case RECIPE -> RecipeOverlay.getInstance().handleWindowClick(self, event);
                case AUCTIONS -> sbs.modid.client.economy.auctions.ui.SimilarAuctionsOverlay.getInstance()
                        .handleClick(self, event);
                case FLIPS -> sbs.modid.client.economy.bazaar.ui.BestFlipsOverlay.getInstance()
                        .handleClick(self, event);
                case FORGE -> sbs.modid.client.economy.forge.ui.ForgeFlipsOverlay.getInstance()
                        .handleClick(self, event);
                case AH_FLIPS -> sbs.modid.client.economy.auctions.ui.AhFlipsOverlay.getInstance()
                        .handleClick(self, event);
                case CALCULATOR -> sbs.modid.client.economy.calculator.CalculatorOverlay.getInstance()
                        .handleClick(self, event);
                case GARDEN_PLOTS -> sbs.modid.client.skills.garden.ui.GardenPlotsOverlay.getInstance()
                        .handleClick(self, event);
                case ESSENCE_SHOP -> sbs.modid.client.economy.essenceshop.ui.EssenceShopOverlay
                        .getInstance().handleClick(self, event);
                case MISSING_SHARDS -> sbs.modid.client.skills.hunting.ui.MissingShardsOverlay
                        .getInstance().handleClick(self, event);
            };
            if (consumed) {
                sbs.modid.client.ui.window.FloatingWindows.raise(layer);
                return true;
            }
        }
        return false;
    }

    /** True when the point lies inside the container GUI's own rectangle. */
    @Unique
    private boolean sbs$inContainer(double mx, double my) {
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) this;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        return mx >= left && mx < left + bounds.skyblockSimplified$imageWidth()
                && my >= top && my < top + bounds.skyblockSimplified$imageHeight();
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$overlayDrag(MouseButtonEvent event, double dragX, double dragY,
                                                CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        // The storage workspace swallows drags: item moves complete on the press, and a vanilla
        // quick-craft drag under our backdrop would scatter the carried item into wrong real slots.
        if (sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().handleDrag()) {
            cir.setReturnValue(true);
            return;
        }
        // Sack Overlay scrollbar thumb.
        if (sbs.modid.client.helper.sacks.SackOverlay.getInstance().handleDrag(self, event.y())) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.auctions.ui.SimilarAuctionsOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.forge.ui.ForgeFlipsOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.bazaar.ui.BestFlipsOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.auctions.ui.AhFlipsOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.itemvalue.ItemValueOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (PriceBrowserManager.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.calculator.CalculatorOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.skills.garden.ui.GardenPlotsOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.essenceshop.ui.EssenceShopOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.skills.hunting.ui.MissingShardsOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (RecipeOverlay.getInstance().handleDrag(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.helper.inventory.ui.InventoryWindow.handleDrag(self, event)
                || sbs.modid.client.helper.inventory.ui.InventoryWindow.swallowWhileFolded(self)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$overlayRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        // The storage workspace swallows the release: our PICKUP already ran on the press, and the
        // vanilla release would drop the carried item outside (slot -999) since its own GUI is hidden.
        if (sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().handleRelease()) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.helper.sacks.SackOverlay.getInstance()
                .handleRelease((AbstractContainerScreen<?>) (Object) this)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.auctions.ui.SimilarAuctionsOverlay.getInstance().handleRelease(event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.forge.ui.ForgeFlipsOverlay.getInstance().handleRelease(event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.bazaar.ui.BestFlipsOverlay.getInstance().handleRelease(event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.auctions.ui.AhFlipsOverlay.getInstance().handleRelease(event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.itemvalue.ItemValueOverlay.getInstance().handleRelease(event)) {
            cir.setReturnValue(true);
            return;
        }
        if (PriceBrowserManager.getInstance().handleRelease(event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.calculator.CalculatorOverlay.getInstance().handleRelease()) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.skills.garden.ui.GardenPlotsOverlay.getInstance().handleRelease()) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.economy.essenceshop.ui.EssenceShopOverlay.getInstance().handleRelease(event)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.skills.hunting.ui.MissingShardsOverlay.getInstance().handleRelease(event)) {
            cir.setReturnValue(true);
            return;
        }
        if (RecipeOverlay.getInstance().handleRelease()) {
            cir.setReturnValue(true);
            return;
        }
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (sbs.modid.client.helper.inventory.ui.InventoryWindow.handleRelease(self)
                || sbs.modid.client.helper.inventory.ui.InventoryWindow.swallowWhileFolded(self)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$overlayScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                                  CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        // A hovered, screen-overflowing item tooltip claims the wheel FIRST, so its lore scrolls
        // instead of the menu / an overlay behind it. It only claims while such a tooltip is actually
        // on screen (Scrollable Tooltips on + a long tooltip drawn in the last few frames), so normal
        // scrolls still fall through to the overlays below. This is the single routing point for the
        // tooltip wheel on container screens (the old standalone TooltipScrollInputMixin was removed to
        // guarantee this order and avoid a double-scroll from two injectors on the same method).
        if (sbs.modid.client.helper.tooltip.ScrollableTooltips.getInstance().onMouseScroll(scrollY)) {
            cir.setReturnValue(true);
            return;
        }
        // Every overlay painted OVER the menu is asked before the "is this the container's own body"
        // question below. They cover the menu rectangle, so a scroll on one of them is never the
        // container's - asking the container first is what made the Ender Chest rows stop scrolling
        // as soon as the menu had been clicked once: the raised container claimed the wheel for its
        // whole rectangle, overlay on top or not.
        if (sbs.modid.client.helper.wardrobe.ArmorSetsOverlay.getInstance().handleScroll(self)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.helper.pets.PetsOverlay.getInstance().handleScroll(self, mouseY, scrollY)) {
            cir.setReturnValue(true);
            return;
        }
        if (sbs.modid.client.helper.loadouts.LoadoutsOverlay.getInstance().handleScroll(self, mouseY, scrollY)) {
            cir.setReturnValue(true);
            return;
        }
        // Only over its own panel: the sack keeps the wheel everywhere else.
        if (sbs.modid.client.helper.sacks.SackOverlay.getInstance()
                .handleScroll(self, mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
            return;
        }
        // The Fishing profit tracker scrolls when the cursor is over its in-inventory panel.
        if (sbs.modid.client.skills.fishing.render.FishingHud.handleScroll(mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
            return;
        }
        // The storage grid: its own rows first, then the exclusivity guard below.
        Object selfScroll = this;
        if (selfScroll instanceof AbstractContainerScreen<?> containerScroll
                && sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance()
                        .handleScroll(containerScroll, mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
            return;
        }
        // Exclusive: swallow every scroll over the storage full UI so no hidden window scrolls.
        if (selfScroll instanceof AbstractContainerScreen<?> storageScreen
                && sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().isWorkspaceActive(storageScreen)) {
            cir.setReturnValue(true);
            return;
        }
        // Only now the container/window question: while the container is raised the windows sit
        // behind it, so a scroll over its body must not reach them. Below this line the windows are
        // offered the wheel in the same z-order clicks use (topmost first), without re-ordering them.
        if (sbs.modid.client.ui.window.FloatingWindows.isContainerRaised()
                && sbs$inContainer(mouseX, mouseY)) {
            return;
        }

        for (sbs.modid.client.ui.window.FloatingWindows.Layer layer
                : sbs.modid.client.ui.window.FloatingWindows.clickOrder()) {
            boolean consumed = switch (layer) {
                case VALUE -> sbs.modid.client.economy.itemvalue.ItemValueOverlay.getInstance()
                        .handleScroll(self, mouseX, mouseY, scrollY);
                case BROWSERS -> PriceBrowserManager.getInstance().handleScroll(self, mouseX, mouseY, scrollY);
                case RECIPE -> RecipeOverlay.getInstance().handleWindowScroll(self, mouseX, mouseY, scrollY);
                case AUCTIONS -> sbs.modid.client.economy.auctions.ui.SimilarAuctionsOverlay.getInstance()
                        .handleScroll(self, mouseX, mouseY, scrollY);
                case FLIPS -> sbs.modid.client.economy.bazaar.ui.BestFlipsOverlay.getInstance()
                        .handleScroll(self, mouseX, mouseY, scrollY);
                case FORGE -> sbs.modid.client.economy.forge.ui.ForgeFlipsOverlay.getInstance()
                        .handleScroll(self, mouseX, mouseY, scrollY);
                case AH_FLIPS -> sbs.modid.client.economy.auctions.ui.AhFlipsOverlay.getInstance()
                        .handleScroll(self, mouseX, mouseY, scrollY);
                case CALCULATOR -> sbs.modid.client.economy.calculator.CalculatorOverlay.getInstance()
                        .handleScroll(self, mouseX, mouseY, scrollY);
                case GARDEN_PLOTS -> sbs.modid.client.skills.garden.ui.GardenPlotsOverlay.getInstance()
                        .handleScroll(self, mouseX, mouseY, scrollY);
                case ESSENCE_SHOP -> sbs.modid.client.economy.essenceshop.ui.EssenceShopOverlay
                        .getInstance().handleScroll(self, mouseX, mouseY, scrollY);
                case MISSING_SHARDS -> sbs.modid.client.skills.hunting.ui.MissingShardsOverlay
                        .getInstance().handleScroll(self, mouseX, mouseY, scrollY);
            };
            if (consumed) {
                cir.setReturnValue(true);
                return;
            }
        }
        if (RecipeOverlay.getInstance().handleScroll(self, mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$searchBarKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        // The price overlay windows (browser page or fallback search) consume every key while focused.
        if (PriceBrowserManager.getInstance().handleKey(event)) {
            cir.setReturnValue(true);
            return;
        }
        // The Best Flips budget box consumes every key while focused.
        if (sbs.modid.client.economy.bazaar.ui.BestFlipsOverlay.getInstance().handleKey(event)) {
            cir.setReturnValue(true);
            return;
        }
        // The seamless storage UI's search bar consumes every key while focused.
        if (sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().handleKey(event)) {
            cir.setReturnValue(true);
            return;
        }
        // The calculator window consumes every key while focused (an 'e' reaching the container
        // would close the inventory mid-expression).
        if (sbs.modid.client.economy.calculator.CalculatorOverlay.getInstance().handleKey(event)) {
            cir.setReturnValue(true);
            return;
        }
        // Hunting: accept / repeat fusion inside the Fusion menu (skipped while a search is typing).
        AbstractContainerScreen<?> fusionSelf = (AbstractContainerScreen<?>) (Object) this;
        if (!SearchHighlightState.getInstance().isSearchFocused()
                && sbs.modid.client.skills.hunting.ShardFusion.handleKey(fusionSelf, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (!RecipeOverlay.getInstance().active() || !SearchHighlightState.getInstance().isSearchFocused()) {
            return;
        }
        SearchHighlightState state = SearchHighlightState.getInstance();
        int key = event.key();
        if (key == KEY_ESCAPE || key == KEY_ENTER || key == KEY_NUMPAD_ENTER) {
            state.setSearchFocused(false);
        } else if (key == KEY_TAB) {
            RecipeOverlay.getInstance().cycleKind(event.hasShiftDown() ? -1 : 1);
        } else if (key == KEY_BACKSPACE) {
            // Ctrl+Backspace deletes the whole last word, like any regular text input.
            if (event.hasControlDown()) {
                state.backspaceWord();
            } else {
                state.backspace();
            }
        } else if (key == KEY_DELETE && event.hasControlDown()) {
            state.backspaceWord(); // Ctrl+Delete – no cursor model, so it edits the tail too
        } else if (event.isSelectAll()) {
            state.selectAll(); // Ctrl+A – the next edit replaces / clears the whole query
        }
        // Consume every key while typing so inventory keybinds (e.g. 'E' = close) don't fire.
        cir.setReturnValue(true);
    }

    /**
     * Added override of {@link ContainerEventHandler#charTyped}: feeds typed characters into the
     * search query while the bar is focused, otherwise defers to the vanilla default (which routes
     * chars to the focused child widget).
     */
    @Override
    public boolean charTyped(CharacterEvent event) {
        if (PriceBrowserManager.getInstance().charTyped(event)) {
            return true;
        }
        if (sbs.modid.client.economy.bazaar.ui.BestFlipsOverlay.getInstance().charTyped(event)) {
            return true;
        }
        if (sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().charTyped(event)) {
            return true;
        }
        if (sbs.modid.client.economy.calculator.CalculatorOverlay.getInstance().charTyped(event)) {
            return true;
        }
        if (RecipeOverlay.getInstance().active() && SearchHighlightState.getInstance().isSearchFocused()) {
            if (event.isAllowedChatCharacter()) {
                SearchHighlightState.getInstance().type(event.codepointAsString());
            }
            return true;
        }
        return ContainerEventHandler.super.charTyped(event);
    }
}
