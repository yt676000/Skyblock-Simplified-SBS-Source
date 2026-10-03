/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventorybuttons.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.helper.inventorybuttons.logic.InventoryButton;
import sbs.modid.client.helper.inventorybuttons.render.InventoryButtonRenderer;
import sbs.modid.client.helper.inventorybuttons.logic.InventoryButtons;

import java.util.Optional;

/**
 * The Inventory Buttons editor: place and arrange the buttons that will sit over the real inventory.
 *
 * <p><b>Why the inventory here is a replica.</b> Opening any screen closes the container the buttons
 * would sit on, so the real inventory cannot be the backdrop of its own editor. Instead this draws a
 * virtual one at the exact dimensions vanilla uses (176x166, with the slot grid where the real slots
 * are), so a button dragged against "the edge of the inventory" lands against the same edge in game.
 *
 * <p>Buttons are dragged freely and {@linkplain InventoryButtons#snap snap} to the panel's edges when
 * released nearby – the same call the live overlay positions with, so what is placed is what appears.
 *
 * <p>Each button carries a "…" badge opening {@link InventoryButtonEditScreen} for its name, command
 * and icon.
 */
public final class InventoryButtonsEditScreen extends Screen {

    /** Vanilla's player-inventory GUI size – the replica must match it exactly to be useful. */
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 166;

    /** Vanilla slot geometry inside that panel. */
    private static final int SLOT = 18;
    private static final int MAIN_X = 8;
    private static final int MAIN_Y = 84;
    private static final int HOTBAR_Y = 142;
    private static final int PREVIEW_X = 26;
    private static final int PREVIEW_Y = 8;
    private static final int PREVIEW_W = 50;
    private static final int PREVIEW_H = 70;

    private int panelX;
    private int panelY;

    /** The button being dragged, and the grab offset inside it. */
    private InventoryButton dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    /** The button being resized by its corner grip (never set at the same time as {@link #dragging}). */
    private InventoryButton resizing;

    private InventoryButton selected;

    public InventoryButtonsEditScreen() {
        super(Component.literal("Inventory Buttons"));
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;

        addRenderableOnly(new PanelRenderable());

        int buttonW = 96;
        int y = panelY + PANEL_H + 12;
        addRenderableWidget(new SciFiButton(this.width / 2 - buttonW - 4, y, buttonW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("+ Add Button"), this::addButton));
        addRenderableWidget(new SciFiButton(this.width / 2 + 4, y, buttonW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Back"),
                () -> Minecraft.getInstance().setScreenAndShow(new SBSMainScreen())));
    }

    private void addButton() {
        selected = InventoryButtons.add(PANEL_W);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        double relX = event.x() - panelX;
        double relY = event.y() - panelY;

        // The "…" badge and the resize grip both take priority over the button body, otherwise
        // neither could ever be hit - they sit on top of it.
        for (InventoryButton button : InventoryButtons.all()) {
            if (InventoryButtonRenderer.hitsDots(button, relX, relY)) {
                Minecraft.getInstance().setScreenAndShow(new InventoryButtonEditScreen(button, this));
                return true;
            }
        }
        for (InventoryButton button : InventoryButtons.all()) {
            if (InventoryButtonRenderer.hitsGrip(button, relX, relY)) {
                selected = button;
                resizing = button;
                return true;
            }
        }
        InventoryButton hit = InventoryButtons.at(relX, relY);
        if (hit != null) {
            selected = hit;
            dragging = hit;
            dragOffsetX = (int) (relX - hit.x);
            dragOffsetY = (int) (relY - hit.y);
            return true;
        }
        selected = null;
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (resizing != null) {
            // The grip drags the bottom-right corner, so the size is simply the distance back to the
            // button's fixed top-left. Both axes are independent - hold Shift to keep it square.
            int newW = (int) Math.round(event.x() - panelX - resizing.x);
            int newH = (int) Math.round(event.y() - panelY - resizing.y);
            if (shiftHeld()) {
                int uniform = Math.max(newW, newH);
                newW = uniform;
                newH = uniform;
            }
            resizing.resize(newW, newH);
            return true;
        }
        if (dragging == null) {
            return super.mouseDragged(event, dragX, dragY);
        }
        // Follow the pointer unsnapped: snapping mid-drag makes the button feel like it is fighting
        // the mouse. It settles on release instead.
        dragging.x = (int) (event.x() - panelX) - dragOffsetX;
        dragging.y = (int) (event.y() - panelY) - dragOffsetY;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (resizing != null) {
            InventoryButtons.save();
            resizing = null;
            return true;
        }
        if (dragging != null) {
            dragging.x = InventoryButtons.snap(dragging.x, PANEL_W, dragging.width());
            dragging.y = InventoryButtons.snap(dragging.y, PANEL_H, dragging.height());
            InventoryButtons.save();
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    /**
     * Whether Shift is held <i>right now</i>.
     *
     * <p>Read from the window rather than {@code MouseButtonEvent.hasShiftDown()}: a drag event
     * carries the modifiers captured when the button was first pressed, so the event would only ever
     * report Shift that was already held before grabbing the grip. Reading it live is what lets Shift
     * be pressed and released in the middle of a resize.
     */
    private static boolean shiftHeld() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = InventoryButtonsEditScreen.this.font;
            g.fill(0, 0, InventoryButtonsEditScreen.this.width, InventoryButtonsEditScreen.this.height,
                    SBSTheme.BG_TINT);

            g.centeredText(font,
                    Component.literal("Inventory Buttons — drag to place, corner grip to resize"),
                    InventoryButtonsEditScreen.this.width / 2, panelY - 22, SBSTheme.ACCENT_BRIGHT);

            drawVirtualInventory(g);
            drawButtons(g, mouseX, mouseY);
        }

        /** A stand-in for the real inventory, at vanilla's exact dimensions and slot layout. */
        private void drawVirtualInventory(GuiGraphicsExtractor g) {
            SciFiRender.glow(g, panelX, panelY, PANEL_W, PANEL_H, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, PANEL_W, PANEL_H, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, PANEL_W - 2, PANEL_H - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            // Player-preview box, where the real inventory shows the character.
            SciFiRender.roundedRectWithBorder(g, panelX + PREVIEW_X - 1, panelY + PREVIEW_Y - 1,
                    PREVIEW_W, PREVIEW_H, SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    SBSTheme.CARD_BORDER);
            var font = InventoryButtonsEditScreen.this.font;
            g.centeredText(font, Component.literal("§8you"),
                    panelX + PREVIEW_X + PREVIEW_W / 2 - 1,
                    panelY + PREVIEW_Y + PREVIEW_H / 2 - font.lineHeight / 2, SBSTheme.TEXT_MUTED);

            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    cell(g, panelX + MAIN_X + col * SLOT, panelY + MAIN_Y + row * SLOT);
                }
            }
            for (int col = 0; col < 9; col++) {
                cell(g, panelX + MAIN_X + col * SLOT, panelY + HOTBAR_Y);
            }
        }

        private void cell(GuiGraphicsExtractor g, int x, int y) {
            SciFiRender.roundedRectWithBorder(g, x, y, SLOT - 1, SLOT - 1, 2,
                    SBSTheme.CARD_BG_DISABLED, SBSTheme.CARD_BORDER);
        }

        private void drawButtons(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            InventoryButton hovered = null;
            for (InventoryButton button : InventoryButtons.all()) {
                int x = panelX + button.x;
                int y = panelY + button.y;
                boolean over = mouseX >= x && mouseX < x + button.width()
                        && mouseY >= y && mouseY < y + button.height();
                InventoryButtonRenderer.draw(g, button, x, y, over, true, button == selected);
                if (over) {
                    hovered = button;
                }
            }
            if (hovered != null && dragging == null && resizing == null) {
                g.setTooltipForNextFrame(InventoryButtonsEditScreen.this.font,
                        InventoryButtonRenderer.tooltip(hovered, true), Optional.empty(),
                        mouseX, mouseY, SBSTheme.tooltipStyle());
            }
            if (InventoryButtons.all().isEmpty()) {
                g.centeredText(InventoryButtonsEditScreen.this.font,
                        Component.literal("§7No buttons yet — press \"+ Add Button\""),
                        InventoryButtonsEditScreen.this.width / 2, panelY + PANEL_H + 34,
                        SBSTheme.TEXT_MUTED);
            }
        }
    }
}
