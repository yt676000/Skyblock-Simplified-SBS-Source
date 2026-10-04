/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.browser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.browser.BrowserScreen;
import sbs.modid.client.economy.pricehistory.logic.PriceBrowser;

/**
 * The Web Browser module's state: one always-on {@link PriceBrowser} (the shared MCEF wrapper, reused
 * as a general browser) that can be dragged/resized anywhere and keeps running over the game world -
 * so a YouTube tab keeps playing while you farm.
 *
 * <p>Interaction happens in the {@link BrowserScreen} (which frees the mouse); when that screen is
 * closed the same browser is still drawn as a HUD overlay at its saved rectangle. The Chromium
 * instance is not paused by closing the screen, so audio and video continue.
 */
public final class WebBrowserManager {

    private static final WebBrowserManager INSTANCE = new WebBrowserManager();

    private PriceBrowser browser;
    /** True once opened; drives the in-world HUD overlay until the browser is closed. */
    private boolean active;

    private WebBrowserManager() {
    }

    public static WebBrowserManager getInstance() {
        return INSTANCE;
    }

    public static SBSConfig.BrowserSettings cfg() {
        return ConfigManager.getInstance().get().browser;
    }

    /** The live browser, created (and pointed at the saved URL) on first use. */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: USER-SUPPLIED. This is an embedded Chromium browser (MCEF), so the address it
//   loads is whatever the user types or clicks - exactly like any browser. Defaults and
//   shortcuts point at https://skyblocksimplified.info, https://sky.shiiyu.moe,
//   https://wiki.hypixel.net and https://hypixelskyblock.minecraft.wiki; a typed bare host
//   gets https:// prefixed and a typed phrase becomes a Google search (see normalise()).
// METHOD: Whatever the loaded page performs. Chromium is a full browser engine.
// PURPOSE: Read prices, wikis and stats pages without leaving the game.
// DATA SENT: Nothing by this mod. The mod passes an address to Chromium and draws the result;
//   it injects no script, reads no page content back, and adds no header of its own. Requests
//   are the page own, made by Chromium, and carry no SBS licence token and no game data.
// DATA RECEIVED: Rendered pixels. No page data is parsed, stored or forwarded by the mod.
// SAFETY DECLARATION: The mod transmits no credentials, session tokens, Mojang uuids or OS
//   telemetry through the browser. What a visited site itself sends or stores is that site
//   business, in its own cookie jar, the same as in any browser - the module page says so.
//   Chromium comes from MCEF, declared in THIRD-PARTY.md; no page is loaded until the user
//   opens the browser, and the start address is a readable config value they can see and edit.
// ============================================================================
    public PriceBrowser browser() {
        if (browser == null) {
            browser = new PriceBrowser();
            browser.open(cfg().url);
            active = true;
        }
        return browser;
    }

    public boolean isActive() {
        return active && browser != null;
    }

    /** Opens the interactive browser screen (or closes it if it is already the open screen). */
    public void toggleScreen() {
        if (!cfg().enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (GuiStateManager.getInstance().getCurrentScreen() instanceof BrowserScreen) {
            minecraft.setScreenAndShow(null);
            return;
        }
        browser();   // ensure it exists
        minecraft.setScreenAndShow(new BrowserScreen());
    }

    /** Navigates the browser and remembers the URL as the restore point. */
    public void navigate(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String full = normalizeUrl(url.trim());
        cfg().url = full;
        ConfigManager.getInstance().save();
        browser().open(full);
    }

    /** Closes the browser entirely (frees Chromium); the HUD overlay stops. */
    public void closeBrowser() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        active = false;
    }

    /** Persists the current window rectangle. */
    public void saveRect(int x, int y, int w, int h) {
        SBSConfig.BrowserSettings c = cfg();
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        ConfigManager.getInstance().save();
    }

    /**
     * In-world HUD overlay: draws the browser at its saved rectangle while no screen is open, so a
     * playing video stays visible during gameplay. No hover is forwarded (mouse is the camera then).
     */
    public void renderHud(GuiGraphicsExtractor g) {
        if (!cfg().enabled || !cfg().showInWorld || !isActive()) {
            return;
        }
        if (GuiStateManager.getInstance().getCurrentScreen() != null) {
            return;   // a screen is open - it draws the browser itself
        }
        // Same window clamp and same page rect as BrowserScreen: the page stays on the same pixels
        // and at the same size whether the screen is open or not, so nothing reflows or jumps.
        SBSConfig.BrowserSettings c = cfg();
        int[] window = BrowserLayout.window(c.x, c.y, c.w, c.h, g.guiWidth(), g.guiHeight());
        int[] page = BrowserLayout.content(window[0], window[1], window[2], window[3]);
        browser.render(g, page[0], page[1], page[2], page[3], Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /** Adds https:// when a bare host/URL is typed, and treats a spaced query as a Google search. */
    private static String normalizeUrl(String input) {
        String text = input;
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return text;
        }
        // A dot and no spaces looks like a domain; otherwise search it on Google.
        if (text.contains(" ") || !text.contains(".")) {
            return "https://www.google.com/search?q="
                    + java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
        }
        return "https://" + text;
    }
}
