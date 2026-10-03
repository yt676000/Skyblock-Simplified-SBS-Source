/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventorybuttons.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.screen.IconPickerScreen;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.helper.inventorybuttons.logic.InventoryButton;
import sbs.modid.client.helper.inventorybuttons.logic.InventoryButtons;

/**
 * The per-button editor reached from a button's "…" badge: its name, the command it runs, and its
 * icon.
 *
 * <p>Edits are written straight through to the {@link InventoryButton} and saved on close, so there
 * is no separate "apply" step to forget. The icon opens {@link IconPickerScreen}, which comes back
 * here with the pick.
 */
public final class InventoryButtonEditScreen extends Screen {

    private static final int PANEL_W = 240;
    private static final int PANEL_H = 204;
    private static final int ROW_H = 18;

    private final InventoryButton button;
    private final Screen parent;

    private int panelX;
    private int panelY;
    private EditBox nameBox;
    private EditBox commandBox;
    private EditBox widthBox;
    private EditBox heightBox;

    public InventoryButtonEditScreen(InventoryButton button, Screen parent) {
        super(Component.literal("Edit Button"));
        this.button = button;
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;
        int pad = SBSTheme.PANEL_PADDING;
        int innerX = panelX + pad;
        int innerW = PANEL_W - pad * 2;
        int textH = this.font.lineHeight;

        addRenderableOnly(new PanelRenderable());

        int y = panelY + SBSTheme.HEADER_HEIGHT + 12;
        nameBox = field(innerX, y, innerW, textH, button.name, "Button name", 32);
        y += ROW_H + 16;
        commandBox = field(innerX, y, innerW, textH, button.command, "/warp hub", 128);

        // Icon row: the preview doubles as the picker button.
        y += ROW_H + 18;
        addRenderableWidget(new SciFiButton(innerX + 24, y, innerW - 24, ROW_H,
                Component.literal("Choose Icon..."),
                () -> Minecraft.getInstance().setScreenAndShow(new IconPickerScreen(button, this))));

        // Size row: exact values for what the corner grip does by hand. Two independent axes.
        y += ROW_H + 18;
        int halfW = innerW / 2 - 3;
        widthBox = field(innerX, y, halfW, textH, String.valueOf(button.width()), "Width", 3);
        heightBox = field(innerX + halfW + 6, y, halfW, textH, String.valueOf(button.height()),
                "Height", 3);

        addRenderableWidget(new SciFiButton(innerX, panelY + PANEL_H - pad - SBSTheme.SEARCH_HEIGHT,
                innerW / 2 - 3, SBSTheme.SEARCH_HEIGHT, Component.literal("Delete"), () -> {
                    InventoryButtons.remove(button);
                    onClose();
                }));
        addRenderableWidget(new SciFiButton(innerX + innerW / 2 + 3,
                panelY + PANEL_H - pad - SBSTheme.SEARCH_HEIGHT, innerW / 2 - 3,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Done"), this::onClose));
    }

    private EditBox field(int x, int y, int w, int h, String value, String hint, int maxLength) {
        EditBox box = new EditBox(this.font, x + 5, y + (ROW_H - h) / 2, w - 10, h,
                Component.literal(hint));
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setTextColor(SBSTheme.TEXT);
        box.setHint(Component.literal(hint));
        box.setValue(value == null ? "" : value);
        addRenderableWidget(box);
        return box;
    }

    /** Writes the fields back and returns to the placement editor. */
    @Override
    public void onClose() {
        button.name = nameBox.getValue().isBlank() ? "Button" : nameBox.getValue().trim();
        button.command = commandBox.getValue().trim();
        button.resize(parseSize(widthBox.getValue(), button.width()),
                parseSize(heightBox.getValue(), button.height()));
        InventoryButtons.save();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    /**
     * A typed size, or the current one when the field holds something that is not a number – a
     * half-typed or emptied box should leave the button as it was, not reset it.
     */
    private static int parseSize(String raw, int fallback) {
        try {
            return Math.clamp(Integer.parseInt(raw.trim()),
                    InventoryButtons.MIN_SIZE, InventoryButtons.MAX_SIZE);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = InventoryButtonEditScreen.this.font;
            g.fill(0, 0, InventoryButtonEditScreen.this.width, InventoryButtonEditScreen.this.height,
                    SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, PANEL_W, PANEL_H, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, PANEL_W, PANEL_H, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, PANEL_W - 2, PANEL_H - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Edit Button"), panelX + PANEL_W / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, panelY + SBSTheme.HEADER_HEIGHT, panelX + PANEL_W - pad,
                    panelY + SBSTheme.HEADER_HEIGHT + 1, SBSTheme.ACCENT);

            int innerX = panelX + pad;
            int innerW = PANEL_W - pad * 2;
            int y = panelY + SBSTheme.HEADER_HEIGHT + 12;
            label(g, "Name", innerX, y - 10);
            box(g, innerX, y, innerW, nameBox.isFocused());
            y += ROW_H + 16;
            label(g, "Command", innerX, y - 10);
            box(g, innerX, y, innerW, commandBox.isFocused());
            y += ROW_H + 18;
            label(g, "Icon", innerX, y - 10);
            // Live preview of the chosen icon, left of the picker button.
            SciFiRender.roundedRectWithBorder(g, innerX, y, ROW_H, ROW_H, SBSTheme.CORNER_RADIUS,
                    SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
            g.item(button.iconStack(), innerX + 1, y + 1);

            y += ROW_H + 18;
            label(g, "Size — width × height (" + InventoryButtons.MIN_SIZE + "–"
                    + InventoryButtons.MAX_SIZE + ")", innerX, y - 10);
            int halfW = innerW / 2 - 3;
            box(g, innerX, y, halfW, widthBox.isFocused());
            box(g, innerX + halfW + 6, y, halfW, heightBox.isFocused());
        }

        private void label(GuiGraphicsExtractor g, String text, int x, int y) {
            g.text(InventoryButtonEditScreen.this.font, Component.literal("§7" + text), x, y,
                    SBSTheme.TEXT_MUTED);
        }

        private void box(GuiGraphicsExtractor g, int x, int y, int w, boolean focused) {
            SciFiRender.roundedRectWithBorder(g, x, y, w, ROW_H, SBSTheme.CORNER_RADIUS,
                    SBSTheme.SEARCH_FILL, focused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        }
    }
}
