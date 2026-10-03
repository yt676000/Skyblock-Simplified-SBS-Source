/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.recipe.ui.RecipeOverlay;
import sbs.modid.client.helper.storage.StoragePreview;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.MenuRenderPriority;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.window.FloatingWindows;

/**
 * Everything the mod draws <i>on top of</i> an open container screen, in one place, so that where it
 * is drawn from can be a setting rather than a rewrite.
 *
 * <p><b>Two hooks, one implementation.</b> This body used to live in the tail of
 * {@code OverlayRenderMixin}, inside the screen's own render. It still runs from there when
 * {@link #onTop()} is off. When it is on - the default - it runs instead from the tail of
 * {@code Gui.extractRenderState}, the last thing in the GUI frame, on a fresh stratum. The two are
 * mutually exclusive and call the same method, because the alternative is two copies of a
 * twenty-call z-ordered list that would drift apart the first time either was touched.
 *
 * <p><b>Why the later hook is the one that wins.</b> Every overlay any mod draws over a screen is an
 * injection into {@code Screen.extractRenderStateWithTooltipAndSubtitles}, and which of them lands
 * on top is decided by which injection the mixin processor happened to apply last. That is a race,
 * not a design, and mixin priority is not a reliable way to settle it - this repository has already
 * been bitten by trusting it for z-order once. {@code Gui.extractRenderState} is the method that
 * <i>calls</i> the screen's, so its tail is structurally after every one of those injections,
 * whatever their priorities. {@code nextStratum} then lifts the layer above anything drawn into the
 * frame before it, vanilla's own flushed tooltips included.
 *
 * <p><b>What it does not do.</b> A mod drawing from the end of the GUI frame as well is in exactly
 * the same position, and then it is a race again. There is no unconditional top.
 *
 * <p>In {@code core} rather than {@code ui} because the list reaches into every theme - the ui
 * packages are the shared machinery those features draw <i>with</i>, and must not depend on them.
 */
public final class ScreenForeground {

    private ScreenForeground() {
    }

    /** Whether the mod's top layer is drawn from the end of the GUI frame instead of the screen's. */
    public static boolean onTop() {
        return ConfigManager.getInstance().get().minecraftOverlay.overlaysOnTop;
    }

    /**
     * The mod's top layer over an open container: previews, full-screen workspaces, the floating
     * windows, popups, and last of all the Recipe Viewer's hover tooltip.
     *
     * <p>Order is z-order. Two entries deliberately return early - the dungeon terminal board and
     * the storage workspace are exclusive, each covering the menu whole, and nothing may paint over
     * either.
     */
    public static void drawTopMost(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                                   int mouseX, int mouseY) {
        MenuFrame frame = MenuFrame.of(container);
        // The terminal board is exclusive while it is up: it covers the menu whole, and a Necron
        // phase is the last place anything else should be painting over the puzzle.
        if (sbs.modid.client.dungeons.terminal.TerminalBoardOverlay.getInstance().isActive(container)) {
            MenuRenderPriority.run("dungeon.terminal_board", RenderTier.RELEVANT,
                    () -> sbs.modid.client.dungeons.terminal.TerminalBoardOverlay.getInstance()
                            .renderTopMost(container, g, mouseX, mouseY));
            return;
        }
        MenuRenderPriority.run("storage.preview", RenderTier.RELEVANT,
                () -> StoragePreview.getInstance().renderTopMost(container, g, mouseX, mouseY));
        // The Accessory Bag's "Missing" chip sits above the menu, never over it.
        MenuRenderPriority.run("accessory.bag_button",
                RenderTier.when(sbs.modid.client.helper.inventory.logic.AccessoryIndex.isBagMenu(frame)),
                () -> sbs.modid.client.helper.inventory.render.AccessoryBagButton.render(container, g, mouseX, mouseY));
        // Equipment column: last-captured necklace/cloak/belt/gloves left of the player inventory.
        MenuRenderPriority.run("inventory.equipment_column",
                RenderTier.when(container instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen),
                () -> sbs.modid.client.helper.inventory.render.EquipmentColumn.render(container, g, mouseX, mouseY));
        MenuRenderPriority.run("loadouts.overlay", RenderTier.RELEVANT,
                () -> sbs.modid.client.helper.loadouts.LoadoutsOverlay.getInstance().renderTopMost(container, g, mouseX, mouseY));
        MenuRenderPriority.run("wardrobe.armor_sets",
                RenderTier.when(sbs.modid.client.helper.wardrobe.ArmorSetsOverlay.isArmorSetsMenu(frame.normalised())),
                () -> sbs.modid.client.helper.wardrobe.ArmorSetsOverlay.getInstance().renderTopMost(container, g, mouseX, mouseY));
        MenuRenderPriority.run("pets.overlay",
                RenderTier.when(sbs.modid.client.helper.pets.PetsOverlay.isPetsMenu(frame.normalised())),
                () -> sbs.modid.client.helper.pets.PetsOverlay.getInstance().renderTopMost(container, g, mouseX, mouseY));
        // Chocolate Factory: slot rings and payback labels ON the menu. The tier comes from the
        // feature's own screen test, not a second copy of it - two tests that agree today are two
        // that disagree after the next rewording, and the symptom is an overlay that stops drawing.
        MenuRenderPriority.run("chocolate.overlay",
                RenderTier.when(sbs.modid.client.helper.chocolate.logic.ChocolateFactory
                        .isFactoryMenu(frame.normalised())),
                () -> sbs.modid.client.helper.chocolate.render.ChocolateOverlay
                        .renderTopMost(container, g, mouseX, mouseY));
        // Sack Overlay: everything in the open sack, priced, in a panel BESIDE the menu - the sack
        // itself stays clickable, which is why this is not a full-menu overlay like the two above.
        MenuRenderPriority.run("sacks.overlay",
                RenderTier.when(sbs.modid.client.helper.sacks.SackOverlay.getInstance().isActive(container)),
                () -> sbs.modid.client.helper.sacks.SackOverlay.getInstance().renderTopMost(container, g, mouseX, mouseY));
        // The storage workspace is exclusive: draw it, and nothing over it (windows, AH popups,
        // the Recipe Viewer hover tooltip all stand down so nothing bleeds over the full UI).
        if (sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().isWorkspaceActive(container)) {
            MenuRenderPriority.run("storage.workspace", RenderTier.RELEVANT,
                    () -> sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().renderTopMost(container, g, mouseX, mouseY));
            return;
        }
        MenuRenderPriority.run("storage.workspace", RenderTier.RELEVANT,
                () -> sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().renderTopMost(container, g, mouseX, mouseY));
        if (!FloatingWindows.isContainerRaised()) {
            renderWindows(container, g, mouseX, mouseY);
        }
        // AH flip popup cards: above every window, so they are always visible and clickable
        // (their click hook in ContainerSearchBarMixin matches: it runs before the window loop).
        MenuRenderPriority.run("auctions.flip_popups", RenderTier.RELEVANT,
                () -> sbs.modid.client.economy.auctions.ui.AhFlipPopups.getInstance().render(container, g, mouseX, mouseY));
        MenuRenderPriority.run("recipe.hover_tooltip", RenderTier.RELEVANT,
                () -> RecipeOverlay.getInstance().renderHoverTooltip(container, g, mouseX, mouseY));
    }

    /**
     * The floating window stack in its z-order (bottom to top).
     *
     * <p>Called from here when the windows are on top, and from the screen mixin's first hook when
     * the container has been raised over them - in which case they are drawn before the screen
     * content instead, which is what puts them behind it.
     *
     * <p>Every layer is {@link RenderTier#RELEVANT} on whatever screen it is drawn over: a window is
     * open because the player opened it, and one that stopped drawing on busy frames would be a bug,
     * not a saving. The whole stack is timed as one pass so the budget counts what it costs.
     */
    public static void renderWindows(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                                     int mouseX, int mouseY) {
        MenuRenderPriority.run("ui.windows", RenderTier.RELEVANT, () -> {
            // Each layer is drawn through the guard, so a window that throws is a window that
            // disappears rather than a client that dies - and the layers above it still render.
            for (FloatingWindows.Layer layer : FloatingWindows.renderOrder()) {
                FloatingWindows.render(layer, () -> {
                    switch (layer) {
                        case RECIPE -> RecipeOverlay.getInstance().renderWindow(container, g, mouseX, mouseY);
                        case BROWSERS -> sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager.getInstance()
                                .renderAll(container, g, mouseX, mouseY);
                        case VALUE -> sbs.modid.client.economy.itemvalue.ItemValueOverlay.getInstance()
                                .renderTopMost(container, g, mouseX, mouseY);
                        case AUCTIONS -> sbs.modid.client.economy.auctions.ui.SimilarAuctionsOverlay.getInstance()
                                .renderTopMost(container, g, mouseX, mouseY);
                        case FLIPS -> sbs.modid.client.economy.bazaar.ui.BestFlipsOverlay.getInstance()
                                .renderTopMost(container, g, mouseX, mouseY);
                        case FORGE -> sbs.modid.client.economy.forge.ui.ForgeFlipsOverlay.getInstance()
                                .renderTopMost(container, g, mouseX, mouseY);
                        case AH_FLIPS -> sbs.modid.client.economy.auctions.ui.AhFlipsOverlay.getInstance()
                                .renderTopMost(container, g, mouseX, mouseY);
                        case CALCULATOR -> sbs.modid.client.economy.calculator.CalculatorOverlay.getInstance()
                                .renderTopMost(container, g, mouseX, mouseY);
                        case ESSENCE_SHOP -> sbs.modid.client.economy.essenceshop.ui.EssenceShopOverlay
                                .getInstance().renderTopMost(container, g, mouseX, mouseY);
                        case GARDEN_PLOTS -> sbs.modid.client.skills.garden.ui.GardenPlotsOverlay.getInstance()
                                .renderTopMost(container, g, mouseX, mouseY);
                        case MISSING_SHARDS -> sbs.modid.client.skills.hunting.ui.MissingShardsOverlay
                                .getInstance().renderTopMost(container, g, mouseX, mouseY);
                    }
                });
            }
        });
    }
}
