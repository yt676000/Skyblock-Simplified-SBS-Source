/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.slothotkey.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.helper.slothotkey.logic.SlotHotkey;
import sbs.modid.client.helper.slothotkey.logic.SlotHotkeyManager;

import java.util.List;

/**
 * The "Edit Hotkeys" overlay of the Slot Hotkeys module: the list of existing hotkeys (each opens the
 * {@link SlotHotkeyEditScreen}) plus an <b>Add Hotkey</b> button that turns the last scanned slot into
 * a new hotkey. A status line at the top says whether a slot has been scanned yet.
 */
public final class SlotHotkeysScreen extends Screen {

    private static final int SCROLLBAR_WIDTH = 4;
    private static final int SCROLLBAR_SPACE = SCROLLBAR_WIDTH + 2;
    private static final int GAP = 4;

    private final SlotHotkeyManager manager = SlotHotkeyManager.getInstance();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int statusY;
    private int addY;
    private int tableTop;
    private int tableHeight;
    private int backY;
    private int rowHeight;
    private int stride;
    private int visibleRows;
    private int scrollIndex;

    public SlotHotkeysScreen() {
        super(Component.literal("Slot Hotkeys"));
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
        statusY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        addY = statusY + this.font.lineHeight + 6;
        backY = panelY + panelH - pad - controlHeight;
        tableTop = addY + controlHeight + SBSTheme.GAP_AFTER_SEARCH;
        tableHeight = Math.max(SBSTheme.ENTRY_HEIGHT, backY - SBSTheme.GAP_AFTER_SEARCH - tableTop);
        rowHeight = SBSTheme.ENTRY_HEIGHT;
        stride = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
        visibleRows = Math.max(1, (tableHeight + SBSTheme.ENTRY_SPACING) / stride);

        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        clampScroll();
        addRenderableOnly(new PanelRenderable());

        addRenderableWidget(new SciFiButton(innerX, addY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("+  Add Hotkey"), this::onAdd));

        List<SlotHotkey> list = manager.hotkeys();
        int rowW = contentWidth - (hasScrollbar() ? SCROLLBAR_SPACE : 0);
        int deleteW = rowHeight;
        int editW = rowW - deleteW - GAP;
        int deleteX = innerX + rowW - deleteW;

        int last = Math.min(list.size(), scrollIndex + visibleRows);
        for (int i = scrollIndex; i < last; i++) {
            final SlotHotkey hk = list.get(i);
            int rowY = tableTop + (i - scrollIndex) * stride;
            String combo = hk.isBound() ? "§b" + hk.comboName() : "§8unbound";
            String label = combo + " §7" + trim(hk.displayLabel() + "  §8slot " + hk.slot(), editW);
            addRenderableWidget(new SciFiButton(innerX, rowY, editW, rowHeight,
                    Component.literal(label),
                    () -> Minecraft.getInstance().setScreenAndShow(new SlotHotkeyEditScreen(hk))));
            addRenderableWidget(new SciFiButton(deleteX, rowY, deleteW, rowHeight,
                    Component.literal("X"), () -> { manager.remove(hk); rebuild(); }));
        }

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
    }

    private void onAdd() {
        SlotHotkey hk = manager.addFromPending();
        if (hk == null) {
            SBSChat.send("Scan a slot first: bind the Scan key, open a menu, press it, then click a slot.");
            return;
        }
        scrollIndex = maxScroll();
        Minecraft.getInstance().setScreenAndShow(new SlotHotkeyEditScreen(hk));
    }

    private int rowCount() {
        return manager.hotkeys().size();
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

    private String trim(String text, int width) {
        if (this.font.width(text) <= width) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(1, width - this.font.width("...")), false) + "...";
    }

    private void onBack() {
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
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = SlotHotkeysScreen.this.font;
            g.fill(0, 0, SlotHotkeysScreen.this.width, SlotHotkeysScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Slot Hotkeys"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            // Status line: what "Add Hotkey" would bind right now.
            String status = manager.hasPending()
                    ? "§7Scanned: §fslot " + manager.pendingSlot() + " §8in " + manager.pendingMenuName()
                    : "§8No slot scanned – press the Scan key in a menu and click a slot";
            g.text(font, Component.literal(status), innerX, statusY, SBSTheme.TEXT_MUTED);

            if (rowCount() == 0) {
                g.centeredText(font,
                        Component.literal("No hotkeys yet – scan a slot, then \"Add Hotkey\""),
                        panelX + panelW / 2, tableTop + tableHeight / 2 - font.lineHeight / 2,
                        SBSTheme.TEXT_MUTED);
            }

            if (hasScrollbar()) {
                int total = rowCount();
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
