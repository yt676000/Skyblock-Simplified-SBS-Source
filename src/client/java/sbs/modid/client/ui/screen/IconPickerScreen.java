/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.helper.inventorybuttons.logic.InventoryButton;
import sbs.modid.client.helper.inventorybuttons.logic.InventoryButtons;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.logic.RecipeRegistry;

import java.util.List;
import java.util.Optional;

/**
 * Picks any SkyBlock item as a button's icon: a searchable item grid, click to choose.
 *
 * <p>Deliberately <b>only</b> a picker. It reuses the Recipe Viewer's item index
 * ({@link RecipeRegistry#search}) – the same catalogue, icons and search behaviour, so every item is
 * reachable and looks right – but none of its recipe machinery: clicking an item sets the icon and
 * returns, rather than opening a recipe. Reusing the index instead of building a second one also
 * means new items appear here automatically.
 */
public final class IconPickerScreen extends Screen {

    private static final int CELL = 20;
    private static final int SEARCH_LIMIT = 400;

    private final InventoryButton button;
    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int gridTop;
    private int gridBottom;
    private int columns;

    private EditBox searchBox;
    private List<ItemRef> results = List.of();
    private int scrollRow;
    private int maxScrollRow;

    public IconPickerScreen(InventoryButton button, Screen parent) {
        super(Component.literal("Choose Icon"));
        this.button = button;
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 300, 460);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 240, 400);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;

        addRenderableOnly(new PanelRenderable());

        int searchY = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER;
        int textH = this.font.lineHeight;
        searchBox = new EditBox(this.font, innerX + 6, searchY + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                contentW - 12, textH, Component.literal("Search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(48);
        searchBox.setTextColor(SBSTheme.TEXT);
        searchBox.setHint(Component.literal("Search items..."));
        searchBox.setResponder(query -> refresh());
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        gridTop = searchY + SBSTheme.SEARCH_HEIGHT + 6;
        gridBottom = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT - 6;
        columns = Math.max(1, contentW / CELL);

        addRenderableWidget(new SciFiButton(innerX, panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT,
                contentW, SBSTheme.SEARCH_HEIGHT, Component.literal("Cancel"), this::onClose));

        refresh();
    }

    private void refresh() {
        results = RecipeRegistry.getInstance()
                .search(searchBox == null ? "" : searchBox.getValue(), SEARCH_LIMIT);
        scrollRow = 0;
    }

    private void choose(ItemRef item) {
        button.icon = item.lookupId();
        InventoryButtons.save();
        onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        ItemRef hit = itemAt(event.x(), event.y());
        if (hit != null) {
            choose(hit);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scrollRow = clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScrollRow);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The item under a screen point, or {@code null}. */
    private ItemRef itemAt(double mouseX, double mouseY) {
        if (mouseX < innerX || mouseX >= innerX + columns * CELL
                || mouseY < gridTop || mouseY >= gridBottom) {
            return null;
        }
        int col = (int) ((mouseX - innerX) / CELL);
        int row = scrollRow + (int) ((mouseY - gridTop) / CELL);
        int index = row * columns + col;
        return index >= 0 && index < results.size() ? results.get(index) : null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = IconPickerScreen.this.font;
            g.fill(0, 0, IconPickerScreen.this.width, IconPickerScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Choose Icon"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, panelY + SBSTheme.HEADER_HEIGHT, panelX + panelW - pad,
                    panelY + SBSTheme.HEADER_HEIGHT + 1, SBSTheme.ACCENT);

            int searchY = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER;
            SciFiRender.roundedRectWithBorder(g, innerX, searchY, contentW, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    searchBox.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            drawGrid(g, mouseX, mouseY);
        }

        private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = IconPickerScreen.this.font;
            if (results.isEmpty()) {
                g.centeredText(font, Component.literal("§7No matching items."),
                        panelX + panelW / 2, gridTop + 12, SBSTheme.TEXT_MUTED);
                maxScrollRow = 0;
                return;
            }
            int visibleRows = Math.max(1, (gridBottom - gridTop) / CELL);
            int totalRows = (results.size() + columns - 1) / columns;
            maxScrollRow = Math.max(0, totalRows - visibleRows);
            scrollRow = clamp(scrollRow, 0, maxScrollRow);

            ItemRef hovered = null;
            int hx = 0;
            int hy = 0;
            for (int row = scrollRow; row < totalRows && row < scrollRow + visibleRows; row++) {
                for (int col = 0; col < columns; col++) {
                    int index = row * columns + col;
                    if (index >= results.size()) {
                        break;
                    }
                    int x = innerX + col * CELL;
                    int y = gridTop + (row - scrollRow) * CELL;
                    boolean over = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
                    SciFiRender.roundedRectWithBorder(g, x, y, CELL - 2, CELL - 2, 2,
                            over ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                            over ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
                    g.item(results.get(index).displayStack(), x + 1, y + 1);
                    if (over) {
                        hovered = results.get(index);
                        hx = mouseX;
                        hy = mouseY;
                    }
                }
            }
            if (hovered != null) {
                g.setTooltipForNextFrame(font, List.of(
                                Component.literal("§f" + hovered.name),
                                Component.literal("§8" + hovered.lookupId()),
                                Component.literal("§8click to use as icon")),
                        Optional.empty(), hx, hy, SBSTheme.tooltipStyle());
            }
            if (maxScrollRow > 0) {
                int trackH = visibleRows * CELL;
                g.fill(innerX + contentW - 3, gridTop, innerX + contentW, gridTop + trackH,
                        SBSTheme.CARD_BG_DISABLED);
                int thumbH = Math.max(8, trackH * visibleRows / totalRows);
                int thumbY = gridTop + (int) ((long) (trackH - thumbH) * scrollRow / maxScrollRow);
                g.fill(innerX + contentW - 3, thumbY, innerX + contentW, thumbY + thumbH,
                        SBSTheme.ACCENT);
            }
        }
    }
}
