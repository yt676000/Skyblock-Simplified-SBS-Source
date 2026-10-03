/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.npcshop.render;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.npcshop.logic.NpcFlips;
import sbs.modid.client.ui.render.MenuFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * "NPC flip: +1,234 each" on an offer in the shop that sells it, appended from
 * {@code PriceTooltipMixin}'s one handler. Returns the list untouched unless the open menu is a shop
 * with a learned, profitable offer for this item.
 */
public final class NpcFlipTooltip {

    private NpcFlipTooltip() {
    }

    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        if (!ConfigManager.getInstance().get().bazaar.npcFlips || stack == null || stack.isEmpty()
                || !(GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen<?> screen)) {
            return lines;
        }
        String npc = PlainText.strip(MenuFrame.of(screen).title()).trim();
        NpcFlips.Flip flip = NpcFlips.flipFor(npc, SkyblockItem.id(stack));
        if (flip == null) {
            return lines;
        }
        boolean offer = ConfigManager.getInstance().get().bazaar.npcFlipSellSide == 1;
        List<Component> out = new ArrayList<>(lines);
        out.add(Component.literal("NPC flip: +" + NumberDisplay.format(Math.floor(flip.profitPerUnit()))
                + " each (" + (offer ? "Bazaar sell offer" : "Bazaar insta-sell") + " after tax)")
                .withColor(0x55FF55));
        out.add(Component.literal("Sells ~" + NumberDisplay.format((double) flip.weekVolume()) + " a week")
                .withColor(0xAAAAAA));
        return out;
    }
}
