/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bitsshop.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.bitsshop.BitsShop;

/**
 * Boxes the best and second-best bits deals wherever they appear in the Bits Shop: green for the
 * single best coins-per-bit offer in the whole shop, yellow for the runner-up.
 *
 * <p>The ranking is shop-wide rather than page-wide on purpose - "is this the best thing to spend
 * bits on" is not a question about the category you happen to be standing in. The consequence is
 * that most pages will show no box at all, which is the honest answer: the best deal is elsewhere.
 * The tooltip still gives every offer its own number and rank, so a page with no highlight is never
 * a page with no information.
 *
 * <p>Drawn at the tail of {@code extractSlots}, the slot-relative space vanilla draws its own slots
 * in - the same convention the Bazaar and Accessory Bag highlighters use, which is what puts the box
 * on the item instead of off to the side.
 */
public final class BitsShopHighlight {

    /** Best deal in the shop. */
    private static final int GREEN = 0x55FF55;
    /** Runner-up. */
    private static final int YELLOW = 0xFFFF55;

    private BitsShopHighlight() {
    }

    private static SBSConfig.BitsShopSettings cfg() {
        return ConfigManager.getInstance().get().bitsShop;
    }

    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        SBSConfig.BitsShopSettings cfg = cfg();
        if (!cfg.enabled || !cfg.highlightBest || !BitsShop.shouldDecorate(screen)) {
            return;
        }
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            String id = BitsShop.offerId(slot.getItem());
            if (id.isEmpty()) {
                continue;
            }
            BitsShop.Ranked ranked = BitsShop.rankOf(id);
            if (ranked == null || ranked.rank() > 2) {
                continue;
            }
            int color = ranked.rank() == 1 ? GREEN : YELLOW;
            g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x55000000 | color);
            g.outline(slot.x - 1, slot.y - 1, 18, 18, 0xFF000000 | color);
        }
    }
}
