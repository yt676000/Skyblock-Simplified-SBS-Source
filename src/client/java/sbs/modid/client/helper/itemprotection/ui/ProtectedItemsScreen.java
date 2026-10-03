/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.itemprotection.logic.ItemProtection;
import sbs.modid.client.helper.itemprotection.logic.ProtectedItems;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The protected-items list: everything marked, including items the player is not carrying, each
 * with a button that unprotects it.
 *
 * <p><b>Two sections, never merged.</b> A uuid row protects one physical item and an id row protects
 * every stack of a kind; showing them in one undifferentiated list would leave the player unable to
 * tell which of the two they were about to remove. The section header carries that distinction so
 * each row does not have to repeat it.
 *
 * <p>Scrolling goes through {@code SciFiScrollbar} - the one scrollbar in this mod, and a real
 * control rather than a picture of one: it is set every frame, offered the click before the rows,
 * and given the drag and the release too.
 */
public final class ProtectedItemsScreen extends Screen {

    private static final int SCROLLBAR_SPACE = SciFiScrollbar.WIDTH + 2;

    private final SciFiScrollbar scrollbar = new SciFiScrollbar();

    /** One rendered line: either a section header, or a removable entry. */
    private record Line(String text, boolean header, boolean type, String key) {
    }

    private List<Line> lines = List.of();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int statusY;
    private int clearY;
    private int tableTop;
    private int tableHeight;
    private int backY;
    private int rowHeight;
    private int stride;
    private int visibleRows;
    private int scrollIndex;

    public ProtectedItemsScreen() {
        super(Component.literal("Protected Items"));
    }

    @Override
    protected void init() {
        // Sized from the viewport, never up to a fixed minimum: the available space is the ceiling.
        int availableW = Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2);
        int availableH = Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2);
        panelW = Math.min(availableW, SBSTheme.PANEL_MAX_WIDTH);
        panelH = Math.min(availableH, SBSTheme.PANEL_MAX_HEIGHT);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;

        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = panelW - pad * 2;

        int controlHeight = SBSTheme.SEARCH_HEIGHT;
        statusY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        clearY = statusY + this.font.lineHeight + 6;
        backY = panelY + panelH - pad - controlHeight;
        tableTop = clearY + controlHeight + SBSTheme.GAP_AFTER_SEARCH;
        tableHeight = Math.max(SBSTheme.ENTRY_HEIGHT, backY - SBSTheme.GAP_AFTER_SEARCH - tableTop);
        rowHeight = SBSTheme.ENTRY_HEIGHT;
        stride = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
        visibleRows = Math.max(1, (tableHeight + SBSTheme.ENTRY_SPACING) / stride);

        rebuild();
    }

    /** Rebuilds the flattened line list and the widgets for the rows currently on screen. */
    private void rebuild() {
        lines = buildLines();
        clampScroll();
        clearWidgets();
        addRenderableOnly(new PanelRenderable());

        addRenderableWidget(new SciFiButton(innerX, clearY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Clear All"), this::onClearAll));

        // The scrollbar strip is reserved unconditionally, so a row's width does not change as the
        // list crosses the threshold where a bar appears.
        int rowW = contentWidth - SCROLLBAR_SPACE;
        int removeW = rowHeight;
        int removeX = innerX + rowW - removeW;

        // Only the remove button is a widget. The item's name is drawn as text by the panel, not as
        // a button that does nothing when pressed - a control that looks pressable and is not is the
        // same lie as a scrollbar that cannot be dragged, and there is nothing else a row could do.
        int last = Math.min(lines.size(), scrollIndex + visibleRows);
        for (int i = scrollIndex; i < last; i++) {
            Line line = lines.get(i);
            if (line.header()) {
                continue;
            }
            int rowY = tableTop + (i - scrollIndex) * stride;
            addRenderableWidget(new SciFiButton(removeX, rowY, removeW, rowHeight,
                    Component.literal("X"), () -> remove(line)));
        }

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    /** Header rows plus one row per entry, in the order they are drawn. */
    private List<Line> buildLines() {
        ProtectedItems store = ProtectedItems.getInstance();
        List<Line> out = new ArrayList<>();
        List<ProtectedItems.Row> items = store.uniqueRows();
        if (!items.isEmpty()) {
            out.add(new Line("§7Individual items (" + items.size() + ")", true, false, ""));
            for (ProtectedItems.Row row : items) {
                out.add(new Line(row.entry().label(), false, false, row.key()));
            }
        }
        List<ProtectedItems.Row> types = store.typeRows();
        if (!types.isEmpty()) {
            out.add(new Line("§7Item types (" + types.size() + ")", true, false, ""));
            for (ProtectedItems.Row row : types) {
                out.add(new Line("Every " + row.entry().label(), false, true, row.key()));
            }
        }
        return out;
    }

    private void remove(Line line) {
        ProtectedItems store = ProtectedItems.getInstance();
        if (line.type()) {
            store.forgetType(line.key());
        } else {
            store.forgetUuid(line.key());
        }
        rebuild();
    }

    private void onClearAll() {
        ProtectedItems.getInstance().clear();
        scrollIndex = 0;
        rebuild();
    }

    private void onBack() {
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
    }

    // ------------------------------------------------------------------ scrolling

    private int maxScroll() {
        return Math.max(0, lines.size() - visibleRows);
    }

    private void clampScroll() {
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll()));
    }

    private void setScroll(int value) {
        int next = Math.max(0, Math.min(maxScroll(), value));
        if (next != scrollIndex) {
            scrollIndex = next;
            rebuild();
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        // The bar is offered the click before the rows, or a click on it would also press the row
        // painted underneath.
        if (scrollbar.handleClick(event.x(), event.y(), scrollIndex, this::setScroll)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX,
                                double dragY) {
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
        if (maxScroll() <= 0 || scrollY == 0) {
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
            var font = ProtectedItemsScreen.this.font;
            g.fill(0, 0, ProtectedItemsScreen.this.width, ProtectedItemsScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Protected Items"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            // Status: whether the feature is even on, because a full list that does nothing is the
            // most confusing state this screen can be in.
            boolean on = ConfigManager.getInstance().get().itemProtection.enabled;
            String status = on
                    ? (ItemProtection.enabled()
                            ? "§7Protection is §aactive"
                            : "§7Protection is on, but §eonly acts on Hypixel SkyBlock")
                    : "§8Protection is switched off in the module settings";
            g.text(font, Component.literal(RowText.fit(font, status, contentWidth)),
                    innerX, statusY, SBSTheme.TEXT_MUTED);

            if (lines.isEmpty()) {
                g.centeredText(font,
                        Component.literal("Nothing protected - hover an item and press your Mark key"),
                        panelX + panelW / 2, tableTop + tableHeight / 2 - font.lineHeight / 2,
                        SBSTheme.TEXT_MUTED);
            }

            // Every row's text, headers and entries alike. Measured against the room actually left
            // beside the remove button and the reserved scrollbar strip, then ellipsised - a long
            // item name must give way rather than run under the button.
            int textW = contentWidth - SCROLLBAR_SPACE - rowHeight - 8;
            int last = Math.min(lines.size(), scrollIndex + visibleRows);
            for (int i = scrollIndex; i < last; i++) {
                Line line = lines.get(i);
                int rowY = tableTop + (i - scrollIndex) * stride;
                int textY = rowY + (rowHeight - font.lineHeight) / 2;
                int color = line.header() ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT;
                g.text(font, Component.literal(RowText.fit(font, line.text(), textW)),
                        innerX + (line.header() ? 0 : 4), textY, color);
            }

            // Set every frame, before rendering and before any hit-test, so the bar the player grabs
            // is exactly the one that was drawn.
            scrollbar.set(innerX + contentWidth - SciFiScrollbar.WIDTH, tableTop, tableHeight,
                    lines.size(), visibleRows);
            scrollbar.render(g, scrollIndex, mouseX, mouseY);
        }
    }
}
