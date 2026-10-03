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
import sbs.modid.client.economy.pricehistory.logic.PriceApi;

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
        private NoLicenceException() {
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
        private NoConsentException(ConsentScope scope) {
            super("No consent for scope '" + (scope == null ? "<undeclared>" : scope.id())
                    + "' - request not sent");
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
// DATA SENT: Whatever the calling client built, plus the licence-token headers added by
//   withLicence below. Player uuid only on the player-scoped proxy routes (see selfUuid).
// DATA RECEIVED: Read-only JSON, parsed by the calling client with Gson. Nothing here can
//   name a class to instantiate and no response is ever deserialized into arbitrary objects.
// SAFETY DECLARATION: Requests carry the licence token, which identifies the PURCHASE, not
//   the player. No Mojang credentials, no session id, no OS or hardware telemetry. Every
//   call declares a ConsentScope and is refused with NoConsentException until the user has
//   granted it; without a token nothing is sent at all (NoLicenceException).
// ============================================================================
    public static <T> HttpResponse<T> send(ConsentScope scope, HttpClient client, HttpRequest request,
                                           HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        ConsentGate.Decision decision = ConsentGate.evaluate(
                scope == null ? null : ConsentManager.getInstance().registry(), hasLicence(), scope);
        switch (decision) {
            case UNDECLARED -> {
                return sendUndeclared(client, request, handler);
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
                return client.send(request, handler);
            }
        }
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
        return sendUndeclared(client, request, handler);
    }

    private static <T> HttpResponse<T> sendUndeclared(HttpClient client, HttpRequest request,
                                                      HttpResponse.BodyHandler<T> handler)
            throws IOException {
        String target = request == null ? "<none>" : String.valueOf(request.uri());
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

    /** What a ticket may look like: opaque, URL-safe, bounded. Anything else is refused. */
    private static final java.util.regex.Pattern TICKET =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._~+/=-]{16,1024}$");

    /** Shared by every socket: the JDK client's own threads are daemons. */
    private static final HttpClient SOCKET_HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();

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
// DATA RECEIVED: {"ticket": "<opaque string>"} parsed with Gson and checked against TICKET;
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
            HttpRequest request = withLicence(HttpRequest.newBuilder(ticketUri))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(ticketBody == null ? "{}" : ticketBody))
                    .build();
            HttpResponse<String> response = send(scope, SOCKET_HTTP, request,
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
                SbsSocket.sharedScheduler(), SOCKET_HTTP, System::currentTimeMillis, Math::random);
    }

    private static boolean ours(java.net.URI uri, String scheme) {
        return uri != null && scheme.equalsIgnoreCase(uri.getScheme()) && DOMAIN.equalsIgnoreCase(uri.getHost());
    }

    /** The ticket out of {@code {"ticket": "…"}}, or an {@link IOException} for anything else. */
    static String parseTicket(String body) throws IOException {
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(body == null ? "" : body);
            if (root.isJsonObject() && root.getAsJsonObject().has("ticket")) {
                com.google.gson.JsonElement ticket = root.getAsJsonObject().get("ticket");
                if (ticket.isJsonPrimitive() && ticket.getAsJsonPrimitive().isString()
                        && TICKET.matcher(ticket.getAsString()).matches()) {
                    return ticket.getAsString();
                }
            }
        } catch (RuntimeException malformed) {
            // Falls through to the refusal below: a body that is not JSON is not a ticket.
        }
        throw new IOException("ticket response was not a ticket");
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
