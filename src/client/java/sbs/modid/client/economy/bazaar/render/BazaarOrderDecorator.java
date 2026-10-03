/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;
import sbs.modid.client.economy.bazaar.model.BazaarOrder;
import sbs.modid.client.economy.bazaar.model.BazaarStatus;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorations;
import sbs.modid.client.ui.render.SlotDecorator;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Colours the player's tracked Bazaar orders inside the "Your Bazaar Orders" / "Co-op Bazaar Orders"
 * menu by their live status.
 *
 * <p>Reads the volatile status the background sync writes, so the colours follow the market without
 * the menu being reopened.
 *
 * <p>Guarded twice so it never touches an unrelated screen: the setting must be on, and the open
 * menu must actually be an orders menu - otherwise the cached slot indices mean nothing and would
 * colour whatever happened to be at those positions.
 */
public final class BazaarOrderDecorator implements SlotDecorator {

    /** Last logged highlight count, so the render path logs only when it changes. */
    private static int lastDrawn = -1;

    @Override
    public String id() {
        return "bazaar_orders";
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(isOrdersMenu(frame));
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        if (!ConfigManager.getInstance().get().bazaar.highlightItems
                || !isOrdersMenu(MenuFrame.of(screen))) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        int slotCount = menu.getItems().size();
        int drawn = 0;
        for (BazaarOrder order : BazaarOrderTracker.getInstance().getOrders()) {
            int index = order.slot();
            if (index < 0 || index >= slotCount) {
                continue;
            }
            BazaarStatus status = order.status();
            if (status == BazaarStatus.UNKNOWN || status == BazaarStatus.FILLED) {
                continue; // not evaluated yet, or 100% filled (sold) - never show a competitive colour
            }
            Slot slot = menu.getSlot(index);
            // A fully-filled slot is handled by the chroma pass only - completely override (disable)
            // the ordinary matched/outdated status overlay for it. Partially-filled slots keep it.
            if (ConfigManager.getInstance().get().bazaar.highlightClaimable
                    && !BazaarChroma.detect(slot.getItem()).showsStatus()) {
                continue;
            }
            SlotDecorations.box(g, slot, fillColor(status), frameColor(status));
            drawn++;
        }

        if (drawn != lastDrawn) {
            lastDrawn = drawn;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Highlighting {} order(s) in the Bazaar menu.",
                    drawn);
        }
    }

    private static int fillColor(BazaarStatus status) {
        return switch (status) {
            case BEST_OFFER -> SBSTheme.BAZAAR_BEST_FILL;
            case MATCHED -> SBSTheme.BAZAAR_MATCHED_FILL;
            case OUTDATED -> SBSTheme.BAZAAR_OUTDATED_FILL;
            default -> 0;
        };
    }

    private static int frameColor(BazaarStatus status) {
        return switch (status) {
            case BEST_OFFER -> SBSTheme.BAZAAR_BEST_FRAME;
            case MATCHED -> SBSTheme.BAZAAR_MATCHED_FRAME;
            case OUTDATED -> SBSTheme.BAZAAR_OUTDATED_FRAME;
            default -> 0;
        };
    }

    /** The one screen test, asked by both the tier and the draw so the two cannot disagree. */
    private static boolean isOrdersMenu(MenuFrame frame) {
        return frame != null && BazaarOrderTracker.isOrdersMenu(frame.normalised());
    }
}
