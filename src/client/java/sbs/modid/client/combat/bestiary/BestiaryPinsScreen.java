/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.component.ReorderableList;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.KeyedScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The pinned Bestiary mobs of the current profile, in HUD order: drag (or Shift+↑↓) to reorder,
 * switch "Highlight in world" per mob, unpin. Pinning itself happens in the Bestiary menu with the ★ -
 * there is no name to type here.
 */
public final class BestiaryPinsScreen extends Screen implements KeyedScreen {

    /** Stable id for per-screen settings (opacity). Never change it once shipped. */
    @Override
    public String screenId() {
        return "bestiary_pins";
    }

    private static final int PANEL_MAX_W = 300;
    private static final int PANEL_MAX_H = 300;
    private static final int GAP = 6;
    private static final int LABEL_GAP = 11;

    private final Screen previous;
    private final ReorderableList<SBSConfig.BestiaryPin> list;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int hintY;
    private boolean wired;

    public BestiaryPinsScreen(Screen previous) {
        super(Component.literal("Pinned Bestiary Mobs"));
        this.previous = previous;
        this.list = new ReorderableList<>("Pinned mobs", new PinAdapter());
    }

    private static BestiaryTracker tracker() {
        return BestiaryTracker.getInstance();
    }

    @Override
    protected void init() {
        // Sized from the viewport, never up to a minimum: a small screen shrinks the panel.
        panelW = Math.min(Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2), PANEL_MAX_W);
        panelH = Math.min(Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2), PANEL_MAX_H);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;

        int innerX = panelX + SBSTheme.PANEL_PADDING;
        int innerW = panelW - SBSTheme.PANEL_PADDING * 2;
        int controlH = SBSTheme.SEARCH_HEIGHT;
        int buttonY = panelY + panelH - SBSTheme.PANEL_PADDING - controlH;
        int nudgeY = buttonY - GAP - controlH;
        hintY = nudgeY - LABEL_GAP;
        int listTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        list.setBounds(innerX, listTop, innerW, Math.max(30, hintY - 3 - listTop));

        if (!wired) {
            wired = true;
            list.setFocused(true);
            list.setOnChange(this::onListChanged);
            list.setItems(tracker().pins());
        }

        clearWidgets();
        addRenderableOnly(new PanelRenderable());
        int half = (innerW - GAP) / 2;
        addRenderableWidget(new SciFiButton(innerX, nudgeY, half, controlH,
                Component.literal("▲ Up"), () -> list.moveSelection(-1)));
        addRenderableWidget(new SciFiButton(innerX + half + GAP, nudgeY, innerW - half - GAP, controlH,
                Component.literal("▼ Down"), () -> list.moveSelection(1)));
        int third = (innerW - GAP * 2) / 3;
        addRenderableWidget(new SciFiButton(innerX, buttonY, third, controlH,
                Component.literal("Highlight"), this::onToggleHighlight));
        addRenderableWidget(new SciFiButton(innerX + third + GAP, buttonY, third, controlH,
                Component.literal("Unpin"), this::onUnpin));
        addRenderableWidget(new SciFiButton(innerX + (third + GAP) * 2, buttonY,
                innerW - (third + GAP) * 2, controlH, Component.literal("Back"), this::onClose));
        addRenderableOnly(new DragRenderable());
    }

    // ------------------------------------------------------------------ state

    private void onListChanged() {
        List<SBSConfig.BestiaryPin> pins = tracker().pins();
        List<SBSConfig.BestiaryPin> next = list.items();
        if (next.equals(pins)) {
            return;
        }
        pins.clear();
        pins.addAll(next);
        tracker().pinsChanged();
    }

    /** Switches "Highlight in world" for the selected mob. */
    private void onToggleHighlight() {
        SBSConfig.BestiaryPin pin = list.selection();
        if (pin == null) {
            return;
        }
        pin.highlight = !pin.highlight;
        tracker().pinsChanged();
    }

    private void onUnpin() {
        SBSConfig.BestiaryPin pin = list.selection();
        if (pin == null) {
            return;
        }
        tracker().pins().remove(pin);
        tracker().pinsChanged();
        list.setItems(tracker().pins());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(previous != null ? previous : new SBSMainScreen());
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (list.mouseClicked(event.x(), event.y(), event.button())) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (list.mouseDragged(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return list.mouseReleased(event.x(), event.y()) || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (list.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (list.keyPressed(event.key(), event.modifiers())) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ list adapter

    /** A pin row; the note says whether Mob Highlight boxes it. */
    private static final class PinAdapter implements ReorderableList.Adapter<SBSConfig.BestiaryPin> {

        @Override
        public String label(SBSConfig.BestiaryPin pin) {
            return pin.name;
        }

        @Override
        public String note(SBSConfig.BestiaryPin pin) {
            return pin.highlight ? "highlight" : "";
        }
    }

    // ------------------------------------------------------------------ chrome

    /** Panel, title, the list and the hint. */
    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = BestiaryPinsScreen.this.font;
            g.fill(0, 0, BestiaryPinsScreen.this.width, BestiaryPinsScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int room = Math.max(0, panelW - SBSTheme.PANEL_PADDING * 2);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal(font.plainSubstrByWidth("Pinned Bestiary Mobs", room)),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            list.render(g, mouseX, mouseY);

            String hint = list.items().isEmpty()
                    ? "Pin mobs with the ★ in the Bestiary menu"
                    : "Drag to reorder, or: ↑↓ choose, Shift+↑↓ move";
            g.text(font, Component.literal(font.plainSubstrByWidth(hint, room)),
                    panelX + pad, hintY, SBSTheme.TEXT_MUTED, false);
        }
    }

    /** The dragged entry, drawn after everything so it is above the list and the buttons. */
    private static final class DragRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            ReorderableList.renderDrag(g, mouseX, mouseY);
        }
    }
}
