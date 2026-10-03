/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.economy.bazaar.ui.FlipFormat;
import sbs.modid.client.helper.chocolate.logic.ChocolateFactory;
import sbs.modid.client.helper.chocolate.logic.UpgradeRanking;
import sbs.modid.client.helper.chocolate.model.FactoryUpgrade;
import sbs.modid.client.ui.render.SlotOutline;

/**
 * What the Chocolate Factory menu gets drawn on top of it: the best buy ringed, each ranked slot's
 * payback written on it, and any stray rabbit marked.
 *
 * <p><b>Marks only.</b> Nothing here is clickable and nothing here clicks - the stray ring is a
 * ring, and the player moves their own mouse to it. That is the whole reason this feature is
 * allowed to exist in a game that bans auto-clicking the factory.
 *
 * <p>An upgrade whose numbers could not be read gets no label at all rather than a zero or a dash
 * in the payback position: the slot simply looks untouched, which is the honest rendering of
 * "nothing is known about this one".
 */
public final class ChocolateOverlay {

    /** The best buy. */
    private static final int BEST_COLOR = 0xFF57D977;
    /** A stray rabbit - the one thing on screen that is about to disappear. */
    private static final int STRAY_COLOR = 0xFFFF6B6B;
    /** Payback text on a slot the player can afford. */
    private static final int AFFORDABLE_TEXT = 0xFFB8F0C4;
    /** Payback text on one they cannot. */
    private static final int SHORT_TEXT = 0xFFFFC85C;
    /** A vanilla slot's item area, which is what a label has to fit inside. */
    private static final int SLOT_SIZE = 16;

    private ChocolateOverlay() {
    }

    /** Called from {@code core/render/ScreenForeground}, above the menu. */
    public static void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                                     int mouseX, int mouseY) {
        SBSConfig.ChocolateFactorySettings cfg = ConfigManager.getInstance().get().chocolateFactory;
        ChocolateFactory factory = ChocolateFactory.getInstance();
        if (!factory.isActive(screen)) {
            return;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        Font font = Minecraft.getInstance().font;

        FactoryUpgrade best = factory.best();
        long balance = factory.balance();

        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            int x = left + slot.x;
            int y = top + slot.y;

            if (factory.isStray(slot.index)) {
                SlotOutline.draw(g, x, y, STRAY_COLOR);
                continue;
            }
            if (!cfg.bestUpgradeHighlight) {
                continue;
            }
            FactoryUpgrade upgrade = factory.upgradeAt(slot.index);
            if (upgrade == null || !upgrade.rankable()) {
                continue; // nothing readable here: leave the slot as Hypixel drew it
            }
            if (best != null && best.slot() == slot.index) {
                SlotOutline.draw(g, x, y, BEST_COLOR);
            }
            drawLabel(g, font, upgrade, balance, x, y);
        }
    }

    /**
     * The payback on the slot, in its largest unit only - {@code 45s}, {@code 12m}, {@code 3h},
     * {@code 2d}.
     *
     * <p><b>Why not the full figure.</b> Menu slots are 18px apart and
     * {@link FlipFormat#duration} produces things like {@code 3h 05m}, which is roughly twice a
     * slot wide - so a full label on every slot runs into its neighbours, and overlapping text
     * renders silently with nothing logged. One unit is two or three characters, fits inside the
     * slot, and is enough for the only question being asked here: which of these is quickest.
     * The exact payback, and the shortfall in coins, are on the HUD card where there is room.
     *
     * <p>A tooltip was the other option and is the wrong one: it describes the slot the cursor is
     * already on, and the whole point is deciding which slot to move the cursor to.
     */
    private static void drawLabel(GuiGraphicsExtractor g, Font font, FactoryUpgrade upgrade,
                                  long balance, int x, int y) {
        boolean affordable = UpgradeRanking.shortfall(upgrade, balance) == 0;
        String text = compact((long) UpgradeRanking.paybackSeconds(upgrade));
        // Right-aligned inside the slot, measured rather than positioned by eye.
        int width = font.width(text);
        g.text(font, Component.literal(text), x + SLOT_SIZE - width, y + SLOT_SIZE - font.lineHeight,
                affordable ? AFFORDABLE_TEXT : SHORT_TEXT);
    }

    /** A duration in its largest unit, at most three characters wide. */
    private static String compact(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + "h";
        }
        return (seconds / 86_400) + "d";
    }
}
