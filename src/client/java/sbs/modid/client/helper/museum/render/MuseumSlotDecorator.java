/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.museum.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.helper.museum.logic.MuseumStatus;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorator;

import java.util.ArrayList;
import java.util.List;

/**
 * A small corner marker on every item still missing from your museum, in any open container and -
 * behind its own switch - in the Auction House. Only for categories fully seen for this profile.
 *
 * <p>The slot walk runs at most every 150 ms (and at once on a new screen or when the menu's
 * contents change); frames in between only draw the cached slots. Stacks are read, never changed.
 * The mark is a cyan notch with a dark edge: a shape, not only a colour, so it reads without telling
 * hues apart. Not drawn on the museum's own pages, which say what is donated themselves.
 */
public final class MuseumSlotDecorator implements SlotDecorator {

    private static final long SCAN_MS = 150L;
    private static final int MARK = 0xFF55FFFF;
    private static final int EDGE = 0xFF0A2A33;

    private AbstractContainerScreen<?> scannedScreen;
    private int scannedState = -1;
    private long scannedAt;
    private List<Slot> marked = List.of();

    @Override
    public String id() {
        return "museum_undonated";
    }

    @Override
    public int order() {
        return 650;
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(frame != null && isAuctionHouse(frame));
    }

    static boolean isAuctionHouse(MenuFrame frame) {
        return frame.titleContains("auction");
    }

    private static boolean isMuseumPage(MenuFrame frame) {
        return frame.titleContains("museum ➜");
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SBSConfig.MuseumHelperSettings cfg = ConfigManager.getInstance().get().museumHelper;
        if (!cfg.enabled) {
            return;
        }
        MenuFrame frame = MenuFrame.of(screen);
        boolean auction = isAuctionHouse(frame);
        if ((auction ? !cfg.highlightAuctions : !cfg.highlightContainers) || isMuseumPage(frame)) {
            return;
        }
        long now = System.currentTimeMillis();
        int state = screen.getMenu().getStateId();
        boolean fresh = screen != scannedScreen || state != scannedState;
        if (screen != scannedScreen || (fresh && now - scannedAt >= SCAN_MS)) {
            marked = scan(screen);
            scannedScreen = screen;
            scannedState = state;
            scannedAt = now;
        }
        for (Slot slot : marked) {
            // A notch in the top-left corner: 5 px wide, 2 px down the side, edged in a dark line.
            g.fill(slot.x - 1, slot.y - 1, slot.x + 5, slot.y + 2, EDGE);
            g.fill(slot.x - 1, slot.y - 1, slot.x + 2, slot.y + 5, EDGE);
            g.fill(slot.x, slot.y, slot.x + 4, slot.y + 1, MARK);
            g.fill(slot.x, slot.y, slot.x + 1, slot.y + 4, MARK);
        }
    }

    private static List<Slot> scan(AbstractContainerScreen<?> screen) {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (MuseumStatus.missing(SkyblockItem.id(stack)) != null) {
                out.add(slot);
            }
        }
        return out.isEmpty() ? List.of() : out;
    }
}
