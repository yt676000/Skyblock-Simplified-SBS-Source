/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Makes request details safe to log: credentials, query values and anything token-shaped are
 * replaced before a string reaches the log.
 */
public final class Redact {

    public static final String MASK = "<redacted>";

    private static final Set<String> SECRET_HEADERS = Set.of("authorization", "x-api-token", "api-key",
            "x-sbs-ticket", "x-sbs-session", "cookie", "set-cookie", "proxy-authorization");

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{32,}");

    private Redact() {
    }

    public static boolean isSecretHeader(String name) {
        return name != null && SECRET_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** A header value for the log: masked when the header carries a credential. */
    public static String header(String name, String value) {
        return isSecretHeader(name) ? MASK : text(value);
    }

    /** Scheme, host and path only; user info, query and fragment are dropped. */
    public static String uri(URI uri) {
        if (uri == null) {
            return "<none>";
        }
        String host = uri.getHost() == null ? "?" : uri.getHost();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return uri.getScheme() + "://" + host + text(path) + (uri.getRawQuery() == null ? "" : "?" + MASK);
    }

    /** Free text with bearer tokens and long token-shaped runs masked. */
    public static String text(String value) {
        if (value == null) {
            return "";
        }
        String masked = BEARER.matcher(value).replaceAll(MASK);
        return LONG_TOKEN.matcher(masked).replaceAll(MASK);
    }
}
