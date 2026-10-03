/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.npcshop.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.npcshop.logic.NpcFlips;
import sbs.modid.client.economy.npcshop.logic.NpcShopReader;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorations;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * NPC flips, in the shop itself: a green frame on every offer that flips for at least the minimum
 * profit. Answers from {@link NpcFlips}' per-offer index, so a frame costs one map lookup per slot;
 * the profit is on the item's tooltip ({@link NpcFlipTooltip}).
 */
public final class NpcShopDecorator implements SlotDecorator {

    private static final int FRAME = 0xFF55FF55;
    private static final int FILL = 0x3355FF55;

    @Override
    public String id() {
        return "npc_flips";
    }

    @Override
    public int order() {
        return 560;
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(frame != null && hasFlips(frame));
    }

    private static boolean hasFlips(MenuFrame frame) {
        if (NpcShopReader.isMarketMenu(frame)) {
            return false;
        }
        String npc = PlainText.strip(frame.title()).trim();
        for (NpcFlips.Flip flip : NpcFlips.ranking().flips()) {
            if (flip.entry().npc.equalsIgnoreCase(npc)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!ConfigManager.getInstance().get().bazaar.npcFlips) {
            return;
        }
        MenuFrame frame = MenuFrame.of(screen);
        if (NpcShopReader.isMarketMenu(frame)) {
            return;
        }
        String npc = PlainText.strip(frame.title()).trim();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            if (NpcFlips.flipFor(npc, SkyblockItem.id(slot.getItem())) != null) {
                SlotDecorations.box(g, slot, FILL, FRAME);
            }
        }
    }
}
