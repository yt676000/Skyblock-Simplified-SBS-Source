/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.streamer.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.DisplayedText;
import sbs.modid.client.helper.streamer.logic.StreamerNames;
import sbs.modid.client.helper.streamer.model.PlayerAlias;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * Streamer Mode's per-player name list: two fields ("Player" → "Shown as") plus Add, and one row per
 * entry with its own ON/OFF pill and an X. An entry here wins over the blanket "Other Players"
 * setting, so a lobby can be blanked while the people you are playing with stay readable.
 *
 * <p><b>This screen draws itself with the whole pipeline off</b> ({@link DisplayedText#runRaw}).
 * Everywhere else, Streamer Mode redacting its own UI is exactly right; here it would be a trap -
 * the list of names you are hiding is the one place those names have to be legible, and a screen
 * that hid them would leave no way to see what you had set or to correct a typo.
 *
 * <p>The list scrolls: only {@link #visibleRows} rows exist as widgets at a time and the wheel moves
 * the window over the list.
 */
public final class StreamerAliasScreen extends Screen {

    private static final int SCROLLBAR_WIDTH = 4;
    private static final int SCROLLBAR_SPACE = SCROLLBAR_WIDTH + 2;
    private static final int GAP = 4;
    private static final int DELETE_W = 18;
    private static final int ADD_W = 40;

    /** Minecraft names are at most 16 characters; what they are shown as may be a little longer. */
    private static final int MAX_NAME = 16;
    private static final int MAX_ALIAS = 32;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int inputRowY;
    private int fieldW;
    private int tableTop;
    private int tableHeight;
    private int backY;
    private int stride;
    private int visibleRows;
    private int scrollIndex;

    private EditBox nameBox;
    private EditBox aliasBox;

    public StreamerAliasScreen() {
        super(Component.literal("Custom Player Names"));
    }

    private static SBSConfig.StreamerSettings settings() {
        return ConfigManager.getInstance().get().streamer;
    }

    private static List<PlayerAlias> aliases() {
        return settings().aliases;
    }

    /** Saves and drops the compiled redactions so an edit shows on the very next frame. */
    private static void save() {
        ConfigManager.getInstance().save();
        StreamerNames.getInstance().invalidate();
    }

    @Override
    protected void init() {
        // Sized from the viewport rather than up to a fixed minimum: on a narrow screen at a large
        // GUI scale, clamping to a minimum width is what hangs a panel off both edges.
        int availableW = Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2);
        int availableH = Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2);
        panelW = Math.min(availableW, SBSTheme.PANEL_MAX_WIDTH);
        panelH = Math.min(availableH, SBSTheme.PANEL_MAX_HEIGHT);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = Math.max(1, panelW - pad * 2);

        stride = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
        inputRowY = dividerY + SBSTheme.GAP_AFTER_HEADER + this.font.lineHeight
                + SBSTheme.GAP_AFTER_SEARCH;
        backY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        tableTop = inputRowY + SBSTheme.ENTRY_HEIGHT + SBSTheme.GAP_AFTER_SEARCH;
        tableHeight = Math.max(SBSTheme.ENTRY_HEIGHT, backY - SBSTheme.GAP_AFTER_SEARCH - tableTop);
        visibleRows = Math.max(1, (tableHeight + SBSTheme.ENTRY_SPACING) / stride);

        // The Add button keeps its width; the two fields share whatever is left, so the row still
        // fits when the panel is only as wide as a small viewport allows.
        fieldW = Math.max(1, (contentWidth - ADD_W - GAP * 2) / 2);

        rebuild("", "");
    }

    /** Rebuilds every widget, restoring what was typed into the two input fields. */
    private void rebuild(String keepName, String keepAlias) {
        clearWidgets();
        clampScroll();
        addRenderableOnly(new PanelRenderable());

        int textH = this.font.lineHeight;
        int editY = inputRowY + (SBSTheme.ENTRY_HEIGHT - textH) / 2;
        nameBox = new EditBox(this.font, innerX + 5, editY, Math.max(1, fieldW - 10), textH,
                Component.literal("Player"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(MAX_NAME);
        nameBox.setTextColor(SBSTheme.TEXT);
        nameBox.setHint(Component.literal("Player..."));
        nameBox.setValue(keepName);
        addRenderableWidget(nameBox);

        aliasBox = new EditBox(this.font, innerX + fieldW + GAP + 5, editY, Math.max(1, fieldW - 10),
                textH, Component.literal("Shown as"));
        aliasBox.setBordered(false);
        aliasBox.setMaxLength(MAX_ALIAS);
        aliasBox.setTextColor(SBSTheme.TEXT);
        aliasBox.setHint(Component.literal("Shown as..."));
        aliasBox.setValue(keepAlias);
        addRenderableWidget(aliasBox);

        addRenderableWidget(new SciFiButton(innerX + contentWidth - ADD_W, inputRowY, ADD_W,
                SBSTheme.ENTRY_HEIGHT, Component.literal("Add"), this::addAlias));

        List<PlayerAlias> list = aliases();
        int rowW = contentWidth - (hasScrollbar() ? SCROLLBAR_SPACE : 0);
        int toggleW = Math.max(1, rowW - DELETE_W - GAP);
        int deleteX = innerX + rowW - DELETE_W;
        int last = Math.min(list.size(), scrollIndex + visibleRows);
        for (int i = scrollIndex; i < last; i++) {
            final PlayerAlias alias = list.get(i);
            int rowY = tableTop + (i - scrollIndex) * stride;
            String shown = alias.alias == null || alias.alias.isBlank() ? "(blank)" : alias.alias;
            String label = fit(alias.name + " -> " + shown, toggleW - 30);
            addRenderableWidget(new SciFiToggleButton(innerX, rowY, toggleW, SBSTheme.ENTRY_HEIGHT,
                    Component.literal(label),
                    () -> alias.enabled,
                    () -> {
                        alias.enabled = !alias.enabled;
                        save();
                    }));
            addRenderableWidget(new SciFiButton(deleteX, rowY, DELETE_W, SBSTheme.ENTRY_HEIGHT,
                    Component.literal("X"), () -> removeAlias(alias)));
        }

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    /**
     * Adds the typed pair, replacing an entry for the same player rather than stacking a second one
     * on top of it - two rules for one name would leave the player guessing which of them wins.
     */
    private void addAlias() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        String alias = aliasBox.getValue().trim();
        List<PlayerAlias> list = aliases();
        for (PlayerAlias existing : list) {
            if (existing != null && existing.name != null && existing.name.equalsIgnoreCase(name)) {
                existing.alias = alias;
                existing.enabled = true;
                save();
                rebuild("", "");
                return;
            }
        }
        list.add(new PlayerAlias(name, alias));
        save();
        scrollIndex = maxScroll();   // the new entry is at the bottom: show it
        rebuild("", "");
    }

    /**
     * Removes the entry itself rather than the row's index. The row was built from a scrolled window,
     * and an index captured there stops meaning the same entry the moment the list is scrolled or
     * another entry is deleted.
     */
    private void removeAlias(PlayerAlias alias) {
        if (aliases().remove(alias)) {
            save();
            rebuild(nameBox.getValue(), aliasBox.getValue());
        }
    }

    private int maxScroll() {
        return Math.max(0, aliases().size() - visibleRows);
    }

    private void clampScroll() {
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll()));
    }

    private boolean hasScrollbar() {
        return aliases().size() > visibleRows;
    }

    /** {@code text} cut to fit {@code width}, with an ellipsis where it was cut. */
    private String fit(String text, int width) {
        if (width <= 0 || this.font.width(text) <= width) {
            return text;
        }
        return this.font.plainSubstrByWidth(text,
                Math.max(1, width - this.font.width("...")), false) + "...";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() <= 0 || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int next = Math.max(0, Math.min(maxScroll(), scrollIndex + (scrollY > 0 ? -1 : 1)));
        if (next != scrollIndex) {
            scrollIndex = next;
            rebuild(nameBox.getValue(), aliasBox.getValue());
        }
        return true;
    }

    private void onBack() {
        save();
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
    }

    /**
     * Draws this screen with every displayed-text edit switched off - see the class note. Without
     * this the editor would hide the very names it exists to let you set.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        DisplayedText.runRaw(() -> super.extractRenderState(g, mouseX, mouseY, partialTick));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = StreamerAliasScreen.this.font;

            g.fill(0, 0, StreamerAliasScreen.this.width, StreamerAliasScreen.this.height, SBSTheme.BG_TINT);

            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Custom Player Names"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            g.fill(panelX + pad, dividerY, panelX + pad + 10, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + panelW - pad - 10, dividerY, panelX + panelW - pad, dividerY + 1,
                    SBSTheme.ACCENT_BRIGHT);

            int total = aliases().size();
            String heading = total == 0 ? "Shown instead of their real name"
                    : "Shown instead of their real name  §8" + (scrollIndex + 1) + "-"
                            + Math.min(total, scrollIndex + visibleRows) + " of " + total;
            g.text(font, Component.literal(fit(heading, contentWidth)), innerX,
                    dividerY + SBSTheme.GAP_AFTER_HEADER, SBSTheme.TEXT_MUTED);

            // Backgrounds for the two borderless edit boxes.
            SciFiRender.roundedRectWithBorder(g, innerX, inputRowY, fieldW, SBSTheme.ENTRY_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    nameBox != null && nameBox.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            SciFiRender.roundedRectWithBorder(g, innerX + fieldW + GAP, inputRowY, fieldW,
                    SBSTheme.ENTRY_HEIGHT, SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    aliasBox != null && aliasBox.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            if (total == 0) {
                g.centeredText(font, Component.literal(fit(
                                "No custom names yet - type a player above and press Add", contentWidth)),
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
