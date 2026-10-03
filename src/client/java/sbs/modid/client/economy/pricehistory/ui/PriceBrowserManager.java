/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.ui;

import sbs.modid.client.economy.pricehistory.logic.PriceBrowser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.economy.pricehistory.ui.PriceHistoryOverlay;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages multiple {@link PriceHistoryOverlay} windows simultaneously. Up to {@value MAX_WINDOWS}
 * browser windows can be open at once; each has its own embedded Chromium instance. The manager
 * handles Z-ordering (last-clicked window on top), event routing, rendering, and the "Close All"
 * button in the bottom-left corner.
 *
 * <p>In <b>container screens</b> the hotkey always opens a new window. In the standalone
 * {@link sbs.modid.client.economy.pricehistory.ui.PriceHistoryScreen PriceHistoryScreen} the hotkey toggles
 * (open/close).
 */
public final class PriceBrowserManager {

    private static final PriceBrowserManager INSTANCE = new PriceBrowserManager();

    /** Maximum number of simultaneous browser windows. */
    private static final int MAX_WINDOWS = 10;

    /** Cascade offset between new windows (px). */
    private static final int CASCADE_STEP = 20;

    /** Close-all button dimensions. */
    private static final int CLOSE_ALL_W = 70;
    private static final int CLOSE_ALL_H = 16;
    private static final int CLOSE_ALL_PAD = 6;

    private final List<PriceHistoryOverlay> windows = new ArrayList<>();

    /** Counter for cascade positioning – wraps around to avoid going off-screen. */
    private int cascadeIndex;

    private PriceBrowserManager() {
    }

    public static PriceBrowserManager getInstance() {
        return INSTANCE;
    }

    /** True when at least one overlay window is open. */
    public boolean isAnyOpen() {
        for (PriceHistoryOverlay w : windows) {
            if (w.isOpen()) {
                return true;
            }
        }
        return false;
    }

    /** True when any window has the keyboard captured (browser focus or fallback search). */
    public boolean isAnyTypingFocused() {
        for (PriceHistoryOverlay w : windows) {
            if (w.isTypingFocused()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens a new browser window on the given item's page. Returns {@code false} if the limit
     * ({@value MAX_WINDOWS}) is reached.
     */
    public boolean openNewItem(List<String> candidates) {
        if (windows.size() >= MAX_WINDOWS) {
            return false;
        }
        PriceHistoryOverlay overlay = createWindow();
        overlay.openItem(candidates);
        return true;
    }

    /**
     * Opens a new browser window on the site's start page / general search. Returns {@code false}
     * if the limit ({@value MAX_WINDOWS}) is reached.
     */
    public boolean openNewSearch() {
        if (windows.size() >= MAX_WINDOWS) {
            return false;
        }
        PriceHistoryOverlay overlay = createWindow();
        overlay.openSearch();
        return true;
    }

    /**
     * Opens a new plain web window (wiki / fandom pages – no token, no chart fallback) with its
     * own header title. Returns {@code false} if the window limit is reached or the browser engine
     * already failed – callers then fall back to Minecraft's external link flow.
     */
    public boolean openNewUrl(String url, String title) {
        if (windows.size() >= MAX_WINDOWS || PriceBrowser.isEngineFailed()) {
            return false;
        }
        PriceHistoryOverlay overlay = createWindow();
        overlay.openUrl(url, title);
        return true;
    }

    private PriceHistoryOverlay createWindow() {
        PriceHistoryOverlay overlay = new PriceHistoryOverlay();
        int ci = cascadeIndex % 8; // wrap after 8 to avoid going far off-screen
        overlay.setCascadeOffset(-ci * CASCADE_STEP, ci * CASCADE_STEP);
        cascadeIndex++;
        windows.add(overlay);
        // A freshly opened window goes on top of the other floating window layers too.
        sbs.modid.client.ui.window.FloatingWindows.raise(sbs.modid.client.ui.window.FloatingWindows.Layer.BROWSERS);
        return overlay;
    }

    /** Closes all open windows and releases all browser resources. */
    public void closeAll() {
        for (PriceHistoryOverlay w : windows) {
            w.close();
        }
        windows.clear();
        cascadeIndex = 0;
    }

    /** Removes a single window (called internally when the ✕ is clicked). */
    void remove(PriceHistoryOverlay overlay) {
        windows.remove(overlay);
        if (windows.isEmpty()) {
            cascadeIndex = 0;
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Renders all open windows in Z-order (bottom to top) and the "Close All" button in the
     * bottom-left corner when at least one window is open.
     */
    public void renderAll(Screen screen, GuiGraphicsExtractor g,
                          int mouseX, int mouseY) {
        // Remove closed windows (e.g. from ✕ click).
        windows.removeIf(w -> !w.isOpen());
        if (windows.isEmpty()) {
            return;
        }
        // Render bottom to top (last in list = topmost).
        for (PriceHistoryOverlay w : windows) {
            w.renderTopMost(screen, g, mouseX, mouseY);
        }
        // "Close All" button – bottom-left corner.
        renderCloseAllButton(g, screen, mouseX, mouseY);
    }

    private void renderCloseAllButton(GuiGraphicsExtractor g, Screen screen,
                                       int mouseX, int mouseY) {
        int bx = CLOSE_ALL_PAD;
        int by = screen.height - CLOSE_ALL_PAD - CLOSE_ALL_H;
        boolean hover = mouseX >= bx && mouseX < bx + CLOSE_ALL_W
                && mouseY >= by && mouseY < by + CLOSE_ALL_H;

        int fill = hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG;
        int border = hover ? SBSTheme.WARN : SBSTheme.PANEL_BORDER;
        SciFiRender.roundedRectWithBorder(g, bx, by, CLOSE_ALL_W, CLOSE_ALL_H,
                SBSTheme.CORNER_RADIUS, fill, border);

        Font font = Minecraft.getInstance().font;
        int textColor = hover ? SBSTheme.WARN : SBSTheme.TEXT_MUTED;
        g.centeredText(font, Component.literal("Close All"),
                bx + CLOSE_ALL_W / 2, by + (CLOSE_ALL_H - font.lineHeight) / 2, textColor);
    }

    private boolean inCloseAllButton(Screen screen, double mx, double my) {
        int bx = CLOSE_ALL_PAD;
        int by = screen.height - CLOSE_ALL_PAD - CLOSE_ALL_H;
        return mx >= bx && mx < bx + CLOSE_ALL_W && my >= by && my < by + CLOSE_ALL_H;
    }

    // ------------------------------------------------------------------
    // Input routing (events go to topmost affected window)
    // ------------------------------------------------------------------

    /**
     * Routes a click to the topmost window that contains the click point, bringing that window to
     * the front of the Z-order. Also handles the "Close All" button.
     */
    public boolean handleClick(Screen screen, MouseButtonEvent event) {
        if (windows.isEmpty()) {
            return false;
        }
        // "Close All" button.
        if (inCloseAllButton(screen, event.x(), event.y()) && event.button() == 0) {
            closeAll();
            return true;
        }
        // Iterate topmost (last) to bottommost (first).
        for (int i = windows.size() - 1; i >= 0; i--) {
            PriceHistoryOverlay w = windows.get(i);
            if (w.handleClick(screen, event)) {
                // Bring to front (move to end of list) if not already there.
                if (i < windows.size() - 1 && w.isOpen()) {
                    windows.remove(i);
                    windows.add(w);
                }
                // Remove if it was closed by the ✕.
                if (!w.isOpen()) {
                    windows.remove(w);
                    if (windows.isEmpty()) {
                        cascadeIndex = 0;
                    }
                }
                return true;
            }
        }
        // Click was outside all windows – release all keyboard focus.
        for (PriceHistoryOverlay w : windows) {
            w.getBrowser().setFocused(false);
        }
        return false;
    }

    /** Routes a drag to the topmost window that is currently dragging or resizing. */
    public boolean handleDrag(Screen screen, MouseButtonEvent event) {
        // The topmost (last) window gets first pick – only one can be dragging at a time.
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).handleDrag(screen, event)) {
                return true;
            }
        }
        return false;
    }

    /** Routes a mouse release to the topmost window that needs it. */
    public boolean handleRelease(MouseButtonEvent event) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).handleRelease(event)) {
                return true;
            }
        }
        return false;
    }

    /** Routes a scroll to the topmost window under the mouse cursor. */
    public boolean handleScroll(Screen screen, double mouseX, double mouseY, double scrollY) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).handleScroll(screen, mouseX, mouseY, scrollY)) {
                return true;
            }
        }
        return false;
    }

    /** Routes a key press to the topmost window that has keyboard focus. */
    public boolean handleKey(KeyEvent event) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).handleKey(event)) {
                return true;
            }
        }
        return false;
    }

    /** Routes a typed character to the topmost window that has keyboard focus. */
    public boolean charTyped(CharacterEvent event) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).charTyped(event)) {
                return true;
            }
        }
        return false;
    }
}
