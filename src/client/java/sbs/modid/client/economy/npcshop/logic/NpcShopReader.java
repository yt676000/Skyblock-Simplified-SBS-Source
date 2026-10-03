/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.npcshop.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.npcshop.model.ShopCost;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.ui.render.MenuFrame;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Learns what an open NPC shop charges. Read-only: it reads the offers' tooltips and never clicks.
 *
 * <p><b>By content, not by title.</b> A Hypixel shop is titled with the NPC's own name, so no title
 * list can find one; a menu counts as a shop when at least {@link #MIN_OFFERS} of its own slots carry
 * a coin price ({@link ShopCost}). The title is then the NPC's name. Market menus that also show
 * prices - Bazaar, Auction House, the Bits and Community shops - are left out by title.
 *
 * <p>The Cost block's shape has never been captured, so the first time a shop is read one offer's
 * whole tooltip is logged ({@code [SBS][NpcShop] shape}) - that is how the parser gets corrected.
 */
public final class NpcShopReader {

    private static final int MIN_OFFERS = 2;
    private static final String[] NOT_NPC_SHOPS = {"bazaar", "auction", "bits shop", "community shop", "bank"};

    private static Screen readScreen;
    private static int readState = -1;
    private static final Set<String> shapeLogged = new HashSet<>();

    private NpcShopReader() {
    }

    /** Game tick. */
    public static void tick(Minecraft minecraft) {
        NpcShopCatalog.getInstance().tick();
        if (!ConfigManager.getInstance().get().bazaar.npcFlips) {
            return;
        }
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            readScreen = null;
            return;
        }
        int state = container.getMenu().getStateId();
        if (screen == readScreen && state == readState) {
            return;   // same page as last tick
        }
        readScreen = screen;
        readState = state;
        MenuFrame frame = MenuFrame.of(container);
        String title = PlainText.strip(frame.title()).trim();
        if (title.isEmpty() || isMarketMenu(frame)) {
            return;
        }
        int offers = 0;
        for (Slot slot : container.getMenu().slots) {
            if (!(slot.container instanceof Inventory)) {
                ShopCost.Cost cost = ShopCost.parse(ItemPriceKey.lore(slot.getItem()));
                if (cost != null && cost.coins() != null && ++offers >= MIN_OFFERS) {
                    break;
                }
            }
        }
        if (offers < MIN_OFFERS) {
            return;
        }
        learn(container, title);
    }

    /** The same test the shop decorator uses, from the frame's cached title. */
    public static boolean isMarketMenu(MenuFrame frame) {
        for (String word : NOT_NPC_SHOPS) {
            if (frame.titleContains(word)) {
                return true;
            }
        }
        return false;
    }

    private static void learn(AbstractContainerScreen<?> container, String npc) {
        int learned = 0;
        for (Slot slot : container.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            String id = SkyblockItem.id(stack);
            List<String> lore = ItemPriceKey.lore(stack);
            ShopCost.Cost cost = ShopCost.parse(lore);
            if (id == null || id.isEmpty() || cost == null) {
                continue;
            }
            String name = PlainText.strip(stack.getHoverName().getString()).trim();
            NpcShopCatalog.getInstance().record(npc, id, name, cost, Math.max(1, stack.getCount()));
            learned++;
            if (shapeLogged.add(npc)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][NpcShop] shape {} / {} x{}: lore={}", npc, name,
                        stack.getCount(), lore);
            }
        }
        if (learned > 0) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][NpcShop] read {} offer(s) from {}", learned, npc);
        }
    }
}
