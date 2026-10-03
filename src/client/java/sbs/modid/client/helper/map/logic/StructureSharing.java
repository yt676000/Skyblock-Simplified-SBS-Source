/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.SbsApi;
import sbs.modid.client.core.api.SbsSocket;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.licence.privacy.ConsentManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.location.hollows.HollowsDetector;
import sbs.modid.client.core.location.hollows.HollowsStructure;
import sbs.modid.client.core.location.hollows.StructureFix;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Crystal Hollows Structure Sharing, the game side: when to connect, what the player has walked
 * into, what other SBS players in the same lobby have, and the waypoints that show it.
 *
 * <h2>What is detected, and how</h2>
 * Only what the player's own sidebar says. {@link HollowsDetector} reads the zone and the lobby on
 * its throttle and hands every reading here through {@link #polled}, while sharing is permitted. A structure is reported when
 * the zone has named it for a few seconds, from the positions the player actually stood at
 * ({@link StructureSampler}). Nothing scans blocks or chunks, nothing reads entities, and nothing
 * here can know of a structure no player has walked into.
 *
 * <h2>When the connection exists</h2>
 * Only while all of these hold: the setting is on, a licence token is set, the
 * {@link ConsentScope#HOLLOWS_STRUCTURES} scope is granted, and the tab list says the player is on
 * the Crystal Hollows ({@link SkyBlockLocation#inCrystalHollows}, the exact gate). Leaving the
 * island unsubscribes at once and closes the connection {@link #LINGER_MS} later. Withdrawing
 * consent drops it at once, without a close handshake. All of this is checked every tick, which
 * is also what keeps it right across an account switch.
 *
 * <h2>Legitimacy</h2>
 * This marks places that a consenting player's own scoreboard has already named, which is what
 * players do by hand in lobby chat. It does not act in the game, it reads nothing the player could
 * not see, and it is off by default. See the feature spec.
 */
public final class StructureSharing implements StructureShareClient.Sink, HollowsDetector.Listener {

    private static final StructureSharing INSTANCE = new StructureSharing();

    /** Buys the short-lived ticket for one connection. Proposed endpoint; see the protocol doc. */
    static final String TICKET_URI = "https://skyblocksimplified.info/api/hollows/ticket";

    /** The per-lobby structure channel. Proposed endpoint; see the protocol doc. */
    static final String SOCKET_URI = "wss://skyblocksimplified.info/ws/hollows";

    /** How long the connection stays up after leaving the island, for a quick swap of lobby. */
    static final long LINGER_MS = 60_000L;

    /** Marker colours: your own find, confirmed by two or more, and a single report. */
    private static final String COLOR_OWN = "57D977";
    private static final String COLOR_CONFIRMED = "5AC8FA";
    private static final String COLOR_UNCONFIRMED = "9AA0A6";

    private StructureShareClient client;

    /** The lobby every map below belongs to, or {@code null} off the island. */
    private String lobby;
    private final Map<HollowsStructure, StructureSampler> samplers = new EnumMap<>(HollowsStructure.class);
    private final Map<HollowsStructure, StructureProtocol.Shared> remote = new EnumMap<>(HollowsStructure.class);
    private final Map<HollowsStructure, Waypoint> markers = new EnumMap<>(HollowsStructure.class);
    private final Set<HollowsStructure> refusalLogged = EnumSet.noneOf(HollowsStructure.class);

    private boolean wasActive;
    private boolean connected;
    private SbsSocket.StopReason stopped;
    private StructureProtocol.ErrorCode lastError;
    private long leftAt;
    private boolean dirty;

    private StructureSharing() {
        HollowsDetector.getInstance().addListener(this);
    }

    public static StructureSharing getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.MapSettings cfg() {
        return ConfigManager.getInstance().get().map;
    }

    /** Whether sharing may run at all: the setting, a licence token and the consent scope. */
    public boolean permitted() {
        return cfg().hollowsShare && SbsApi.hasLicence()
                && ConsentManager.isGranted(ConsentScope.HOLLOWS_STRUCTURES);
    }

    // ------------------------------------------------------------------ lifecycle (every tick)

    /** Called from the shared client-tick list. Off the island and switched off it is three reads. */
    public void onClientTick() {
        if (!permitted()) {
            if (client != null && isRunning()) {
                // Consent withdrawn means gone now, not after a close handshake.
                boolean consent = ConsentManager.isGranted(ConsentScope.HOLLOWS_STRUCTURES);
                client.stop(consent);
                connected = false;
            }
            leaveLobby();
            wasActive = false;
            return;
        }
        boolean here = SkyBlockLocation.inCrystalHollows();
        long now = System.currentTimeMillis();
        if (here) {
            leftAt = 0L;
            // Started on arrival and on being switched on, not every tick: a socket that stopped
            // itself (licence rejected) stays stopped until the player does something.
            if (!wasActive || client == null || client.state() == SbsSocket.State.IDLE) {
                stopped = null;
                lastError = null;
                ensureClient().start();
            }
        } else if (client != null) {
            if (lobby != null) {
                client.unsubscribe();
                leaveLobby();
            }
            if (leftAt == 0L) {
                leftAt = now;
            } else if (now - leftAt >= LINGER_MS && isRunning()) {
                client.stop(true);
                connected = false;
            }
        }
        wasActive = here;
        if (dirty) {
            dirty = false;
            republish();
        }
    }

    private boolean isRunning() {
        SbsSocket.State state = client.state();
        return state != SbsSocket.State.IDLE && state != SbsSocket.State.STOPPED;
    }

    private StructureShareClient ensureClient() {
        if (client == null) {
            StructureShareClient created = new StructureShareClient(this,
                    runnable -> Minecraft.getInstance().execute(runnable), modVersion());
            created.attach(SbsApi.openSocket(ConsentScope.HOLLOWS_STRUCTURES, URI.create(SOCKET_URI),
                    URI.create(TICKET_URI), ticketBody(), created));
            client = created;
        }
        return client;
    }

    /** The ticket request's body: which channel and which protocol, nothing about the player. */
    private static String ticketBody() {
        return "{\"v\":" + StructureProtocol.VERSION + ",\"scope\":\""
                + ConsentScope.HOLLOWS_STRUCTURES.id() + "\"}";
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(SkyblockSimplifiedSBS.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    // ------------------------------------------------------------------ detection (every poll)

    /**
     * One reading from {@link HollowsDetector}: the lobby, the zone and where the player stands.
     * Called on its 500 ms throttle while the player is on the Hollows.
     *
     * @param server the tab list's {@code Server:} id, or {@code null} before it shows up
     */
    public void onPoll(String server, String zone, BlockPos pos, long nowMs) {
        if (client == null || !permitted() || !SkyBlockLocation.inCrystalHollows()) {
            return;
        }
        if (!StructureProtocol.validLobby(server)) {
            return;   // no lobby id, no sharing: a position without its lobby means nothing
        }
        if (!server.equals(lobby)) {
            leaveLobby();
            lobby = server;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hollows] sharing in lobby {}", server);
        }
        // Every poll, not only on a change: after a reconnect or a restart the client has
        // forgotten the lobby, and this is what puts it back. A repeat for the same lobby is free.
        client.subscribe(server);

        HollowsStructure structure = HollowsStructure.fromZone(zone);
        if (structure == null || !structure.shared() || pos == null) {
            return;
        }
        StructureSampler sampler = samplers.computeIfAbsent(structure, StructureSampler::new);
        StructureSampler.Report report = sampler.sample(pos.getX(), pos.getY(), pos.getZ(), nowMs);
        dirty = true;
        if (report == null || !cfg().hollowsShareContribute) {
            return;
        }
        if (client.report(report)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hollows] reported {} at {}, {}, {} ({} samples, lobby {})",
                    structure.wireId(), report.x(), report.y(), report.z(), report.samples(), lobby);
        } else if (refusalLogged.add(structure)) {
            // The estimate in HollowsGeometry is the likely cause. Logged once per structure and
            // lobby, so a wrong estimate shows up in the log instead of failing silently.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hollows] not reporting {}: {}, {}, {} box {} is outside "
                            + "the estimated bounds or quadrant", structure.wireId(),
                    report.x(), report.y(), report.z(), report.box());
        }
    }

    /** Forgets the current lobby: its samples, what others shared and the markers. */
    private void leaveLobby() {
        if (lobby == null && samplers.isEmpty() && remote.isEmpty() && markers.isEmpty()) {
            return;
        }
        lobby = null;
        samplers.clear();
        remote.clear();
        refusalLogged.clear();
        markers.clear();
        WaypointStore.clearTransient(Waypoint.SOURCE_HOLLOWS);
    }

    // ------------------------------------------------------------------ server results (client thread)

    @Override
    public void onSnapshot(StructureProtocol.Snapshot snapshot) {
        if (!snapshot.lobby().equals(lobby)) {
            return;
        }
        remote.clear();
        for (StructureProtocol.Shared shared : snapshot.structures()) {
            remote.put(shared.structure(), shared);
            toMap(shared);
        }
        dirty = true;
    }

    @Override
    public void onUpdate(StructureProtocol.Update update) {
        if (!update.lobby().equals(lobby)) {
            return;
        }
        remote.put(update.structure().structure(), update.structure());
        toMap(update.structure());
        dirty = true;
    }

    /**
     * Hands a shared structure to the Crystal Hollows map, which keeps it with the lobby (for its
     * six hours) and draws it next to your own finds. The map filters unconfirmed ones itself.
     */
    private void toMap(StructureProtocol.Shared shared) {
        HollowsMapTracker.getInstance().store().offerShared(lobby, shared.structure(), shared.x(),
                shared.y(), shared.z(), shared.confirmations(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ detector (polls)

    @Override
    public boolean wantsPolls() {
        return permitted();
    }

    @Override
    public void polled(String lobbyId, String zone, int x, int y, int z, long now) {
        onPoll(lobbyId.isEmpty() ? null : lobbyId, zone, new BlockPos(x, y, z), now);
    }

    @Override
    public void lobbyClosing(String lobbyId, List<StructureFix> fixes) {
        // Sharing keeps its own samples per lobby and drops them in onPoll on a lobby change.
    }

    @Override
    public void lobbyOpened(String lobbyId) {
    }

    @Override
    public void onServerError(StructureProtocol.ErrorCode code) {
        if (code != lastError) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hollows] server answered {}", code.wireId());
        }
        lastError = code;
        if (code == StructureProtocol.ErrorCode.UNAUTHORIZED
                || code == StructureProtocol.ErrorCode.UNSUPPORTED_VERSION) {
            // Neither fixes itself by reconnecting.
            if (client != null) {
                client.stop(true);
            }
            connected = false;
        }
    }

    @Override
    public void onConnection(boolean open) {
        connected = open;
        if (open) {
            lastError = null;
        }
    }

    @Override
    public void onStopped(SbsSocket.StopReason reason) {
        stopped = reason;
        connected = false;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hollows] sharing stopped: {}", reason);
    }

    // ------------------------------------------------------------------ display

    /** What one structure shows as, merged from your own samples and the server's view. */
    private record Shown(HollowsStructure structure, int x, int y, int z, boolean own, int confirmations) {
    }

    private List<Shown> shown() {
        boolean showUnconfirmed = cfg().hollowsShareShowUnconfirmed;
        List<Shown> out = new ArrayList<>();
        for (HollowsStructure structure : HollowsStructure.values()) {
            StructureSampler sampler = samplers.get(structure);
            StructureSampler.Report own = sampler == null ? null : sampler.current();
            StructureProtocol.Shared shared = remote.get(structure);
            if (shared != null) {
                if (own == null && shared.confirmations() < 2 && !showUnconfirmed) {
                    continue;
                }
                // The server's median, once there is one, is better than one player's walk.
                out.add(new Shown(structure, shared.x(), shared.y(), shared.z(), own != null,
                        shared.confirmations()));
            } else if (own != null) {
                // Your own find shows at once, before any server echo.
                out.add(new Shown(structure, own.x(), own.y(), own.z(), true, 0));
            }
        }
        return out;
    }

    /**
     * Brings the markers in line with {@link #shown()}. When only positions or labels changed, the
     * existing waypoints are updated in place; republishing the set invalidates the pathfinder,
     * which a marker drifting toward the middle of a structure every half second must not do.
     */
    private void republish() {
        List<Shown> now = shown();
        boolean sameSet = now.size() == markers.size();
        for (Shown entry : now) {
            sameSet &= markers.containsKey(entry.structure());
        }
        if (!sameSet) {
            markers.clear();
            String dimension = WaypointStore.currentDimension();
            for (Shown entry : now) {
                Waypoint waypoint = new Waypoint(entry.structure().zone(),
                        new BlockPos(entry.x(), entry.y(), entry.z()), dimension, Waypoint.SOURCE_HOLLOWS);
                waypoint.routable = false;
                waypoint.showDistance = true;
                waypoint.throughWalls = true;
                markers.put(entry.structure(), waypoint);
            }
        }
        for (Shown entry : now) {
            style(markers.get(entry.structure()), entry);
        }
        if (!sameSet) {
            WaypointStore.setTransient(Waypoint.SOURCE_HOLLOWS, new ArrayList<>(markers.values()));
        }
    }

    private static void style(Waypoint waypoint, Shown entry) {
        waypoint.x = entry.x();
        waypoint.y = entry.y();
        waypoint.z = entry.z();
        if (entry.own()) {
            waypoint.colorHex = COLOR_OWN;
            waypoint.opacity = 100;
            waypoint.subLabel = entry.confirmations() >= 2
                    ? "found by you - confirmed by " + entry.confirmations()
                    : "found by you";
        } else if (entry.confirmations() >= 2) {
            waypoint.colorHex = COLOR_CONFIRMED;
            waypoint.opacity = 100;
            waypoint.subLabel = "confirmed by " + entry.confirmations() + " players";
        } else {
            waypoint.colorHex = COLOR_UNCONFIRMED;
            waypoint.opacity = 55;
            waypoint.subLabel = "unconfirmed - 1 report";
        }
    }

    /** A shared structure for the map: one other players reported and you have not walked into. */
    public record MapEntry(HollowsStructure structure, int x, int y, int z, int confirmations) {
    }

    /** What the map should add on top of the structures {@link HollowsDetector} found itself. */
    public List<MapEntry> sharedForMap() {
        if (lobby == null || !permitted()) {
            return List.of();
        }
        boolean showUnconfirmed = cfg().hollowsShareShowUnconfirmed;
        List<MapEntry> out = new ArrayList<>();
        for (StructureProtocol.Shared shared : remote.values()) {
            if (shared.confirmations() < 2 && !showUnconfirmed) {
                continue;
            }
            out.add(new MapEntry(shared.structure(), shared.x(), shared.y(), shared.z(),
                    shared.confirmations()));
        }
        return out;
    }

    /**
     * Why sharing is or is not doing anything, in one line for the settings page. A feature that
     * shows nothing without saying why reads as broken while it is working as told.
     */
    public String statusLine() {
        SBSConfig.MapSettings cfg = cfg();
        if (!cfg.hollowsShare) {
            return "Sharing is off";
        }
        if (!SbsApi.hasLicence()) {
            return "Sharing needs a licence token - nothing is sent";
        }
        if (!ConsentManager.isGranted(ConsentScope.HOLLOWS_STRUCTURES)) {
            return "Sharing needs \"Crystal Hollows structure sharing\" allowed in Licence Token > "
                    + "Privacy & data - nothing is sent";
        }
        if (stopped == SbsSocket.StopReason.REJECTED) {
            return "The server rejected the licence token - rejoin the Hollows to try again";
        }
        if (lastError == StructureProtocol.ErrorCode.UNSUPPORTED_VERSION) {
            return "The server needs a newer SBS version for sharing";
        }
        if (client == null || !isRunning()) {
            return "Connects when you are on the Crystal Hollows";
        }
        if (!connected) {
            long retry = client.nextAttemptAt();
            long seconds = retry == 0L ? 0L : Math.max(0L, (retry - System.currentTimeMillis()) / 1000L);
            return seconds > 0L
                    ? "Sharing server unavailable - retrying in " + seconds + "s"
                    : "Connecting to the sharing server";
        }
        if (lobby == null) {
            return "Connected - waiting for the lobby id";
        }
        return "Connected - lobby " + lobby + ", " + remote.size() + " shared structure(s)";
    }
}
