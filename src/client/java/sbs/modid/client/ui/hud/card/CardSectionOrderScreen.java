/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.card;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.component.ReorderableList;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.KeyedScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The section order editor for any {@link CardSections.Card}: one list, dragged or moved with the
 * keyboard into the order the card draws its sections in.
 *
 * <p>Built on the same {@link ReorderableList} as the Custom Scoreboard and Diana editors - one
 * drag-and-keyboard list in this mod, not a second. There is no palette: whether a section shows is
 * the card's own toggle, so the list only orders, and a switched-off section stays in it (muted,
 * noted {@code off}) so its place is kept for when it is switched back on.
 *
 * <p><b>Drag is not the only way.</b> {@code ↑}/{@code ↓} choose, {@code Shift} with them moves, and
 * the Up / Down buttons do the same for anyone who cannot hold a button while moving the mouse. Every
 * change saves at once; Undo walks back, and Reset (itself undoable) restores the default order.
 */
public final class CardSectionOrderScreen extends Screen implements KeyedScreen {

    /** Stable id for per-screen settings (opacity). Never change it once shipped. */
    @Override
    public String screenId() {
        return "card_section_order";
    }

    private static final int PANEL_MAX_W = 300;
    private static final int PANEL_MAX_H = 300;
    private static final int GAP = 6;
    private static final int LABEL_GAP = 11;

    /** How many steps back the editor can go. A mis-drop must not cost a hand-built order. */
    private static final int UNDO_DEPTH = 32;

    private final Screen previous;
    private final CardSections.Card card;
    private final ReorderableList<CardSections.Section> list;
    private final Deque<List<String>> undo = new ArrayDeque<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int hintY;
    private boolean wired;

    public CardSectionOrderScreen(Screen previous, CardSections.Card card) {
        super(Component.literal(card.title()));
        this.previous = previous;
        this.card = card;
        this.list = new ReorderableList<>("Sections", new SectionAdapter());
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
            list.setItems(CardSections.orderedSections(card));
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
                Component.literal("Undo"), this::onUndo));
        addRenderableWidget(new SciFiButton(innerX + third + GAP, buttonY, third, controlH,
                Component.literal("Reset"), this::onReset));
        addRenderableWidget(new SciFiButton(innerX + (third + GAP) * 2, buttonY,
                innerW - (third + GAP) * 2, controlH, Component.literal("Back"), this::onClose));
        addRenderableOnly(new DragRenderable());
    }

    // ------------------------------------------------------------------ state

    private static List<String> ids(List<CardSections.Section> sections) {
        List<String> out = new ArrayList<>(sections.size());
        for (CardSections.Section section : sections) {
            out.add(section.id());
        }
        return out;
    }

    private void onListChanged() {
        List<String> before = CardSections.order(card);
        List<String> next = ids(list.items());
        if (next.equals(before)) {
            return;
        }
        undo.addFirst(before);
        while (undo.size() > UNDO_DEPTH) {
            undo.removeLast();
        }
        commit(next);
    }

    private void commit(List<String> ids) {
        card.storeOrder(new ArrayList<>(ids));
        ConfigManager.getInstance().save();
    }

    private void onUndo() {
        if (undo.isEmpty()) {
            return;
        }
        commit(undo.removeFirst());
        list.setItems(CardSections.orderedSections(card));
    }

    /** Back to the default order. Undoable like any other change. */
    private void onReset() {
        List<String> current = CardSections.order(card);
        List<String> defaults = CardSections.defaults(card.sections());
        if (defaults.equals(current)) {
            return;
        }
        undo.addFirst(current);
        commit(defaults);
        list.setItems(CardSections.orderedSections(card));
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

    /** A section row; one the card has switched off is muted and noted, never hidden. */
    private final class SectionAdapter implements ReorderableList.Adapter<CardSections.Section> {

        @Override
        public String label(CardSections.Section section) {
            return section.label();
        }

        @Override
        public String note(CardSections.Section section) {
            return card.enabled(section.id()) ? "" : "off";
        }

        @Override
        public int color(CardSections.Section section) {
            return card.enabled(section.id()) ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED;
        }
    }

    // ------------------------------------------------------------------ chrome

    /** Panel, title, the list and the keyboard hint. */
    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = CardSectionOrderScreen.this.font;
            g.fill(0, 0, CardSectionOrderScreen.this.width, CardSectionOrderScreen.this.height,
                    SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int room = Math.max(0, panelW - SBSTheme.PANEL_PADDING * 2);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal(font.plainSubstrByWidth(card.title(), room)),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            list.render(g, mouseX, mouseY);

            String hint = "Drag to reorder, or: ↑↓ choose, Shift+↑↓ move";
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
