/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.logic;

import sbs.modid.client.core.api.ApiFailure;
import sbs.modid.client.core.net.SessionIdentity;
import sbs.modid.client.core.net.LinkFilter;

import sbs.modid.client.core.net.RemoteText;

import sbs.modid.client.core.net.validation.MessageSchemas;
import sbs.modid.client.core.net.validation.MessageTypes;
import sbs.modid.client.core.net.validation.SbsReply;
import sbs.modid.client.core.net.validation.ValidationException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.licence.privacy.ConsentManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.core.config.ConfigManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client for the SBS Party Finder service ({@code /party/} on the SBS backend) – the same
 * transport pattern as {@link sbs.modid.client.social.chat.logic.IrcClient}: JDK {@link HttpClient}, licence-token
 * auth, long-poll for live party chat + roster.
 *
 * <p>All calls are asynchronous (daemon executors, never the client thread). This class is a thin
 * request layer; {@link PartyFinderManager} owns the current-party state and the poll loop.
 */
public final class PartyFinderApi {

    /** Every request goes to the SBS backend over HTTPS. */
    private static final String BASE = "https://skyblocksimplified.info";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(32);

    private static final PartyFinderApi INSTANCE = new PartyFinderApi();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();
    private final ExecutorService io = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "SBS-Party-IO");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService pollExec = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "SBS-Party-Poll");
        t.setDaemon(true);
        return t;
    });

    /** One JSON result: exactly one of {@code result} / {@code error} is non-null. */
    public interface Callback {
        void done(JsonObject result, String error);
    }

    private PartyFinderApi() {
    }

    public static PartyFinderApi getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Identity helpers
    // ------------------------------------------------------------------

    public static String selfName() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getGameProfile().name();
    }

    public static String selfUuid() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getUUID().toString();
    }

    public static boolean ready() {
        // Consent belongs in the same answer as "logged in" and "has a token": every caller of
        // ready() is asking whether the party finder can be used at all, and a user who declined
        // should see the feature unavailable rather than see it offered and then fail per request.
        // No token is fine: SbsApi claims a free one on the first request.
        return selfName() != null && ConsentManager.isGranted(ConsentScope.PARTY_FINDER);
    }

    /** Party chat stays licence-only until it has moderation; free tokens never reach it. */
    public static boolean chatAllowed() {
        return sbs.modid.client.core.net.Credentials.current() == sbs.modid.client.core.net.Credentials.Kind.LICENCE;
    }

    /** Shown when a player without a licence tries to chat. */
    public static final String CHAT_LICENCE_ONLY = "Party chat needs a licence token for now.";

    // ------------------------------------------------------------------
    // Endpoints (async)
    // ------------------------------------------------------------------

    /**
     * Lists parties. {@code query} is the free-text search (note, type, location, mob, mode AND
     * member names, server-side); {@code type} filters to one party type; {@code eligibleOnly}
     * drops parties whose requirements the player provably fails.
     */
    public void list(String type, String query, boolean eligibleOnly, Callback cb) {
        StringBuilder q = new StringBuilder("/party/list?v=1");
        if (type != null && !type.isBlank()) {
            q.append("&type=").append(enc(type));
        }
        if (query != null && !query.isBlank()) {
            q.append("&q=").append(enc(query));
        }
        if (eligibleOnly) {
            q.append("&eligible=1");
        }
        io.execute(() -> run(get(q.toString(), TIMEOUT, T_LIST, false), T_LIST, cb));
    }

    public void create(JsonObject spec, Callback cb) {
        try {
            LinkFilter.check(spec, "note", "mob", "location", "mode");
        } catch (LinkFilter.LinkRefusedException refused) {
            cb.done(null, LinkFilter.REFUSED);
            return;
        }
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/party/{create,join,leave,kick,list,chat,poll}
// METHOD: GET and POST
// PURPOSE: The SBS Party Finder - advertise a party with its requirements, and join one.
// DATA SENT: Creating sends the party requirements; join and leave send the party id; kick
//   sends the target uuid, which the party leader has necessarily already seen in game. The
//   sender is identified by the verified SBS session (X-SBS-Session, see SessionIdentity), not
//   by a name or uuid in the body. Credential headers on every call: the licence token, or for a
//   player with no licence the free token (FreeAccess), which is tied to the player's uuid. Chat
//   (POST /party/chat) sends the message text and is licence-only: never sent with a free token.
//   Poll (GET /party/poll) sends the party id and the last message number. Bodies are built with
//   Gson, never string-concatenated.
// DATA RECEIVED: Read-only JSON - the party list and its members. Parsed with Gson.
// SAFETY DECLARATION: The server learns the player's name and uuid from the verified session
//   handshake (Mojang's own join check), never from a self-declared field. The SBS session is
//   held in memory only and sent only to the SBS origin; no Mojang credential is sent. Nothing is sent until the user
//   grants ConsentScope.PARTY_FINDER, which starts off, and then only while they are actively
//   using the finder. No OS or hardware telemetry. See PRIVACY.md.
// ============================================================================
        io.execute(() -> run(post("/party/create", MessageTypes.OUT_PARTY_CREATE, spec, T_SINGLE), T_SINGLE, cb));
    }

    public void join(String partyId, Callback cb) {
        io.execute(() -> run(post("/party/join", MessageTypes.OUT_PARTY_MEMBER, ident(partyId), T_SINGLE), T_SINGLE, cb));
    }

    public void leave(String partyId, Callback cb) {
        io.execute(() -> run(post("/party/leave", MessageTypes.OUT_PARTY_MEMBER, ident(partyId), MessageTypes.PARTY_OK),
                MessageTypes.PARTY_OK, cb));
    }

    public void kick(String partyId, String targetUuid, Callback cb) {
        JsonObject body = ident(partyId);
        body.addProperty("target", targetUuid);
        io.execute(() -> run(post("/party/kick", MessageTypes.OUT_PARTY_KICK, body, T_SINGLE), T_SINGLE, cb));
    }

    public void chat(String partyId, String msg, Callback cb) {
        if (!chatAllowed()) {
            cb.done(null, CHAT_LICENCE_ONLY);
            return;
        }
        if (LinkFilter.containsLink(msg)) {
            cb.done(null, LinkFilter.REFUSED);
            return;
        }
        JsonObject body = ident(partyId);
        body.addProperty("msg", msg);
        io.execute(() -> run(post("/party/chat", MessageTypes.OUT_PARTY_CHAT, body, MessageTypes.PARTY_CHAT_ACK),
                MessageTypes.PARTY_CHAT_ACK, cb));
    }

    /** One long-poll cycle on the poll thread (blocking up to ~32s). */
    public void poll(String partyId, int since, Callback cb) {
        pollExec.execute(() -> run(get("/party/poll?party_id=" + enc(partyId)
                + "&since=" + since + "&timeout=25", POLL_TIMEOUT, MessageTypes.PARTY_POLL, true),
                MessageTypes.PARTY_POLL, cb));
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private JsonObject ident(String partyId) {
        JsonObject body = new JsonObject();
        body.addProperty("party_id", partyId);
        return body;
    }

    /** Shown for 403 {@code not_leader}. */
    public static final String NOT_LEADER = "Only the party leader can do that";

    private static final String T_LIST = MessageTypes.PARTY_LIST;
    private static final String T_SINGLE = MessageTypes.PARTY_SINGLE;

    /** A response, or the message to show when the request could not be made. */
    private record Sent(HttpResponse<String> response, String error) {
    }

    /** {@code required}: the route needs the verified session (all but list, where it is optional). */
    private Sent get(String path, Duration timeout, String typeId, boolean required) {
        try {
            java.util.function.Supplier<HttpRequest.Builder> builder =
                    () -> auth(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(timeout), URI.create(BASE + path)).GET();
            SessionIdentity session = SessionIdentity.get();
            return new Sent(required ? session.send(ConsentScope.PARTY_FINDER, typeId, builder)
                    : session.sendOptional(ConsentScope.PARTY_FINDER, typeId, builder), null);
        } catch (SessionIdentity.SessionException session) {
            return new Sent(null, session.userMessage());
        } catch (Exception e) {
            Exception cause = e instanceof java.io.UncheckedIOException u ? u.getCause() : e;
            ApiFailure.log("[SBS][Party]", "GET " + typeId, cause);
            return new Sent(null, ApiFailure.message(cause));
        }
    }

    private Sent post(String path, String outTypeId, JsonObject body, String typeId) {
        try {
            LinkFilter.check(body, "msg", "note", "mob", "location", "mode");
            MessageSchemas.checkOutgoing(outTypeId, body);
            String json = gson.toJson(body);
            return new Sent(SessionIdentity.get().send(ConsentScope.PARTY_FINDER, typeId,
                    () -> auth(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(TIMEOUT), URI.create(BASE + path))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))), null);
        } catch (LinkFilter.LinkRefusedException refused) {
            return new Sent(null, LinkFilter.REFUSED);
        } catch (SessionIdentity.SessionException session) {
            return new Sent(null, session.userMessage());
        } catch (Exception e) {
            Exception cause = e instanceof java.io.UncheckedIOException u ? u.getCause() : e;
            ApiFailure.log("[SBS][Party]", "POST " + outTypeId, cause);
            return new Sent(null, ApiFailure.message(cause));
        }
    }

    /**
     * Adds the credential through {@link sbs.modid.client.core.net.Credentials#apply}: the licence or
     * tester token, or with none entered the automatic free token (claimed under
     * {@link ConsentScope#PARTY_FINDER} when needed). Runs on the io thread, never the render thread.
     */
    private HttpRequest.Builder auth(HttpRequest.Builder builder, URI uri) {
        try {
            return sbs.modid.client.core.net.Credentials.apply(builder, uri);
        } catch (java.io.IOException e) {
            // The builder supplier cannot throw checked exceptions; unwrapped in get()/post().
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Parses the JSON body and dispatches to the callback (error message on non-200 / parse fail). */
    private void run(Sent sent, String typeId, Callback cb) {
        if (sent.response() == null) {
            cb.done(null, sent.error());
            return;
        }
        HttpResponse<String> response = sent.response();
        SbsReply reply;
        try {
            reply = SbsReply.parse(typeId, response.body());
        } catch (ValidationException invalid) {
            cb.done(null, ValidationException.USER_MESSAGE);
            return;
        }
        // Names, notes and messages are shown in screens and chat lines; they are cleaned once here.
        JsonObject clean = RemoteText.sanitizeTree(reply.data(), 1_024).getAsJsonObject();
        if (response.statusCode() == 200 && !reply.isError()) {
            cb.done(clean, null);
        } else {
            String error = reply.isError() ? reply.error() : "HTTP " + response.statusCode();
            cb.done(clean, "not_leader".equals(error) ? NOT_LEADER : error);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(orEmpty(s), StandardCharsets.UTF_8);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
