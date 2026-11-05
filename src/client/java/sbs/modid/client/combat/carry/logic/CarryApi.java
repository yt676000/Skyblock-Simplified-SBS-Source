/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry.logic;

import sbs.modid.client.core.api.ApiFailure;
import sbs.modid.client.core.net.SessionIdentity;
import sbs.modid.client.core.net.LinkFilter;

import sbs.modid.client.core.net.RemoteText;
import sbs.modid.client.core.net.validation.MessageSchemas;
import sbs.modid.client.core.net.validation.MessageTypes;
import sbs.modid.client.core.net.validation.SbsReply;
import sbs.modid.client.core.net.validation.ValidationException;

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
//   JSON body with the category, tier and note the user typed or chose; claiming sends only
//   the ticket id. The sender is identified by the verified SBS session (X-SBS-Session, see
//   SessionIdentity), not by a name in the body. Bodies are built with Gson.
// DATA RECEIVED: Read-only JSON - the ticket list and its metadata. Parsed with Gson.
// SAFETY DECLARATION: The server learns who posted from the verified session handshake
//   (Mojang's own join check). The SBS session is held in memory only and sent only to the
//   SBS origin. No Mojang credentials, no OS telemetry. Gated
//   on ConsentScope.CARRY_TICKETS, which starts off. See PRIVACY.md.
// ============================================================================
        get("/carry/meta", MessageTypes.CARRY_META, cb, false);
    }

    public void tickets(Callback cb) {
        get("/carry/tickets", MessageTypes.CARRY_TICKETS, cb, false);
    }

    public void create(String category, String tier, String note, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("category", category);
        body.addProperty("tier", tier);
        body.addProperty("note", note);
        post("/carry/tickets/create", MessageTypes.OUT_CARRY_CREATE, body, MessageTypes.CARRY_TICKET, cb);
    }

    public void claim(String ticketId, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket_id", ticketId);
        post("/carry/tickets/claim", MessageTypes.OUT_CARRY_TICKET_ID, body, MessageTypes.CARRY_TICKET, cb);
    }

    public void unclaim(String ticketId, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket_id", ticketId);
        post("/carry/tickets/unclaim", MessageTypes.OUT_CARRY_TICKET_ID, body, MessageTypes.CARRY_TICKET, cb);
    }

    public void close(String ticketId, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket_id", ticketId);
        post("/carry/tickets/close", MessageTypes.OUT_CARRY_TICKET_ID, body, MessageTypes.CARRY_OK, cb);
    }

    public void chat(String ticketId, String msg, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket_id", ticketId);
        body.addProperty("msg", msg);
        post("/carry/chat", MessageTypes.OUT_CARRY_CHAT, body, MessageTypes.CARRY_CHAT_ACK, cb);
    }

    /** One long-poll round for a ticket's chat; the caller re-arms while it stays open. */
    public void poll(String ticketId, int since, Callback cb) {
        String path = "/carry/poll?ticket_id=" + urlEncode(ticketId) + "&since=" + since;
        pollExec.execute(() -> run(path, null, null, MessageTypes.CARRY_POLL, cb, true));
    }

    public void adminCarriers(Callback cb) {
        get("/carry/admin/carriers", MessageTypes.CARRY_ADMIN_CARRIERS, cb, false);
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
        post("/carry/admin/set_carrier", MessageTypes.OUT_CARRY_SET_CARRIER, body, MessageTypes.CARRY_ADMIN_SET, cb);
    }

    public void adminRemoveCarrier(String ign, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("ign", ign);
        post("/carry/admin/remove_carrier", MessageTypes.OUT_CARRY_REMOVE_CARRIER, body, MessageTypes.CARRY_OK, cb);
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private void get(String path, String typeId, Callback cb, boolean isPoll) {
        io.execute(() -> run(path, null, null, typeId, cb, isPoll));
    }

    private void post(String path, String outTypeId, JsonObject body, String typeId, Callback cb) {
        io.execute(() -> run(path, outTypeId, body, typeId, cb, false));
    }

    private void run(String path, String outTypeId, JsonObject body, String typeId, Callback cb, boolean isPoll) {
        try {
            if (body != null) {
                LinkFilter.check(body, "note", "msg");
                MessageSchemas.checkOutgoing(outTypeId, body);
            }
            String json = body == null ? null : gson.toJson(body);
            java.util.function.Supplier<HttpRequest.Builder> request = () -> {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path))
                        .timeout(isPoll ? POLL_TIMEOUT : TIMEOUT)
                        .header("Authorization", "Bearer " + token())
                        .header("Content-Type", "application/json");
                return json == null ? builder.GET()
                        : builder.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            };
            // The ticket board, its chat and poll need the verified session; meta and the admin
            // routes are licence-only and never start a handshake.
            HttpResponse<String> response = needsSession(path)
                    ? SessionIdentity.get().send(ConsentScope.CARRY_TICKETS, typeId, request)
                    : sbs.modid.client.core.api.SbsApi.send(ConsentScope.CARRY_TICKETS, typeId, request.get().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            SbsReply reply = SbsReply.parse(typeId, response.body());
            if (reply.isError()) {
                complete(cb, null, reply.error());
            } else {
                // Notes, names and chat lines are drawn into screens; cleaned once here.
                complete(cb, RemoteText.sanitizeTree(reply.data(), 1_024).getAsJsonObject(), null);
            }
        } catch (LinkFilter.LinkRefusedException refused) {
            complete(cb, null, LinkFilter.REFUSED);
        } catch (SessionIdentity.SessionException session) {
            complete(cb, null, session.userMessage());
        } catch (ValidationException invalid) {
            complete(cb, null, ValidationException.USER_MESSAGE);
        } catch (Exception e) {
            ApiFailure.log("[SBS][Carry]", path, e);
            complete(cb, null, ApiFailure.message(e));
        }
    }

    static boolean needsSession(String path) {
        return path.startsWith("/carry/tickets") || path.startsWith("/carry/chat") || path.startsWith("/carry/poll");
    }

    /** Results hop back to the client thread - callers touch screen state directly. */
    private static void complete(Callback cb, JsonObject result, String error) {
        Minecraft.getInstance().execute(() -> cb.done(result, error));
    }

    private static String urlEncode(String text) {
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
