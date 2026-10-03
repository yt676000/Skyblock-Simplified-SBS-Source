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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.economy.pricehistory.logic.PriceBrowser;
import sbs.modid.client.economy.pricehistory.logic.PriceHistoryView;
import sbs.modid.client.ui.render.DevNotice;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * A <b>movable and resizable</b> Item Price History window over container screens: a small SBS
 * panel hosting the embedded website browser ({@link PriceBrowser}), opened with the module's
 * keybind. Each overlay owns its own {@link PriceBrowser} instance; the
 * {@link PriceBrowserManager} manages multiple overlays simultaneously.
 *
 * <p>Drag it by its header, resize it by grabbing any edge or corner (Windows-style). Ctrl+scroll
 * wheel also resizes. Clicks outside the panel fall through, so the container underneath stays
 * fully usable while comparing prices.
 *
 * <p>If the browser engine cannot initialize, the native {@link PriceHistoryView} chart takes over
 * inside the same panel.
 */
public final class PriceHistoryOverlay {

    private static final int HEADER_H = 16;
    private static final int PAD = 6;
    private static final int MARGIN = 2;

    private static final int MIN_W = 200;
    private static final int MAX_W = 700;
    private static final int MIN_H = 140;
    private static final int MAX_H = 460;

    /** Width of the invisible resize grab zones at edges/corners (in GUI pixels). */
    private static final int GRIP = 5;

    /** Size of the visual corner grip indicator (small L-shape). */
    private static final int GRIP_VISUAL = 8;

    private final PriceHistoryView view = new PriceHistoryView();
    private final PriceBrowser browser = new PriceBrowser();

    private boolean open;

    /** Panel position/size (session-persistent); {@code MIN_VALUE} = place at the default spot. */
    private int posX = Integer.MIN_VALUE;
    private int posY = Integer.MIN_VALUE;
    private int sizeW = 320;
    private int sizeH = 220;

    // Actual size after clamping to the screen (recomputed every frame).
    private int panelW = sizeW;
    private int panelH = sizeH;

    // --- Header drag ---
    private boolean dragging;
    private double grabDX;
    private double grabDY;

    // --- Edge/corner resize ---
    private ResizeDir resizing = ResizeDir.NONE;
    private int resizeStartMouseX;
    private int resizeStartMouseY;
    private int resizeStartPosX;
    private int resizeStartPosY;
    private int resizeStartW;
    private int resizeStartH;

    /** What was last opened – replayed into the native chart if the browser engine fails. */
    private List<String> lastCandidates;
    private boolean fallbackPrepared;

    /** Window header text (price windows keep the default; URL windows carry their page name). */
    private String title = "Item Price History";

    /**
     * Non-null for plain web windows (wiki / fandom pages): there is no native chart to fall back
     * to, so if the browser engine fails, the URL is handed to Minecraft's external link flow.
     */
    private String customUrl;

    /** Cascade offset for new windows, set by PriceBrowserManager. */
    private int cascadeOffsetX;
    private int cascadeOffsetY;

    // ------------------------------------------------------------------
    // Resize directions
    // ------------------------------------------------------------------

    enum ResizeDir {
        NONE,
        N, S, E, W,
        NE, NW, SE, SW;

        boolean hasNorth() { return this == N || this == NE || this == NW; }
        boolean hasSouth() { return this == S || this == SE || this == SW; }
        boolean hasEast()  { return this == E || this == NE || this == SE; }
        boolean hasWest()  { return this == W || this == NW || this == SW; }
    }

    PriceHistoryOverlay() {
    }

    /** Sets a cascade offset so that multiple windows don't stack directly on top of each other. */
    void setCascadeOffset(int dx, int dy) {
        this.cascadeOffsetX = dx;
        this.cascadeOffsetY = dy;
    }

    public boolean isOpen() {
        return open;
    }

    /** True while the window is capturing the keyboard (browser page or fallback search field). */
    public boolean isTypingFocused() {
        return open && (browser.isFocused() || view.isSearchFocused());
    }

    /** Opens (or retargets) the window on one item's page. */
    public void openItem(List<String> candidates) {
        open = true;
        lastCandidates = candidates;
        customUrl = null;
        fallbackPrepared = false;
        if (PriceBrowser.isEngineFailed()) {
            prepareFallback();
        } else {
            browser.open(PriceBrowser.urlFor(candidates));
        }
    }

    /** Opens (or retargets) the window on the site's start page / general search. */
    public void openSearch() {
        open = true;
        lastCandidates = null;
        customUrl = null;
        fallbackPrepared = false;
        if (PriceBrowser.isEngineFailed()) {
            prepareFallback();
        } else {
            browser.open(PriceBrowser.site());
        }
    }

    /**
     * Opens (or retargets) the window as a plain web window on any URL (wiki / fandom pages) with
     * its own header title. No token, no chart fallback – callers must check
     * {@link PriceBrowser#isEngineFailed()} first and use the external link flow instead.
     */
    public void openUrl(String url, String title) {
        open = true;
        lastCandidates = null;
        customUrl = url;
        this.title = title;
        fallbackPrepared = false;
        browser.open(url);
    }

    /** Closes this overlay and releases the browser's Chromium resources. */
    public void close() {
        open = false;
        dragging = false;
        resizing = ResizeDir.NONE;
        browser.setFocused(false);
        browser.close();
        view.unfocusSearch();
    }

    PriceBrowser getBrowser() {
        return browser;
    }

    private boolean usingFallback() {
        return PriceBrowser.isEngineFailed();
    }

    /** Routes the last request into the native chart once the browser engine has failed. */
    private void prepareFallback() {
        if (fallbackPrepared) {
            return;
        }
        fallbackPrepared = true;
        if (lastCandidates != null && !lastCandidates.isEmpty()) {
            view.openItem(lastCandidates);
        } else {
            view.openSearch();
        }
    }

    // ------------------------------------------------------------------
    // Resize hit-testing
    // ------------------------------------------------------------------

    /**
     * Determines which resize direction (if any) the mouse is hovering over.
     * The grip zones are {@value GRIP}px wide along the panel edges/corners.
     */
    private ResizeDir hitTestResize(double mx, double my) {
        if (!inPanel(mx, my)) {
            return ResizeDir.NONE;
        }
        boolean nearLeft   = mx >= posX && mx < posX + GRIP;
        boolean nearRight  = mx >= posX + panelW - GRIP && mx < posX + panelW;
        boolean nearTop    = my >= posY && my < posY + GRIP;
        boolean nearBottom = my >= posY + panelH - GRIP && my < posY + panelH;

        if (nearTop && nearLeft)   return ResizeDir.NW;
        if (nearTop && nearRight)  return ResizeDir.NE;
        if (nearBottom && nearLeft)  return ResizeDir.SW;
        if (nearBottom && nearRight) return ResizeDir.SE;
        if (nearTop)    return ResizeDir.N;
        if (nearBottom) return ResizeDir.S;
        if (nearLeft)   return ResizeDir.W;
        if (nearRight)  return ResizeDir.E;
        return ResizeDir.NONE;
    }

    // ------------------------------------------------------------------
    // Rendering (called from PriceBrowserManager → OverlayRenderMixin)
    // ------------------------------------------------------------------

    public void renderTopMost(Screen screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        panelW = Math.min(sizeW, screen.width - MARGIN * 2);
        panelH = Math.min(sizeH, screen.height - MARGIN * 2);
        if (posX == Integer.MIN_VALUE) {
            // Default: right edge, vertically centered – beside a centered container, not over it.
            posX = screen.width - panelW - 8 + cascadeOffsetX;
            posY = (screen.height - panelH) / 2 + cascadeOffsetY;
        }
        posX = clamp(posX, MARGIN, screen.width - panelW - MARGIN);
        posY = clamp(posY, MARGIN, screen.height - panelH - MARGIN);

        // --- Visual corner grip indicators ---
        ResizeDir hoverDir = (resizing != ResizeDir.NONE) ? resizing : hitTestResize(mouseX, mouseY);
        renderCornerGrips(g, hoverDir);

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        // Header: title (drag handle) + close ✕.
        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        String shownTitle = font.plainSubstrByWidth(title, panelW - PAD * 2 - 16, false);
        g.text(font, Component.literal(shownTitle), posX + PAD, textY, SBSTheme.ACCENT_BRIGHT);
        // The in-development tag belongs to OUR price windows only. A window showing someone else's
        // page is not ours to label, and a warning about this mod's numbers on a wiki article says
        // the wrong thing about the wrong thing.
        if (customUrl == null) {
            String devTag = DevNotice.TAG;
            int tagX = posX + PAD + font.width(shownTitle) + 6;
            if (tagX + font.width(devTag) < closeX() - 4) {
                g.text(font, Component.literal(devTag), tagX, textY, SBSTheme.TEXT_MUTED);
            }
        }
        boolean closeHover = inCloseBox(mouseX, mouseY);
        g.text(font, Component.literal("x"), closeX() + 3, textY, closeHover ? SBSTheme.WARN : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1, SBSTheme.ACCENT_SOFT);

        int contentX = posX + PAD;
        int contentY = posY + HEADER_H + 4;
        int contentW = panelW - PAD * 2;
        int contentH = panelH - HEADER_H - 4 - PAD;
        if (usingFallback()) {
            if (customUrl != null) {
                // Web windows have no native fallback: hand the page to the external link flow.
                String url = customUrl;
                close();
                Minecraft.getInstance().execute(() ->
                        net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(screen, url));
                return;
            }
            prepareFallback();
            view.setBounds(contentX, contentY, contentW, contentH);
            view.render(g, mouseX, mouseY);
        } else {
            browser.render(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
        }
    }

    /**
     * Draws small L-shaped grip marks at each corner and edge midpoint indicators. The active /
     * hovered direction is highlighted with the accent color, the rest is drawn muted.
     */
    private void renderCornerGrips(GuiGraphicsExtractor g, ResizeDir activeDir) {
        int bright = SBSTheme.ACCENT;
        int dim = 0x44FFFFFF;
        int gs = GRIP_VISUAL;

        // Corner grips: small L-shapes (2px thick)
        // Top-left
        int c = (activeDir == ResizeDir.NW || activeDir == ResizeDir.N || activeDir == ResizeDir.W) ? bright : dim;
        g.fill(posX - 1, posY - 1, posX - 1 + gs, posY + 1, c);  // horizontal
        g.fill(posX - 1, posY - 1, posX + 1, posY - 1 + gs, c);  // vertical

        // Top-right
        c = (activeDir == ResizeDir.NE || activeDir == ResizeDir.N || activeDir == ResizeDir.E) ? bright : dim;
        g.fill(posX + panelW + 1 - gs, posY - 1, posX + panelW + 1, posY + 1, c);
        g.fill(posX + panelW - 1, posY - 1, posX + panelW + 1, posY - 1 + gs, c);

        // Bottom-left
        c = (activeDir == ResizeDir.SW || activeDir == ResizeDir.S || activeDir == ResizeDir.W) ? bright : dim;
        g.fill(posX - 1, posY + panelH - 1, posX - 1 + gs, posY + panelH + 1, c);
        g.fill(posX - 1, posY + panelH + 1 - gs, posX + 1, posY + panelH + 1, c);

        // Bottom-right
        c = (activeDir == ResizeDir.SE || activeDir == ResizeDir.S || activeDir == ResizeDir.E) ? bright : dim;
        g.fill(posX + panelW + 1 - gs, posY + panelH - 1, posX + panelW + 1, posY + panelH + 1, c);
        g.fill(posX + panelW - 1, posY + panelH + 1 - gs, posX + panelW + 1, posY + panelH + 1, c);
    }

    private int closeX() {
        return posX + panelW - 14;
    }

    private boolean inCloseBox(double mx, double my) {
        return mx >= closeX() && mx < closeX() + 12 && my >= posY + 2 && my < posY + HEADER_H;
    }

    private boolean inPanel(double mx, double my) {
        // Include the 1px border outside the panel for easier grip targeting.
        return mx >= posX - 1 && mx < posX + panelW + 1 && my >= posY - 1 && my < posY + panelH + 1;
    }

    private boolean inPanelStrict(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + panelH;
    }

    // ------------------------------------------------------------------
    // Input (routed from PriceBrowserManager)
    // ------------------------------------------------------------------

    /**
     * Mouse click: everything inside the panel is ours (close, resize start, drag start,
     * browser/chart clicks – and plain swallowing so no click reaches a slot underneath).
     * Clicks outside pass through and only release the keyboard focus.
     */
    public boolean handleClick(Screen screen, MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        double mx = event.x();
        double my = event.y();

        // Check resize grip zones first (corners/edges of the panel).
        ResizeDir dir = hitTestResize(mx, my);
        if (dir != ResizeDir.NONE && event.button() == 0) {
            resizing = dir;
            resizeStartMouseX = (int) mx;
            resizeStartMouseY = (int) my;
            resizeStartPosX = posX;
            resizeStartPosY = posY;
            resizeStartW = sizeW;
            resizeStartH = sizeH;
            return true;
        }

        if (!inPanelStrict(mx, my)) {
            browser.setFocused(false);
            view.unfocusSearch();
            return false;
        }
        if (inCloseBox(mx, my) && event.button() == 0) {
            close();
            return true;
        }
        if (my < posY + HEADER_H && event.button() == 0) {
            dragging = true;
            grabDX = mx - posX;
            grabDY = my - posY;
            return true;
        }
        if (usingFallback()) {
            if (event.button() == 0) {
                view.mouseClicked(mx, my);
            }
        } else {
            browser.mouseClicked(event);
        }
        return true;
    }

    /** Header drag or edge resize → move/resize the panel (clamped inside the screen). */
    public boolean handleDrag(Screen screen, MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        if (resizing != ResizeDir.NONE) {
            int dx = (int) event.x() - resizeStartMouseX;
            int dy = (int) event.y() - resizeStartMouseY;
            applyResize(screen, dx, dy);
            return true;
        }
        if (dragging) {
            posX = clamp((int) (event.x() - grabDX), MARGIN, screen.width - panelW - MARGIN);
            posY = clamp((int) (event.y() - grabDY), MARGIN, screen.height - panelH - MARGIN);
            return true;
        }
        return false;
    }

    /** Applies a resize delta from the current drag direction. */
    private void applyResize(Screen screen, int dx, int dy) {
        int newW = resizeStartW;
        int newH = resizeStartH;
        int newX = resizeStartPosX;
        int newY = resizeStartPosY;

        if (resizing.hasEast()) {
            newW = clamp(resizeStartW + dx, MIN_W, MAX_W);
        }
        if (resizing.hasWest()) {
            int wantW = clamp(resizeStartW - dx, MIN_W, MAX_W);
            newX = resizeStartPosX + (resizeStartW - wantW);
            newW = wantW;
        }
        if (resizing.hasSouth()) {
            newH = clamp(resizeStartH + dy, MIN_H, MAX_H);
        }
        if (resizing.hasNorth()) {
            int wantH = clamp(resizeStartH - dy, MIN_H, MAX_H);
            newY = resizeStartPosY + (resizeStartH - wantH);
            newH = wantH;
        }

        // Clamp position to screen.
        newX = clamp(newX, MARGIN, screen.width - newW - MARGIN);
        newY = clamp(newY, MARGIN, screen.height - newH - MARGIN);

        sizeW = newW;
        sizeH = newH;
        posX = newX;
        posY = newY;
    }

    /** Ends a header drag, resize, or completes a click inside the browser page. */
    public boolean handleRelease(MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        if (resizing != ResizeDir.NONE) {
            resizing = ResizeDir.NONE;
            return true;
        }
        if (dragging) {
            dragging = false;
            return true;
        }
        return browser.mouseReleased(event);
    }

    /**
     * Mouse wheel inside the panel: with Ctrl held it resizes the window, otherwise it scrolls the
     * page / list. Never reaches the container behind the panel.
     */
    public boolean handleScroll(Screen screen, double mouseX, double mouseY, double scrollY) {
        if (!open || !inPanelStrict(mouseX, mouseY)) {
            return false;
        }
        if (isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            sizeW = clamp(sizeW + step * 30, MIN_W, MAX_W);
            sizeH = clamp(sizeH + step * 20, MIN_H, MAX_H);
            return true;
        }
        if (usingFallback()) {
            view.mouseScrolled(mouseX, mouseY, scrollY);
        } else {
            browser.mouseScrolled(mouseX, mouseY, scrollY);
        }
        return true;
    }

    /** Keyboard while the page / fallback search is focused (consumes everything while typing). */
    public boolean handleKey(KeyEvent event) {
        if (!open) {
            return false;
        }
        if (browser.keyPressed(event)) {
            return true;
        }
        return view.keyPressed(event);
    }

    /** Typed characters for the focused page / fallback search field. */
    public boolean charTyped(CharacterEvent event) {
        if (!open) {
            return false;
        }
        if (browser.charTyped(event)) {
            return true;
        }
        if (!view.isSearchFocused()) {
            return false;
        }
        if (event.isAllowedChatCharacter()) {
            view.charTyped(event.codepointAsString());
        }
        return true;
    }

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
}
