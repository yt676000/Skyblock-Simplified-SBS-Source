/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.licence.privacy.ConsentGate;
import sbs.modid.client.core.licence.privacy.ConsentManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.core.net.Credentials;
import sbs.modid.client.core.net.FreeAccess;
import sbs.modid.client.economy.pricehistory.logic.PriceApi;

import sbs.modid.client.core.net.Redact;
import sbs.modid.client.core.net.SecureHttp;
import sbs.modid.client.core.net.validation.MessageSchemas;
import sbs.modid.client.core.net.validation.MessageTypes;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Shared access to the SBS cloud proxy for the former direct-Hypixel callers (Bazaar, LBIN, the
 * item catalogue). Every Hypixel request now goes through <b>our own server</b>: the client sends
 * only its licence token, the server holds the Hypixel key and caches the responses. This keeps the
 * Hypixel key off every client and collapses N client polls into one server-side fetch per interval.
 *
 * <p>Base-URL selection mirrors {@link PriceApi} (settled HTTPS base, legacy direct-port fallback);
 * auth is the Licence Token module's token as {@code Authorization: Bearer} + {@code X-Api-Token},
 * exactly like {@link sbs.modid.client.economy.auctions.logic.AppraiseApi}.
 */
public final class SbsApi {

    /** Client uuid is only needed for player-scoped proxy calls (profiles); harmless elsewhere. */
    private SbsApi() {
    }

    /** The proxy base URL to try first (settled base, else HTTPS primary). */
    public static String base() {
        return PriceApi.base();
    }

    /** The legacy direct-port base, for the connect-failure fallback. */
    public static String legacyBase() {
        return PriceApi.legacyBase();
    }

    /** Our backend's domain – what tells our own URLs apart from third-party ones. */
    public static final String DOMAIN = "skyblocksimplified.info";

    /** True when a licence token is configured (proxy access requires it). */
    public static boolean hasLicence() {
        return PriceApi.hasToken();
    }

    /**
     * True when a URL points at our backend while no licence token is set, so it must not be loaded.
     *
     * <p>For the embedded browser, which is not the API path and cannot go through {@link #send}. It
     * is deliberately scoped to the domain: the same browser also shows the wiki, SkyCrypt and video
     * sites, and none of those have anything to do with our licence.
     */
    public static boolean blockedUrl(String url) {
        return url != null && url.contains(DOMAIN) && !hasLicence();
    }

    /** The player's dashed uuid (for {@code /api/hypixel/profiles}), or {@code null} when offline. */
    public static String selfUuid() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getUUID().toString();
    }

    /**
     * Thrown in place of contacting the backend when no licence token is set. It extends
     * {@link IOException} on purpose: every caller already treats an {@code IOException} as "the
     * request did not work", so a tokenless client behaves exactly like an offline one instead of
     * needing a second failure path everywhere.
     */
    public static final class NoLicenceException extends IOException {
        NoLicenceException() {
            super("No SBS licence token set - request not sent");
        }
    }

    /**
     * Thrown in place of contacting the backend when the user has not consented to the purpose the
     * request declared. Extends {@link IOException} for the same reason
     * {@link NoLicenceException} does - the caller already handles "it did not work", and a
     * refusal must look exactly like being offline rather than like a new error to work around.
     *
     * <p>Callers must <b>not</b> catch this into a retry or a queue-for-later. A dropped request is
     * dropped: re-sending it when consent arrives would turn a "no" into a delayed "yes" and would
     * ship data the user declined at the moment it was collected.
     */
    public static final class NoConsentException extends IOException {
        private final transient ConsentScope scope;

        NoConsentException(ConsentScope scope) {
            super("No consent for scope '" + (scope == null ? "<undeclared>" : scope.id())
                    + "' - request not sent");
            this.scope = scope;
        }

        /** The scope that was not granted, or {@code null} for a request that declared none. */
        public ConsentScope scope() {
            return scope;
        }
    }

    /**
     * Sends a request to the SBS backend, or refuses it outright when the licence or the user's
     * consent does not cover it.
     *
     * <p><b>Every</b> call to our own server goes through here, and this is the only reason that
     * guarantee holds: the individual clients each own their {@link HttpClient} with their own
     * timeouts, so there is no other single point they pass through. Checking here rather than only
     * at each entry point means a caller that forgets the check still cannot leak a request - it
     * gets an {@code IOException} it was already prepared to handle. Requests to third parties
     * (Hypixel, Mojang, the NEU repo) do not belong here and are untouched.
     *
     * <p>The two gates are checked in this order and are not interchangeable. The licence answers
     * "may this client use the paid service"; the scope answers "did this person agree to us
     * sending <i>this</i>". A valid subscription is not agreement, which is why a licensed client
     * with no consent still sends nothing but licence validation.
     *
     * @param scope what the request is for - see {@link ConsentScope}. Never {@code null}; the
     *              no-scope overload exists only to fail loudly.
     */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/... (every SBS backend route; DOMAIN below)
// METHOD: GET / POST - whichever the caller built
// PURPOSE: The single choke point for all traffic to our own backend. Every SBS request in
//   the mod is sent from here, so the licence gate and the consent gate cannot be bypassed
//   by a caller that forgets them.
// DATA SENT: Whatever the calling client built, plus the credential headers: the licence token
//   (withLicence below), or for a player with no licence a free token (Credentials.apply /
//   FreeAccess), only on the PlayerViewer and party finder routes. Player uuid only on the
//   player-scoped proxy routes (see selfUuid).
// DATA RECEIVED: Read-only JSON, parsed by the calling client with Gson. Nothing here can
//   name a class to instantiate and no response is ever deserialized into arbitrary objects.
// SAFETY DECLARATION: A licence token identifies the PURCHASE, not the player. A free token is
//   issued for the player's Mojang-verified uuid and DOES identify the player. No Mojang
//   credentials, no session id, no OS or hardware telemetry. Every call declares a ConsentScope
//   and is refused with NoConsentException until the user has granted it. Without any token,
//   only the PlayerViewer and party finder routes and the two free-token routes that issue one
//   (/v1/free-token/challenge, /claim) may be reached - and only once that feature's consent is
//   granted; everything else is refused unsent (NoLicenceException).
// ============================================================================
    public static <T> HttpResponse<T> send(ConsentScope scope, HttpClient client, HttpRequest request,
                                           HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return send(scope, (String) null, request, handler);
    }

    /**
     * {@link #send(ConsentScope, HttpClient, HttpRequest, HttpResponse.BodyHandler)} with the body
     * capped at the size registered for {@code typeId} in
     * {@link sbs.modid.client.core.net.validation.MessageSchemas} (the default cap when
     * {@code null}). The request always travels on {@link SecureHttp}'s client: https only, default
     * trust and hostname verification, no redirects, bounded time.
     */
    public static <T> HttpResponse<T> send(ConsentScope scope, String typeId, HttpRequest request,
                                           HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        // A tester token, or with no token entered the automatic free access, stands in for the
        // licence only on the routes that accept it (and the two routes that issue it).
        boolean licensed = hasLicence()
                || (request != null && sbs.modid.client.core.net.Credentials.freeAccessAllowed(request.uri()));
        ConsentGate.Decision decision = ConsentGate.evaluate(
                scope == null ? null : ConsentManager.getInstance().registry(), licensed, scope);
        switch (decision) {
            case UNDECLARED -> {
                return sendUndeclared(request);
            }
            case NO_LICENCE -> throw new NoLicenceException();
            case NO_CONSENT -> {
                // Debug, not warn: for a user who simply declined this is the system working, and a
                // warning per poll cycle would be log spam that trains people to ignore the log.
                SkyblockSimplifiedSBS.LOGGER.debug(
                        "[SBS][Privacy] Dropped a request for scope {} - not granted.", scope.id());
                throw new NoConsentException(scope);
            }
            default -> {
                sbs.modid.client.core.perf.Perf.countNetworkCall();
                HttpResponse<T> response = typeId == null
                        ? SecureHttp.send(request, handler, SecureHttp.defaultCap(), SecureHttp.DEFAULT_TOTAL_TIMEOUT)
                        : SecureHttp.send(typeId, request, handler);
                if (response.statusCode() == 401 && tokenOnlyRoute(request.uri())) {
                    // Expired or revoked tester token: drop it so the next request claims a new one.
                    request.headers().firstValue("Authorization")
                            .map(h -> h.startsWith("Bearer ") ? h.substring(7) : h)
                            .filter(t -> Credentials.kindOf(t) == Credentials.Kind.FREE)
                            .ifPresent(t -> FreeAccess.get().rejected(t));
                }
                return response;
            }
        }
    }

    /** Routes where a 401 can only mean the credential itself was refused (not a session). */
    private static boolean tokenOnlyRoute(java.net.URI uri) {
        String path = uri == null ? null : uri.getRawPath();
        return path != null && (path.startsWith("/v1/playerviewer/") || path.startsWith("/api/hypixel/profiles")
                || path.equals("/v1/session/challenge"));
    }

    /**
     * The old signature, kept only so an un-migrated caller fails in a way somebody notices.
     *
     * <p>In a development environment it throws {@link IllegalStateException} - unhandled, loud, and
     * at the exact call site that forgot to declare its purpose, so it cannot reach a release. In
     * production it drops the request instead of crashing a player's game: a mistake of ours is not
     * a reason to send data nobody agreed to, and it is also not a reason to take down their client.
     *
     * @deprecated declare a {@link ConsentScope} and call
     *             {@link #send(ConsentScope, HttpClient, HttpRequest, HttpResponse.BodyHandler)}.
     */
    @Deprecated
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                           HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return sendUndeclared(request);
    }

    private static <T> HttpResponse<T> sendUndeclared(HttpRequest request) throws IOException {
        String target = request == null ? "<none>" : Redact.uri(request.uri());
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            throw new IllegalStateException(
                    "SBS request to " + target + " declared no ConsentScope. Every backend call must "
                            + "name what it is for - see ConsentScope and docs/PRIVACY-BACKEND.md.");
        }
        SkyblockSimplifiedSBS.LOGGER.error(
                "[SBS][Privacy] Dropped a request to {} that declared no ConsentScope. This is a bug; "
                        + "please report it.", target);
        throw new NoConsentException(null);
    }

    /** The handshake header a socket ticket travels in. Never a query parameter. */
    public static final String TICKET_HEADER = "X-Sbs-Ticket";

    /**
     * Creates a live WebSocket channel to the SBS backend. It does not connect until
     * {@link SbsSocket#start()} is called.
     *
     * <p>The socket gets no route around {@link #send}. Every connection attempt first buys a ticket
     * with an ordinary request through {@code send}, so the licence and consent gates decide each
     * attempt the way they decide any other request. When they refuse, the socket stops instead of
     * retrying. The licence token travels only on that ticket request; the socket itself carries the
     * short-lived ticket, as a handshake header.
     *
     * @param scope      what the channel is for; checked on every attempt
     * @param socketUri  {@code wss://} on {@link #DOMAIN}
     * @param ticketUri  {@code https://} on {@link #DOMAIN}, answered with {@code {"ticket": "…"}}
     * @param ticketBody the JSON body of the ticket request, built by the caller
     * @throws IllegalArgumentException when either URI is not one of ours
     */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: ticketUri - https://skyblocksimplified.info/api/... (the caller's constant), then
//   socketUri - wss://skyblocksimplified.info/ws/... (the caller's constant). Both are checked
//   against DOMAIN below; nothing else can be opened through here.
// METHOD: POST for the ticket, then a WebSocket handshake (see SbsSocket)
// PURPOSE: Live channels to our own backend. Today the only one is Crystal Hollows Structure
//   Sharing (helper/map/logic/StructureSharing).
// DATA SENT: The ticket request carries the caller's JSON body and the licence-token headers
//   (withLicence). The handshake carries only the ticket, in the X-Sbs-Ticket header.
// DATA RECEIVED: {"ticket": "<opaque string>"} validated against the sbs.socket.ticket schema;
//   then the socket's text frames, which the caller parses.
// SAFETY DECLARATION: THE TICKET REQUEST TRANSMITS THE LICENCE TOKEN, like every backend call.
//   It identifies the purchase, not the player. No Mojang credentials, no session id, no OS
//   telemetry. Every attempt goes through send(scope, ...): no licence means nothing is sent
//   (NoLicenceException), no consent for the scope means nothing is sent (NoConsentException),
//   and either one stops the socket instead of queueing a retry.
// ============================================================================
    public static SbsSocket openSocket(ConsentScope scope, java.net.URI socketUri, java.net.URI ticketUri,
                                       String ticketBody, SbsSocket.Listener listener) {
        if (!ours(socketUri, "wss") || !ours(ticketUri, "https")) {
            throw new IllegalArgumentException("SBS sockets connect to " + DOMAIN + " only, got "
                    + socketUri + " / " + ticketUri);
        }
        if (scope == null && FabricLoader.getInstance().isDevelopmentEnvironment()) {
            throw new IllegalStateException("SBS socket to " + socketUri + " declared no ConsentScope.");
        }
        SbsSocket.TicketSource tickets = () -> {
            String body = ticketBody == null ? "{}" : ticketBody;
            MessageSchemas.checkOutgoing(MessageTypes.OUT_SOCKET_TICKET,
                    com.google.gson.JsonParser.parseString(body));
            HttpRequest request = withLicence(HttpRequest.newBuilder(ticketUri))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = send(scope, MessageTypes.SOCKET_TICKET, request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code == 401 || code == 403) {
                throw new SbsSocket.TicketRejectedException("HTTP " + code);
            }
            if (code != 200) {
                throw new IOException("ticket request answered HTTP " + code);
            }
            return parseTicket(response.body());
        };
        return new SbsSocket(socketUri, TICKET_HEADER, tickets, listener, SbsSocket.Settings.defaults(),
                SbsSocket.sharedScheduler(), SecureHttp.client(), System::currentTimeMillis, Math::random);
    }

    private static boolean ours(java.net.URI uri, String scheme) {
        return uri != null && scheme.equalsIgnoreCase(uri.getScheme()) && DOMAIN.equalsIgnoreCase(uri.getHost());
    }

    /** The ticket out of {@code {"ticket": "…"}}, or an {@link IOException} for anything else. */
    static String parseTicket(String body) throws IOException {
        return MessageSchemas.parse(MessageTypes.SOCKET_TICKET, body == null ? "" : body)
                .getAsJsonObject().get("ticket").getAsString();
    }

    /** Adds the licence-token auth headers (both accepted forms) to a request builder. */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: request builder - applies to every https://skyblocksimplified.info route
// METHOD: header mutation, no traffic of its own
// PURPOSE: Attach the licence token so the backend can tell a paid client from anyone else.
// DATA SENT: Authorization: Bearer <licence token> and X-Api-Token: <licence token>. Both
//   forms because the backend accepts either. Nothing is added when no token is configured.
// DATA RECEIVED: Nothing.
// SAFETY DECLARATION: THIS METHOD DOES TRANSMIT A CREDENTIAL, and says so rather than
//   claiming otherwise. The token is issued for a purchase, is entered by the user in the
//   Licence Token module, is stored locally in config/sbs/license/token.json, and is not a
//   Mojang or Microsoft credential - it grants nothing on the player's game account.
// ============================================================================
    public static HttpRequest.Builder withLicence(HttpRequest.Builder builder) {
        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
            builder.header("X-Api-Token", token.trim());
        }
        return builder;
    }
}
