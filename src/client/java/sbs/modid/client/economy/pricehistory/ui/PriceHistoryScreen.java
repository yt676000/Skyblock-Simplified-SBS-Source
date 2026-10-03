/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.pricehistory.logic.PriceBrowser;
import sbs.modid.client.economy.pricehistory.logic.PriceHistoryView;

import java.util.List;

/**
 * The <b>fixed</b> Item Price History view: the standard SBS panel layout (title header, accent
 * divider, Close button) hosting the embedded website browser ({@link PriceBrowser}). Opened by
 * pressing the module's keybind in-world with no screen open (site start page) or from the Recipe
 * Viewer on a hovered item (that item's page). Centered and not movable, but resizable with
 * <b>Ctrl + scroll wheel</b>; if the browser engine cannot initialize, the native
 * {@link PriceHistoryView} chart takes over inside the same panel.
 *
 * <p>This screen owns its own {@link PriceBrowser} instance (separate from the container overlay
 * windows managed by {@link sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager}).
 * The keybind toggles the screen open/closed.
 */
public final class PriceHistoryScreen extends Screen {

    private static final int MIN_W = 260;
    private static final int MAX_W = 800;
    private static final int MIN_H = 180;
    private static final int MAX_H = 480;

    /** Session-persistent Ctrl+scroll size override ({@code 0} = default from the window size). */
    private static int sizeW;
    private static int sizeH;

    private final PriceHistoryView view = new PriceHistoryView();
    private final PriceBrowser browser = new PriceBrowser();
    private final List<String> candidates;
    private boolean fallbackPrepared;

    /**
     * The screen is opened from a non-cancelling key hook ({@code CommandKeyMixin}), so the very
     * key press that opened it still reaches this screen – ignore the close-hotkey briefly.
     */
    private final long openedAt = System.currentTimeMillis();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int contentTop;
    private int contentHeight;
    private int backY;

    public PriceHistoryScreen() {
        this(null);
    }

    /** Optionally opens directly on an item's page ({@code null} → the site's start page). */
    public PriceHistoryScreen(List<String> itemCandidates) {
        super(Component.literal("Item Price History"));
        this.candidates = itemCandidates;
        if (PriceBrowser.isEngineFailed()) {
            prepareFallback();
        } else {
            browser.open(PriceBrowser.urlFor(itemCandidates));
        }
    }

    /** Routes the request into the native chart once the browser engine has failed. */
    private void prepareFallback() {
        if (fallbackPrepared) {
            return;
        }
        fallbackPrepared = true;
        if (candidates == null || candidates.isEmpty()) {
            view.openSearch();
        } else {
            view.openItem(candidates);
        }
    }

    @Override
    protected void init() {
        // Wider than the small module panels – the website needs the room.
        int defaultW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 300, 560);
        int defaultH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 200, 360);
        panelW = clamp(sizeW > 0 ? sizeW : defaultW, MIN_W, Math.max(MIN_W, this.width - 8));
        panelH = clamp(sizeH > 0 ? sizeH : defaultH, MIN_H, Math.max(MIN_H, this.height - 8));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        innerX = panelX + SBSTheme.PANEL_PADDING;
        contentWidth = panelW - SBSTheme.PANEL_PADDING * 2;
        backY = panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT;
        contentTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        contentHeight = backY - SBSTheme.GAP_AFTER_SEARCH - contentTop;

        addRenderableOnly(new PanelRenderable());

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Close"), this::onClose));

        view.setBounds(innerX, contentTop, contentWidth, contentHeight);
    }

    @Override
    public void onClose() {
        browser.close();
        super.onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        if (PriceBrowser.isEngineFailed()) {
            return event.button() == 0 && view.mouseClicked(event.x(), event.y());
        }
        return browser.mouseClicked(event);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (browser.mouseReleased(event)) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            sizeW = clamp(panelW + step * 40, MIN_W, MAX_W);
            sizeH = clamp(panelH + step * 25, MIN_H, MAX_H);
            this.rebuildWidgets();
            return true;
        }
        if (PriceBrowser.isEngineFailed()) {
            if (view.mouseScrolled(mouseX, mouseY, scrollY)) {
                return true;
            }
        } else if (browser.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (browser.keyPressed(event)) {
            return true; // the page has keyboard focus (Escape released it)
        }
        if (view.keyPressed(event)) {
            return true; // fallback search field had focus and consumed the key
        }
        int openKey = ConfigManager.getInstance().get().priceHistory.openKey;
        if (openKey != 0 && event.key() == openKey
                && System.currentTimeMillis() - openedAt > 250) {
            onClose(); // the opening keybind also closes the view (toggle)
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (browser.charTyped(event)) {
            return true;
        }
        if (view.isSearchFocused()) {
            if (event.isAllowedChatCharacter()) {
                view.charTyped(event.codepointAsString());
            }
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = PriceHistoryScreen.this.font;

            g.fill(0, 0, PriceHistoryScreen.this.width, PriceHistoryScreen.this.height, SBSTheme.BG_TINT);

            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Item Price History"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            String sizeHint = "Ctrl+Scroll = size";
            g.text(font, Component.literal(sizeHint),
                    panelX + panelW - SBSTheme.PANEL_PADDING - font.width(sizeHint), titleY, SBSTheme.TEXT_MUTED);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            g.fill(panelX + pad, dividerY, panelX + pad + 10, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + panelW - pad - 10, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT_BRIGHT);

            if (PriceBrowser.isEngineFailed()) {
                prepareFallback();
                view.setBounds(innerX, contentTop, contentWidth, contentHeight);
                view.render(g, mouseX, mouseY);
            } else {
                browser.render(g, innerX, contentTop, contentWidth, contentHeight,
                        mouseX, mouseY);
            }
        }
    }
}
