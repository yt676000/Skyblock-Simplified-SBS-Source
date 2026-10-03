/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.component.ReorderableList;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The Custom Scoreboard's layout editor: drag elements between a palette and the panel's layout,
 * with the real scoreboard drawn beside them as you go.
 *
 * <p><b>It edits the catalogue, not the screen.</b> The palette lists every element SBS knows about,
 * including the ones this island does not show - a dungeon row can be positioned from the Hub, and
 * an event row in the middle of the year. The editor this replaced listed whatever the sidebar
 * happened to be saying at the moment it opened, which left most of the panel unarrangeable most of
 * the time.
 *
 * <p><b>Nothing is keyed on text.</b> Both lists hold {@link ScoreboardElements} ids, and ids are
 * what is saved. A row may rewrite its own words as often as Hypixel likes and keep its slot.
 *
 * <p><b>Drag is not the only way.</b> {@code Tab} moves between the two lists, {@code ↑}/{@code ↓}
 * choose, {@code Shift} with them reorders, and {@code Enter} moves an entry across - the same
 * operations the mouse performs, for anyone who cannot comfortably drag.
 */
public final class CustomScoreboardScreen extends Screen implements sbs.modid.client.ui.theme.KeyedScreen {

    /** Stable id for per-screen settings (opacity). Never change it once shipped. */
    @Override
    public String screenId() {
        return "scoreboard_layout";
    }


    /**
     * Wider and taller than the shared panel bounds, which are sized for one column of settings
     * rows. Three panes side by side is what this screen is; squeezing them into 340px would leave
     * every element name cut off at an ellipsis.
     */
    private static final int PANEL_MAX_W = 520;
    private static final int PANEL_MAX_H = 420;
    private static final int PANEL_MIN_W = 340;
    private static final int PANEL_MIN_H = 220;

    private static final int COLUMN_GAP = 8;
    private static final int LABEL_GAP = 11;

    /** GLFW key codes, spelled out here so the screen does not depend on the GLFW binding. */
    private static final int KEY_TAB = 258;

    /** How many steps back the editor can go. A mis-drop must not cost a hand-built layout. */
    private static final int UNDO_DEPTH = 32;

    private final ReorderableList<String> palette =
            new ReorderableList<>("Available", new ElementAdapter());
    private final ReorderableList<String> layout =
            new ReorderableList<>("Scoreboard", new ElementAdapter());

    /** Previous layouts, most recent first - one entry per change, whichever input made it. */
    private final Deque<List<String>> undo = new ArrayDeque<>();

    /** The layout as last written to the config, so a change knows what it is replacing. */
    private List<String> committed = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int columnTop;
    private int columnHeight;
    private int layoutX;
    private int previewX;
    private int previewW;
    private int hintY;
    private boolean wired;

    public CustomScoreboardScreen() {
        super(Component.literal("Custom Scoreboard Layout"));
    }

    private static SBSConfig.CustomScoreboardSettings cfg() {
        return ConfigManager.getInstance().get().customScoreboard;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, PANEL_MIN_W, PANEL_MAX_W);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, PANEL_MIN_H, PANEL_MAX_H);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;

        int innerX = panelX + SBSTheme.PANEL_PADDING;
        int innerW = panelW - SBSTheme.PANEL_PADDING * 2;
        int controlH = SBSTheme.SEARCH_HEIGHT;
        int buttonY = panelY + panelH - SBSTheme.PANEL_PADDING - controlH;
        hintY = buttonY - LABEL_GAP;
        int nudgeY = hintY - LABEL_GAP - controlH;

        columnTop = dividerY + SBSTheme.GAP_AFTER_HEADER + LABEL_GAP;
        columnHeight = Math.max(60, nudgeY - 4 - columnTop);

        int listW = (innerW - COLUMN_GAP * 2) * 34 / 100;
        layoutX = innerX + listW + COLUMN_GAP;
        palette.setBounds(innerX, columnTop, listW, columnHeight);
        layout.setBounds(layoutX, columnTop, listW, columnHeight);
        previewX = layoutX + listW + COLUMN_GAP;
        previewW = innerX + innerW - previewX;

        if (!wired) {
            wired = true;
            palette.setCopySource(true);
            palette.setOrderable(false);
            palette.setPeer(layout);
            layout.setPeer(palette);
            palette.setOnChange(this::onListsChanged);
            layout.setOnChange(this::onListsChanged);
            layout.setFocused(true);
            committed = savedOrSeededOrder();
            applyOrder(committed);
        }

        clearWidgets();
        addRenderableOnly(new PanelRenderable());

        // The pointer path that is not a drag. Dragging and the keyboard between them still leave out
        // anyone who uses a mouse but cannot hold a button down while moving it, and ordering a list
        // is exactly the operation that becomes impossible for them.
        int nudgeSpan = listW * 2 + COLUMN_GAP; // the two lists, edge to edge
        int nudgeW = (nudgeSpan - COLUMN_GAP * 2) / 3;
        addRenderableWidget(new SciFiButton(innerX, nudgeY, nudgeW, controlH,
                Component.literal("▲ Up"), () -> layout.moveSelection(-1)));
        addRenderableWidget(new SciFiButton(innerX + nudgeW + COLUMN_GAP, nudgeY, nudgeW, controlH,
                Component.literal("▼ Down"), () -> layout.moveSelection(1)));
        addRenderableWidget(new SciFiButton(innerX + (nudgeW + COLUMN_GAP) * 2, nudgeY, nudgeW, controlH,
                Component.literal("⇄ Move"), this::onTransfer));

        int third = (innerW - COLUMN_GAP * 2) / 3;
        addRenderableWidget(new SciFiButton(innerX, buttonY, third, controlH,
                Component.literal("Undo"), this::onUndo));
        addRenderableWidget(new SciFiButton(innerX + third + COLUMN_GAP, buttonY, third, controlH,
                Component.literal("Reset"), this::onReset));
        addRenderableWidget(new SciFiButton(innerX + (third + COLUMN_GAP) * 2, buttonY,
                innerW - (third + COLUMN_GAP) * 2, controlH,
                Component.literal("Back"), this::onBack));
        addRenderableOnly(new DragRenderable());
    }

    // ------------------------------------------------------------------ state

    /**
     * The layout to open with: the saved one, or - for a player who has never arranged anything - the
     * order the panel is in right now, which is the arrangement they are looking at.
     */
    private static List<String> savedOrSeededOrder() {
        List<String> saved = cfg().elementOrder;
        if (saved != null && !saved.isEmpty()) {
            return new ArrayList<>(saved);
        }
        return ScoreboardLayout.seedOrder(CustomScoreboardRenderer.allRows(cfg()));
    }

    /** Pushes {@code order} into the two lists. The palette is derived, never edited directly. */
    private void applyOrder(List<String> order) {
        layout.setItems(order);
        palette.setItems(ScoreboardLayout.unplaced(order));
    }

    /**
     * Persists whatever the lists now say, remembering what it replaced.
     *
     * <p>Hidden is stored as "every catalogue element the layout does not place", so an element the
     * player dragged out stays out - while an element that only appears in a <i>later</i> catalogue
     * is in neither list, and lands at the "Everything Else" slot rather than vanishing.
     */
    private void onListsChanged() {
        List<String> order = layout.items();
        // The palette is a view of "not placed", so it is rebuilt rather than edited in place -
        // otherwise a blank row dragged out of the layout would appear in it a second time, and an
        // entry dragged out of the palette and straight back would land at the end of it.
        palette.setItems(ScoreboardLayout.unplaced(order));
        if (order.equals(committed)) {
            return;
        }
        undo.addFirst(committed);
        while (undo.size() > UNDO_DEPTH) {
            undo.removeLast();
        }
        committed = order;
        persist(order);
    }

    private void persist(List<String> order) {
        SBSConfig.CustomScoreboardSettings cfg = cfg();
        cfg.elementOrder = new ArrayList<>(order);
        cfg.hiddenElements = unplacedIds(new HashSet<>(order));
        cfg.layoutResetNotice = false;
        ConfigManager.getInstance().save();
    }

    /** Every catalogue element the layout does not place - what "hidden" means once one exists. */
    private static List<String> unplacedIds(Set<String> placed) {
        List<String> hidden = new ArrayList<>();
        for (ScoreboardElement element : ScoreboardElements.catalog()) {
            // A blank or a rule is never "hidden": it is simply not placed, and the palette always
            // keeps offering it.
            if (!element.repeatable() && !placed.contains(element.id())) {
                hidden.add(element.id());
            }
        }
        return hidden;
    }

    /** Sends whichever list has the keyboard across - "place this" from one side, "remove" from the other. */
    private void onTransfer() {
        if (palette.isFocused()) {
            palette.transferSelection();
        } else {
            layout.transferSelection();
        }
    }

    private void onUndo() {
        if (undo.isEmpty()) {
            return;
        }
        List<String> previous = undo.removeFirst();
        committed = previous;
        applyOrder(previous);
        persist(previous);
    }

    /**
     * Back to the layout the mod ships with - not to "no layout at all".
     *
     * <p>Those are different things now that there is a shipped default: clearing the order would
     * hand back Hypixel's raw sidebar, which is not what a player who hits Reset on a panel they
     * have been arranging is asking for. Undoable like any other change.
     */
    private void onReset() {
        undo.addFirst(committed);
        committed = ScoreboardLayout.defaultOrder();
        applyOrder(committed);
        persist(committed);
    }

    private void onBack() {
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (palette.mouseClicked(event.x(), event.y(), event.button())
                || layout.mouseClicked(event.x(), event.y(), event.button())) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (palette.mouseDragged(event.x(), event.y()) || layout.mouseDragged(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        // Both are asked: only the list that started the drag answers, and which one that was is its
        // own business rather than the screen's.
        boolean handled = palette.mouseReleased(event.x(), event.y());
        handled |= layout.mouseReleased(event.x(), event.y());
        return handled || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (palette.mouseScrolled(mouseX, mouseY, scrollY)
                || layout.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == KEY_TAB) {
            boolean toLayout = palette.isFocused();
            layout.setFocused(toLayout);
            palette.setFocused(!toLayout);
            return true;
        }
        if (palette.keyPressed(event.key(), event.modifiers())
                || layout.keyPressed(event.key(), event.modifiers())) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------ preview

    /** The rows the preview draws, and which of them are stand-ins for elements that are absent. */
    private record Preview(List<ScoreboardLine> rows, Set<ScoreboardLine> stand) {
    }

    /**
     * The live preview's content.
     *
     * <p>Built by feeding the <i>real</i> pipeline: the panel's current rows, plus one example row
     * for every element the layout places that is not on screen right now, then through the same
     * {@link ScoreboardLayout} the HUD uses. That is what makes this a preview rather than a drawing
     * of one - a mistake in the layout engine shows up here identically.
     */
    private Preview buildPreview() {
        SBSConfig.CustomScoreboardSettings cfg = cfg();
        List<ScoreboardLine> base = new ArrayList<>(CustomScoreboardRenderer.allRows(cfg));
        Set<String> covered = new HashSet<>(ScoreboardElements.idsFor(base));
        Set<ScoreboardLine> stand = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String id : layout.items()) {
            ScoreboardElement element = ScoreboardElements.byId(id);
            if (element == null || element.repeatable()
                    || element.kind() == ScoreboardElement.Kind.UNRECOGNIZED || !covered.add(id)) {
                continue;
            }
            ScoreboardLine placeholder = exampleRow(element);
            stand.add(placeholder);
            base.add(placeholder);
        }
        Set<String> placed = new HashSet<>(layout.items());
        return new Preview(ScoreboardLayout.apply(base, layout.items(), unplacedIds(placed)), stand);
    }

    /**
     * A stand-in row for an element that is not on screen right now.
     *
     * <p>Its signature is the element's own id, so the layout engine classifies it straight back to
     * the element it stands for instead of re-deriving one from the example text.
     */
    private static ScoreboardLine exampleRow(ScoreboardElement element) {
        String text = element.example().isEmpty() ? element.name() + ": —" : element.example();
        return new ScoreboardLine(Component.literal(text), text, element.id());
    }

    // ------------------------------------------------------------------ list adapter

    /** How an element id is drawn in either list. Ids in, names out - nothing is keyed on the name. */
    private static final class ElementAdapter implements ReorderableList.Adapter<String> {

        @Override
        public String label(String id) {
            return ScoreboardElements.nameOf(id);
        }

        @Override
        public String note(String id) {
            ScoreboardElement element = ScoreboardElements.byId(id);
            if (element == null) {
                return "?";
            }
            return switch (element.kind()) {
                case SBS -> "SBS";
                case SPACER, SEPARATOR -> "any";
                case UNRECOGNIZED -> "slot";
                case SERVER -> "";
            };
        }

        @Override
        public int color(String id) {
            ScoreboardElement element = ScoreboardElements.byId(id);
            if (element == null) {
                return SBSTheme.TEXT_MUTED;
            }
            return switch (element.kind()) {
                case SBS -> SBSTheme.ACCENT_BRIGHT;
                case SPACER, SEPARATOR, UNRECOGNIZED -> SBSTheme.TEXT_MUTED;
                case SERVER -> SBSTheme.TEXT;
            };
        }

        @Override
        public boolean repeatable(String id) {
            return ScoreboardElements.repeatable(id);
        }
    }

    // ------------------------------------------------------------------ chrome

    /** Panel, column headings, the two lists and the live preview. */
    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = CustomScoreboardScreen.this.font;

            g.fill(0, 0, CustomScoreboardScreen.this.width, CustomScoreboardScreen.this.height,
                    SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Scoreboard Layout"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            int headingY = columnTop - LABEL_GAP;
            g.text(font, Component.literal("Available"), panelX + pad, headingY,
                    SBSTheme.TEXT_MUTED, false);
            g.text(font, Component.literal("Scoreboard"), layoutX, headingY,
                    SBSTheme.TEXT_MUTED, false);
            g.text(font, Component.literal("Preview"), previewX, headingY,
                    SBSTheme.TEXT_MUTED, false);

            palette.render(g, mouseX, mouseY);
            layout.render(g, mouseX, mouseY);
            drawPreview(g);
            drawHint(g);
        }

        /** The real panel, drawn in its own column and clipped to it. */
        private void drawPreview(GuiGraphicsExtractor g) {
            var font = CustomScoreboardScreen.this.font;
            Preview preview = buildPreview();
            if (preview.rows().isEmpty()) {
                g.text(font, Component.literal("nothing to show"), previewX, columnTop + 4,
                        SBSTheme.TEXT_MUTED, false);
                return;
            }
            g.enableScissor(previewX, columnTop, previewX + previewW, columnTop + columnHeight);
            CustomScoreboardRenderer.drawPreview(g, cfg(), preview.rows(), preview.stand(),
                    previewX, columnTop);
            g.disableScissor();
        }

        /**
         * The line under the columns: what the chosen element is, or - when nothing is chosen - the
         * keyboard path, which is the part nobody discovers by looking at a drag-and-drop screen.
         */
        private void drawHint(GuiGraphicsExtractor g) {
            var font = CustomScoreboardScreen.this.font;
            String chosen = layout.isFocused() ? layout.selection() : palette.selection();
            ScoreboardElement element = chosen == null ? null : ScoreboardElements.byId(chosen);
            String text = element != null && !element.note().isEmpty()
                    ? element.name() + " - " + element.note()
                    : "Drag between the lists, or: Tab switches, Shift+↑↓ moves, Enter sends across";
            g.text(font, Component.literal(text), panelX + SBSTheme.PANEL_PADDING, hintY,
                    SBSTheme.TEXT_MUTED, false);
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
