/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.browser;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.helper.browser.WebBrowserManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.pricehistory.logic.PriceBrowser;

import java.util.ArrayList;
import java.util.List;

/**
 * The interactive Web Browser window: a movable, resizable frame hosting the shared {@link PriceBrowser}.
 * The window rectangle IS the page; the toolbar (nav buttons + URL field) is drawn OVER the top of it,
 * so the whole window can be dragged flush to any screen edge (nothing is reserved outside it).
 *
 * <p>Closing this screen does NOT close the browser - {@link WebBrowserManager} keeps the same Chromium
 * instance running and draws it as a HUD overlay at the saved rectangle, so a video keeps playing while
 * you go back to farming. The toolbar's "x" closes the browser for real.
 */
public final class BrowserScreen extends Screen {

    private static final int TOOLBAR_H = 18;
    private static final int BTN = 16;
    private static final int HANDLE = 10;
    private static final int MIN_W = 200;
    private static final int MIN_H = 120;

    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;

    private final PriceBrowser browser = WebBrowserManager.getInstance().browser();
    private final long openedAt = System.currentTimeMillis();

    /** Live window rectangle (= the page); mirrors the config, saved on release. */
    private int rx;
    private int ry;
    private int rw;
    private int rh;

    private boolean moving;
    private boolean resizing;
    private int offX;
    private int offY;

    private String urlText = "";
    private boolean urlFocused;

    /** Per-frame toolbar button hit rects -> action. */
    private final List<int[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    public BrowserScreen() {
        super(Component.literal("Browser"));
    }

    private static SBSConfig.BrowserSettings cfg() {
        return WebBrowserManager.cfg();
    }

    @Override
    protected void init() {
        SBSConfig.BrowserSettings c = cfg();
        rw = clamp(c.w, MIN_W, this.width);
        rh = clamp(c.h, MIN_H, this.height);
        rx = clamp(c.x, 0, this.width - rw);
        ry = clamp(c.y, 0, this.height - rh);
        urlText = c.url;
        addRenderableOnly(new PanelRenderable());
    }

    @Override
    public void onClose() {
        persist();
        super.onClose();   // the browser stays alive for the HUD overlay
    }

    private void persist() {
        WebBrowserManager.getInstance().saveRect(rx, ry, rw, rh);
    }

    private int urlFieldX() {
        return rx + 2 + BTN * 4 + 6;   // after back / fwd / reload / home
    }

    private int urlFieldRight() {
        return rx + rw - BTN - 4;      // before the close button
    }

    private boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        if (event.button() == 0) {
            // Toolbar buttons first.
            for (int i = 0; i < buttonRects.size(); i++) {
                int[] r = buttonRects.get(i);
                if (inRect(mx, my, r[0], r[1], r[2], r[3])) {
                    buttonActions.get(i).run();
                    return true;
                }
            }
            // Resize handle (bottom-right corner).
            if (inRect(mx, my, rx + rw - HANDLE, ry + rh - HANDLE, HANDLE, HANDLE)) {
                resizing = true;
                offX = (int) mx - (rx + rw);
                offY = (int) my - (ry + rh);
                return true;
            }
            // URL field.
            if (inRect(mx, my, urlFieldX(), ry + 1, urlFieldRight() - urlFieldX(), BTN)) {
                urlFocused = true;
                return true;
            }
            // Anywhere else on the toolbar strip = drag to move.
            if (inRect(mx, my, rx, ry, rw, TOOLBAR_H)) {
                moving = true;
                offX = (int) mx - rx;
                offY = (int) my - ry;
                return true;
            }
            urlFocused = false;
        }
        return browser.mouseClicked(event);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (moving || resizing) {
            moving = false;
            resizing = false;
            persist();
            return true;
        }
        return browser.mouseReleased(event) || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (browser.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (urlFocused) {
            if (key == KEY_ENTER || key == KEY_NUMPAD_ENTER) {
                navigate();
            } else if (key == KEY_ESCAPE) {
                urlFocused = false;
            } else if (key == KEY_BACKSPACE && !urlText.isEmpty()) {
                urlText = urlText.substring(0, urlText.length() - 1);
            } else if (event.isPaste()) {
                urlText += net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard();
            }
            return true;
        }
        if (browser.keyPressed(event)) {
            return true;   // the page has keyboard focus
        }
        int openKey = cfg().openKey;
        if (key == KEY_ESCAPE
                || (openKey != 0 && key == openKey && System.currentTimeMillis() - openedAt > 250)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (urlFocused) {
            if (event.isAllowedChatCharacter() && urlText.length() < 300) {
                urlText += event.codepointAsString();
            }
            return true;
        }
        return browser.charTyped(event) || super.charTyped(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void navigate() {
        WebBrowserManager.getInstance().navigate(urlText);
        urlFocused = false;
    }

    private static int clamp(int value, int min, int max) {
        int hi = Math.max(min, max);
        return Math.max(min, Math.min(value, hi));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = BrowserScreen.this.font;
            buttonRects.clear();
            buttonActions.clear();

            // Live move / resize follow the cursor here (no mouseDragged needed). Flush to all edges.
            if (moving) {
                rx = clamp((int) mouseX - offX, 0, BrowserScreen.this.width - rw);
                ry = clamp((int) mouseY - offY, 0, BrowserScreen.this.height - rh);
            } else if (resizing) {
                rw = clamp((int) mouseX - offX - rx, MIN_W, BrowserScreen.this.width - rx);
                rh = clamp((int) mouseY - offY - ry, MIN_H, BrowserScreen.this.height - ry);
            }

            g.fill(0, 0, BrowserScreen.this.width, BrowserScreen.this.height, SBSTheme.BG_TINT);

            // The page fills the whole window; the toolbar is painted over its top strip.
            browser.render(g, rx, ry, rw, rh, mouseX, mouseY);

            SciFiRender.roundedRect(g, rx, ry, rw, TOOLBAR_H, 0, 0xE00A1420);
            g.fill(rx, ry + TOOLBAR_H, rx + rw, ry + TOOLBAR_H + 1, SBSTheme.ACCENT_SOFT);

            int bx = rx + 2;
            int by = ry + 1;
            toolbarButton(g, font, bx, by, "◀", mouseX, mouseY, browser::goBack);
            toolbarButton(g, font, bx + BTN, by, "▶", mouseX, mouseY, browser::goForward);
            toolbarButton(g, font, bx + BTN * 2, by, "⟳", mouseX, mouseY, browser::reload);
            toolbarButton(g, font, bx + BTN * 3, by, "⌂", mouseX, mouseY,
                    () -> WebBrowserManager.getInstance().navigate("https://www.google.com"));

            // URL field.
            int ux = urlFieldX();
            int uw = urlFieldRight() - ux;
            SciFiRender.roundedRectWithBorder(g, ux, by, uw, BTN, 2, SBSTheme.SEARCH_FILL,
                    urlFocused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            String shown = urlText.isEmpty() && !urlFocused ? "§8type a URL or search…" : urlText;
            String fitted = shown;
            while (font.width(fitted) > uw - 8 && fitted.length() > 1) {
                fitted = fitted.substring(1);
            }
            g.text(font, Component.literal(fitted), ux + 4, by + (BTN - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
            if (urlFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
                int caretX = ux + 4 + font.width(fitted);
                g.fill(caretX, by + 2, caretX + 1, by + BTN - 2, SBSTheme.ACCENT_BRIGHT);
            }

            // Close (closes the browser entirely).
            toolbarButton(g, font, rx + rw - BTN - 1, by, "§cx", mouseX, mouseY, () -> {
                WebBrowserManager.getInstance().closeBrowser();
                BrowserScreen.super.onClose();
            });

            // Resize handle.
            int hx = rx + rw - HANDLE;
            int hy = ry + rh - HANDLE;
            g.fill(hx, hy, rx + rw, ry + rh, 0x88FFFFFF);
        }

        private void toolbarButton(GuiGraphicsExtractor g, Font font, int x, int y, String label,
                                   int mouseX, int mouseY, Runnable action) {
            boolean hover = mouseX >= x && mouseX < x + BTN && mouseY >= y && mouseY < y + BTN;
            SciFiRender.roundedRectWithBorder(g, x, y, BTN, BTN, 2,
                    hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            g.centeredText(font, Component.literal(label), x + BTN / 2, y + (BTN - font.lineHeight) / 2 + 1,
                    SBSTheme.TEXT);
            buttonRects.add(new int[]{x, y, BTN, BTN});
            buttonActions.add(action);
        }
    }
}
