/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor for the IRC whitelist/blacklist names (Chat Options module): the same panel + table
 * mechanics as the Command Keybinds screen, but with a single editable name column per row plus a
 * delete button. Whether the list acts as whitelist or blacklist is chosen by the "IRC Filter"
 * setting; this screen only maintains the names. Edits persist on Back / close.
 */
public final class IrcNamesScreen extends Screen {

    private static final int SCROLLBAR_SPACE = 8;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int controlHeight;
    private int addNewY;
    private int backY;
    private int tableTop;
    private int tableHeight;
    private int rowHeight;
    private int stride;
    private int visibleRows;
    private int scrollIndex;

    public IrcNamesScreen() {
        super(Component.literal("IRC Names"));
    }

    private List<String> names() {
        List<String> list = ConfigManager.getInstance().get().chatOptions.ircNames;
        if (list == null) {
            list = new ArrayList<>();
            ConfigManager.getInstance().get().chatOptions.ircNames = list;
        }
        return list;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, SBSTheme.PANEL_MIN_WIDTH, SBSTheme.PANEL_MAX_WIDTH);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, SBSTheme.PANEL_MIN_HEIGHT, SBSTheme.PANEL_MAX_HEIGHT);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;

        innerX = panelX + SBSTheme.PANEL_PADDING;
        contentWidth = panelW - SBSTheme.PANEL_PADDING * 2;

        controlHeight = SBSTheme.SEARCH_HEIGHT;
        addNewY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        backY = panelY + panelH - SBSTheme.PANEL_PADDING - controlHeight;

        tableTop = addNewY + controlHeight + SBSTheme.GAP_AFTER_SEARCH;
        int tableBottom = backY - SBSTheme.GAP_AFTER_SEARCH;
        tableHeight = Math.max(SBSTheme.ENTRY_HEIGHT, tableBottom - tableTop);

        rowHeight = SBSTheme.ENTRY_HEIGHT;
        stride = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
        visibleRows = Math.max(1, (tableHeight + SBSTheme.ENTRY_SPACING) / stride);

        rebuild();
    }

    /** Clears and re-adds every widget for the current scroll position. */
    private void rebuild() {
        clearWidgets();
        clampScroll();

        addRenderableOnly(new PanelRenderable());

        addRenderableWidget(new SciFiButton(innerX, addNewY, contentWidth, controlHeight,
                Component.literal("+  Add Name"), this::onAddNew));

        List<String> names = names();
        int rowW = rowWidth();
        int deleteW = rowHeight;
        int deleteX = innerX + rowW - deleteW;
        int nameW = rowW - deleteW - 6;

        int last = Math.min(names.size(), scrollIndex + visibleRows);
        for (int i = scrollIndex; i < last; i++) {
            final int index = i;
            int rowY = tableTop + (i - scrollIndex) * stride;

            int textHeight = this.font.lineHeight;
            int editY = rowY + (rowHeight - textHeight) / 2;
            EditBox nameBox = new EditBox(this.font, innerX + 6, editY,
                    nameW - 10, textHeight, Component.literal("Name"));
            nameBox.setBordered(false);
            nameBox.setMaxLength(16);
            nameBox.setTextColor(SBSTheme.TEXT);
            nameBox.setHint(Component.literal("player name"));
            nameBox.setValue(names.get(index));
            nameBox.setResponder(value -> {
                List<String> current = names();
                if (index < current.size()) {
                    current.set(index, value.trim());
                }
            });
            addRenderableWidget(nameBox);

            addRenderableWidget(new SciFiButton(deleteX, rowY, deleteW, rowHeight,
                    Component.literal("X"), () -> onDelete(index)));
        }

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, controlHeight,
                Component.literal("Back"), this::onBack));
    }

    private int maxScroll() {
        return Math.max(0, names().size() - visibleRows);
    }

    private void clampScroll() {
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll()));
    }

    private boolean hasScrollbar() {
        return names().size() > visibleRows;
    }

    private int rowWidth() {
        return contentWidth - (hasScrollbar() ? SCROLLBAR_SPACE : 0);
    }

    private void onAddNew() {
        names().add("");
        scrollIndex = maxScroll();
        rebuild();
    }

    private void onDelete(int index) {
        List<String> names = names();
        if (index < names.size()) {
            names.remove(index);
        }
        save();
        rebuild();
    }

    private void onBack() {
        save();
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
    }

    /** Drops empty rows and persists. */
    private void save() {
        names().removeIf(name -> name == null || name.isBlank());
        ConfigManager.getInstance().save();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() <= 0 || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int next = scrollIndex + (scrollY > 0 ? -1 : 1);
        next = Math.max(0, Math.min(maxScroll(), next));
        if (next != scrollIndex) {
            scrollIndex = next;
            rebuild();
        }
        return true;
    }

    @Override
    public void removed() {
        save();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Panel, header and name-cell backgrounds, drawn behind the widgets. */
    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = IrcNamesScreen.this.font;

            g.fill(0, 0, IrcNamesScreen.this.width, IrcNamesScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("IRC Whitelist / Blacklist Names"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            List<String> names = names();
            if (names.isEmpty()) {
                g.centeredText(font, Component.literal("No names yet - click \"Add Name\""),
                        panelX + panelW / 2, tableTop + tableHeight / 2 - font.lineHeight / 2,
                        SBSTheme.TEXT_MUTED);
            } else {
                int rowW = rowWidth();
                int nameW = rowW - rowHeight - 6;
                int last = Math.min(names.size(), scrollIndex + visibleRows);
                for (int i = scrollIndex; i < last; i++) {
                    int rowY = tableTop + (i - scrollIndex) * stride;
                    SciFiRender.roundedRectWithBorder(g, innerX, rowY, nameW, rowHeight,
                            SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, SBSTheme.CARD_BORDER);
                }
            }

            if (hasScrollbar()) {
                int trackX = panelX + panelW - SBSTheme.PANEL_PADDING - 4;
                int trackH = tableHeight;
                float ratio = visibleRows / (float) Math.max(1, names.size());
                int thumbH = Math.max(12, (int) (trackH * ratio));
                int range = Math.max(1, maxScroll());
                int thumbY = tableTop + (int) ((trackH - thumbH) * (scrollIndex / (float) range));
                g.fill(trackX, tableTop, trackX + 3, tableTop + trackH, SBSTheme.CARD_BORDER);
                g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, SBSTheme.ACCENT);
            }
        }
    }
}
