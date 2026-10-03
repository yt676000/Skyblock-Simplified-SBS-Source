/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiCycleButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.helper.storage.StorageIndex;
import sbs.modid.client.helper.storage.StorageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The central item search ("Full UI" mode of the Skyblock Menu module): one screen that answers
 * "where is my stuff and how much of it do I have", across every storage the client has indexed.
 *
 * <p>Layout: the sort selector and the search box on top, then an <b>item grid</b> – one icon per
 * merged item with its abbreviated amount underneath, densest first (dense layout, SBS
 * styling). The tooltip carries the full name, total and the per-location breakdown.
 *
 * <p>Interaction:
 * <ul>
 *   <li>typing filters live (the query is re-run against the index on every keystroke);</li>
 *   <li>the sort button cycles Amount / Price / Name / Location;</li>
 *   <li>left-click opens the item's best location when that storage has a command
 *       ({@code /enderchest 3}, {@code /backpack 2}, ...);</li>
 *   <li>right-click stars / unstars an item – favourites sort above everything.</li>
 * </ul>
 */
public final class StorageSearchScreen extends Screen {

    /** Grid cell edge: a 16px icon plus breathing room, and space for the amount line below. */
    private static final int CELL_W = 22;
    private static final int CELL_H = 28;
    private static final int RECENT_MAX = 8;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentW;
    private int listTop;
    private int listBottom;
    private int columns;

    private EditBox search;
    private List<StorageIndex.Entry> results = List.of();
    private int scrollRows;
    private int scrollMax;

    public StorageSearchScreen() {
        super(Component.literal("Item Search"));
    }

    /**
     * The single entry point for opening the item search – used by both the in-world hotkey and the
     * settings button, so "Open Item Search" can never be a silent no-op.
     *
     * <p>The search is only useful once storages are indexed, which only happens in "Full UI" mode.
     * So this raises the Skyblock Menu to Full UI whenever it is lower before showing the screen:
     * Full UI is a strict superset of the preview modes ({@code StoragePreviewMode} only ever adds
     * behaviour going up), and the player just asked for the search, so turning on what powers it is
     * exactly their intent – rather than opening onto a screen that can never fill.
     */
    public static void open() {
        SBSConfig config = ConfigManager.getInstance().get();
        if (!config.skyblockMenu.previewMode.indexes()) {
            config.skyblockMenu.previewMode = sbs.modid.client.helper.storage.StoragePreviewMode.FULL_UI;
            ConfigManager.getInstance().save();
        }
        Minecraft.getInstance().setScreenAndShow(new StorageSearchScreen());
    }

    private static SBSConfig.StorageSearchSettings cfg() {
        return ConfigManager.getInstance().get().storageSearch;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 420, 660);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 260, 460);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;
        columns = Math.max(1, (contentW - 6) / CELL_W);

        int topY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int sortW = 110;
        int textH = this.font.lineHeight;

        listTop = topY + SBSTheme.SEARCH_HEIGHT + 6;
        listBottom = panelY + panelH - pad - font.lineHeight - 4;

        // Panel first so the widgets added after it (sort button, search text) paint on top.
        addRenderableOnly(new PanelRenderable());

        // Sort selector top-left; cycles through the Sort enum.
        addRenderableWidget(new SciFiCycleButton(innerX, topY, sortW, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Sort"),
                () -> Component.literal(sort().displayName()),
                this::cycleSort));

        search = new EditBox(this.font, innerX + sortW + 12 + 6, topY + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                contentW - sortW - 24, textH, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(SBSTheme.TEXT);
        search.setHint(Component.literal("Search..."));
        search.setResponder(query -> refresh());
        addRenderableWidget(search);
        setInitialFocus(search);

        refresh();
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private StorageIndex.Sort sort() {
        return StorageIndex.Sort.byName(cfg().sort);
    }

    private void cycleSort() {
        cfg().sort = sort().next().name();
        save();
        refresh();
    }

    /** Re-runs the query and re-sorts, pinning favourites to the top. */
    private void refresh() {
        String query = search == null ? "" : search.getValue();
        List<StorageIndex.Entry> found =
                new ArrayList<>(StorageIndex.getInstance().search(query, sort()));
        // Favourites first, each group keeping the chosen sort order.
        List<String> favorites = cfg().favorites;
        found.sort((a, b) -> Boolean.compare(favorites.contains(b.key()), favorites.contains(a.key())));
        results = found;
        scrollRows = 0;
    }

    private boolean isFavorite(StorageIndex.Entry entry) {
        return cfg().favorites.contains(entry.key());
    }

    private void toggleFavorite(StorageIndex.Entry entry) {
        List<String> favorites = cfg().favorites;
        if (!favorites.remove(entry.key())) {
            favorites.add(entry.key());
        }
        save();
        refresh();
    }

    /** Records a query in the recent list (most recent first, deduped, capped). */
    private void recordRecent(String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        List<String> recent = cfg().recentSearches;
        recent.removeIf(q -> q.equalsIgnoreCase(query));
        recent.add(0, query);
        while (recent.size() > RECENT_MAX) {
            recent.remove(recent.size() - 1);
        }
        save();
    }

    /** Runs the open command of an item's biggest location, if that storage has one. */
    private void openLocation(StorageIndex.Entry entry) {
        for (StorageIndex.Located located : entry.locations()) {
            StorageSource source = located.source();
            if (source.canOpen()) {
                recordRecent(search.getValue());
                Minecraft.getInstance().player.connection.sendCommand(source.openCommand());
                onClose();
                return;
            }
        }
    }

    /** The entry under the given mouse position, or {@code null}. */
    private StorageIndex.Entry entryAt(double mx, double my) {
        if (mx < innerX || mx >= innerX + columns * CELL_W
                || my < listTop || my >= listBottom) {
            return null;
        }
        int col = (int) ((mx - innerX) / CELL_W);
        int row = scrollRows + (int) ((my - listTop) / CELL_H);
        int index = row * columns + col;
        return index >= 0 && index < results.size() ? results.get(index) : null;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        // Recent-search chips along the bottom.
        List<String> recent = cfg().recentSearches;
        int ry = listBottom + 2;
        if (event.y() >= ry && event.y() <= ry + font.lineHeight) {
            int x = innerX;
            for (String query : recent) {
                int w = font.width(query) + 8;
                if (event.x() >= x && event.x() < x + w) {
                    search.setValue(query);
                    refresh();
                    return true;
                }
                x += w + 4;
                if (x > innerX + contentW) {
                    break;
                }
            }
        }
        StorageIndex.Entry entry = entryAt(event.x(), event.y());
        if (entry == null) {
            return false;
        }
        if (event.button() == 1) {
            toggleFavorite(entry);
        } else {
            openLocation(entry);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // A hovered, screen-overflowing item tooltip scrolls its own lore first (plain Screen, not
        // covered by the container-side tooltip scroll routing).
        if (sbs.modid.client.helper.tooltip.ScrollableTooltips.getInstance().onMouseScroll(scrollY)) {
            return true;
        }
        if (scrollY != 0) {
            scrollRows = clamp(scrollRows - (int) Math.signum(scrollY), 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Enter remembers the query; the configured open key closes the screen again (toggle).
        if (event.key() == 257 || event.key() == 335) {
            recordRecent(search.getValue());
            return true;
        }
        if (event.key() == cfg().openKey && !search.isFocused()) {
            onClose();
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

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = StorageSearchScreen.this.font;
            g.fill(0, 0, StorageSearchScreen.this.width, StorageSearchScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Item Search"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            // Search field chrome (right of the sort selector).
            int topY = dividerY + SBSTheme.GAP_AFTER_HEADER;
            SciFiRender.roundedRectWithBorder(g, innerX + 110 + 12, topY, contentW - 110 - 12,
                    SBSTheme.SEARCH_HEIGHT, SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    search.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            drawGrid(g, mouseX, mouseY);
            drawFooter(g);
        }

        private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = StorageSearchScreen.this.font;
            if (results.isEmpty()) {
                g.centeredText(font, Component.literal(StorageIndex.getInstance().sourceCount() == 0
                                ? "§7Open your storages once so they can be indexed."
                                : "§7No matching items."),
                        panelX + panelW / 2, listTop + 12, SBSTheme.TEXT_MUTED);
                scrollMax = 0;
                return;
            }
            int visibleRows = Math.max(1, (listBottom - listTop) / CELL_H);
            int totalRows = (results.size() + columns - 1) / columns;
            scrollMax = Math.max(0, totalRows - visibleRows);
            scrollRows = clamp(scrollRows, 0, scrollMax);

            StorageIndex.Entry hovered = null;
            int start = scrollRows * columns;
            int end = Math.min(results.size(), start + visibleRows * columns);
            for (int i = start; i < end; i++) {
                int cellIndex = i - start;
                int x = innerX + (cellIndex % columns) * CELL_W;
                int y = listTop + (cellIndex / columns) * CELL_H;
                StorageIndex.Entry entry = results.get(i);
                boolean hover = mouseX >= x && mouseX < x + CELL_W && mouseY >= y && mouseY < y + CELL_H;
                if (hover) {
                    hovered = entry;
                }
                drawCell(g, entry, x, y, hover);
            }

            if (totalRows > visibleRows) {
                int trackH = visibleRows * CELL_H;
                g.fill(innerX + contentW - 3, listTop, innerX + contentW, listTop + trackH,
                        SBSTheme.CARD_BG_DISABLED);
                int thumbH = Math.max(8, trackH * visibleRows / totalRows);
                int thumbY = listTop + (int) ((long) (trackH - thumbH) * scrollRows / Math.max(1, scrollMax));
                g.fill(innerX + contentW - 3, thumbY, innerX + contentW, thumbY + thumbH, SBSTheme.ACCENT);
            }
            if (hovered != null) {
                g.setTooltipForNextFrame(font, tooltip(hovered), java.util.Optional.empty(),
                        mouseX, mouseY, SBSTheme.tooltipStyle());
            }
        }

        /** One grid cell: icon on a card, the abbreviated amount underneath, gold frame = favourite. */
        private void drawCell(GuiGraphicsExtractor g, StorageIndex.Entry entry, int x, int y,
                              boolean hover) {
            var font = StorageSearchScreen.this.font;
            boolean favorite = isFavorite(entry);
            SciFiRender.roundedRectWithBorder(g, x, y, CELL_W - 2, CELL_H - 2, 2,
                    hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    favorite ? 0xFFFFD64D : hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            g.item(entry.icon(), x + (CELL_W - 2 - 16) / 2, y + 1);
            String amount = fmt(entry.total());
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(x + (CELL_W - 2) / 2f, y + CELL_H - 9);
            pose.scale(0.75f, 0.75f);
            g.centeredText(font, Component.literal(amount), 0, 0,
                    favorite ? 0xFFFFD64D : SBSTheme.TEXT);
            pose.popMatrix();
        }

        private List<Component> tooltip(StorageIndex.Entry entry) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("§f" + entry.displayName()));
            tip.add(Component.literal("§7Total: §b"
                    + sbs.modid.client.core.util.NumberDisplay.format(entry.total())));
            double value = StorageIndex.totalValue(entry);
            if (value > 0) {
                tip.add(Component.literal("§7Value: §6" + coins(value)));
            }
            tip.add(Component.literal(" "));
            boolean openable = false;
            for (StorageIndex.Located located : entry.locations()) {
                tip.add(Component.literal("§8• §7" + located.source().displayName()
                        + " §f" + sbs.modid.client.core.util.NumberDisplay.format(located.count())));
                openable |= located.source().canOpen();
            }
            tip.add(Component.literal(" "));
            if (openable) {
                tip.add(Component.literal("§8click to open the location"));
            }
            tip.add(Component.literal(isFavorite(entry) ? "§8right-click to unfavorite"
                    : "§8right-click to favorite"));
            return tip;
        }

        /** Recent-search chips plus what the index currently covers. */
        private void drawFooter(GuiGraphicsExtractor g) {
            var font = StorageSearchScreen.this.font;
            int y = listBottom + 2;
            List<String> recent = cfg().recentSearches;
            int x = innerX;
            if (!recent.isEmpty()) {
                for (String query : recent) {
                    int w = font.width(query) + 8;
                    if (x + w > innerX + contentW - 90) {
                        break;
                    }
                    SciFiRender.roundedRect(g, x, y - 1, w, font.lineHeight + 2, 2, SBSTheme.CARD_BG);
                    g.text(font, Component.literal("§7" + query), x + 4, y, SBSTheme.TEXT_MUTED);
                    x += w + 4;
                }
            }
            String status = "§8" + StorageIndex.getInstance().sourceCount() + " storages indexed";
            g.text(font, Component.literal(status), innerX + contentW - font.width(status), y,
                    SBSTheme.TEXT_MUTED);
        }
    }

    /**
     * 23400 -> "23.4K", 1200000 -> "1.2M" - the amount badge under each icon. Always short,
     * whatever "Shorten Numbers" says: it is drawn inside a 16px slot, where a grouped figure has
     * nowhere to go.
     */
    private static String fmt(int value) {
        return sbs.modid.client.core.util.NumberDisplay.shorten(value);
    }

    /** Coin value, abbreviated like the fishing HUD. */
    private static String coins(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
