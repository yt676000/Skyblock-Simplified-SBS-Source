/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The credential kinds the client can attach to an SBS request, and which routes each may reach.
 *
 * <p>With no token entered at all, the PlayerViewer routes still work: {@link #apply} then attaches
 * the automatic free token from {@link FreeAccess}, which is claimed after proving the Minecraft
 * account. A licence or a hand-entered tester token always wins over it.
 *
 * <p>Both kinds are entered in the Licence Token module and stored there. A value of the form
 * {@code sbsfree_} followed by 64 hex characters is a {@link Kind#FREE} tester token; anything
 * else is a {@link Kind#LICENCE} token. A licence token is accepted on every SBS route. A tester
 * token is attached only to the player viewer relay, the profile list route, the session handshake
 * and the party finder on the SBS origin - never to party chat ({@link #FREE_EXCLUDED}), which stays
 * licence-only until it has moderation. {@link #apply} and {@link #checkRequest} refuse it anywhere
 * else, so it never reaches IRC, carry or the other licence routes. Token values are never logged.
 */
public final class Credentials {

    /** A kind of credential. */
    public enum Kind {
        NONE,
        LICENCE,
        FREE
    }

    /** Shown when the relay answers 401 to a tester token. */
    public static final String FREE_TOKEN_REJECTED =
            "Your tester token has expired or was revoked. Enter a new one in the Licence Token module.";

    static final String SBS_HOST = "skyblocksimplified.info";

    /** Path prefixes on the SBS origin that accept a tester token. */
    static final List<String> FREE_ROUTES = List.of("/v1/playerviewer/", "/api/hypixel/profiles",
            "/v1/session/", "/party/");

    /** Paths under {@link #FREE_ROUTES} that still need a licence: chat, until it has moderation. */
    static final List<String> FREE_EXCLUDED = List.of("/party/chat");

    private static final Pattern FREE = Pattern.compile("^sbsfree_[0-9a-f]{64}$");

    private Credentials() {
    }

    /** The kind of a token value as entered. */
    public static Kind kindOf(String token) {
        if (token == null || token.isBlank()) {
            return Kind.NONE;
        }
        String trimmed = token.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("sbsfree_")) {
            return FREE.matcher(trimmed).matches() ? Kind.FREE : Kind.NONE;
        }
        return Kind.LICENCE;
    }

    /** The kind of the token currently entered in the Licence Token module. */
    public static Kind current() {
        return kindOf(sbs.modid.client.core.config.LicenceToken.getInstance().get());
    }

    /** Whether a tester token may be sent to {@code uri}. */
    public static boolean freeTokenAllowed(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getPort() != -1 || !SBS_HOST.equalsIgnoreCase(uri.getHost()) || uri.getRawPath() == null) {
            return false;
        }
        String path = uri.getRawPath();
        if (path.contains("..") || path.contains("%") || path.contains("//")) {
            return false;
        }
        for (String excluded : FREE_EXCLUDED) {
            if (path.startsWith(excluded)) {
                return false;
            }
        }
        for (String prefix : FREE_ROUTES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a request to {@code uri} may go out without a licence: a hand-entered tester token on
     * the PlayerViewer routes, or, with no token entered, the automatic free access on those routes
     * and on the two routes that issue it. Consent is still checked separately by the caller.
     */
    public static boolean freeAccessAllowed(URI uri) {
        return switch (current()) {
            case FREE -> freeTokenAllowed(uri);
            case NONE -> freeTokenAllowed(uri) || FreeAccess.isIssueRoute(uri);
            case LICENCE -> false;
        };
    }

    /** True when a request to {@code uri} would carry the automatic free token from {@link FreeAccess}. */
    public static boolean usesAutomaticFreeToken(URI uri) {
        return current() == Kind.NONE && freeTokenAllowed(uri);
    }

    /**
     * True only when nothing is entered and {@code uri} is a PlayerViewer route: the one case where
     * {@link #apply} asks {@link FreeAccess} for a token. A licence or tester token never does.
     */
    static boolean needsAutomaticToken(Kind entered, URI uri) {
        return entered == Kind.NONE && freeTokenAllowed(uri);
    }

    /** Whether {@code token} of its kind may be sent to {@code uri}. */
    public static boolean allowed(String token, URI uri) {
        return switch (kindOf(token)) {
            case LICENCE -> true;
            case FREE -> freeTokenAllowed(uri);
            case NONE -> false;
        };
    }

    /**
     * Adds the entered token to a request for {@code uri} when its kind may go there. With no token
     * entered and a PlayerViewer route, adds the automatic free token instead, claiming it first when
     * none is held (network; never call this on the render thread for such a route).
     *
     * @throws IOException when the entered token is a tester token and the route does not accept it,
     *                     or when the automatic free token cannot be had (consent refused included)
     */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: request builder - applies to https://skyblocksimplified.info routes only
// METHOD: header mutation; with no token entered it may run the FreeAccess handshake (see there)
// PURPOSE: Attach the credential that lets the backend tell a licensed or free client apart.
// DATA SENT: Authorization: Bearer <token> and X-Api-Token: <token>. The token is the licence
//   token, a hand-entered tester token, or - with nothing entered, and only on the PlayerViewer and
//   party finder routes - the automatic free token, which is tied to the player's uuid.
// DATA RECEIVED: Nothing.
// SAFETY DECLARATION: THIS METHOD DOES TRANSMIT A CREDENTIAL. A tester or free token is never
//   attached to any other route (and checkRequest refuses one that got there anyway). Not a Mojang
//   or Microsoft credential - it grants nothing on the player's game account.
// ============================================================================
    public static HttpRequest.Builder apply(HttpRequest.Builder builder, URI uri) throws IOException {
        SecureHttp.requireHttps(uri);
        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        Kind kind = kindOf(token);
        if (kind == Kind.NONE) {
            if (!needsAutomaticToken(kind, uri)) {
                return builder;
            }
            try {
                token = FreeAccess.get().token(FreeAccess.scopeFor(uri));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("free access interrupted");
            }
        } else if (!allowed(token, uri)) {
            throw new CredentialRefusedException(uri);
        }
        builder.header("Authorization", "Bearer " + token.trim());
        builder.header("X-Api-Token", token.trim());
        return builder;
    }

    /**
     * Refuses a built request that carries a tester token in a credential header for a route that
     * does not accept it. Applied by {@link SecureHttp} to every request.
     */
    public static void checkRequest(HttpRequest request) throws IOException {
        // The verified SBS session is an SBS-only credential, like the tester token.
        if (!request.headers().allValues(SessionIdentity.HEADER).isEmpty()
                && !SessionIdentity.isSbsOrigin(request.uri())) {
            throw new CredentialRefusedException(request.uri());
        }
        for (String header : new String[] {"Authorization", "X-Api-Token", "API-Key"}) {
            for (String value : request.headers().allValues(header)) {
                String token = value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7) : value;
                if (kindOf(token) == Kind.FREE && !freeTokenAllowed(request.uri())) {
                    throw new CredentialRefusedException(request.uri());
                }
            }
        }
    }

    /** A tester token was about to be sent to a route that does not accept it. */
    public static final class CredentialRefusedException extends IOException {
        CredentialRefusedException(URI uri) {
            super("tester token is not accepted on " + Redact.uri(uri));
        }
    }
}
