/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.helper.build.command.BuildEdits;
import sbs.modid.client.helper.build.logic.EditEngine;
import sbs.modid.client.helper.build.logic.Timeline;
import sbs.modid.client.helper.build.model.EditHistory;
import sbs.modid.client.helper.build.model.EditRecord;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The undo timeline: every edit made in this world, newest at the top, each with its name, block
 * count and time. Clicking one puts the world back (or forward) to right after that edit - any
 * point, not one step at a time. The row the world currently matches is marked; rows above it are
 * undone and can be redone by clicking them.
 *
 * <p>Rows are buttons because clicking one does something; scrolling is the shared
 * {@link SciFiScrollbar}, set every frame and offered the click first. The screen follows the
 * timeline while a jump replays, so it never shows a cursor the world has already left.
 */
public final class TimelineScreen extends Screen {

    private static final int SCROLLBAR_SPACE = SciFiScrollbar.WIDTH + 2;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SciFiScrollbar scrollbar = new SciFiScrollbar();

    /** One row: a timeline index (-1 = "before the first edit") and its caption. */
    private record Row(int index, String text, boolean current, boolean undone) {
    }

    private List<Row> rows = List.of();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int statusY;
    private int tableTop;
    private int tableHeight;
    private int buttonsY;
    private int stride;
    private int visibleRows;
    private int scrollIndex;

    /** What the rows were built from, so a finished replay rebuilds them. */
    private int builtCursor = Integer.MIN_VALUE;
    private int builtSize = -1;
    private boolean builtBusy;

    public TimelineScreen() {
        super(Component.literal("Build Timeline"));
    }

    @Override
    protected void init() {
        int availableW = Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2);
        int availableH = Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2);
        panelW = Math.min(availableW, SBSTheme.PANEL_MAX_WIDTH + 80);
        panelH = Math.min(availableH, SBSTheme.PANEL_MAX_HEIGHT);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = panelW - pad * 2;
        statusY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        tableTop = statusY + this.font.lineHeight + SBSTheme.GAP_AFTER_SEARCH;
        buttonsY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        tableHeight = Math.max(SBSTheme.ENTRY_HEIGHT, buttonsY - SBSTheme.GAP_AFTER_SEARCH - tableTop);
        stride = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
        visibleRows = Math.max(1, (tableHeight + SBSTheme.ENTRY_SPACING) / stride);
        rebuild();
    }

    private void rebuild() {
        EditHistory<EditRecord> history = Timeline.history();
        builtCursor = history.cursor();
        builtSize = history.entries().size();
        builtBusy = EditEngine.busy();
        rows = buildRows(history);
        scrollIndex = Math.max(0, Math.min(scrollIndex, Math.max(0, rows.size() - visibleRows)));
        clearWidgets();
        addRenderableOnly(new PanelRenderable());

        int rowW = contentWidth - SCROLLBAR_SPACE;
        int last = Math.min(rows.size(), scrollIndex + visibleRows);
        for (int i = scrollIndex; i < last; i++) {
            Row row = rows.get(i);
            int rowY = tableTop + (i - scrollIndex) * stride;
            String label = RowText.fit(this.font, row.text(), rowW - 10);
            SciFiButton button = new SciFiButton(innerX, rowY, rowW, SBSTheme.ENTRY_HEIGHT,
                    Component.literal(label), () -> jump(row));
            button.active = !row.current() && !builtBusy;
            addRenderableWidget(button);
        }

        // Undo / Redo / Close share the bottom line; measured, and each gets a third.
        int gap = 4;
        int third = (contentWidth - gap * 2) / 3;
        SciFiButton undo = new SciFiButton(innerX, buttonsY, third, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Undo"), () -> BuildEdits.jumpTo(Timeline.history().cursor() - 1));
        undo.active = history.canUndo() && !builtBusy;
        SciFiButton redo = new SciFiButton(innerX + third + gap, buttonsY, third, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Redo"), () -> BuildEdits.jumpTo(Timeline.history().cursor() + 1));
        redo.active = history.canRedo() && !builtBusy;
        addRenderableWidget(undo);
        addRenderableWidget(redo);
        addRenderableWidget(new SciFiButton(innerX + (third + gap) * 2, buttonsY, contentWidth - (third + gap) * 2,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Close"), this::onClose));
    }

    private static List<Row> buildRows(EditHistory<EditRecord> history) {
        List<Row> out = new ArrayList<>();
        List<EditHistory.Entry<EditRecord>> entries = history.entries();
        int cursor = history.cursor();
        for (int i = entries.size() - 1; i >= 0; i--) {
            EditHistory.Entry<EditRecord> entry = entries.get(i);
            boolean current = i == cursor;
            boolean undone = i > cursor;
            String text = (current ? "▶ " : undone ? "   (undone) " : "   ") + entry.name()
                    + String.format(Locale.ROOT, "  •  %,d blocks  •  ", entry.blocks())
                    + TIME.format(Instant.ofEpochMilli(entry.time()));
            out.add(new Row(i, text, current, undone));
        }
        if (!entries.isEmpty()) {
            boolean current = cursor == -1;
            out.add(new Row(-1, (current ? "▶ " : "   ") + (history.droppedCount() > 0
                    ? "Oldest kept point" : "Before the first edit"), current, false));
        }
        return out;
    }

    private void jump(Row row) {
        BuildEdits.jumpTo(row.index());
        rebuild();
    }

    @Override
    public void tick() {
        super.tick();
        EditHistory<EditRecord> history = Timeline.history();
        if (history.cursor() != builtCursor || history.entries().size() != builtSize || EditEngine.busy() != builtBusy) {
            rebuild();
        }
    }

    private void setScroll(int value) {
        int next = Math.max(0, Math.min(Math.max(0, rows.size() - visibleRows), value));
        if (next != scrollIndex) {
            scrollIndex = next;
            rebuild();
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (scrollbar.handleClick(event.x(), event.y(), scrollIndex, this::setScroll)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        if (scrollbar.handleDrag(event.y(), this::setScroll)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (scrollbar.release()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (rows.size() <= visibleRows || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        setScroll(scrollIndex + (scrollY > 0 ? -1 : 1));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = TimelineScreen.this.font;
            g.fill(0, 0, TimelineScreen.this.width, TimelineScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            g.centeredText(font, Component.literal("Build Timeline"), panelX + panelW / 2,
                    panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            EditHistory<EditRecord> history = Timeline.history();
            String status;
            if (EditEngine.busy()) {
                status = "Working: " + EditEngine.label();
            } else if (history.entries().isEmpty()) {
                status = "No edits in this world yet";
            } else {
                status = "Click an edit to put the world back to right after it"
                        + (history.droppedCount() > 0 ? "  •  " + history.droppedCount()
                        + " older edits were dropped to save memory" : "");
            }
            g.text(font, Component.literal(RowText.fit(font, status, contentWidth)), innerX, statusY, SBSTheme.TEXT_MUTED);

            scrollbar.set(innerX + contentWidth - SciFiScrollbar.WIDTH, tableTop, tableHeight, rows.size(), visibleRows);
            scrollbar.render(g, scrollIndex, mouseX, mouseY);
        }
    }
}
