/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.logic;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    private static String token() {
        String t = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        return t == null ? "" : t.trim();
    }

    public static boolean ready() {
        // Consent belongs in the same answer as "logged in" and "has a token": every caller of
        // ready() is asking whether the party finder can be used at all, and a user who declined
        // should see the feature unavailable rather than see it offered and then fail per request.
        return selfName() != null && !token().isEmpty()
                && ConsentManager.isGranted(ConsentScope.PARTY_FINDER);
    }

    // ------------------------------------------------------------------
    // Endpoints (async)
    // ------------------------------------------------------------------

    /**
     * Lists parties. {@code query} is the free-text search (note, type, location, mob, mode AND
     * member names, server-side); {@code type} filters to one party type; {@code eligibleOnly}
     * drops parties whose requirements the player provably fails.
     */
    public void list(String type, String query, boolean eligibleOnly, Callback cb) {
        StringBuilder q = new StringBuilder("/party/list?uuid=").append(enc(selfUuid()));
        if (type != null && !type.isBlank()) {
            q.append("&type=").append(enc(type));
        }
        if (query != null && !query.isBlank()) {
            q.append("&q=").append(enc(query));
        }
        if (eligibleOnly) {
            q.append("&eligible=1");
        }
        io.execute(() -> run(get(q.toString()), cb));
    }

    public void create(JsonObject spec, Callback cb) {
        spec.addProperty("name", orEmpty(selfName()));
        spec.addProperty("uuid", orEmpty(selfUuid()));
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/party/{create,join,leave,kick,list}
// METHOD: GET and POST
// PURPOSE: The SBS Party Finder - advertise a party with its requirements, and join one.
// DATA SENT: Creating sends a JSON body with THE LOCAL PLAYER NAME AND MOJANG UUID plus the
//   party requirements; join and leave send the party id; kick sends the target uuid, which
//   the party leader has necessarily already seen in game. Licence-token headers on every
//   call. Bodies are built with Gson, never string-concatenated.
// DATA RECEIVED: Read-only JSON - the party list and its members. Parsed with Gson.
// SAFETY DECLARATION: THIS ENDPOINT DOES TRANSMIT THE LOCAL PLAYER NAME AND MOJANG UUID - a
//   party finder cannot list a party without saying who is in it. Both are public account
//   data, never a session token and never a Mojang credential. Nothing is sent until the user
//   grants ConsentScope.PARTY_FINDER, which starts off, and then only while they are actively
//   using the finder. No OS or hardware telemetry. See PRIVACY.md.
// ============================================================================
        io.execute(() -> run(post("/party/create", spec), cb));
    }

    public void join(String partyId, Callback cb) {
        io.execute(() -> run(post("/party/join", ident(partyId)), cb));
    }

    public void leave(String partyId, Callback cb) {
        io.execute(() -> run(post("/party/leave", ident(partyId)), cb));
    }

    public void kick(String partyId, String targetUuid, Callback cb) {
        JsonObject body = ident(partyId);
        body.addProperty("target", targetUuid);
        io.execute(() -> run(post("/party/kick", body), cb));
    }

    public void chat(String partyId, String msg, Callback cb) {
        JsonObject body = ident(partyId);
        body.addProperty("msg", msg);
        io.execute(() -> run(post("/party/chat", body), cb));
    }

    /** One long-poll cycle on the poll thread (blocking up to ~32s). */
    public void poll(String partyId, int since, Callback cb) {
        pollExec.execute(() -> run(get("/party/poll?party_id=" + enc(partyId)
                + "&since=" + since + "&timeout=25", POLL_TIMEOUT), cb));
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private JsonObject ident(String partyId) {
        JsonObject body = new JsonObject();
        body.addProperty("party_id", partyId);
        body.addProperty("name", orEmpty(selfName()));
        body.addProperty("uuid", orEmpty(selfUuid()));
        return body;
    }

    private HttpResponse<String> get(String path) {
        return get(path, TIMEOUT);
    }

    private HttpResponse<String> get(String path, Duration timeout) {
        try {
            HttpRequest request = auth(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(timeout))
                    .GET().build();
            return sbs.modid.client.core.api.SbsApi.send(
                    ConsentScope.PARTY_FINDER, http, request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Party] GET {} failed: {}", path, e.toString());
            return null;
        }
    }

    private HttpResponse<String> post(String path, JsonObject body) {
        try {
            HttpRequest request = auth(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(TIMEOUT))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            return sbs.modid.client.core.api.SbsApi.send(
                    ConsentScope.PARTY_FINDER, http, request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Party] POST {} failed: {}", path, e.toString());
            return null;
        }
    }

    private HttpRequest.Builder auth(HttpRequest.Builder builder) {
        String t = token();
        if (!t.isEmpty()) {
            builder.header("Authorization", "Bearer " + t);
            builder.header("X-Api-Token", t);
        }
        return builder;
    }

    /** Parses the JSON body and dispatches to the callback (error message on non-200 / parse fail). */
    private void run(HttpResponse<String> response, Callback cb) {
        if (response == null) {
            cb.done(null, "Connection failed");
            return;
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            cb.done(null, "Bad response");
            return;
        }
        if (response.statusCode() == 200 && !json.has("error")) {
            cb.done(json, null);
        } else {
            cb.done(json, json.has("error") ? json.get("error").getAsString()
                    : "HTTP " + response.statusCode());
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(orEmpty(s), StandardCharsets.UTF_8);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
