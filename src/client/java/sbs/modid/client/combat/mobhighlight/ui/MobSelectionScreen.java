/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.mobhighlight.ui;

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
import sbs.modid.client.helper.storage.StorageSearchScreen;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.combat.mobhighlight.model.SkyblockMobCatalog;
import sbs.modid.client.combat.mobhighlight.model.SkyblockMobCatalog.SkyblockMob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The searchable multi-select for the Mob Highlight module: every SkyBlock mob from the wiki
 * catalogue in one scrollable, filterable, checkbox list. Ticking a row adds the mob's name
 * ("Lapis Zombie") to {@link SBSConfig.MobHighlightSettings#selectedMobs}; the change is saved
 * immediately, so the selection is already persisted for the next game start by the time the screen
 * closes. The search box matches both the mob name and its area, so typing an area ("Crimson",
 * "Spider's Den") narrows the list to that place's mobs.
 *
 * <p>Modelled on {@code StorageSearchScreen}: search box on top (live filter on every keystroke),
 * scrollable list in the middle with its own scrollbar, a footer showing how many mobs are ticked
 * and clear/all shortcuts. This is the "own solution" the task asks for – the vanilla widget set has
 * no searchable multi-select, so the list, filter and checkboxes are drawn here directly.
 */
public final class MobSelectionScreen extends Screen {

    private static final int ROW_H = 18;

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentW;
    private int listTop;
    private int listBottom;

    private EditBox search;
    private List<SkyblockMob> results = List.of();
    private int scroll;
    private int scrollMax;

    /** The list's scrollbar - the shared one, so it can actually be dragged. */
    private final SciFiScrollbar bar = new SciFiScrollbar();

    /** The synthetic "add the typed name as a custom mob" row, or null when not applicable. */
    private SkyblockMob addEntry;

    /**
     * @param parent the screen to return to on close (the settings screen), so the module page is
     *               restored exactly as the player left it.
     */
    public MobSelectionScreen(Screen parent) {
        super(Component.literal("Select Mobs"));
        this.parent = parent;
    }

    private static SBSConfig.MobHighlightSettings cfg() {
        return ConfigManager.getInstance().get().mobHighlight;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 380, 560);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 260, 460);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;

        int topY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int btnW = 70;
        int textH = this.font.lineHeight;

        listTop = topY + SBSTheme.SEARCH_HEIGHT + 6;
        listBottom = panelY + panelH - pad - font.lineHeight - 4;

        // The panel (background, search-field chrome, the list) renders FIRST, so the widgets added
        // after it paint ON TOP - otherwise the 94%-opaque search-field fill covers the EditBox text
        // and typing looks like it does nothing (the query still updates, so search worked blind).
        addRenderableOnly(new PanelRenderable());

        search = new EditBox(this.font, innerX + 6, topY + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                contentW - btnW * 2 - 24, textH, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(SBSTheme.TEXT);
        search.setHint(Component.literal("Search mobs..."));
        search.setResponder(query -> refresh());
        addRenderableWidget(search);
        setInitialFocus(search);

        // Bulk actions: tick everything currently shown by the filter, or clear the whole selection.
        addRenderableWidget(new SciFiButton(innerX + contentW - btnW * 2 - 6, topY, btnW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Select All"), this::selectAllShown));
        addRenderableWidget(new SciFiButton(innerX + contentW - btnW, topY, btnW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Clear"), this::clearAll));

        refresh();
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private void refresh() {
        String q = search == null ? "" : search.getValue().trim();
        String needle = q.toLowerCase(Locale.ROOT);
        List<SkyblockMob> list = new ArrayList<>();
        // Custom (non-catalogue) names the player already added stay visible and removable.
        for (String selected : cfg().selectedMobs) {
            if (!SkyblockMobCatalog.isKnown(selected)
                    && (needle.isEmpty() || selected.toLowerCase(Locale.ROOT).contains(needle))) {
                list.add(new SkyblockMob(selected, "Custom"));
            }
        }
        list.addAll(SkyblockMobCatalog.search(q));
        // Offer to add the typed text as a brand-new custom mob - any nametag'd Hypixel mob can be
        // highlighted by its exact name, even one the wiki catalogue does not list.
        addEntry = null;
        if (!q.isEmpty() && !SkyblockMobCatalog.isKnown(q) && !cfg().selectedMobs.contains(q)) {
            addEntry = new SkyblockMob(q, "+ Add as custom mob");
            list.add(0, addEntry);
        }
        results = list;
        scroll = 0;
    }

    private boolean isSelected(SkyblockMob mob) {
        return cfg().selectedMobs.contains(mob.name());
    }

    private void toggle(SkyblockMob mob) {
        var selected = cfg().selectedMobs;
        if (mob == addEntry) {
            selected.add(mob.name());   // commit the freshly typed custom name
            save();
            refresh();                  // it becomes a normal "Custom" row now
            return;
        }
        if (!selected.remove(mob.name())) {
            selected.add(mob.name());
        }
        save();
    }

    /** Ticks every mob the current filter shows (leaving the rest of the selection untouched). */
    private void selectAllShown() {
        for (SkyblockMob mob : results) {
            cfg().selectedMobs.add(mob.name());
        }
        save();
    }

    private void clearAll() {
        cfg().selectedMobs.clear();
        save();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        // The bar before the rows: a click on it must not also tick the entry behind it.
        if (bar.handleClick(event.x(), event.y(), scroll, value -> scroll = value)) {
            return true;
        }
        if (event.x() < innerX || event.x() > innerX + contentW
                || event.y() < listTop || event.y() >= listBottom) {
            return false;
        }
        int row = scroll + (int) ((event.y() - listTop) / ROW_H);
        if (row < 0 || row >= results.size()) {
            return false;
        }
        toggle(results.get(row));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (bar.handleDrag(event.y(), value -> scroll = value)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return bar.release() || super.mouseReleased(event);
    }

    /** Feeds the shared scrollbar the list's track box, from what the render pass measured. */
    private void syncBar(int total, int visible) {
        bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, visible * ROW_H, total, visible);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Escape closes back to the settings page even while the search box is focused.
        if (event.key() == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
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
            var font = MobSelectionScreen.this.font;
            g.fill(0, 0, MobSelectionScreen.this.width, MobSelectionScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Select Mobs to Highlight"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            int topY = dividerY + SBSTheme.GAP_AFTER_HEADER;
            SciFiRender.roundedRectWithBorder(g, innerX, topY, contentW - 70 * 2 - 12, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    search.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            drawList(g, mouseX, mouseY);
            drawFooter(g);
        }

        private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = MobSelectionScreen.this.font;
            if (results.isEmpty()) {
                g.centeredText(font, Component.literal("§7No matching mobs."),
                        panelX + panelW / 2, listTop + 12, SBSTheme.TEXT_MUTED);
                scrollMax = 0;
                return;
            }
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            scrollMax = Math.max(0, results.size() - visible);
            scroll = clamp(scroll, 0, scrollMax);

            boolean scrollable = results.size() > visible;
            int rowW = contentW - (scrollable ? SciFiScrollbar.WIDTH + 3 : 0);
            int y = listTop;
            for (int i = scroll; i < results.size() && i < scroll + visible; i++) {
                drawRow(g, results.get(i), innerX, y, rowW, mouseX, mouseY);
                y += ROW_H;
            }
            syncBar(results.size(), visible);
            bar.render(g, scroll, mouseX, mouseY);
        }

        /** One mob row: a checkbox, the mob name, and its muted area. */
        private void drawRow(GuiGraphicsExtractor g, SkyblockMob mob, int x, int y, int w,
                             int mouseX, int mouseY) {
            var font = MobSelectionScreen.this.font;
            int h = ROW_H - 2;
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                    hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);

            int textY = y + (h - font.lineHeight) / 2;
            boolean isAdd = mob == addEntry;
            boolean selected = isSelected(mob);

            // Checkbox: a green "+" for the add-row, a check when ticked, a hollow border otherwise.
            int boxSize = 9;
            int boxX = x + 4;
            int boxY = y + (h - boxSize) / 2;
            SciFiRender.roundedRectWithBorder(g, boxX, boxY, boxSize, boxSize, 2,
                    selected ? SBSTheme.ACCENT : SBSTheme.CARD_BG,
                    isAdd || selected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            if (isAdd) {
                g.text(font, Component.literal("§a+"), boxX + 1, boxY - 1, SBSTheme.TEXT);
            } else if (selected) {
                g.text(font, Component.literal("§f✔"), boxX + 1, boxY - 1, SBSTheme.TEXT);
            }

            int nameX = boxX + boxSize + 5;
            g.text(font, Component.literal((isAdd ? "§a" : selected ? "§f" : "§7") + mob.name()), nameX,
                    textY, SBSTheme.TEXT);

            String area = "§8" + mob.area();
            int areaW = font.width(area);
            if (nameX + font.width(mob.name()) + 8 + areaW < x + w - 4) {
                g.text(font, Component.literal(area), x + w - 4 - areaW, textY, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawFooter(GuiGraphicsExtractor g) {
            var font = MobSelectionScreen.this.font;
            int y = listBottom + 2;
            String status = "§7" + cfg().selectedMobs.size() + " selected §8· "
                    + SkyblockMobCatalog.all().size() + " total";
            g.text(font, Component.literal(status), innerX, y, SBSTheme.TEXT_MUTED);
            String hint = "§8click a row to toggle · Esc to go back";
            g.text(font, Component.literal(hint), innerX + contentW - font.width(hint), y, SBSTheme.TEXT_MUTED);
        }
    }
}
