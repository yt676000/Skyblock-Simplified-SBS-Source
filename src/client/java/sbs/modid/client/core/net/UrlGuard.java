/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import sbs.modid.SkyblockSimplifiedSBS;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The only way the mod opens a web link in the player's browser.
 *
 * <p>A link is accepted when it is {@code https}, has no user info, uses the default port, names a
 * host by DNS name rather than an IP literal, and that host is on the compiled allowlist (an exact
 * entry, or a subdomain of an entry marked as such). Opening always goes through Minecraft's
 * {@link ConfirmLinkScreen}, which shows the full address, and only ever in response to the
 * player's own click.
 */
public final class UrlGuard {

    /** Hosts accepted exactly. */
    private static final Set<String> EXACT = Set.of(
            "hypixel.net",
            "sky.shiiyu.moe",
            "skyblocksimplified.de",
            "www.skyblocksimplified.de",
            "skyblocksimplified.info",
            "www.skyblocksimplified.info",
            "github.com",
            "modrinth.com");

    /** Hosts whose subdomains are also accepted. */
    private static final Set<String> WITH_SUBDOMAINS = Set.of("hypixel.net");

    private UrlGuard() {
    }

    /** The parsed link when it passes every rule. */
    public static Optional<URI> check(String url) {
        if (url == null || url.isEmpty() || url.length() > 2048) {
            return Optional.empty();
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '\\') {
                return Optional.empty();
            }
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
        if (!"https".equals(uri.getScheme()) || uri.isOpaque()) {
            return Optional.empty();
        }
        if (uri.getRawUserInfo() != null || uri.getRawAuthority() == null
                || uri.getRawAuthority().indexOf('@') >= 0) {
            return Optional.empty();
        }
        if (uri.getPort() != -1) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || host.endsWith(".")) {
            return Optional.empty();
        }
        host = host.toLowerCase(Locale.ROOT);
        if (isIpLiteral(host)) {
            return Optional.empty();
        }
        return isAllowedHost(host) ? Optional.of(uri) : Optional.empty();
    }

    public static boolean isAllowed(String url) {
        return check(url).isPresent();
    }

    static boolean isAllowedHost(String host) {
        if (EXACT.contains(host)) {
            return true;
        }
        for (String parent : WITH_SUBDOMAINS) {
            if (host.endsWith("." + parent) && host.length() > parent.length() + 1) {
                String label = host.substring(0, host.length() - parent.length() - 1);
                if (label.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*")) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isIpLiteral(String host) {
        if (host.startsWith("[") || host.indexOf(':') >= 0) {
            return true;
        }
        // Dotted, decimal, octal and hex IPv4 forms all consist of digits, dots and x/a-f only and
        // end in a numeric label; no allowlisted name does.
        String last = host.substring(host.lastIndexOf('.') + 1);
        return !last.isEmpty() && (last.chars().allMatch(Character::isDigit) || last.startsWith("0x"));
    }

    /**
     * Shows the confirmation for a link the player clicked. A link that fails {@link #check} is not
     * offered at all; the attempt is logged with the host only.
     *
     * @return whether the confirmation was shown
     */
    public static boolean openAfterClick(Screen parent, String url) {
        Optional<URI> checked = check(url);
        if (checked.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Net] Refused to open a link that is not on the allowlist");
            return false;
        }
        ConfirmLinkScreen.confirmLinkNow(parent, checked.get().toString());
        return true;
    }
}
