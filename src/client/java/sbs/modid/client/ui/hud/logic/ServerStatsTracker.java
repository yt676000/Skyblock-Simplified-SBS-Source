/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * Collects the values for the "Show Server Stats" HUD card: the player's ping, an estimated
 * server TPS and the client FPS.
 *
 * <p><b>TPS</b> has no client API, so it is estimated the way most stat mods do: the server sends
 * a {@code ClientboundSetTimePacket} every 20 ticks, so at a healthy 20 TPS the packets arrive
 * exactly once per second – the arrival interval directly yields the tick rate
 * ({@code TPS = 20 / intervalSeconds}, capped at 20). {@code TimeUpdateMixin} feeds
 * {@link #onTimeUpdate()}; a small rolling window smooths jitter, and huge gaps (world change,
 * freeze) restart the sampling instead of polluting it.
 *
 * <p><b>Ping</b> is the latency Hypixel reports for the local player in the tab list.
 */
public final class ServerStatsTracker {

    private static final ServerStatsTracker INSTANCE = new ServerStatsTracker();

    /** Rolling window of packet intervals: ~5s of data at full speed – smooth but responsive. */
    private static final int SAMPLES = 5;
    /** Intervals above this (or no packet for this long) mean "not measuring" – world change,
     *  integrated pause or a hard freeze, not a meaningful tick rate. */
    private static final long STALE_NANOS = 10_000_000_000L;

    private final long[] intervals = new long[SAMPLES];
    private int count;
    private int head;
    private long lastUpdateNanos;

    private ServerStatsTracker() {
    }

    public static ServerStatsTracker getInstance() {
        return INSTANCE;
    }

    /** Called from {@code TimeUpdateMixin} for every time-update packet (main thread). */
    public synchronized void onTimeUpdate() {
        long now = System.nanoTime();
        if (lastUpdateNanos != 0) {
            long interval = now - lastUpdateNanos;
            if (interval > 0 && interval < STALE_NANOS) {
                intervals[head] = interval;
                head = (head + 1) % SAMPLES;
                if (count < SAMPLES) {
                    count++;
                }
            } else {
                count = 0;
                head = 0;
            }
        }
        lastUpdateNanos = now;
    }

    /** Estimated server TPS (0..20), or {@code -1} while unknown / stale. */
    public synchronized double tps() {
        if (count == 0 || System.nanoTime() - lastUpdateNanos > STALE_NANOS) {
            return -1;
        }
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += intervals[i];
        }
        double averageSeconds = sum / (double) count / 1_000_000_000.0;
        return Math.min(20.0, 20.0 / averageSeconds);
    }

    // ------------------------------------------------------------------
    // Ping: measured as a REAL round trip via play-phase ping/pong packets.
    // Hypixel's tab-list latency for the local player is unreliable (it
    // reports ~1ms through the proxy), so we send our own
    // ServerboundPingRequestPacket every 2s and time the pong.
    // ------------------------------------------------------------------

    private static final long PING_INTERVAL_MS = 2_000;
    private static final long PING_FRESH_MS = 15_000;
    /** How long a one-shot measurement waits for its pong before reporting "unknown". */
    private static final long REQUEST_TIMEOUT_MS = 5_000;

    /** Payloads of OUR in-flight ping requests (vanilla's debug pinger uses its own values). */
    private final Map<Long, Boolean> pendingPings = new ConcurrentHashMap<>();
    private volatile long lastPingSentMs;
    private volatile long lastPongMs;
    private volatile int rttMs = -1;

    /**
     * A caller waiting for one measurement: {@code sentAtMs} is what tells a pong that answers
     * <i>this</i> request apart from one already in flight, {@code deadlineMs} when to give up.
     */
    private record PingRequest(IntConsumer onResult, long sentAtMs, long deadlineMs) {
    }

    private final List<PingRequest> requests = new CopyOnWriteArrayList<>();

    /** Called from {@code PongResponseMixin} with the echoed payload (network thread). */
    public void onPong(long payload) {
        if (pendingPings.remove(payload) != null) {
            rttMs = (int) Math.max(0, System.currentTimeMillis() - payload);
            lastPongMs = System.currentTimeMillis();
        }
    }

    /**
     * The measured server ping in ms ({@code -1} while unknown). Also drives the 2s send
     * cadence - called every rendered frame by the HUD card.
     */
    public int ping(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            pendingPings.clear();
            rttMs = -1;
            return -1;
        }
        long now = System.currentTimeMillis();
        if (now - lastPingSentMs >= PING_INTERVAL_MS) {
            send(minecraft, now);
        }
        return fresh(now) ? rttMs : tabLatency(minecraft);
    }

    /**
     * Measures the ping once, on demand, and hands the result to {@code onResult} - {@code -1} when
     * the server does not answer within {@value #REQUEST_TIMEOUT_MS} ms.
     *
     * <p>Commands cannot use {@link #ping(Minecraft)}: that one only returns a value once the 2s
     * cadence has already produced one, and that cadence is driven by the Server Stats HUD card,
     * which is <b>off by default</b>. Asking for the ping without the card on therefore used to
     * answer "unknown" every time. This sends its own request and waits for the pong.
     *
     * <p>The callback runs on the client thread from {@link #onClientTick()}, never on the network
     * thread the pong arrives on - callers put the result into chat, which is not safe from there.
     */
    public void measurePing(IntConsumer onResult) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            onResult.accept(-1);
            return;
        }
        long now = System.currentTimeMillis();
        if (fresh(now)) {
            onResult.accept(rttMs);   // the HUD card is running: the value is already current
            return;
        }
        send(minecraft, now);
        requests.add(new PingRequest(onResult, now, now + REQUEST_TIMEOUT_MS));
    }

    /** Settles the waiting one-shot measurements. Called every client tick; free while none wait. */
    public void onClientTick() {
        if (requests.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean gone = minecraft.player == null || minecraft.getConnection() == null;
        long now = System.currentTimeMillis();
        for (PingRequest request : requests) {
            if (!gone && rttMs >= 0 && lastPongMs >= request.sentAtMs()) {
                requests.remove(request);
                request.onResult().accept(rttMs);
            } else if (gone || now > request.deadlineMs()) {
                requests.remove(request);
                request.onResult().accept(-1);
            }
        }
    }

    private void send(Minecraft minecraft, long now) {
        lastPingSentMs = now;
        if (pendingPings.size() > 8) {
            pendingPings.clear(); // server never answered - don't grow forever
        }
        pendingPings.put(now, Boolean.TRUE);
        minecraft.getConnection().send(new ServerboundPingRequestPacket(now));
    }

    private boolean fresh(long now) {
        return rttMs >= 0 && now - lastPongMs <= PING_FRESH_MS;
    }

    /** Fallback: tab-list latency, but only when it looks real (Hypixel reports ~1ms). */
    private static int tabLatency(Minecraft minecraft) {
        PlayerInfo info = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
        int tab = info != null ? info.getLatency() : -1;
        return tab > 5 ? tab : -1;
    }
}
