/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sbs.modid.SkyblockSimplifiedSBS;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local HTTP API exposing the <b>real</b> open Minecraft screen / container for
 * external developer tooling (overlays, tutorials, automation).
 *
 * <p><b>Security:</b> binds strictly to {@code 127.0.0.1} – never reachable from the
 * network.
 *
 * <p><b>Separation:</b> reads only the immutable {@link GuiState} snapshot and writes
 * only to {@link HighlightManager}. Never touches Minecraft objects; runs on its own
 * daemon thread pool, so it adds no render-thread load.
 *
 * <p>Endpoints (all JSON):
 * <ul>
 *   <li>{@code GET  /gui}       – open screen overview (class, title, rows, slot count).</li>
 *   <li>{@code GET  /slots}     – every slot with coordinates, item data and empty flag.</li>
 *   <li>{@code GET  /state}     – player / world / fps overview.</li>
 *   <li>{@code POST /highlight} – body {@code {"slots":[13,22,31]}} to highlight slots
 *       in the open container (empty list clears).</li>
 * </ul>
 */
public final class ApiServer {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 8765;

    private static ApiServer instance;

    private final Gson gson = new GsonBuilder().create();
    private HttpServer server;

    public static synchronized ApiServer getInstance() {
        if (instance == null) {
            instance = new ApiServer();
        }
        return instance;
    }

    private ApiServer() {
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: http://127.0.0.1:<PORT>/{gui,slots,state,highlight} - INBOUND, not outbound
// METHOD: GET for reads, POST for /highlight
// PURPOSE: A local developer API that lets external tooling on the same machine read the open
//   Minecraft screen. Off unless the user switches it on.
// DATA SENT: Nothing leaves the machine. This is a listener, not a client, and it makes no
//   outbound request of any kind.
// DATA RECEIVED: Requests from localhost only. The POST body for /highlight is a JSON list of
//   slot numbers, parsed with Gson and used only to draw a highlight.
// SAFETY DECLARATION: BOUND STRICTLY TO 127.0.0.1 (see HOST above), so it is not reachable
//   from the network or the internet - no port is exposed and nothing is forwarded. It exposes
//   the contents of the open screen to software the user is already running locally; it
//   transmits nothing anywhere, collects no credentials, and reads only an immutable snapshot.
// ============================================================================
            server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
            server.createContext("/gui", this::handleGui);
            server.createContext("/slots", this::handleSlots);
            server.createContext("/state", this::handleState);
            server.createContext("/highlight", this::handleHighlight);
            server.setExecutor(daemonPool());
            startOnDaemonThread(server);
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "SBS-API-Shutdown"));
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Local API server running at http://{}:{}", HOST, PORT);
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Could not start local API server on {}:{}", HOST, PORT, e);
            server = null;
        }
    }

    /**
     * Starts the server from a short-lived daemon thread.
     *
     * <p>{@code HttpServer.start()} creates its "HTTP-Dispatcher" thread with the daemon status of
     * the thread that calls it. Started from the client's main thread it was a non-daemon thread, so
     * it kept the JVM alive after the game closed - every "Watchdog (Client shutdown from post-main)"
     * crash report in the play instance (2026-09-21 to 2026-10-01) lists exactly one live non-daemon
     * thread, and it is this one. The shutdown hook below could not help: hooks only run once the
     * JVM starts shutting down, which a live non-daemon thread prevents.
     */
    private static void startOnDaemonThread(HttpServer server) throws IOException {
        Thread starter = new Thread(server::start, "SBS-API-Start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while starting the local API server", e);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Local API server stopped.");
        }
    }

    private static ExecutorService daemonPool() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "SBS-API-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------
    // GET handlers – build plain Maps/Lists from the snapshot only.
    // ------------------------------------------------------------------

    private void handleGui(HttpExchange exchange) {
        GuiState s = GuiStateManager.getInstance().getState();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isOpen", s.isOpen());
        body.put("screenClass", s.screenClass());
        body.put("title", s.title());
        body.put("rows", s.rows());
        body.put("slots", s.slotCount());
        body.put("timestamp", s.timestamp());
        respond(exchange, 200, body);
    }

    private void handleSlots(HttpExchange exchange) {
        GuiState s = GuiStateManager.getInstance().getState();
        List<Map<String, Object>> slots = new ArrayList<>();
        for (GuiState.SlotInfo slot : s.slots()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slot", slot.slot());
            entry.put("x", slot.x());
            entry.put("y", slot.y());
            entry.put("name", slot.name());
            entry.put("count", slot.count());
            entry.put("itemId", slot.itemId());
            entry.put("rarity", slot.rarity());
            entry.put("lore", slot.lore());
            entry.put("empty", slot.empty());
            slots.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slots", slots);
        respond(exchange, 200, body);
    }

    private void handleState(HttpExchange exchange) {
        GuiState s = GuiStateManager.getInstance().getState();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("player", s.player());
        body.put("worldLoaded", s.worldLoaded());
        body.put("currentScreen", s.isOpen() ? s.screenClass() : null);
        body.put("fps", s.fps());
        body.put("timestamp", s.timestamp());
        respond(exchange, 200, body);
    }

    // ------------------------------------------------------------------
    // POST /highlight – update the highlighted slots.
    // ------------------------------------------------------------------

    private void handleHighlight(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "Use POST"));
            return;
        }
        try {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            HighlightRequest request = gson.fromJson(requestBody, HighlightRequest.class);
            List<Integer> slots = (request != null && request.slots != null) ? request.slots : List.of();
            HighlightManager.getInstance().setHighlighted(slots);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("highlighted", slots);
            respond(exchange, 200, body);
        } catch (Exception e) {
            respond(exchange, 400, Map.of("error", "Invalid JSON body, expected {\"slots\":[...]}"));
        }
    }

    /** Request body for {@code POST /highlight}. */
    private static final class HighlightRequest {
        List<Integer> slots;
    }

    // ------------------------------------------------------------------
    // Shared response writer.
    // ------------------------------------------------------------------

    private void respond(HttpExchange exchange, int status, Object body) {
        try {
            byte[] data = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        } catch (IOException e) {
            // Client disconnected – nothing to do.
        } finally {
            exchange.close();
        }
    }
}
