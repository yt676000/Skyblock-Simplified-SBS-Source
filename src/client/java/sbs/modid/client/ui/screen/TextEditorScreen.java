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
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.visual.ui.VisualsScreen;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.helper.visual.model.TextReplacement;

import java.util.List;

/**
 * The Text Editor sub-screen of the Visuals module (Command-Keybinds style): two input fields
 * ("Text" → "Replace with") plus Add, a global On/Off toggle, and one row per rule with its own
 * ON/OFF pill and an X delete button. Rules apply everywhere text is displayed (item names,
 * tooltips, chat).
 *
 * <p>The rule list scrolls: only {@link #visibleRows} rows exist as widgets at a time and the wheel
 * moves the window over the list. Before this, rules past the bottom of the panel were simply not
 * built, so a long list could only be edited by hand in {@code config.json}.
 */
public final class TextEditorScreen extends Screen {

    private static final int SCROLLBAR_WIDTH = 4;
    private static final int SCROLLBAR_SPACE = SCROLLBAR_WIDTH + 2;
    private static final int GAP = 4;
    private static final int DELETE_W = 18;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int toggleY;
    private int inputRowY;
    private int fieldW;
    private int tableTop;
    private int tableHeight;
    private int backY;
    private int stride;
    private int visibleRows;
    private int scrollIndex;

    private EditBox fromBox;
    private EditBox toBox;

    public TextEditorScreen() {
        super(Component.literal("Text Editor"));
    }

    private static SBSConfig.VisualsSettings settings() {
        return ConfigManager.getInstance().get().visuals;
    }

    private static List<TextReplacement> rules() {
        return settings().textReplacements;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, SBSTheme.PANEL_MIN_WIDTH, SBSTheme.PANEL_MAX_WIDTH);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, SBSTheme.PANEL_MIN_HEIGHT, SBSTheme.PANEL_MAX_HEIGHT);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = panelW - pad * 2;

        stride = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
        toggleY = dividerY + SBSTheme.GAP_AFTER_HEADER + this.font.lineHeight + SBSTheme.GAP_AFTER_SEARCH;
        inputRowY = toggleY + stride;
        backY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        tableTop = inputRowY + SBSTheme.ENTRY_HEIGHT + SBSTheme.GAP_AFTER_SEARCH;
        tableHeight = Math.max(SBSTheme.ENTRY_HEIGHT, backY - SBSTheme.GAP_AFTER_SEARCH - tableTop);
        visibleRows = Math.max(1, (tableHeight + SBSTheme.ENTRY_SPACING) / stride);

        int addW = 40;
        fieldW = (contentWidth - addW - GAP * 2) / 2;

        rebuild("", "");
    }

    /** Rebuilds every widget, restoring what was typed into the two input fields. */
    private void rebuild(String keepFrom, String keepTo) {
        clearWidgets();
        clampScroll();
        addRenderableOnly(new PanelRenderable());

        // Global master toggle.
        addRenderableWidget(new SciFiToggleButton(innerX, toggleY, contentWidth, SBSTheme.ENTRY_HEIGHT,
                Component.literal("Text Editor"),
                () -> settings().textEditorEnabled,
                this::toggleEnabled));

        // Input row: [from] [to] [Add]
        int addW = 40;
        int textH = this.font.lineHeight;
        int editY = inputRowY + (SBSTheme.ENTRY_HEIGHT - textH) / 2;
        fromBox = new EditBox(this.font, innerX + 5, editY, fieldW - 10, textH, Component.literal("Text"));
        fromBox.setBordered(false);
        fromBox.setMaxLength(100);
        fromBox.setTextColor(SBSTheme.TEXT);
        fromBox.setHint(Component.literal("Text..."));
        fromBox.setValue(keepFrom);
        addRenderableWidget(fromBox);
        toBox = new EditBox(this.font, innerX + fieldW + GAP + 5, editY, fieldW - 10, textH,
                Component.literal("Replace with"));
        toBox.setBordered(false);
        toBox.setMaxLength(100);
        toBox.setTextColor(SBSTheme.TEXT);
        toBox.setHint(Component.literal("Replace with..."));
        toBox.setValue(keepTo);
        addRenderableWidget(toBox);
        addRenderableWidget(new SciFiButton(innerX + contentWidth - addW, inputRowY, addW, SBSTheme.ENTRY_HEIGHT,
                Component.literal("Add"), this::addRule));

        // One row per visible rule: [from -> to  ON/OFF pill] [X]
        List<TextReplacement> list = rules();
        int rowW = contentWidth - (hasScrollbar() ? SCROLLBAR_SPACE : 0);
        int toggleW = rowW - DELETE_W - GAP;
        int deleteX = innerX + rowW - DELETE_W;
        int last = Math.min(list.size(), scrollIndex + visibleRows);
        for (int i = scrollIndex; i < last; i++) {
            final TextReplacement rule = list.get(i);
            int rowY = tableTop + (i - scrollIndex) * stride;
            String label = trim("\"" + rule.from + "\" -> \"" + rule.to + "\"", toggleW - 30);
            addRenderableWidget(new SciFiToggleButton(innerX, rowY, toggleW, SBSTheme.ENTRY_HEIGHT,
                    Component.literal(label),
                    () -> rule.enabled,
                    () -> {
                        rule.enabled = !rule.enabled;
                        ConfigManager.getInstance().save();
                    }));
            addRenderableWidget(new SciFiButton(deleteX, rowY, DELETE_W, SBSTheme.ENTRY_HEIGHT,
                    Component.literal("X"), () -> removeRule(rule)));
        }

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    private void toggleEnabled() {
        settings().textEditorEnabled = !settings().textEditorEnabled;
        ConfigManager.getInstance().save();
    }

    private void addRule() {
        String from = fromBox.getValue().trim();
        if (from.isEmpty()) {
            return;
        }
        rules().add(new TextReplacement(from, toBox.getValue()));
        ConfigManager.getInstance().save();
        scrollIndex = maxScroll();   // the new rule is at the bottom: show it
        rebuild("", "");
    }

    /**
     * Removes the rule itself rather than the row's index. The row was built from a scrolled window,
     * and an index captured there stops meaning the same entry the moment the list is scrolled or
     * another rule is deleted.
     */
    private void removeRule(TextReplacement rule) {
        if (rules().remove(rule)) {
            ConfigManager.getInstance().save();
            rebuild(fromBox.getValue(), toBox.getValue());
        }
    }

    private int maxScroll() {
        return Math.max(0, rules().size() - visibleRows);
    }

    private void clampScroll() {
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll()));
    }

    private boolean hasScrollbar() {
        return rules().size() > visibleRows;
    }

    private String trim(String text, int width) {
        if (this.font.width(text) <= width) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(1, width - this.font.width("...")), false) + "...";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() <= 0 || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int next = Math.max(0, Math.min(maxScroll(), scrollIndex + (scrollY > 0 ? -1 : 1)));
        if (next != scrollIndex) {
            scrollIndex = next;
            rebuild(fromBox.getValue(), toBox.getValue());
        }
        return true;
    }

    private void onBack() {
        Minecraft.getInstance().setScreenAndShow(new VisualsScreen());
    }

    /**
     * Draws this screen with the replacement rules switched off.
     *
     * <p>The rules now apply to every drawn string, this screen's own labels included - so a rule
     * like "Text" → something else would rewrite the very row you turn it off with, and a careless
     * one could leave no readable way back out. The editor shows its rules as they were typed, which
     * means standing down every displayed-text feature and not only this one: a rule naming a player
     * would otherwise come back redacted by Streamer Mode and be just as unreadable.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        sbs.modid.client.core.util.DisplayedText.runRaw(
                () -> super.extractRenderState(g, mouseX, mouseY, partialTick));
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
            var font = TextEditorScreen.this.font;

            g.fill(0, 0, TextEditorScreen.this.width, TextEditorScreen.this.height, SBSTheme.BG_TINT);

            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Text Editor"), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            g.fill(panelX + pad, dividerY, panelX + pad + 10, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + panelW - pad - 10, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT_BRIGHT);

            int total = rules().size();
            String heading = total == 0 ? "Replacements"
                    : "Replacements  §8" + (scrollIndex + 1) + "-"
                            + Math.min(total, scrollIndex + visibleRows) + " of " + total;
            g.text(font, Component.literal(heading), innerX,
                    dividerY + SBSTheme.GAP_AFTER_HEADER, SBSTheme.TEXT_MUTED);

            // Backgrounds for the two borderless edit boxes.
            SciFiRender.roundedRectWithBorder(g, innerX, inputRowY, fieldW, SBSTheme.ENTRY_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    fromBox != null && fromBox.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            SciFiRender.roundedRectWithBorder(g, innerX + fieldW + GAP, inputRowY, fieldW, SBSTheme.ENTRY_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    toBox != null && toBox.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            if (total == 0) {
                g.centeredText(font, Component.literal("No replacements yet – type a text above and press Add"),
                        panelX + panelW / 2, tableTop + tableHeight / 2 - font.lineHeight / 2,
                        SBSTheme.TEXT_MUTED);
            }

            if (hasScrollbar()) {
                int sbX = innerX + contentWidth - SCROLLBAR_WIDTH;
                SciFiRender.roundedRect(g, sbX, tableTop, SCROLLBAR_WIDTH, tableHeight,
                        SCROLLBAR_WIDTH / 2, 0x22FFFFFF);
                int thumbH = Math.max(16, (int) ((long) tableHeight * visibleRows / total));
                int travel = tableHeight - thumbH;
                int max = maxScroll();
                int thumbY = tableTop + (max <= 0 ? 0 : (int) ((long) scrollIndex * travel / max));
                SciFiRender.roundedRect(g, sbX, thumbY, SCROLLBAR_WIDTH, thumbH,
                        SCROLLBAR_WIDTH / 2, SBSTheme.ACCENT);
            }
        }
    }
}
