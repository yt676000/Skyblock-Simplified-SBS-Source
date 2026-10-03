/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import sbs.modid.SkyblockSimplifiedSBS;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * One long-lived WebSocket to the SBS backend: a ticket, a connection, a heartbeat and a reconnect
 * schedule. Created only through {@link SbsApi#openSocket}, which supplies the ticket source that
 * runs through the licence and consent gates.
 *
 * <h2>Lifecycle</h2>
 * {@link #start()} begins connecting and {@link #stop()} ends it. In between, the socket keeps
 * itself connected: a dropped connection is retried after a {@link Backoff} delay (exponential,
 * jittered, capped), and a connection that has heard nothing for {@code staleAfterMs} is treated
 * as dropped. There is at most one connection per instance, and every attempt gets a generation
 * number, so a callback from an earlier connection never acts on the current one.
 *
 * <p>Each attempt fetches a fresh ticket first. A ticket refused for consent or licence reasons
 * ({@link SbsApi.NoConsentException}, {@link SbsApi.NoLicenceException}) or rejected by the server
 * ({@link TicketRejectedException}) <b>stops</b> the socket instead of backing it off. A retry would
 * turn the user's "no" into a delayed "yes".
 *
 * <h2>Threads</h2>
 * Listener methods are called on the HTTP client's threads or on this socket's scheduler, never on
 * the game thread, and never while this object's lock is held. A listener must not touch game
 * state; it parses and hands the result over to the client thread itself.
 *
 * <h2>Sending</h2>
 * The JDK socket refuses a text send while the previous one is incomplete, so {@link #send} chains
 * each message on the one before it. Callers may send from any thread.
 */
public final class SbsSocket {

    /** What the owner of a socket is told. Called off the game thread; must not throw. */
    public interface Listener {

        /** A connection is open and ready for {@link #send}. */
        void onOpen();

        /** One complete text message from the server. */
        void onMessage(String text);

        /** The connection ended; a reconnect is scheduled unless the socket was stopped. */
        void onClosed(String why);

        /** The socket stopped on its own and will not retry until {@link #start()} is called. */
        default void onStopped(StopReason reason) {
        }
    }

    /** Fetches a short-lived ticket for one connection attempt. Blocking; run off the game thread. */
    @FunctionalInterface
    public interface TicketSource {
        String fetch() throws IOException, InterruptedException;
    }

    /** The server answered the ticket request with a refusal that waiting will not fix. */
    public static final class TicketRejectedException extends IOException {
        public TicketRejectedException(String message) {
            super(message);
        }
    }

    /** Where the socket is in its lifecycle. */
    public enum State {
        /** Never started. */
        IDLE,
        /** Fetching a ticket or opening the connection. */
        CONNECTING,
        /** Connected. */
        OPEN,
        /** Disconnected, with a retry scheduled. */
        WAITING,
        /** Stopped; nothing is scheduled. */
        STOPPED
    }

    /** Why the socket stopped itself. */
    public enum StopReason {
        /** The licence or consent gate refused the ticket request; nothing was sent. */
        REFUSED,
        /** The server rejected the ticket request (bad or expired licence). */
        REJECTED
    }

    /** Timings and limits. {@link #defaults()} is what production uses. */
    public record Settings(long baseDelayMs, long capDelayMs, long stableAfterMs, long heartbeatMs,
                           long staleAfterMs, int maxMessageChars, Duration connectTimeout) {

        /** Reconnect from 2 s up to 5 min, heartbeat every 30 s, give up on silence after 75 s. */
        public static Settings defaults() {
            return new Settings(2_000L, 300_000L, 60_000L, 30_000L, 75_000L, 64 * 1024,
                    Duration.ofSeconds(10));
        }
    }

    /** One daemon thread for every socket's attempts and timers: none of that work is heavy. */
    private static ScheduledExecutorService sharedScheduler;

    static synchronized ScheduledExecutorService sharedScheduler() {
        if (sharedScheduler == null) {
            sharedScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SBS-Socket");
                thread.setDaemon(true);
                return thread;
            });
        }
        return sharedScheduler;
    }

    private final URI uri;
    private final String ticketHeader;
    private final TicketSource tickets;
    private final Listener listener;
    private final Settings settings;
    private final ScheduledExecutorService scheduler;
    private final HttpClient http;
    private final LongSupplier clock;
    private final Backoff backoff;

    private State state = State.IDLE;
    private int generation;
    private WebSocket current;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private ScheduledFuture<?> retry;
    private ScheduledFuture<?> heartbeat;
    private long nextAttemptAt;
    private volatile long lastInboundAt;
    private volatile boolean pingInFlight;

    SbsSocket(URI uri, String ticketHeader, TicketSource tickets, Listener listener, Settings settings,
              ScheduledExecutorService scheduler, HttpClient http, LongSupplier clock,
              DoubleSupplier jitter) {
        this.uri = uri;
        this.ticketHeader = ticketHeader;
        this.tickets = tickets;
        this.listener = listener;
        this.settings = settings;
        this.scheduler = scheduler;
        this.http = http;
        this.clock = clock;
        this.backoff = new Backoff(settings.baseDelayMs(), settings.capDelayMs(),
                settings.stableAfterMs(), jitter);
    }

    // ------------------------------------------------------------------ control

    /** Starts connecting, unless already connected or trying to. Safe to call every tick. */
    public void start() {
        int gen;
        synchronized (this) {
            if (state == State.CONNECTING || state == State.OPEN || state == State.WAITING) {
                return;
            }
            backoff.reset();
            state = State.CONNECTING;
            gen = ++generation;
        }
        scheduler.execute(() -> attempt(gen));
    }

    /**
     * Closes the connection and cancels every timer. Nothing is sent after this returns except the
     * close frame itself.
     *
     * @param graceful {@code true} to send a close frame before dropping the connection,
     *                 {@code false} to drop it at once (consent withdrawn)
     */
    public void stop(boolean graceful) {
        WebSocket socket;
        synchronized (this) {
            if (state == State.STOPPED || state == State.IDLE) {
                state = State.STOPPED;
                return;
            }
            state = State.STOPPED;
            generation++;
            socket = current;
            current = null;
            cancelTimers();
        }
        if (socket == null) {
            return;
        }
        if (!graceful) {
            socket.abort();
            return;
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "")
                    .orTimeout(2, TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> socket.abort());
        } catch (IllegalStateException alreadyClosing) {
            socket.abort();
        }
    }

    /** {@link #stop(boolean)} with a close frame. */
    public void stop() {
        stop(true);
    }

    /**
     * Queues one text message behind any still being sent.
     *
     * @return {@code false} when no connection is open, so nothing was queued
     */
    public boolean send(String text) {
        synchronized (this) {
            WebSocket socket = current;
            if (socket == null || state != State.OPEN) {
                return false;
            }
            int gen = generation;
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: the WebSocket opened by attempt() below - only ever an SBS backend URI, which
//   SbsApi.openSocket checks before this object exists
// METHOD: WebSocket text frame
// PURPOSE: Sends one message the owning feature built (for Crystal Hollows Structure Sharing:
//   subscribe / report / unsubscribe, see StructureProtocol)
// DATA SENT: Exactly the text passed in. This class adds nothing to it.
// DATA RECEIVED: Nothing on this path.
// SAFETY DECLARATION: No credential is sent here; the ticket travels once, as a header of the
//   handshake. No Mojang credentials, no session id, no OS telemetry. Only reachable after the
//   consent gate admitted the ticket request that opened this connection.
// ============================================================================
            sendChain = sendChain
                    .handle((ignored, error) -> null)
                    .thenCompose(ignored -> socket.sendText(text, true))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            socket.abort();
                            disconnected(gen, "send failed: " + error);
                        }
                    });
            return true;
        }
    }

    /** Where the socket is now. */
    public synchronized State state() {
        return state;
    }

    /** When the next reconnect is due ({@code clock} time), or {@code 0} when none is scheduled. */
    public synchronized long nextAttemptAt() {
        return state == State.WAITING ? nextAttemptAt : 0L;
    }

    // ------------------------------------------------------------------ connecting

    private void attempt(int gen) {
        synchronized (this) {
            if (gen != generation || state != State.CONNECTING) {
                return;
            }
        }
        String ticket;
        try {
            ticket = tickets.fetch();
        } catch (SbsApi.NoConsentException | SbsApi.NoLicenceException refused) {
            stopSelf(gen, StopReason.REFUSED);
            return;
        } catch (TicketRejectedException rejected) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Socket] ticket rejected for {}: {}",
                    uri.getPath(), rejected.getMessage());
            stopSelf(gen, StopReason.REJECTED);
            return;
        } catch (IOException failed) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Socket] no ticket for {}: {}",
                    uri.getPath(), failed.toString());
            scheduleRetry(gen);
            return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            scheduleRetry(gen);
            return;
        }
        if (ticket == null || ticket.isBlank()) {
            scheduleRetry(gen);
            return;
        }
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: the URI handed to SbsApi.openSocket - only ever wss://skyblocksimplified.info/...
// METHOD: WebSocket handshake (HTTP Upgrade over TLS)
// PURPOSE: Opens the live channel a feature needs, for Crystal Hollows Structure Sharing the
//   per-lobby structure feed
// DATA SENT: The short-lived ticket as one request header (never in the URL). Nothing else is
//   added by this class.
// DATA RECEIVED: Text frames, handed to the owner's Listener whole, capped at
//   Settings.maxMessageChars. Parsed by the owner with Gson; never deserialized into objects.
// SAFETY DECLARATION: The ticket is minted by our backend from the licence token for one
//   connection and expires on its own; the licence token itself does not travel on this
//   socket. No Mojang credentials, no session id, no OS telemetry. Reached only after
//   SbsApi.send admitted the ticket request for the caller's ConsentScope.
// ============================================================================
        try {
            http.newWebSocketBuilder()
                    .connectTimeout(settings.connectTimeout())
                    .header(ticketHeader, ticket)
                    .buildAsync(uri, new Wire(gen))
                    .whenComplete((socket, error) -> {
                        if (error != null) {
                            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Socket] connect to {} failed: {}",
                                    uri.getPath(), error.toString());
                            scheduleRetry(gen);
                        }
                    });
        } catch (RuntimeException invalid) {
            // An illegal header value or URI. Nothing reached the network; retrying will not help.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Socket] cannot connect to {}: {}",
                    uri.getPath(), invalid.toString());
            scheduleRetry(gen);
        }
    }

    /** A failed attempt: schedules the next one, unless the socket moved on or was stopped. */
    private void scheduleRetry(int gen) {
        synchronized (this) {
            if (gen != generation || state != State.CONNECTING) {
                return;
            }
            scheduleRetryLocked();
        }
    }

    /** Moves to {@link State#WAITING} under a new generation. The caller holds the lock. */
    private void scheduleRetryLocked() {
        long delay = backoff.nextDelayMs();
        state = State.WAITING;
        nextAttemptAt = clock.getAsLong() + delay;
        int next = ++generation;
        retry = scheduler.schedule(() -> {
            synchronized (this) {
                if (next != generation || state != State.WAITING) {
                    return;
                }
                state = State.CONNECTING;
            }
            attempt(next);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void stopSelf(int gen, StopReason reason) {
        synchronized (this) {
            if (gen != generation) {
                return;
            }
            state = State.STOPPED;
            generation++;
            cancelTimers();
        }
        safely(() -> listener.onStopped(reason));
    }

    private void opened(int gen, WebSocket socket) {
        synchronized (this) {
            if (gen != generation || state != State.CONNECTING) {
                socket.abort();
                return;
            }
            current = socket;
            state = State.OPEN;
            sendChain = CompletableFuture.completedFuture(null);
            lastInboundAt = clock.getAsLong();
            pingInFlight = false;
            backoff.onConnected(lastInboundAt);
            long every = settings.heartbeatMs();
            heartbeat = scheduler.scheduleAtFixedRate(() -> beat(gen), every, every, TimeUnit.MILLISECONDS);
        }
        safely(listener::onOpen);
    }

    /**
     * An open connection ended. The state change and the retry happen in one locked step, so a
     * second callback for the same connection (an error after a close, a close after an abort)
     * finds the generation moved on and does nothing.
     */
    private void disconnected(int gen, String why) {
        synchronized (this) {
            if (gen != generation || state != State.OPEN) {
                return;
            }
            current = null;
            cancelTimers();
            backoff.onDisconnected(clock.getAsLong());
            scheduleRetryLocked();
        }
        SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Socket] {} closed: {}", uri.getPath(), why);
        safely(() -> listener.onClosed(why));
    }

    /** Sends a ping, or drops a connection that has been silent too long. */
    private void beat(int gen) {
        WebSocket socket;
        synchronized (this) {
            if (gen != generation || state != State.OPEN) {
                return;
            }
            socket = current;
        }
        if (socket == null) {
            return;
        }
        if (clock.getAsLong() - lastInboundAt > settings.staleAfterMs()) {
            socket.abort();
            disconnected(gen, "no traffic for " + settings.staleAfterMs() + " ms");
            return;
        }
        if (pingInFlight) {
            return;
        }
        pingInFlight = true;
        try {
            socket.sendPing(ByteBuffer.allocate(0)).whenComplete((ignored, error) -> pingInFlight = false);
        } catch (IllegalStateException busy) {
            pingInFlight = false;
        }
    }

    private void cancelTimers() {
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
    }

    private static void safely(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException listenerBug) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Socket] listener failed: {}", listenerBug.toString());
        }
    }

    /** The JDK listener for one connection attempt, bound to its generation. */
    private final class Wire implements WebSocket.Listener {

        private final int gen;
        private final StringBuilder partial = new StringBuilder();

        private Wire(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket socket) {
            opened(gen, socket);
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            lastInboundAt = clock.getAsLong();
            if (partial.length() + data.length() > settings.maxMessageChars()) {
                partial.setLength(0);
                socket.abort();
                disconnected(gen, "message over " + settings.maxMessageChars() + " chars");
                return null;
            }
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                boolean live;
                synchronized (SbsSocket.this) {
                    live = gen == generation && state == State.OPEN;
                }
                if (live) {
                    safely(() -> listener.onMessage(message));
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            // The protocol is text only; binary frames are ignored, never interpreted.
            lastInboundAt = clock.getAsLong();
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            lastInboundAt = clock.getAsLong();
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
            lastInboundAt = clock.getAsLong();
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            disconnected(gen, "closed by server (" + statusCode + ")");
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            disconnected(gen, error.toString());
        }
    }
}
