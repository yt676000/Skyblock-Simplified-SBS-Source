/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.SbsSocket;
import sbs.modid.client.core.location.hollows.HollowsStructure;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The Structure Sharing protocol over one {@link SbsSocket}, with no game state in it: which lobby
 * is subscribed, which of the player's own reports to send again after a reconnect, and which
 * incoming messages belong to the lobby the player is in.
 *
 * <p>Kept apart from {@link StructureSharing} so the whole exchange can run against a mock server
 * in a test, with no Minecraft behind it.
 *
 * <h2>Threads</h2>
 * The socket calls in on its own threads. Messages are decoded there, which is the only work done
 * off the client thread, and then handed to the {@link Sink} through {@code deliver}, which in the
 * game is {@code Minecraft::execute}. The control methods ({@link #subscribe}, {@link #report},
 * {@link #unsubscribe}) may be called from any thread.
 *
 * <h2>Reconnects</h2>
 * Every open re-sends {@code subscribe} for the current lobby and then the player's own latest
 * report for each structure, so a dropped connection loses nothing the player found. The server
 * answers the subscribe with a fresh snapshot, which replaces whatever the sink was holding.
 */
public final class StructureShareClient implements SbsSocket.Listener {

    /** Where validated results go. Called through {@code deliver}, never on a socket thread. */
    public interface Sink {
        void onSnapshot(StructureProtocol.Snapshot snapshot);

        void onUpdate(StructureProtocol.Update update);

        void onServerError(StructureProtocol.ErrorCode code);

        /** The connection opened ({@code true}) or dropped ({@code false}). */
        void onConnection(boolean open);

        /** The socket stopped itself and will not retry. */
        void onStopped(SbsSocket.StopReason reason);
    }

    private final Sink sink;
    private final Executor deliver;
    private final String modVersion;

    private SbsSocket socket;
    private boolean open;
    private String lobby;
    private final Map<HollowsStructure, StructureSampler.Report> ownReports = new EnumMap<>(HollowsStructure.class);

    /**
     * @param sink       where results go
     * @param deliver    runs the sink calls; the client thread in the game, direct in a test
     * @param modVersion sent in {@code subscribe}
     */
    public StructureShareClient(Sink sink, Executor deliver, String modVersion) {
        this.sink = sink;
        this.deliver = deliver;
        this.modVersion = modVersion;
    }

    /** Binds the socket this client talks over. Called once, before {@link #start()}. */
    public synchronized void attach(SbsSocket socket) {
        this.socket = socket;
    }

    /** Starts connecting; harmless when already connected. */
    public void start() {
        SbsSocket bound;
        synchronized (this) {
            bound = socket;
        }
        if (bound != null) {
            bound.start();
        }
    }

    /**
     * Closes the connection and forgets the subscription.
     *
     * @param graceful {@code false} drops the connection at once, for a withdrawn consent
     */
    public void stop(boolean graceful) {
        SbsSocket bound;
        synchronized (this) {
            bound = socket;
            open = false;
            lobby = null;
            ownReports.clear();
        }
        if (bound != null) {
            bound.stop(graceful);
        }
    }

    /** The socket's state, or {@link SbsSocket.State#IDLE} before one is attached. */
    public synchronized SbsSocket.State state() {
        return socket == null ? SbsSocket.State.IDLE : socket.state();
    }

    /** When the socket retries next, or {@code 0}. */
    public synchronized long nextAttemptAt() {
        return socket == null ? 0L : socket.nextAttemptAt();
    }

    /** The lobby currently subscribed (or about to be), or {@code null}. */
    public synchronized String lobby() {
        return lobby;
    }

    /**
     * Follows the player into {@code newLobby}: unsubscribes the old one, forgets its reports, and
     * subscribes the new one. Does nothing when it is the lobby already subscribed.
     */
    public synchronized void subscribe(String newLobby) {
        if (!StructureProtocol.validLobby(newLobby) || newLobby.equals(lobby)) {
            return;
        }
        if (lobby != null) {
            sendLocked(StructureProtocol.unsubscribe(lobby));
        }
        lobby = newLobby;
        ownReports.clear();
        sendLocked(StructureProtocol.subscribe(lobby, modVersion));
    }

    /** Leaves the current lobby, keeping the connection itself. */
    public synchronized void unsubscribe() {
        if (lobby != null) {
            sendLocked(StructureProtocol.unsubscribe(lobby));
        }
        lobby = null;
        ownReports.clear();
    }

    /**
     * Sends one of the player's own reports for the current lobby, and keeps it to send again after
     * a reconnect.
     *
     * @return {@code false} when there is no lobby or the report fails the protocol checks
     */
    public synchronized boolean report(StructureSampler.Report report) {
        if (lobby == null || report == null) {
            return false;
        }
        String encoded = StructureProtocol.report(lobby, report);
        if (encoded == null) {
            return false;
        }
        ownReports.put(report.structure(), report);
        sendLocked(encoded);
        return true;
    }

    private void sendLocked(String encoded) {
        if (open && socket != null && encoded != null) {
            socket.send(encoded);
        }
    }

    // ------------------------------------------------------------------ socket callbacks

    @Override
    public void onOpen() {
        synchronized (this) {
            open = true;
            if (lobby != null) {
                sendLocked(StructureProtocol.subscribe(lobby, modVersion));
                List<StructureSampler.Report> again = new ArrayList<>(ownReports.values());
                for (StructureSampler.Report report : again) {
                    sendLocked(StructureProtocol.report(lobby, report));
                }
            }
        }
        deliver.execute(() -> sink.onConnection(true));
    }

    @Override
    public void onMessage(String text) {
        StructureProtocol.Message message = StructureProtocol.decode(text);
        if (message == null) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Hollows] ignored a server message ({} chars)",
                    text == null ? 0 : text.length());
            return;
        }
        String subscribed;
        synchronized (this) {
            subscribed = lobby;
        }
        switch (message) {
            case StructureProtocol.Snapshot snapshot -> {
                if (snapshot.lobby().equals(subscribed)) {
                    deliver.execute(() -> sink.onSnapshot(snapshot));
                }
            }
            case StructureProtocol.Update update -> {
                if (update.lobby().equals(subscribed)) {
                    deliver.execute(() -> sink.onUpdate(update));
                }
            }
            case StructureProtocol.ServerError error -> deliver.execute(() -> sink.onServerError(error.code()));
        }
    }

    @Override
    public void onClosed(String why) {
        synchronized (this) {
            open = false;
        }
        deliver.execute(() -> sink.onConnection(false));
    }

    @Override
    public void onStopped(SbsSocket.StopReason reason) {
        synchronized (this) {
            open = false;
        }
        deliver.execute(() -> sink.onStopped(reason));
    }
}
