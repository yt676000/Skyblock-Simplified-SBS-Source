/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry.logic;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client for the SBS Carry-Ticket service ({@code /carry/} on the SBS backend) - the same
 * transport pattern as {@code PartyFinderApi}: JDK HttpClient, licence-token Bearer auth, one
 * long-poll per open ticket chat.
 *
 * <p>All calls are asynchronous (daemon executors, never the client thread). Messages carry the
 * player's display name; the carrier and owner tags shown beside them are assigned by the server,
 * not by this client.
 */
public final class CarryApi {

    private static final String BASE = "https://skyblocksimplified.info";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(32);

    private static final CarryApi INSTANCE = new CarryApi();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Gson gson = new Gson();
    private final ExecutorService io = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "SBS-Carry-IO");
        t.setDaemon(true);
        return t;
    });
    // A long-poll holds its thread for up to ~30s. A single-thread executor therefore let only ONE
    // ticket's chat update at a time - switching between claimed tickets queued the new poll behind
    // the old one. A cached pool runs them concurrently; stale rounds are ignored by generation.
    private final ExecutorService pollExec = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "SBS-Carry-Poll");
        t.setDaemon(true);
        return t;
    });

    /** One JSON result: exactly one of {@code result} / {@code error} is non-null. */
    public interface Callback {
        void done(JsonObject result, String error);
    }

    private CarryApi() {
    }

    public static CarryApi getInstance() {
        return INSTANCE;
    }

    public static String selfName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().name();
    }

    private static String token() {
        return sbs.modid.client.core.config.LicenceToken.getInstance().get();
    }

    // ------------------------------------------------------------------
    // Endpoints
    // ------------------------------------------------------------------

    public void meta(Callback cb) {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/carry/{meta,tickets,create,claim}
// METHOD: GET and POST
// PURPOSE: The carry-ticket board - list what carriers offer, post a ticket, claim one.
// DATA SENT: Reads send nothing beyond the licence-token headers. Posting a ticket sends a
//   JSON body with the category, tier, note and THE LOCAL PLAYER DISPLAY NAME, all of which
//   the user typed or chose in the act of posting; claiming sends only the ticket id. Bodies
//   are built with Gson.
// DATA RECEIVED: Read-only JSON - the ticket list and its metadata. Parsed with Gson.
// SAFETY DECLARATION: Posting a ticket transmits the local player display name, because a
//   ticket nobody can be contacted about is useless; reading the board transmits nothing but
//   the licence token. No Mojang credentials, no session id, no uuid, no OS telemetry. Gated
//   on ConsentScope.CARRY_TICKETS, which starts off. See PRIVACY.md.
// ============================================================================
        get("/carry/meta", cb, false);
    }

    public void tickets(Callback cb) {
        get("/carry/tickets", cb, false);
    }

    public void create(String category, String tier, String note, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("category", category);
        body.addProperty("tier", tier);
        body.addProperty("note", note);
        body.addProperty("name", selfName());
        post("/carry/tickets/create", body, cb);
    }

    public void claim(String ticketId, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket_id", ticketId);
        post("/carry/tickets/claim", body, cb);
    }

    public void unclaim(String ticketId, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket_id", ticketId);
        post("/carry/tickets/unclaim", body, cb);
    }

    public void close(String ticketId, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket_id", ticketId);
        post("/carry/tickets/close", body, cb);
    }

    public void chat(String ticketId, String msg, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket_id", ticketId);
        body.addProperty("msg", msg);
        body.addProperty("name", selfName());
        post("/carry/chat", body, cb);
    }

    /** One long-poll round for a ticket's chat; the caller re-arms while it stays open. */
    public void poll(String ticketId, int since, Callback cb) {
        String path = "/carry/poll?ticket_id=" + urlEncode(ticketId) + "&since=" + since;
        pollExec.execute(() -> run(path, null, cb, true));
    }

    public void adminCarriers(Callback cb) {
        get("/carry/admin/carriers", cb, false);
    }

    public void adminSetCarrier(String targetToken, String ign, String category, String tiersCsv,
                                Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("token", targetToken);
        body.addProperty("ign", ign);
        body.addProperty("category", category);
        String csv = tiersCsv == null ? "" : tiersCsv.trim();
        if (csv.isEmpty() || csv.equalsIgnoreCase("all")) {
            body.addProperty("tiers", "all");
        } else {
            com.google.gson.JsonArray tiers = new com.google.gson.JsonArray();
            for (String tier : csv.split("[,;\\s]+")) {
                if (!tier.isBlank()) {
                    tiers.add(tier.trim());
                }
            }
            body.add("tiers", tiers);
        }
        post("/carry/admin/set_carrier", body, cb);
    }

    public void adminRemoveCarrier(String ign, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ign", ign);
        post("/carry/admin/remove_carrier", body, cb);
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private void get(String path, Callback cb, boolean isPoll) {
        io.execute(() -> run(path, null, cb, isPoll));
    }

    private void post(String path, JsonObject body, Callback cb) {
        io.execute(() -> run(path, body, cb, false));
    }

    private void run(String path, JsonObject body, Callback cb, boolean isPoll) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(isPoll ? POLL_TIMEOUT : TIMEOUT)
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json");
            HttpRequest request = body == null ? builder.GET().build()
                    : builder.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body),
                            StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = sbs.modid.client.core.api.SbsApi.send(
                    ConsentScope.CARRY_TICKETS, http, request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // Parse leniently: Gson 2.11 throws if a non-object body (a bare string/number the
            // server may return on some paths) is coerced into JsonObject.class. Read a
            // JsonElement first and branch on its actual shape instead of crashing to "network:".
            JsonElement parsed = null;
            try {
                if (response.body() != null && !response.body().isBlank()) {
                    parsed = JsonParser.parseString(response.body());
                }
            } catch (JsonParseException malformed) {
                parsed = null;
            }
            if (parsed == null || !parsed.isJsonObject()) {
                // Treat a primitive body as the server's error text; anything else is empty.
                String message = parsed != null && parsed.isJsonPrimitive()
                        ? parsed.getAsString() : "empty_response";
                complete(cb, null, message);
                return;
            }
            JsonObject json = parsed.getAsJsonObject();
            if (json.has("error") && !json.get("error").isJsonNull()) {
                complete(cb, null, json.get("error").getAsString());
            } else {
                complete(cb, json, null);
            }
        } catch (Exception e) {
            complete(cb, null, "network: " + e.getMessage());
        }
    }

    /** Results hop back to the client thread - callers touch screen state directly. */
    private static void complete(Callback cb, JsonObject result, String error) {
        Minecraft.getInstance().execute(() -> cb.done(result, error));
    }

    private static String urlEncode(String text) {
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
