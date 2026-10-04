/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.diana.logic.DianaHudLayout;
import sbs.modid.client.combat.diana.logic.DianaHudRows;
import sbs.modid.client.combat.diana.logic.DianaPreview;
import sbs.modid.client.combat.diana.model.DianaHudLine;
import sbs.modid.client.combat.diana.model.DianaPanel;
import sbs.modid.client.combat.diana.render.DianaHud;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.component.ReorderableList;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.KeyedScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The Diana card line editor: which lines each card shows, in what order, with both cards drawn
 * beside the lists as you go.
 *
 * <p>The same shape and the same {@link ReorderableList} as the Custom Scoreboard's editor - one
 * drag-and-keyboard list in this mod, not two. A switch at the top picks the card being edited; the
 * left list is every line <i>not</i> on it (noted {@code hidden} or {@code other card}), the right
 * list is the card. Dropping a line from the other card here moves it, because a line is on at most
 * one card.
 *
 * <p><b>The preview is the real thing.</b> Rows come from {@link DianaHudRows} with sample numbers
 * and are drawn by {@link DianaHud#draw}, so what is shown here is what the HUD will draw.
 *
 * <p><b>Drag is not the only way.</b> {@code Tab} moves between the two lists, {@code ↑}/{@code ↓}
 * choose, {@code Shift} with them reorders, {@code Enter} moves an entry across, and the three
 * buttons under the lists do the same for anyone who cannot hold a button while moving the mouse.
 */
public final class DianaLayoutScreen extends Screen implements KeyedScreen {

    /** Stable id for per-screen settings (opacity). Never change it once shipped. */
    @Override
    public String screenId() {
        return "diana_layout";
    }

    /** Wider than the shared panel bounds: three columns side by side is what this screen is. */
    private static final int PANEL_MAX_W = 520;
    private static final int PANEL_MAX_H = 380;

    private static final int COLUMN_GAP = 8;
    private static final int LABEL_GAP = 11;
    private static final int CARD_GAP = 6;

    private static final int KEY_TAB = 258;

    /** How many steps back the editor can go. A mis-drop must not cost a hand-built layout. */
    private static final int UNDO_DEPTH = 32;

    private static final List<String> PANEL_OPTIONS = List.of(
            DianaPanel.TRACKER.editorName(), DianaPanel.CREATURES.editorName());

    private final Screen previous;
    private final ReorderableList<DianaHudLine> palette =
            new ReorderableList<>("Not on this card", new LineAdapter());
    private final ReorderableList<DianaHudLine> card =
            new ReorderableList<>("Card", new LineAdapter());

    private final Deque<DianaHudLayout.Layout> undo = new ArrayDeque<>();
    private DianaHudLayout.Layout committed;
    private DianaPanel editing = DianaPanel.TRACKER;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int columnTop;
    private int columnHeight;
    private int cardX;
    private int previewX;
    private int previewW;
    private int hintY;
    private boolean wired;

    public DianaLayoutScreen(Screen previous) {
        super(Component.literal("Diana Card Lines"));
        this.previous = previous;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    @Override
    protected void init() {
        // Sized from the viewport, never up to a minimum: on a screen smaller than the preferred
        // panel the panel shrinks with it rather than hanging off both edges.
        panelW = Math.min(Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2), PANEL_MAX_W);
        panelH = Math.min(Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2), PANEL_MAX_H);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;

        int innerX = panelX + SBSTheme.PANEL_PADDING;
        int innerW = panelW - SBSTheme.PANEL_PADDING * 2;
        int controlH = SBSTheme.SEARCH_HEIGHT;
        int switchY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int buttonY = panelY + panelH - SBSTheme.PANEL_PADDING - controlH;
        hintY = buttonY - LABEL_GAP;
        int nudgeY = hintY - LABEL_GAP - controlH;

        columnTop = switchY + controlH + 4 + LABEL_GAP;
        columnHeight = Math.max(30, nudgeY - 4 - columnTop);

        int listW = (innerW - COLUMN_GAP * 2) * 31 / 100;
        cardX = innerX + listW + COLUMN_GAP;
        palette.setBounds(innerX, columnTop, listW, columnHeight);
        card.setBounds(cardX, columnTop, listW, columnHeight);
        previewX = cardX + listW + COLUMN_GAP;
        previewW = innerX + innerW - previewX;

        if (!wired) {
            wired = true;
            palette.setOrderable(false);
            palette.setPeer(card);
            card.setPeer(palette);
            palette.setOnChange(this::onListsChanged);
            card.setOnChange(this::onListsChanged);
            card.setFocused(true);
            committed = DianaHudLayout.current(cfg());
            applyPanel();
        }

        clearWidgets();
        addRenderableOnly(new PanelRenderable());

        addRenderableWidget(new SciFiSegmentedSwitch(innerX, switchY, controlH, PANEL_OPTIONS,
                () -> editing.ordinal(), index -> {
                    editing = DianaPanel.values()[index];
                    applyPanel();
                }));

        int nudgeSpan = listW * 2 + COLUMN_GAP;
        int nudgeW = (nudgeSpan - COLUMN_GAP * 2) / 3;
        addRenderableWidget(new SciFiButton(innerX, nudgeY, nudgeW, controlH,
                Component.literal("▲ Up"), () -> card.moveSelection(-1)));
        addRenderableWidget(new SciFiButton(innerX + nudgeW + COLUMN_GAP, nudgeY, nudgeW, controlH,
                Component.literal("▼ Down"), () -> card.moveSelection(1)));
        addRenderableWidget(new SciFiButton(innerX + (nudgeW + COLUMN_GAP) * 2, nudgeY, nudgeW, controlH,
                Component.literal("⇄ Move"), this::onTransfer));

        int third = (innerW - COLUMN_GAP * 2) / 3;
        addRenderableWidget(new SciFiButton(innerX, buttonY, third, controlH,
                Component.literal("Undo"), this::onUndo));
        addRenderableWidget(new SciFiButton(innerX + third + COLUMN_GAP, buttonY, third, controlH,
                Component.literal("Reset"), this::onReset));
        addRenderableWidget(new SciFiButton(innerX + (third + COLUMN_GAP) * 2, buttonY,
                innerW - (third + COLUMN_GAP) * 2, controlH,
                Component.literal("Back"), this::onClose));
        addRenderableOnly(new DragRenderable());
    }

    // ------------------------------------------------------------------ state

    /** Pushes the committed layout into the two lists for the card being edited. */
    private void applyPanel() {
        card.setItems(committed.lines(editing));
        palette.setItems(notOn(committed, editing));
    }

    /** Every line not on {@code panel}, in catalogue order. Derived, never edited directly. */
    private static List<DianaHudLine> notOn(DianaHudLayout.Layout layout, DianaPanel panel) {
        List<DianaHudLine> out = new ArrayList<>();
        for (DianaHudLine line : DianaHudLine.values()) {
            if (!layout.lines(panel).contains(line)) {
                out.add(line);
            }
        }
        return out;
    }

    private void onListsChanged() {
        DianaHudLayout.Layout next = committed.with(editing, card.items());
        // The palette is a view of "not on this card", rebuilt rather than edited in place, so a line
        // dragged out and straight back lands in catalogue order instead of at the end.
        palette.setItems(notOn(next, editing));
        if (next.equals(committed)) {
            return;
        }
        undo.addFirst(committed);
        while (undo.size() > UNDO_DEPTH) {
            undo.removeLast();
        }
        commit(next);
    }

    private void commit(DianaHudLayout.Layout layout) {
        committed = layout;
        DianaHudLayout.write(cfg(), layout);
        ConfigManager.getInstance().save();
    }

    private void onTransfer() {
        if (palette.isFocused()) {
            palette.transferSelection();
        } else {
            card.transferSelection();
        }
    }

    private void onUndo() {
        if (undo.isEmpty()) {
            return;
        }
        commit(undo.removeFirst());
        applyPanel();
    }

    /** Back to the shipped layout, for both cards. Undoable like any other change. */
    private void onReset() {
        DianaHudLayout.Layout defaults = DianaHudLayout.defaults();
        if (defaults.equals(committed)) {
            return;
        }
        undo.addFirst(committed);
        commit(defaults);
        applyPanel();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(previous != null ? previous : new SBSMainScreen());
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (palette.mouseClicked(event.x(), event.y(), event.button())
                || card.mouseClicked(event.x(), event.y(), event.button())) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (palette.mouseDragged(event.x(), event.y()) || card.mouseDragged(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        // Both are asked: only the list that started the drag answers.
        boolean handled = palette.mouseReleased(event.x(), event.y());
        handled |= card.mouseReleased(event.x(), event.y());
        return handled || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (palette.mouseScrolled(mouseX, mouseY, scrollY) || card.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == KEY_TAB) {
            boolean toCard = palette.isFocused();
            card.setFocused(toCard);
            palette.setFocused(!toCard);
            return true;
        }
        if (palette.keyPressed(event.key(), event.modifiers())
                || card.keyPressed(event.key(), event.modifiers())) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ list adapter

    /** A line in either list. The note says where a line in the left list currently is. */
    private final class LineAdapter implements ReorderableList.Adapter<DianaHudLine> {

        @Override
        public String label(DianaHudLine line) {
            return line.displayName();
        }

        @Override
        public String note(DianaHudLine line) {
            DianaPanel on = committed == null ? null : committed.panelOf(line);
            if (on == null) {
                return "hidden";
            }
            return on == editing ? "" : "other card";
        }

        @Override
        public int color(DianaHudLine line) {
            return committed != null && committed.panelOf(line) == null
                    ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT;
        }
    }

    // ------------------------------------------------------------------ chrome

    /** Panel, column headings, the two lists and the preview. */
    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = DianaLayoutScreen.this.font;

            g.fill(0, 0, DianaLayoutScreen.this.width, DianaLayoutScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Diana Card Lines"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            int headingY = columnTop - LABEL_GAP;
            int listW = cardX - COLUMN_GAP - (panelX + pad);
            heading(g, "Not on this card", panelX + pad, headingY, listW);
            heading(g, editing.editorName(), cardX, headingY, listW);
            heading(g, "Preview", previewX, headingY, previewW);

            palette.render(g, mouseX, mouseY);
            card.render(g, mouseX, mouseY);
            drawPreview(g);
            drawHint(g);
        }

        private void heading(GuiGraphicsExtractor g, String text, int x, int y, int room) {
            var font = DianaLayoutScreen.this.font;
            g.text(font, Component.literal(font.plainSubstrByWidth(text, Math.max(0, room))), x, y,
                    SBSTheme.TEXT_MUTED, false);
        }

        /** Both cards, stacked, with sample numbers - the card being edited first. */
        private void drawPreview(GuiGraphicsExtractor g) {
            if (previewW <= 0) {
                return;
            }
            SBSConfig.DianaSettings cfg = cfg();
            DianaHudRows.Data sample = DianaPreview.sampleData();
            g.enableScissor(previewX, columnTop, previewX + previewW, columnTop + columnHeight);
            int y = columnTop;
            for (DianaPanel panel : new DianaPanel[] {editing, editing.other()}) {
                List<DianaHudRows.Row> rows = DianaHudRows.build(committed.lines(panel), sample,
                        DianaHud.perCreatureCap(cfg));
                if (rows.isEmpty()) {
                    g.text(DianaLayoutScreen.this.font,
                            Component.literal(panel.editorName() + ": not drawn"),
                            previewX, y, SBSTheme.TEXT_MUTED, false);
                    y += DianaLayoutScreen.this.font.lineHeight + CARD_GAP;
                    continue;
                }
                DianaHud.CardStyle style = DianaHud.style(cfg, panel);
                int[] size = DianaHud.measure(panel, style, rows);
                DianaHud.draw(g, previewX, y, size[0], size[1], panel, style, rows);
                y += size[1] + CARD_GAP;
            }
            g.disableScissor();
        }

        /** The keyboard path, which is the part nobody discovers by looking at a drag-and-drop list. */
        private void drawHint(GuiGraphicsExtractor g) {
            var font = DianaLayoutScreen.this.font;
            String text = "Drag between the lists, or: Tab switches, Shift+↑↓ moves, Enter sends across";
            int room = panelW - SBSTheme.PANEL_PADDING * 2;
            g.text(font, Component.literal(font.plainSubstrByWidth(text, Math.max(0, room))),
                    panelX + SBSTheme.PANEL_PADDING, hintY, SBSTheme.TEXT_MUTED, false);
        }
    }

    /** The dragged entry, drawn after everything so it is above both lists and the buttons. */
    private static final class DragRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            ReorderableList.renderDrag(g, mouseX, mouseY);
        }
    }
}
