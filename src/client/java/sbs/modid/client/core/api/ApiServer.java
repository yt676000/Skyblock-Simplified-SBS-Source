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
 * network. <b>Off by default</b>: started and stopped only through {@link #setEnabled}, which
 * follows the "Local API Server" setting ({@code SBSConfig.localApi.enabled}).
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
    private volatile HttpServer server;

    /** Whether the server is bound and serving; read every tick by the capture gate. */
    public boolean running() {
        return server != null;
    }

    public static synchronized ApiServer getInstance() {
        if (instance == null) {
            instance = new ApiServer();
        }
        return instance;
    }

    /**
     * Starts and stops happen here rather than on the caller's thread: binding the socket and
     * {@link #startOnDaemonThread} can block for up to five seconds, which must never land on the
     * client thread when the settings toggle is clicked. Its own single thread (not
     * {@code SbsExecutors.worker()}) because order matters - on, off, on clicked quickly must end
     * running. Daemon, like every thread the mod starts.
     */
    private final ExecutorService control = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-API-Control");
        thread.setDaemon(true);
        return thread;
    });

    /** The state the user last asked for; every queued task applies the latest, not its own. */
    private volatile boolean wanted;

    private boolean shutdownHookAdded;   // guarded by this

    private ApiServer() {
    }

    /**
     * Applies the "Local API Server" setting: starts the server when {@code enabled}, stops it when
     * not. Safe from any thread and returns at once; the work runs on {@link #control}.
     */
    public void setEnabled(boolean enabled) {
        wanted = enabled;
        control.execute(this::applyWanted);
    }

    /** The state last requested through {@link #setEnabled}. */
    public boolean enabled() {
        return wanted;
    }

    private void applyWanted() {
        if (wanted) {
            start();
        } else {
            stop();
        }
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
//   Minecraft screen. Off by default: it runs only while the "Local API Server" setting (SBS
//   Settings, config key localApi.enabled) is on, and stops when that is switched off.
// DATA SENT: Nothing leaves the machine. This is a listener, not a client, and it makes no
//   outbound request of any kind.
// DATA RECEIVED: Requests from localhost only, addressed by Host and carrying no Origin. The
//   POST body for /highlight is a JSON list of slot numbers (64 KiB cap, local.highlight
//   schema), used only to draw a highlight.
// SAFETY DECLARATION: BOUND STRICTLY TO 127.0.0.1 (see HOST above), so it is not reachable
//   from the network or the internet - no port is exposed and nothing is forwarded. It exposes
//   the contents of the open screen to software the user is already running locally; it
//   transmits nothing anywhere, collects no credentials, and reads only an immutable snapshot.
// ============================================================================
            server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
            server.createContext("/gui", guarded(this::handleGui));
            server.createContext("/slots", guarded(this::handleSlots));
            server.createContext("/state", guarded(this::handleState));
            server.createContext("/highlight", guarded(this::handleHighlight));
            server.setExecutor(daemonPool());
            startOnDaemonThread(server);
            if (!shutdownHookAdded) {   // once per JVM, not once per toggle
                Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "SBS-API-Shutdown"));
                shutdownHookAdded = true;
            }
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

    /**
     * Accepts a request only when it is addressed to this listener by name ({@code Host} is
     * {@code 127.0.0.1:PORT} or {@code localhost:PORT}) and carries no {@code Origin} header, so a
     * web page in a local browser cannot read or drive it.
     */
    private com.sun.net.httpserver.HttpHandler guarded(com.sun.net.httpserver.HttpHandler handler) {
        return exchange -> {
            String host = exchange.getRequestHeaders().getFirst("Host");
            boolean hostOk = (HOST + ":" + PORT).equals(host) || ("localhost:" + PORT).equals(host);
            if (!hostOk || exchange.getRequestHeaders().containsKey("Origin")) {
                respond(exchange, 403, Map.of("error", "forbidden"));
                return;
            }
            handler.handle(exchange);
        };
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
        GuiStateManager.getInstance().awaitFresh();
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
        GuiStateManager.getInstance().awaitFresh();
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
        GuiStateManager.getInstance().awaitFresh();
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
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                respond(exchange, 415, Map.of("error", "Use Content-Type: application/json"));
                return;
            }
            byte[] raw = sbs.modid.client.core.net.SecureHttp.limit(exchange.getRequestBody(), 64 * 1024).readAllBytes();
            String requestBody = new String(raw, StandardCharsets.UTF_8);
            HighlightRequest request = sbs.modid.client.core.net.validation.MessageSchemas.parse(
                    sbs.modid.client.core.net.validation.MessageTypes.LOCAL_HIGHLIGHT, requestBody, HighlightRequest.class);
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
