/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.keybind.CommandKeybind;
import sbs.modid.client.core.keybind.CommandKeybindManager;
import sbs.modid.client.core.keybind.CommandShortcut;
import sbs.modid.client.core.keybind.CommandShortcutManager;

import java.util.List;

/**
 * The command-keybind manager overview, opened from the Quality of Life → Command Keybinds module.
 *
 * <p>Two tabs sharing the SBS panel look:
 * <ul>
 *   <li><b>Keybinds</b>: each row is an on/off toggle, a wide "key combo · summary" button that opens
 *       the full {@link KeybindEditScreen}, and a delete. Unlimited entries, scrollable.</li>
 *   <li><b>Shortcuts</b>: each row is an alias field + a command field + delete – the aliases that
 *       {@link CommandShortcutManager} expands everywhere in the mod (e.g. {@code garden} →
 *       {@code /warp garden}).</li>
 * </ul>
 */
public final class CommandKeybindsScreen extends Screen {

    private static final int SCROLLBAR_WIDTH = 4;
    private static final int SCROLLBAR_SPACE = SCROLLBAR_WIDTH + 2;
    private static final int TEXT_INSET = 6;
    private static final int GAP = 4;

    private final CommandKeybindManager keybinds = CommandKeybindManager.getInstance();
    private final CommandShortcutManager shortcuts = CommandShortcutManager.getInstance();

    /** 0 = Keybinds tab, 1 = Shortcuts tab (kept across rebuilds). */
    private static int tab;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int tabsY;
    private int addNewY;
    private int tableTop;
    private int tableHeight;
    private int backY;
    private int rowHeight;
    private int stride;
    private int visibleRows;
    private int scrollIndex;

    public CommandKeybindsScreen() {
        super(Component.literal("Command Keybinds"));
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

        int controlHeight = SBSTheme.SEARCH_HEIGHT;
        tabsY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        addNewY = tabsY + controlHeight + 4;
        backY = panelY + panelH - pad - controlHeight;
        tableTop = addNewY + controlHeight + SBSTheme.GAP_AFTER_SEARCH;
        tableHeight = Math.max(SBSTheme.ENTRY_HEIGHT, backY - SBSTheme.GAP_AFTER_SEARCH - tableTop);
        rowHeight = SBSTheme.ENTRY_HEIGHT;
        stride = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
        visibleRows = Math.max(1, (tableHeight + SBSTheme.ENTRY_SPACING) / stride);

        rebuild();
    }

    // ------------------------------------------------------------------
    // Widget building
    // ------------------------------------------------------------------

    private void rebuild() {
        clearWidgets();
        clampScroll();
        addRenderableOnly(new PanelRenderable());

        // Tab switcher.
        int half = (contentWidth - 6) / 2;
        addRenderableWidget(new SciFiButton(innerX, tabsY, half, SBSTheme.SEARCH_HEIGHT,
                Component.literal((tab == 0 ? "▸ " : "") + "Keybinds"),
                () -> { tab = 0; scrollIndex = 0; rebuild(); }));
        addRenderableWidget(new SciFiButton(innerX + half + 6, tabsY, contentWidth - half - 6, SBSTheme.SEARCH_HEIGHT,
                Component.literal((tab == 1 ? "▸ " : "") + "Shortcuts"),
                () -> { tab = 1; scrollIndex = 0; rebuild(); }));

        addRenderableWidget(new SciFiButton(innerX, addNewY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("+  Add New"), this::onAddNew));

        if (tab == 0) {
            buildKeybindRows();
        } else {
            buildShortcutRows();
        }

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    private void buildKeybindRows() {
        List<CommandKeybind> list = keybinds.getKeybinds();
        int rowW = rowWidth();
        int toggleW = 40;
        int deleteW = rowHeight;
        int editX = innerX + toggleW + GAP;
        int editW = rowW - toggleW - deleteW - GAP * 2;
        int deleteX = innerX + rowW - deleteW;

        int last = Math.min(list.size(), scrollIndex + visibleRows);
        for (int i = scrollIndex; i < last; i++) {
            final CommandKeybind kb = list.get(i);
            int rowY = tableTop + (i - scrollIndex) * stride;
            addRenderableWidget(new SciFiToggleButton(innerX, rowY, toggleW, rowHeight,
                    Component.literal(""), kb::enabled,
                    () -> { kb.setEnabled(!kb.enabled()); keybinds.save(); }));
            addRenderableWidget(new SciFiButton(editX, rowY, editW, rowHeight,
                    Component.literal("§b" + kb.keyComboName().getString() + " §7" + trim(kb.summary(), editW)),
                    () -> Minecraft.getInstance().setScreenAndShow(new KeybindEditScreen(kb))));
            addRenderableWidget(new SciFiButton(deleteX, rowY, deleteW, rowHeight,
                    Component.literal("X"), () -> { keybinds.remove(kb); rebuild(); }));
        }
    }

    private void buildShortcutRows() {
        List<CommandShortcut> list = shortcuts.getShortcuts();
        int rowW = rowWidth();
        int deleteW = rowHeight;
        int aliasW = 84;
        int commandX = innerX + aliasW + GAP;
        int commandW = rowW - aliasW - deleteW - GAP * 2;
        int deleteX = innerX + rowW - deleteW;
        int textH = this.font.lineHeight;

        int last = Math.min(list.size(), scrollIndex + visibleRows);
        for (int i = scrollIndex; i < last; i++) {
            final CommandShortcut sc = list.get(i);
            int rowY = tableTop + (i - scrollIndex) * stride;
            int editY = rowY + (rowHeight - textH) / 2;

            EditBox aliasBox = new EditBox(this.font, innerX + TEXT_INSET, editY, aliasW - TEXT_INSET - 4, textH,
                    Component.literal("Alias"));
            aliasBox.setBordered(false);
            aliasBox.setMaxLength(24);
            aliasBox.setTextColor(SBSTheme.TEXT);
            aliasBox.setHint(Component.literal("alias"));
            aliasBox.setValue(sc.alias());
            aliasBox.setResponder(v -> { sc.setAlias(v); shortcuts.save(); });
            addRenderableWidget(aliasBox);

            EditBox commandBox = new EditBox(this.font, commandX + TEXT_INSET, editY, commandW - TEXT_INSET - 4, textH,
                    Component.literal("Command"));
            commandBox.setBordered(false);
            commandBox.setMaxLength(256);
            commandBox.setTextColor(SBSTheme.TEXT);
            commandBox.setHint(Component.literal("/warp garden"));
            commandBox.setValue(sc.command());
            commandBox.setResponder(v -> { sc.setCommand(v); shortcuts.save(); });
            addRenderableWidget(commandBox);

            addRenderableWidget(new SciFiButton(deleteX, rowY, deleteW, rowHeight,
                    Component.literal("X"), () -> { shortcuts.remove(sc); rebuild(); }));
        }
    }

    // ------------------------------------------------------------------
    // Actions / scrolling
    // ------------------------------------------------------------------

    private void onAddNew() {
        if (tab == 0) {
            keybinds.add();
        } else {
            shortcuts.add();
        }
        scrollIndex = maxScroll();
        rebuild();
    }

    private int rowCount() {
        return tab == 0 ? keybinds.getKeybinds().size() : shortcuts.getShortcuts().size();
    }

    private int maxScroll() {
        return Math.max(0, rowCount() - visibleRows);
    }

    private void clampScroll() {
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll()));
    }

    private boolean hasScrollbar() {
        return rowCount() > visibleRows;
    }

    private int rowWidth() {
        return contentWidth - (hasScrollbar() ? SCROLLBAR_SPACE : 0);
    }

    private String trim(String text, int width) {
        return this.font.plainSubstrByWidth(text, width - this.font.width("§b" + " Left Shift + G "), false);
    }

    private void onBack() {
        keybinds.save();
        shortcuts.save();
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() <= 0 || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int next = Math.max(0, Math.min(maxScroll(), scrollIndex + (scrollY > 0 ? -1 : 1)));
        if (next != scrollIndex) {
            scrollIndex = next;
            rebuild();
        }
        return true;
    }

    @Override
    public void removed() {
        keybinds.save();
        shortcuts.save();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Panel backgrounds
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = CommandKeybindsScreen.this.font;
            g.fill(0, 0, CommandKeybindsScreen.this.width, CommandKeybindsScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Command Keybinds"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            int total = rowCount();
            if (total == 0) {
                g.centeredText(font, Component.literal(tab == 0
                                ? "No keybinds yet – click \"Add New\""
                                : "No shortcuts yet – e.g. garden → /warp garden"),
                        panelX + panelW / 2, tableTop + tableHeight / 2 - font.lineHeight / 2, SBSTheme.TEXT_MUTED);
            } else if (tab == 1) {
                // Card backgrounds behind the alias / command EditBoxes.
                int rowW = rowWidth();
                int deleteW = rowHeight;
                int aliasW = 84;
                int commandX = innerX + aliasW + GAP;
                int commandW = rowW - aliasW - deleteW - GAP * 2;
                int last = Math.min(total, scrollIndex + visibleRows);
                for (int i = scrollIndex; i < last; i++) {
                    int rowY = tableTop + (i - scrollIndex) * stride;
                    SciFiRender.roundedRectWithBorder(g, innerX, rowY, aliasW, rowHeight,
                            SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, SBSTheme.CARD_BORDER);
                    SciFiRender.roundedRectWithBorder(g, commandX, rowY, commandW, rowHeight,
                            SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, SBSTheme.CARD_BORDER);
                }
            }

            if (hasScrollbar()) {
                int sbX = innerX + contentWidth - SCROLLBAR_WIDTH;
                SciFiRender.roundedRect(g, sbX, tableTop, SCROLLBAR_WIDTH, tableHeight, SCROLLBAR_WIDTH / 2, 0x22FFFFFF);
                int thumbH = Math.max(16, (int) ((long) tableHeight * visibleRows / total));
                int travel = tableHeight - thumbH;
                int max = maxScroll();
                int thumbY = tableTop + (max <= 0 ? 0 : (int) ((long) scrollIndex * travel / max));
                SciFiRender.roundedRect(g, sbX, thumbY, SCROLLBAR_WIDTH, thumbH, SCROLLBAR_WIDTH / 2, SBSTheme.ACCENT);
            }
        }
    }
}
