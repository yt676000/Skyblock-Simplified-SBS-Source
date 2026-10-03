/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.logic;

import net.dimaskama.mcef.api.MCEFBrowser;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Injects the licence token from the Licence Token module as an {@code Authorization: Bearer}
 * header into every embedded-browser request against <b>skyblocksimplified.info</b> – and ONLY
 * that domain: wiki / fandom windows and any other site pass through untouched.
 *
 * <p>Installed once on MCEF's shared {@link CefClient} (all browser windows are created from the
 * same client), the first time a browser instance exists. The token is read fresh from the config
 * on every request, so pasting a new token in the module applies immediately – no restart, no
 * token in any URL or browser history.
 *
 * <p>The {@code org.cef} classes are compile-time only ({@code libs/jcef-api.jar}); at runtime the
 * identical classes load from MCEF Modern's nested jar.
 */
public final class PriceTokenInjector {

    private static final String DOMAIN = "skyblocksimplified.info";

    private static volatile boolean installed;

    private PriceTokenInjector() {
    }

    /** Installs the header handler on the shared client once; later calls are no-ops. */
    public static void install(MCEFBrowser browser) {
        if (installed || browser == null) {
            return;
        }
        try {
            CefClient client = browser.getCefBrowser().getClient();
            client.addRequestHandler(new CefRequestHandlerAdapter() {
                @Override
                public CefResourceRequestHandler getResourceRequestHandler(
                        CefBrowser cefBrowser, CefFrame frame, CefRequest request,
                        boolean isNavigation, boolean isDownload, String requestInitiator,
                        BoolRef disableDefaultHandling) {
                    return RESOURCE_HANDLER;
                }
            });
            installed = true;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][PriceHistory] Licence-token header injection active for {}.", DOMAIN);
        } catch (Throwable t) {
            // Defensive: a JCEF binary/API mismatch must never take the browser down – the site
            // then simply sees unauthenticated requests.
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][PriceHistory] Could not install the token request handler: {}", t.toString());
        }
    }

    /** Shared per-request hook: stamps the Bearer header onto own-domain requests only. */
    private static final CefResourceRequestHandlerAdapter RESOURCE_HANDLER =
            new CefResourceRequestHandlerAdapter() {
                @Override
                public boolean onBeforeResourceLoad(CefBrowser cefBrowser, CefFrame frame, CefRequest request) {
                    String url = request.getURL();
                    if (url != null && url.contains(DOMAIN)) {
                        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
                        if (token != null && !token.isBlank()) {
                            request.setHeaderByName("Authorization", "Bearer " + token.trim(), true);
                        }
                    }
                    return false; // never block the request
                }
            };
}
