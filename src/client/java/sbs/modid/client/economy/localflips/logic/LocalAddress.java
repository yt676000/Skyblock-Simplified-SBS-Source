/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.logic;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * The address checks for the Local Flip Finder: what the player typed, and where it resolves.
 *
 * <p>The flip program runs on the player's own PC or LAN, and this feature never talks to anything
 * else: an address that resolves to a public host is refused before a connection is opened. That is
 * what makes the network disclosure on {@code LocalFlipConnection} true whatever is typed into the
 * address field.
 */
public final class LocalAddress {

    private LocalAddress() {
    }

    /**
     * Normalises a typed address to {@code scheme://host[:port]} with no path, query or trailing
     * slash. A bare {@code host:port} gets {@code http://}. Returns {@code null} for anything that is
     * not an http(s) address with a host - credentials in the address are refused too, since they
     * would end up in logs and error text.
     */
    public static URI base(String typed) {
        if (typed == null) {
            return null;
        }
        String text = typed.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (!text.contains("://")) {
            text = "http://" + text;
        }
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            if (uri.getHost() == null || uri.getRawUserInfo() != null) {
                return null;
            }
            return new URI(scheme, null, uri.getHost(), uri.getPort(), null, null, null);
        } catch (Exception malformed) {
            return null;
        }
    }

    /**
     * True for loopback, private (RFC 1918), link-local and IPv6 unique-local addresses - this PC
     * and the LAN, nothing routed beyond it.
     */
    public static boolean isLocal(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            byte first = address.getAddress()[0];
            return (first & 0xFE) == 0xFC;   // fc00::/7, unique local
        }
        return false;
    }
}
