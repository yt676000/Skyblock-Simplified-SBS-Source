/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.helper.rarity.RarityOverlay;
import sbs.modid.client.helper.rarity.RarityOverlayMode;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * One storage page card: a framed card with the page's name and value on its title row and the
 * page's items as a nine-wide grid of small cells under it. The SBS storage workspace and the
 * Player Viewer's Ender Chest / Backpacks pages both draw through this, so a page looks the same
 * whether it is your own storage or someone else's profile.
 *
 * <p>Pure drawing: no scroll state, no input. Callers own the layout, the clip band and what a
 * click does; this class owns the card's look.
 */
public final class StoragePageCard {

    /** One item cell. */
    public static final int MINI = 18;
    /** Cells across: a storage page is always nine wide. */
    public static final int GRID_COLS = 9;
    /** Item rows of the largest page. */
    public static final int MAX_ROWS = 6;
    /** Height of the title row. */
    public static final int TITLE_H = 13;
    /** Card width: nine cells plus the inner margin. */
    public static final int CARD_W = GRID_COLS * MINI + 6;
    /** Highlight of the page that is open right now. */
    public static final int ACTIVE_BORDER = 0xFFFFE24B;

    private StoragePageCard() {
    }

    /** Whether page values are shown at all (the storage "Show Storage Value" setting). */
    public static boolean showValue() {
        return ConfigManager.getInstance().get().storageSearch.showStorageValue;
    }

    /** Height of a card whose grid has {@code rows} item rows. */
    public static int height(int rows) {
        return TITLE_H + rows * MINI + 4;
    }

    /** Item rows needed for {@code slots} cells. */
    public static int rowsFor(int slots) {
        return Math.max(1, (slots + GRID_COLS - 1) / GRID_COLS);
    }

    /**
     * The card's frame and title row. {@code title} is drawn as given (colour codes included) after
     * being cut to the room the value leaves; {@code value} ("" for none) sits right-aligned in gold.
     */
    public static void frame(GuiGraphicsExtractor g, Font font, int x, int y, int cardH, String titleColour,
                             String title, String suffix, String value, boolean active) {
        SciFiRender.roundedRectWithBorder(g, x - 1, y - 1, CARD_W + 2, cardH + 2, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG, active ? ACTIVE_BORDER : SBSTheme.CARD_BORDER);
        if (active) {
            // A second frame in place: the active page is highlighted, never relocated.
            SciFiRender.roundedRectWithBorder(g, x - 2, y - 2, CARD_W + 4, cardH + 4,
                    SBSTheme.CORNER_RADIUS, 0x00000000, ACTIVE_BORDER);
        }
        int valueW = value.isEmpty() ? 0 : font.width(value) + 4;
        String shown = trim(font, title, CARD_W - 6 - valueW - font.width(suffix));
        g.text(font, Component.literal(titleColour + shown + suffix), x + 2, y + 2, SBSTheme.ACCENT_BRIGHT);
        if (!value.isEmpty()) {
            g.text(font, Component.literal("§6" + value), x + CARD_W - 2 - font.width(value), y + 2, SBSTheme.TEXT);
        }
    }

    /** Top-left of grid cell {@code index} on a card at {@code x, y}. */
    public static int cellX(int x, int index) {
        return x + 3 + (index % GRID_COLS) * MINI;
    }

    public static int cellY(int y, int index) {
        return y + TITLE_H + (index / GRID_COLS) * MINI;
    }

    /** One static item cell: rarity tint, the item, its count. */
    public static void item(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y) {
        drawRarity(g, stack, x, y);
        g.item(stack, x, y);
        if (stack.getCount() > 1) {
            g.itemDecorations(font, stack, x, y);
        }
    }

    /**
     * The item-rarity tint under one cell's item, honouring the same Overlays setting the real slots
     * use. These cards draw their own item grid instead of vanilla's slots, so the container-slot
     * mixin never sees these cells - without this the rarity colours simply stopped at the edge of
     * the card, which reads as the feature being broken rather than as a different surface.
     */
    public static void drawRarity(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        RarityOverlayMode mode = ConfigManager.getInstance().get().itemOverlay.rarityMode;
        if (mode == RarityOverlayMode.OFF) {
            return;
        }
        Rarity rarity = Rarity.detect(stack);
        if (rarity != null) {
            RarityOverlay.draw(g, x, y, 16, rarity, mode);
        }
    }

    /** {@code text} cut to {@code maxWidth}, ellipsised. */
    public static String trim(Font font, String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }
}
