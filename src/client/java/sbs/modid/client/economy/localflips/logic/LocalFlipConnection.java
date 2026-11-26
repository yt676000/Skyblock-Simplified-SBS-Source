/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sbs.modid.client.economy.localflips.model.LocalFlip;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * One live link to the local flip program: the event stream, plus the flip list and health
 * refreshed every 20 seconds.
 *
 * <p><b>Threads.</b> Two daemon threads of its own, started by {@link #start} and ended by
 * {@link #stop}: the stream thread blocks in a socket read for as long as the connection lives, so
 * it cannot share {@code SbsExecutors}' pool without permanently taking one of its workers; the
 * list thread does the periodic JSON requests so they never wait behind the stream. Neither thread
 * touches game state. Every parsed result is handed to the client thread through the
 * {@code client} executor ({@code Minecraft.execute}), where the {@link Sink} runs - so a find is
 * drawn and alerted in the same client tick it is delivered in.
 *
 * <p><b>Latency.</b> No polling for SOFORT finds: the stream is read line by line as bytes arrive,
 * each event is parsed on the stream thread the moment its terminating blank line is read, and the
 * hand-off is a single queued task.
 *
 * <p><b>Failure.</b> Unreachable is a normal state. A dropped stream, an error or 40 seconds without
 * a byte (the program pings every 15) reconnects after {@link Backoff}'s schedule. A 401 does not
 * retry: the token is wrong and only a settings change can fix it, so the thread ends and the
 * finder starts a new connection when the settings change. Results from a stopped connection are
 * dropped on the client thread, so a stop is final even for tasks already queued.
 */
public final class LocalFlipConnection {

    /** What the status line shows. */
    public enum State {
        CONNECTING, CONNECTED, DISCONNECTED, UNAUTHORIZED, INVALID_ADDRESS
    }

    /** Receives everything, always on the client thread. */
    public interface Sink {
        void onState(State state, long retryAtMs);

        void onSofort(LocalFlip flip);

        void onWeg(String auction);

        /** Any line arrived (an event or a ping): the connection is alive. */
        void onAlive();

        void onList(List<LocalFlip> flips);

        /** {@code apiLastUpdatedMs} or {@code updatedSeconds} may be absent: -1 / NaN. */
        void onHealth(long apiLastUpdatedMs, double updatedSeconds);
    }

    /** Thrown for a 401, which ends the connection instead of retrying. */
    static final class Unauthorized extends IOException {
        Unauthorized() {
            super("401");
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    /** The program pings every 15 s; 40 s of silence means the link is dead. */
    static final int STREAM_IDLE_TIMEOUT_MS = 40_000;
    /** Over VPN: 15 s ping interval + 10 s slack, as the host's spec asks. */
    static final int VPN_IDLE_TIMEOUT_MS = 25_000;
    private static final int JSON_TIMEOUT_MS = 5_000;
    static final long LIST_REFRESH_MS = 20_000;
    /** The SBS flip server pings every 15 s; 45 s of silence means the link is dead. */
    static final int REMOTE_IDLE_TIMEOUT_MS = 45_000;
    static final long ALERTS_LIST_REFRESH_MS = 30_000;
    static final long REMOTE_RETRY_MS = 3_000;
    private static final String TOKEN_HEADER = "X-SBS-Token";

    private final URI base;
    private final String token;
    private final Sink sink;
    private final Executor client;
    private final boolean vpn;
    /** The SBS flip server over https: no local-address check, licence + consent gate instead. */
    private final boolean remote;
    private final String healthPath;
    private final int idleTimeoutMs;
    private final long listRefreshMs;

    private volatile boolean running;
    private volatile boolean connected;
    private volatile HttpURLConnection stream;
    private Thread streamThread;
    private Thread listThread;
    private final Object listWake = new Object();

    public LocalFlipConnection(URI base, String token, Sink sink, Executor client) {
        this(base, token, sink, client, false);
    }

    /** {@code vpn}: Radmin VPN source - 26.0.0.0/8 allowed, 3-30 s backoff, 25 s silence limit. */
    public LocalFlipConnection(URI base, String token, Sink sink, Executor client, boolean vpn) {
        this(base, token, sink, client, vpn, false, "/health", vpn ? VPN_IDLE_TIMEOUT_MS : STREAM_IDLE_TIMEOUT_MS,
                LIST_REFRESH_MS);
    }

    /**
     * For AH Flip Alerts: the SBS flip server ({@code remote}, https, 45 s without a ping = dead,
     * reconnect after 3 s) or the dev program on this PC. Both refresh the list every 30 s.
     */
    public static LocalFlipConnection flipAlerts(URI base, String healthPath, String token, Sink sink,
                                                 Executor client, boolean remote) {
        return new LocalFlipConnection(base, token, sink, client, false, remote, healthPath,
                remote ? REMOTE_IDLE_TIMEOUT_MS : STREAM_IDLE_TIMEOUT_MS, ALERTS_LIST_REFRESH_MS);
    }

    private LocalFlipConnection(URI base, String token, Sink sink, Executor client, boolean vpn,
                                boolean remote, String healthPath, int idleTimeoutMs, long listRefreshMs) {
        this.remote = remote;
        this.healthPath = healthPath;
        this.idleTimeoutMs = idleTimeoutMs;
        this.listRefreshMs = listRefreshMs;
        this.vpn = vpn;
        this.base = base;
        this.token = token == null ? "" : token.trim();
        this.sink = sink;
        this.client = client;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        streamThread = new Thread(this::streamLoop, "SBS-LocalFlips-Stream");
        streamThread.setDaemon(true);
        listThread = new Thread(this::listLoop, "SBS-LocalFlips-List");
        listThread.setDaemon(true);
        streamThread.start();
        listThread.start();
    }

    /** Ends both threads; idempotent. Closing the socket is what unblocks the stream read. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        connected = false;
        HttpURLConnection open = stream;
        if (open != null) {
            open.disconnect();
        }
        if (streamThread != null) {
            streamThread.interrupt();
        }
        if (listThread != null) {
            listThread.interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Runs {@code task} on the client thread unless this connection has been stopped by then. */
    private void post(Runnable task) {
        client.execute(() -> {
            if (running) {
                task.run();
            }
        });
    }

    // ------------------------------------------------------------------
    // The stream
    // ------------------------------------------------------------------

    private void streamLoop() {
        int failures = 0;
        while (running) {
            post(() -> sink.onState(State.CONNECTING, 0));
            try {
                if (remote ? !remotePermitted() : !resolvesLocally()) {
                    if (remote) {
                        post(() -> sink.onState(State.UNAUTHORIZED, 0));
                        break;
                    }
                    post(() -> sink.onState(State.INVALID_ADDRESS, 0));
                    break;
                }
                readStream();
                if (!running) {
                    break;
                }
                failures = 1;   // a clean end of stream (program restart): retry quickly
            } catch (Unauthorized unauthorized) {
                post(() -> sink.onState(State.UNAUTHORIZED, 0));
                break;
            } catch (IOException | RuntimeException e) {
                if (!running) {
                    break;
                }
                failures = connected ? 1 : failures + 1;
            } finally {
                connected = false;
                stream = null;
            }
            long delay = remote ? REMOTE_RETRY_MS
                    : vpn ? Backoff.vpnDelayMs(failures) : Backoff.delayMs(failures);
            long retryAt = System.currentTimeMillis() + delay;
            post(() -> sink.onState(State.DISCONNECTED, retryAt));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                break;
            }
        }
        running = false;
        synchronized (listWake) {
            listWake.notifyAll();
        }
    }

    /**
     * The remote source may only ever be our own backend over https, and only while the licence and
     * the user's consent cover it - the same gate {@code SbsApi.send} applies to every other request.
     */
    private boolean remotePermitted() {
        return "https".equals(base.getScheme()) && sbs.modid.client.core.api.SbsApi.DOMAIN.equals(base.getHost())
                && !token.isEmpty()
                && sbs.modid.client.core.api.SbsApi.permits(
                        sbs.modid.client.core.licence.privacy.ConsentScope.LICENCE_VALIDATION);
    }

    /** True when the host is this PC or the LAN. Resolving a name happens here, off the client thread. */
    private boolean resolvesLocally() throws IOException {
        for (InetAddress address : InetAddress.getAllByName(base.getHost())) {
            if (!FlipSource.allowed(address, vpn)) {
                return false;
            }
        }
        return true;
    }

    // ============================================================================
    // [MODERATOR AUDIT / NETWORK DISCLOSURE]
    // KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
    // ENDPOINT: {address typed by the player}/flips/stream - default http://127.0.0.1:3020; refused
    //   unless it resolves to a loopback, private-LAN, link-local or unique-local address; in the
    //   "vpn" flip source mode also http://{Radmin IP typed by the player}:{port}, IPv4 in 26.0.0.0/8
    //   only (Radmin VPN's own range - a friend's PC on the same private VPN network)
    // METHOD: GET (Server-Sent Events, one long-lived connection)
    // PURPOSE: Developer-mode Local Flip Finder - live auction flip finds from a flip program the
    //   player runs on their own PC or LAN, shown in a window and in chat
    // DATA SENT: The request line and standard headers; the player's own token for that program as
    //   X-SBS-Token, only when they set one. No Minecraft account data, no SBS licence token
    // DATA RECEIVED: Text event stream - flip finds as JSON (item names, prices, auction ids),
    //   "auction gone" ids and keep-alive comments. Parsed with Gson; the auction id is validated as
    //   32 hex characters and the command to open it is built locally, never taken from the data
    // SAFETY DECLARATION: Only reaches an address on the player's own machine, LAN or Radmin VPN
    //   network, only while developer mode and the feature are both on. Opening an
    //   [AH Flip Alerts, "Server" source] ENDPOINT https://skyblocksimplified.info/flips/stream,
    //   /flips/liste, /flips/health: sends the SBS licence token as X-SBS-Token (header only, never
    //   in the URL), refused unless the licence + ConsentScope.LICENCE_VALIDATION gate of
    //   SbsApi.permits allows it. Receives the same flip JSON as above.
    //   auction is a click by the player; nothing is bought, bid or clicked automatically
    // ============================================================================
    private void readStream() throws IOException {
        int idleMs = idleTimeoutMs;
        HttpURLConnection connection = open("/flips/stream", idleMs);
        connection.setRequestProperty("Accept", "text/event-stream");
        stream = connection;
        if (!running) {
            connection.disconnect();
            return;
        }
        int status = connection.getResponseCode();
        if (status == 401) {
            throw new Unauthorized();
        }
        if (status != 200) {
            throw new IOException("HTTP " + status);
        }
        connected = true;
        post(() -> sink.onState(State.CONNECTED, 0));
        synchronized (listWake) {
            listWake.notifyAll();   // fetch the flip list right away
        }
        SseParser parser = new SseParser(new SseParser.Listener() {
            @Override
            public void onEvent(String type, String data) {
                handleEvent(type, data);
            }

            @Override
            public void onComment(String text) {
                // The keep-alive ping; onAlive below already covers it.
            }
        });
        try (InputStream in = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            long lastAlive = 0;
            while (running && (line = reader.readLine()) != null) {
                // An event is several lines; one "alive" hand-off a second is enough for the
                // "last signal" readout, and the event itself is posted without waiting for it.
                long now = System.currentTimeMillis();
                if (now - lastAlive >= 1_000) {
                    lastAlive = now;
                    post(sink::onAlive);
                }
                parser.accept(line);
            }
        } catch (SocketTimeoutException idle) {
            throw new IOException("stream idle for " + idleMs / 1000 + " s", idle);
        }
    }

    /** Parses one event on the stream thread; only the result crosses to the client thread. */
    private void handleEvent(String type, String data) {
        try {
            switch (type) {
                case "sofort" -> {
                    JsonElement json = JsonParser.parseString(data);
                    if (json.isJsonObject()) {
                        LocalFlip flip = LocalFlip.parse(json.getAsJsonObject());
                        if (flip != null) {
                            post(() -> sink.onSofort(flip));
                        }
                    }
                }
                case "weg" -> {
                    JsonElement json = JsonParser.parseString(data);
                    if (json.isJsonObject() && json.getAsJsonObject().has("auction")
                            && json.getAsJsonObject().get("auction").isJsonPrimitive()) {
                        String auction = LocalFlip.normaliseAuction(
                                json.getAsJsonObject().get("auction").getAsString());
                        if (auction != null) {
                            post(() -> sink.onWeg(auction));
                        }
                    }
                }
                default -> {
                    // Unknown event types are ignored, so the program can add some later.
                }
            }
        } catch (RuntimeException malformed) {
            // One bad event must not end the stream; the next one is independent of it.
        }
    }

    // ------------------------------------------------------------------
    // The flip list and health, every 20 s while connected
    // ------------------------------------------------------------------

    private void listLoop() {
        while (running) {
            synchronized (listWake) {
                try {
                    if (!connected) {
                        listWake.wait(listRefreshMs);
                    }
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
            if (!running) {
                return;
            }
            if (connected) {
                refreshList();
                refreshHealth();
                synchronized (listWake) {
                    try {
                        listWake.wait(listRefreshMs);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }
    }

    // ============================================================================
    // [MODERATOR AUDIT / NETWORK DISCLOSURE]
    // KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
    // ENDPOINT: {address typed by the player}/flips/liste - same local/LAN/Radmin-VPN-only address as the stream
    // METHOD: GET
    // PURPOSE: Developer-mode Local Flip Finder - the program's list of buy-it-now and bid flips,
    //   refreshed every 20 seconds while the stream is connected
    // DATA SENT: The request line and standard headers; X-SBS-Token only when the player set one
    // DATA RECEIVED: Read-only JSON - flips with item names, prices and auction ids. Parsed with Gson
    // SAFETY DECLARATION: Local, LAN or Radmin VPN address only; no Minecraft account data, no SBS licence token
    // ============================================================================
    private void refreshList() {
        try {
            JsonObject body = getJson("/flips/liste");
            JsonElement list = body.get("liste");
            if (list == null || !list.isJsonArray()) {
                return;
            }
            List<LocalFlip> flips = parseList(list.getAsJsonArray());
            post(() -> sink.onList(flips));
        } catch (IOException | RuntimeException e) {
            // The stream decides the connection state; a failed list refresh keeps the last list.
        }
    }

    // ============================================================================
    // [MODERATOR AUDIT / NETWORK DISCLOSURE]
    // KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
    // ENDPOINT: {address typed by the player}/health - same local/LAN/Radmin-VPN-only address as the stream
    // METHOD: GET
    // PURPOSE: Developer-mode Local Flip Finder - how old the program's Hypixel auction data is, for
    //   the "Hypixel AH API stuck" warning; also the "Test connection" button
    // DATA SENT: The request line and standard headers; X-SBS-Token only when the player set one
    // DATA RECEIVED: Read-only JSON - ok flag, port, whether a token is required, two timestamps
    // SAFETY DECLARATION: Local, LAN or Radmin VPN address only; no Minecraft account data, no SBS licence token
    // ============================================================================
    private void refreshHealth() {
        try {
            Health health;
            if (remote) {
                long started = System.nanoTime();
                health = parseHealth(getJson(healthPath), (System.nanoTime() - started) / 1_000_000L);
            } else if (!"/health".equals(healthPath)) {
                checkAllowed(base, vpn);
                long started = System.nanoTime();
                health = parseHealth(getJson(healthPath), (System.nanoTime() - started) / 1_000_000L);
            } else {
                health = health(base, token, vpn);
            }
            post(() -> sink.onHealth(health.apiLastUpdatedMs(), health.updatedSeconds()));
        } catch (IOException | RuntimeException e) {
            // Same as the list: the stream owns the state.
        }
    }

    static List<LocalFlip> parseList(JsonArray array) {
        List<LocalFlip> flips = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                LocalFlip flip = LocalFlip.parse(element.getAsJsonObject());
                if (flip != null) {
                    flips.add(flip);
                }
            }
        }
        return List.copyOf(flips);
    }

    private JsonObject getJson(String path) throws IOException {
        return getJson(base, token, path);
    }

    private HttpURLConnection open(String path, int readTimeoutMs) throws IOException {
        return open(base, token, path, readTimeoutMs);
    }

    // ------------------------------------------------------------------
    // Shared by the connection and the one-off "Test connection"
    // ------------------------------------------------------------------

    /** What {@code /health} answered, and how long it took. */
    public record Health(boolean ok, boolean tokenRequired, long apiLastUpdatedMs, double updatedSeconds,
                         long millis) {
    }

    /**
     * Blocking {@code GET /health}. Throws {@link Unauthorized} on 401 and {@link IOException} when
     * the address is not local or the program does not answer. Never call from the client thread.
     */
    public static Health health(URI base, String token) throws IOException {
        return health(base, token, false);
    }

    public static Health health(URI base, String token, boolean vpn) throws IOException {
        checkAllowed(base, vpn);
        long started = System.nanoTime();
        JsonObject body = getJson(base, token, "/health");
        return parseHealth(body, (System.nanoTime() - started) / 1_000_000L);
    }

    /** Blocking {@code GET /flips/sofort} - the token check of "Test connection". */
    public static void probeSofort(URI base, String token, boolean vpn) throws IOException {
        checkAllowed(base, vpn);
        getJson(base, token, "/flips/sofort");
    }

    private static void checkAllowed(URI base, boolean vpn) throws IOException {
        for (InetAddress address : InetAddress.getAllByName(base.getHost())) {
            if (!FlipSource.allowed(address, vpn)) {
                throw new IOException("not a local, LAN or Radmin VPN address");
            }
        }
    }

    private static Health parseHealth(JsonObject body, long millis) {
        boolean ok = body.has("ok") && body.get("ok").isJsonPrimitive() && body.get("ok").getAsBoolean();
        boolean tokenRequired = body.has("token_required") && body.get("token_required").isJsonPrimitive()
                && body.get("token_required").getAsBoolean();
        long api = body.has("api_last_updated") && body.get("api_last_updated").isJsonPrimitive()
                && body.get("api_last_updated").getAsJsonPrimitive().isNumber()
                ? body.get("api_last_updated").getAsLong() : -1;
        double updated = body.has("updated") && body.get("updated").isJsonPrimitive()
                && body.get("updated").getAsJsonPrimitive().isNumber()
                ? body.get("updated").getAsDouble() : Double.NaN;
        return new Health(ok, tokenRequired, api, updated, millis);
    }

    private static JsonObject getJson(URI base, String token, String path) throws IOException {
        HttpURLConnection connection = open(base, token, path, JSON_TIMEOUT_MS);
        try {
            int status = connection.getResponseCode();
            if (status == 401) {
                throw new Unauthorized();
            }
            if (status != 200) {
                throw new IOException("HTTP " + status);
            }
            try (InputStream in = connection.getInputStream();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement json = JsonParser.parseReader(reader);
                if (!json.isJsonObject()) {
                    throw new IOException("not a JSON object");
                }
                return json.getAsJsonObject();
            }
        } catch (RuntimeException malformed) {
            throw new IOException("malformed JSON", malformed);
        }
    }

    /**
     * Opens a GET without a proxy: the target is on this machine or the LAN, and a system proxy
     * would carry the request (and the token) off it.
     */
    private static HttpURLConnection open(URI base, String token, String path, int readTimeoutMs)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) base.resolve(path).toURL()
                .openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "SBS-LocalFlipFinder");
        if (!token.isEmpty()) {
            connection.setRequestProperty(TOKEN_HEADER, token);
        }
        return connection;
    }
}
