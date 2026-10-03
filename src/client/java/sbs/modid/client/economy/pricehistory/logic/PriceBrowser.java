/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.logic;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * An in-game Chromium browser instance (via MCEF Modern) showing skyblocksimplified.info.
 * Multiple instances can coexist – each owns its own {@link MCEFBrowser}, while the MCEF engine
 * (API + CEF natives) is shared statically and initialized once on first use.
 *
 * <p>Coordinates: the browser is sized in <b>physical pixels</b> ({@code gui size × gui scale}) so
 * the page renders razor-sharp at any GUI scale; all forwarded input is translated from GUI space
 * into browser-local pixels. If MCEF initialization fails, {@link #isFailed()} turns true and the
 * hosts fall back to the native {@link PriceHistoryView} chart.
 */
public final class PriceBrowser {

    /**
     * The website origin (HTTPS once port 443 serves, the legacy HTTP origin until then – decided
     * by {@link PriceApi#siteBase()}); item deep links are {@code site() + "#/item/" + <ID>}.
     */
    public static String site() {
        return PriceApi.getInstance().siteBase();
    }

    private static final int KEY_ESCAPE = 256;

    // ----- Shared MCEF engine state (initialized once, used by all instances) -----
    private static volatile MCEFApi sharedApi;
    private static volatile boolean sharedFailed;
    private static boolean sharedInitStarted;

    private MCEFBrowser browser;
    private String pendingUrl;
    private int texW = -1;
    private int texH = -1;

    // GUI-space bounds of the last render (input events are translated against these).
    private int guiX;
    private int guiY;
    private int guiW;
    private int guiH;

    private boolean focused;
    private boolean mouseDownInside;
    private int lastMoveX = Integer.MIN_VALUE;
    private int lastMoveY = Integer.MIN_VALUE;

    /** CEF zoom level (0 = 100%, each step ≈ 20%); changed with Ctrl + / Ctrl -, kept per session. */
    private double zoomLevel;

    /** Right-click-hold panning: the page scrolls with the dragged mouse (grab-style). */
    private boolean panning;
    private int panLastX;
    private int panLastY;

    public PriceBrowser() {
    }

    /** The item deep link for the first lookup candidate ({@code null}/empty → site start page). */
    public static String urlFor(java.util.List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return site();
        }
        return site() + "#/item/" + candidates.get(0);
    }

    /** True when MCEF could not initialize – hosts must fall back to the native chart. */
    public boolean isFailed() {
        return sharedFailed;
    }

    /** Static check: true when MCEF could not initialize (usable without an instance). */
    public static boolean isEngineFailed() {
        return sharedFailed;
    }

    /** True while the browser is capturing the keyboard (a click into the page focuses it). */
    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean value) {
        this.focused = value;
        if (browser != null) {
            browser.setFocus(value);
        }
    }

    /**
     * Starts the shared MCEF engine on first use and navigates to the URL (deferred until the
     * engine is ready).
     */
    public void open(String url) {
        if (sharedFailed || sbs.modid.client.core.api.SbsApi.blockedUrl(url)) {
            return;
        }
        // Settle which endpoint is reachable (HTTPS vs legacy) while the engine spins up.
        PriceApi.getInstance().probe();
        if (!sharedInitStarted) {
            sharedInitStarted = true;
            MCEFApi.getInstanceFuture().whenComplete((instance, error) -> {
                if (error != null) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PriceHistory] MCEF initialization failed"
                            + " - falling back to the native chart: {}", error.toString());
                    sharedFailed = true;
                } else {
                    sharedApi = instance;
                }
            });
        }
        if (browser != null) {
            navigate(url);
        } else {
            pendingUrl = url;
        }
    }

    /**
     * Closes this browser instance and releases its Chromium resources.
     */
    public void close() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        texW = -1;
        texH = -1;
        focused = false;
        mouseDownInside = false;
        panning = false;
    }

    /**
     * Navigates the live browser. {@code CefBrowser.loadURL} is resolved reflectively because the
     * JCEF classes live in MCEF's nested jar (runtime-only, not on the compile classpath); if that
     * ever fails, the browser is recreated on the new URL instead.
     */
    private void navigate(String url) {
        if (sbs.modid.client.core.api.SbsApi.blockedUrl(url)) {
            return; // our own site without a licence token - do not request it
        }
        try {
            if (loadUrlMethod == null) {
                loadUrlMethod = Class.forName("org.cef.browser.CefBrowser")
                        .getMethod("loadURL", String.class);
            }
            loadUrlMethod.invoke(browser, url);
        } catch (ReflectiveOperationException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PriceHistory] loadURL failed - recreating browser: {}",
                    e.toString());
            browser.close();
            browser = null;
            texW = -1;
            pendingUrl = url; // recreated on the next render
        }
    }

    private java.lang.reflect.Method loadUrlMethod;

    /** Browser back / forward / reload (CefBrowser methods, resolved reflectively like loadURL). */
    public void goBack() {
        invokeCef("goBack");
    }

    public void goForward() {
        invokeCef("goForward");
    }

    public void reload() {
        invokeCef("reload");
    }

    private void invokeCef(String method) {
        if (browser == null) {
            return;
        }
        try {
            Class.forName("org.cef.browser.CefBrowser").getMethod(method).invoke(browser);
        } catch (ReflectiveOperationException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Browser] {} failed: {}", method, e.toString());
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Renders the browser into the given GUI-space rectangle (or the init progress while CEF is
     * still downloading/starting). Also drives hover: the mouse position is forwarded every frame.
     */
    public void render(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
        guiX = x;
        guiY = y;
        guiW = w;
        guiH = h;
        Font font = Minecraft.getInstance().font;
        if (sharedFailed) {
            return; // hosts render the fallback chart instead
        }
        if (sharedApi == null) {
            drawProgress(g, font, x, y, w, h);
            return;
        }
        if (browser == null) {
            String wanted = pendingUrl != null ? pendingUrl : site();
            if (sbs.modid.client.core.api.SbsApi.blockedUrl(wanted)) {
                // Never spawn a Chromium instance pointed at our own site without a token: creating
                // the browser IS the request. Other sites still open normally.
                g.centeredText(font, Component.literal("Licence token required"),
                        x + w / 2, y + h / 2 - 10, SBSTheme.TEXT);
                g.centeredText(font, Component.literal(
                                "Enter it in the Licence Token module to load " + site() + "."),
                        x + w / 2, y + h / 2 + 2, SBSTheme.TEXT_MUTED);
                return;
            }
            browser = sharedApi.createBrowser(wanted, false);
            pendingUrl = null;
            texW = -1; // force the initial resize below
            applyZoom();
            // One-time: Bearer-token header for own-domain requests (shared CefClient).
            PriceTokenInjector.install(browser);
        } else if (pendingUrl != null) {
            String url = pendingUrl;
            pendingUrl = null;
            navigate(url);
        }

        int scale = guiScale();
        int wantW = Math.max(1, w * scale);
        int wantH = Math.max(1, h * scale);
        if (wantW != texW || wantH != texH) {
            texW = wantW;
            texH = wantH;
            browser.resize(texW, texH);
        }

        // Hover / drag tracking: Chromium wants continuous mouse-move events.
        if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
            int px = (mouseX - x) * scale;
            int py = (mouseY - y) * scale;
            if (px != lastMoveX || py != lastMoveY) {
                lastMoveX = px;
                lastMoveY = py;
                browser.onMouseMoved(px, py);
            }
        }

        // Right-click-hold panning: scroll the page by the (inverted) mouse delta each frame.
        if (panning) {
            int dx = mouseX - panLastX;
            int dy = mouseY - panLastY;
            if (dx != 0 || dy != 0) {
                double zoomFactor = Math.pow(1.2, zoomLevel); // CSS px shrink as the page zooms in
                long cssX = Math.round(-dx * scale / zoomFactor);
                long cssY = Math.round(-dy * scale / zoomFactor);
                executeJs("scrollBy(" + cssX + "," + cssY + ");");
                panLastX = mouseX;
                panLastY = mouseY;
            }
        }

        GpuTextureView view = browser.getTextureView();
        if (view == null) {
            g.centeredText(font, Component.literal("Loading " + site() + "..."),
                    x + w / 2, y + h / 2 - 4, SBSTheme.TEXT_MUTED);
            return;
        }
        // 1:1 physical pixels (texture is w*scale × h*scale, drawn over w × h GUI units).
        g.blit(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
                x, y, x + w, y + h, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    private void drawProgress(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h) {
        MCEFApi.Initialization init = MCEFApi.initialize();
        String text = switch (init.getStage()) {
            case DOWNLOADING -> {
                float percent = init.getPercentage();
                yield "Downloading browser engine" + (percent >= 0 ? " (" + (int) percent + "%)" : "...");
            }
            case EXTRACTING -> "Extracting browser engine...";
            case INSTALL, INITIALIZING -> "Starting browser engine...";
            default -> "Preparing browser engine...";
        };
        g.centeredText(font, Component.literal(text), x + w / 2, y + h / 2 - 10, SBSTheme.TEXT_MUTED);
        g.centeredText(font, Component.literal("One-time setup - this can take a minute."),
                x + w / 2, y + h / 2 + 2, SBSTheme.TEXT_MUTED);
    }

    // ------------------------------------------------------------------
    // Input (GUI-space; translated to browser pixels)
    // ------------------------------------------------------------------

    /** Click: inside the page focuses the browser and forwards; outside only releases focus. */
    public boolean mouseClicked(MouseButtonEvent event) {
        if (browser == null || sharedFailed) {
            return false;
        }
        if (!inside(event.x(), event.y())) {
            setFocused(false);
            return false;
        }
        if (event.button() == 1) {
            // Right button = grab-panning; never forwarded (no Chromium context menu either).
            panning = true;
            panLastX = (int) event.x();
            panLastY = (int) event.y();
            setFocused(true);
            return true;
        }
        mouseDownInside = true;
        setFocused(true);
        browser.onMouseClicked(translate(event), false);
        return true;
    }

    /** Completes a click that started inside the page (Chromium needs the release to act). */
    public boolean mouseReleased(MouseButtonEvent event) {
        if (panning) {
            panning = false;
            return true;
        }
        if (browser == null || !mouseDownInside) {
            return false;
        }
        mouseDownInside = false;
        browser.onMouseReleased(translate(event));
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (browser == null || sharedFailed || !inside(mouseX, mouseY)) {
            return false;
        }
        int scale = guiScale();
        browser.onMouseScrolled((int) ((mouseX - guiX) * scale), (int) ((mouseY - guiY) * scale), scrollY);
        return true;
    }

    // GLFW key codes handled explicitly below.
    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_DELETE = 261;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_LEFT = 263;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;
    private static final int KEY_PAGE_UP = 266;
    private static final int KEY_PAGE_DOWN = 267;
    private static final int KEY_HOME = 268;
    private static final int KEY_END = 269;
    private static final int KEY_F5 = 294;

    /**
     * Keys while the page is focused: Escape releases focus, everything else is consumed – 'E'
     * must not close the container.
     *
     * <p>Editing and navigation keys are emulated with JavaScript commands inside the page instead
     * of CEF key events: JCEF's native Windows path reads private AWT fields that synthesized
     * {@code java.awt.event.KeyEvent}s never carry, so raw KEY_PRESSED events silently do nothing
     * there (typing works – that is the separate KEY_TYPED path). The JS route works everywhere.
     */
    public boolean keyPressed(KeyEvent event) {
        if (browser == null || !focused) {
            return false;
        }
        int key = event.key();
        if (key == KEY_ESCAPE) {
            setFocused(false);
            return true;
        }
        // Ctrl + / Ctrl - / Ctrl 0: page zoom. Both the US and the German '+'/'-' physical keys
        // are covered (GLFW codes are layout-independent US positions), plus the numpad.
        if (event.hasControlDown()) {
            if (key == 61 || key == 334 || key == 93) {          // = / KP_ADD / ] (German '+')
                setZoom(zoomLevel + 0.5);
                return true;
            }
            if (key == 45 || key == 333 || key == 47) {          // - / KP_SUBTRACT / slash (German '-')
                setZoom(zoomLevel - 0.5);
                return true;
            }
            if (key == 48 || key == 320) {                       // 0 / KP_0: reset
                setZoom(0);
                return true;
            }
        }
        if (event.isSelectAll()) {
            executeJs("document.execCommand('selectAll');");
        } else if (event.isCopy()) {
            executeJs("document.execCommand('copy');");
        } else if (event.isCut()) {
            executeJs("document.execCommand('cut');");
        } else if (event.isPaste()) {
            String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clipboard != null && !clipboard.isEmpty()) {
                executeJs("document.execCommand('insertText',false," + GSON.toJson(clipboard) + ");");
            }
        } else {
            switch (key) {
                case KEY_BACKSPACE -> executeJs("document.execCommand('delete');");
                case KEY_DELETE -> executeJs("document.execCommand('forwardDelete');");
                case KEY_ENTER, KEY_NUMPAD_ENTER -> executeJs(
                        "(function(){var a=document.activeElement||document.body;"
                        + "var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};"
                        + "a.dispatchEvent(new KeyboardEvent('keydown',o));"
                        + "a.dispatchEvent(new KeyboardEvent('keypress',o));"
                        + "a.dispatchEvent(new KeyboardEvent('keyup',o));"
                        + "if(a.form&&a.form.requestSubmit)a.form.requestSubmit();})();");
                case KEY_LEFT -> executeJs(caretJs(-1, "backward", "scrollBy(-40,0);"));
                case KEY_RIGHT -> executeJs(caretJs(1, "forward", "scrollBy(40,0);"));
                case KEY_UP -> executeJs(
                        "(function(){var a=document.activeElement;"
                        + "if(a&&a.isContentEditable){var s=getSelection();if(s)s.modify('move','backward','line');}"
                        + "else if(!a||(a.tagName!=='INPUT'&&a.tagName!=='TEXTAREA')){scrollBy(0,-40);}})();");
                case KEY_DOWN -> executeJs(
                        "(function(){var a=document.activeElement;"
                        + "if(a&&a.isContentEditable){var s=getSelection();if(s)s.modify('move','forward','line');}"
                        + "else if(!a||(a.tagName!=='INPUT'&&a.tagName!=='TEXTAREA')){scrollBy(0,40);}})();");
                case KEY_HOME -> executeJs(
                        "(function(){var a=document.activeElement;"
                        + "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA')){a.setSelectionRange(0,0);}"
                        + "else if(a&&a.isContentEditable){var s=getSelection();if(s)s.modify('move','backward','lineboundary');}"
                        + "else{scrollTo(0,0);}})();");
                case KEY_END -> executeJs(
                        "(function(){var a=document.activeElement;"
                        + "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA')){var l=a.value.length;a.setSelectionRange(l,l);}"
                        + "else if(a&&a.isContentEditable){var s=getSelection();if(s)s.modify('move','forward','lineboundary');}"
                        + "else{scrollTo(0,document.body.scrollHeight);}})();");
                case KEY_PAGE_UP -> executeJs("scrollBy(0,-Math.round(innerHeight*0.9));");
                case KEY_PAGE_DOWN -> executeJs("scrollBy(0,Math.round(innerHeight*0.9));");
                case KEY_F5 -> executeJs("location.reload();");
                default -> {
                    // Anything else still goes through the CEF key-event path (works per platform).
                    browser.onKeyPressed(event);
                    browser.onKeyReleased(event);
                }
            }
        }
        return true;
    }

    /** Caret left/right: input fields via selectionStart, contenteditable via Selection.modify. */
    private static String caretJs(int delta, String direction, String scrollFallback) {
        return "(function(){var a=document.activeElement;"
                + "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA')){"
                + "var p=Math.max(0,(a.selectionStart||0)+(" + delta + "));a.setSelectionRange(p,p);}"
                + "else if(a&&a.isContentEditable){var s=getSelection();"
                + "if(s)s.modify('move','" + direction + "','character');}"
                + "else{" + scrollFallback + "}})();";
    }

    private void setZoom(double level) {
        zoomLevel = Math.max(-4.0, Math.min(8.0, level));
        applyZoom();
    }

    /** Applies the session zoom level ({@code CefBrowser.setZoomLevel}, resolved like loadURL). */
    private void applyZoom() {
        if (browser == null) {
            return;
        }
        try {
            if (setZoomMethod == null) {
                setZoomMethod = Class.forName("org.cef.browser.CefBrowser")
                        .getMethod("setZoomLevel", double.class);
            }
            setZoomMethod.invoke(browser, zoomLevel);
        } catch (ReflectiveOperationException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PriceHistory] setZoomLevel failed: {}", e.toString());
        }
    }

    private java.lang.reflect.Method setZoomMethod;

    /** Runs JS in the page ({@code CefBrowser.executeJavaScript}, resolved like {@code loadURL}). */
    private void executeJs(String code) {
        try {
            if (executeJsMethod == null) {
                executeJsMethod = Class.forName("org.cef.browser.CefBrowser")
                        .getMethod("executeJavaScript", String.class, String.class, int.class);
            }
            executeJsMethod.invoke(browser, code, site(), 0);
        } catch (ReflectiveOperationException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PriceHistory] executeJavaScript failed: {}", e.toString());
        }
    }

    private java.lang.reflect.Method executeJsMethod;

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    public boolean charTyped(CharacterEvent event) {
        if (browser == null || !focused) {
            return false;
        }
        browser.onCharTyped(event);
        return true;
    }

    private boolean inside(double mx, double my) {
        return mx >= guiX && mx < guiX + guiW && my >= guiY && my < guiY + guiH;
    }

    private MouseButtonEvent translate(MouseButtonEvent event) {
        int scale = guiScale();
        return new MouseButtonEvent(
                (event.x() - guiX) * scale,
                (event.y() - guiY) * scale,
                event.buttonInfo());
    }

    private static int guiScale() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }
}
