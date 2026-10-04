/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.itemvalue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * "What is everything in here worth": a small card above the open menu totalling the value of the
 * container's contents and of your own inventory, each on its own line.
 *
 * <p>Works on <b>every</b> container screen rather than a list of known menus, so the Ender Chest and
 * its pages, Storage backpacks, Hypixel's own menus and a plain vanilla chest are all covered by the
 * same code – the split is simply "slots belonging to the player's {@link Inventory}" against "the
 * rest", which is what makes a chest a chest at the menu level. On the plain inventory screen there
 * is no other half, so only the inventory line is drawn.
 *
 * <p><b>Throttled, not per-frame.</b> A double chest plus the player inventory is well over a
 * hundred stacks, each of which is parsed for its modifiers; doing that every frame would be felt.
 * The totals are recomputed at most {@value #REFRESH_MS} ms apart and the card draws the last
 * result, which is far faster than any container's contents change in practice.
 *
 * <p>Values come from {@link ItemAppraisal} – the warm local price caches, so opening a chest never
 * waits on the network. Items no market knows are counted separately and named on the card rather
 * than silently treated as worthless (see {@link ItemAppraisal}).
 */
public final class ContainerValueOverlay {

    private static final ContainerValueOverlay INSTANCE = new ContainerValueOverlay();

    /** How often the slot sweep may run, in milliseconds. */
    private static final long REFRESH_MS = 500;

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;

    private long lastScan;
    private int lastContainerId = -1;

    private long containerValue;
    private long inventoryValue;
    private int containerUnpriced;
    private int inventoryUnpriced;
    private boolean hasContainerSlots;

    private ContainerValueOverlay() {
    }

    public static ContainerValueOverlay getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().itemOverlay.containerValue;
    }

    /** Drawn from {@code OverlayRenderMixin}, before the tooltip flush so tooltips stay on top. */
    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        // Not in creative: the purse means nothing there, and the card sat over the top tab row.
        if (!enabled() || screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) {
            return;
        }
        refresh(screen);
        Font font = Minecraft.getInstance().font;

        // Hypixel's menus are containers too, full of glass panes and decorative heads worth
        // nothing. Showing "Chest: 0 coins" over every one of them would be noise, so the container
        // line only appears once there is actually something in the container to report.
        boolean showContainer = hasContainerSlots && (containerValue > 0 || containerUnpriced > 0);

        String containerLabel = "Chest";
        String containerText = coins(containerValue, containerUnpriced);
        String inventoryText = coins(inventoryValue, inventoryUnpriced);
        int labelW = showContainer
                ? Math.max(font.width(containerLabel), font.width("Inventory")) : font.width("Inventory");
        int valueW = showContainer
                ? Math.max(font.width(containerText), font.width(inventoryText)) : font.width(inventoryText);

        int lines = showContainer ? 2 : 1;
        int w = PAD * 2 + labelW + 8 + valueW;
        int h = PAD * 2 + lines * font.lineHeight + (lines - 1) * LINE_GAP;

        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int x = bounds.skyblockSimplified$leftPos();
        // Above the menu, or - when the menu is tall enough to reach the top of the screen - just
        // inside the top edge, where it still reads as belonging to the screen below it.
        int y = Math.max(2, bounds.skyblockSimplified$topPos() - h - 3);

        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);

        int textY = y + PAD;
        if (showContainer) {
            drawRow(g, font, containerLabel, containerText, x, textY, w, containerUnpriced);
            textY += font.lineHeight + LINE_GAP;
        }
        drawRow(g, font, "Inventory", inventoryText, x, textY, w, inventoryUnpriced);
    }

    private void drawRow(GuiGraphicsExtractor g, Font font, String label, String value,
                         int x, int y, int w, int unpriced) {
        g.text(font, Component.literal(label), x + PAD, y, SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal(value), x + w - PAD - font.width(value), y,
                unpriced > 0 ? SBSTheme.TEXT : SBSTheme.ACCENT_BRIGHT);
    }

    /**
     * "12.4M coins", or "12.4M+ coins" when part of the total could not be priced – the {@code +}
     * says the real figure is higher, which is the only honest way to show a sum with holes in it.
     */
    private static String coins(long value, int unpriced) {
        return NumberDisplay.format(value) + (unpriced > 0 ? "+" : "") + " coins";
    }

    // ------------------------------------------------------------------
    // Slot sweep
    // ------------------------------------------------------------------

    /** Re-totals the open menu, at most every {@link #REFRESH_MS} ms (see the class note). */
    private void refresh(AbstractContainerScreen<?> screen) {
        long now = System.currentTimeMillis();
        int containerId = screen.getMenu().containerId;
        // Every menu the server opens gets its own id, so a change here means a different menu:
        // re-total it now instead of showing the last one's number for up to half a second. Paging
        // through the Ender Chest is exactly that case, and the one where a stale total misleads.
        if (now - lastScan < REFRESH_MS && containerId == lastContainerId) {
            return;
        }
        lastScan = now;
        lastContainerId = containerId;

        // Split by side, then the shared rule (ValueSum) - the storage page values use the same one.
        List<ItemStack> containerStacks = new ArrayList<>();
        List<ItemStack> inventoryStacks = new ArrayList<>();
        boolean sawContainerSlot = false;
        for (Slot slot : screen.getMenu().slots) {
            boolean player = slot.container instanceof Inventory;
            if (!player) {
                sawContainerSlot = true;
            }
            (player ? inventoryStacks : containerStacks).add(slot.getItem());
        }
        ValueSum.Total containerTotal = ValueSum.of(containerStacks);
        ValueSum.Total inventoryTotal = ValueSum.of(inventoryStacks);

        containerValue = containerTotal.value();
        inventoryValue = inventoryTotal.value();
        containerUnpriced = containerTotal.unpriced();
        inventoryUnpriced = inventoryTotal.unpriced();
        hasContainerSlots = sawContainerSlot;
    }
}
