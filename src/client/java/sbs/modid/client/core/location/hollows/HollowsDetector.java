/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.location.hollows;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.timers.ServerWorldTime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which Crystal Hollows lobby you are in, and where its structures are - found by the zone line you
 * are standing in, nothing else.
 *
 * <p>The Hollows are regenerated every few hours and differ per lobby, so nothing in them has a
 * coordinate worth writing into a data file. What can be known is what <i>you</i> have walked into
 * in <i>this</i> lobby: {@link SkyBlockLocation#zone()} names the place, and the position you were
 * at while it did is where the place is. No block is read, nothing is scanned.
 *
 * <p><b>The one owner of that knowledge.</b> The SkyBlock Map's Hollows layer, the schematic
 * Crystal Hollows map and Structure Sharing all read it here; none keeps a second copy. Consumers
 * that outlive a lobby (persistence) register a {@link Listener} and are handed the lobby's fixes
 * before they are cleared.
 *
 * <p><b>Lobby id</b> is {@link ServerWorldTime#serverName()} ({@code mini24CD}). It walks every tab
 * line, as does {@code zone()}, so both are read on a {@link #POLL_MS} throttle and compared as
 * cached strings. Until the tab list has served it the lobby is {@code ""}; what is found meanwhile
 * belongs to whichever lobby turns up first, since that is the one you were in.
 */
public final class HollowsDetector {

    /** Told about a lobby before its fixes are thrown away, and about the next one when it opens. */
    public interface Listener {

        /**
         * The lobby is being left. {@code fixes} are copies and are the listener's to keep.
         *
         * @param lobby the id being left, never empty
         */
        void lobbyClosing(String lobby, List<StructureFix> fixes);

        /** A lobby became current; {@code ""} when the player left the Hollows. */
        void lobbyOpened(String lobby);

        /**
         * Whether this listener needs {@link #polled} right now. The detector polls while discovery
         * is on or any listener asks, so a feature with its own switch (Structure Sharing) works with
         * discovery off. Checked every tick: keep it to a few field reads.
         */
        default boolean wantsPolls() {
            return false;
        }

        /**
         * One throttled reading on the Hollows, after the detector has taken it: the lobby
         * ({@code ""} until the tab list names it), the zone (may be blank) and the position.
         */
        default void polled(String lobby, String zone, int x, int y, int z, long now) {
        }
    }

    private static final HollowsDetector INSTANCE = new HollowsDetector();

    /** How often the zone and the lobby id are read. Both walk lines; neither changes fast. */
    static final long POLL_MS = 500L;

    private final Map<HollowsStructure, StructureFix> fixes = new EnumMap<>(HollowsStructure.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** The lobby the fixes belong to; {@code ""} before the tab list names it or off the Hollows. */
    private volatile String lobby = "";

    /** Whether the player is on the Hollows as of the last poll. */
    private boolean onHollows;

    /** The zone on the previous poll, so only a real transition is logged. */
    private String lastZone = "";

    private long lastPollAt;

    HollowsDetector() {
    }

    public static HollowsDetector getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().map.hollowsDiscover;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    private boolean anyWantsPolls() {
        for (Listener listener : listeners) {
            if (listener.wantsPolls()) {
                return true;
            }
        }
        return false;
    }

    /** Called from the shared client-tick list. Off the Hollows this is a config read and a compare. */
    public void onClientTick() {
        boolean record = enabled();
        if (!record && !anyWantsPolls()) {
            if (onHollows || !fixes.isEmpty()) {
                update(null, null, 0, 0, 0, System.currentTimeMillis());
            }
            return;
        }
        if (!SkyBlockLocation.onIsland(HollowsGeometry.ISLAND)) {
            if (onHollows) {
                update(null, null, 0, 0, 0, System.currentTimeMillis());
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPollAt < POLL_MS) {
            return;
        }
        lastPollAt = now;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        BlockPos pos = minecraft.player.blockPosition();
        String server = ServerWorldTime.serverName();
        String zone = SkyBlockLocation.zone();
        String raw = zone != null && !zone.equals(lastZone) ? SkyBlockLocation.zoneLine() : "";
        update(server == null ? lobby : server, zone, raw, pos.getX(), pos.getY(), pos.getZ(), now,
                record);
        for (Listener listener : listeners) {
            if (listener.wantsPolls()) {
                try {
                    listener.polled(lobby, zone, pos.getX(), pos.getY(), pos.getZ(), now);
                } catch (RuntimeException e) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Hollows] poll listener failed", e);
                }
            }
        }
    }

    /** {@link #update(String, String, String, int, int, int, long, boolean)} recording, no raw line. */
    void update(String lobbyNow, String zone, int x, int y, int z, long now) {
        update(lobbyNow, zone, "", x, y, z, now, true);
    }

    /**
     * One poll's worth of input, already read. The whole decision lives here so it can be tested
     * without a game behind it.
     *
     * @param lobbyNow the lobby id, {@code ""} when not served yet, or {@code null} for "not on the
     *                 Hollows any more"
     * @param zone     the zone line, may be {@code null} or blank
     * @param raw      the sidebar line the zone came from, for the transition log; may be empty
     * @param record   whether structures are recorded (discovery on); off, the lobby and the zone
     *                 are still followed for the poll listeners, and nothing is kept
     */
    void update(String lobbyNow, String zone, String raw, int x, int y, int z, long now, boolean record) {
        if (lobbyNow == null) {
            if (onHollows || !fixes.isEmpty()) {
                close("left the island");
                onHollows = false;
                lobby = "";
                fire("");
            }
            return;
        }
        if (!onHollows) {
            onHollows = true;
            if (!lobbyNow.isEmpty()) {
                lobby = lobbyNow;
                fire(lobby);
            }
        } else if (!lobbyNow.isEmpty() && !lobbyNow.equals(lobby)) {
            if (lobby.isEmpty()) {
                // The tab list caught up: what was found so far was found in this lobby.
                lobby = lobbyNow;
            } else {
                close("lobby changed to " + lobbyNow);
                lobby = lobbyNow;
            }
            fire(lobby);
        }

        if (zone == null || zone.isBlank()) {
            return;
        }
        if (!zone.equals(lastZone)) {
            logTransition(zone, raw, x, y, z);
            lastZone = zone;
        }
        if (!record) {
            fixes.clear();   // discovery switched off mid-lobby: nothing found stays on the map
            return;
        }
        HollowsStructure structure = HollowsStructure.fromZone(zone);
        if (structure == null) {
            return;
        }
        fixes.computeIfAbsent(structure, s -> new StructureFix(s, now)).sample(x, y, z);
    }

    /**
     * The evidence line for {@link HollowsGeometry}: every zone transition, its position and what the
     * ESTIMATED layout predicts there. A region zone the layout places elsewhere gets a second line
     * of its own, so a session's worth of disagreement is one grep.
     */
    private void logTransition(String zone, String raw, int x, int y, int z) {
        HollowsRegion predicted = HollowsGeometry.classify(x, y, z);
        // raw is the sidebar line exactly as the parser saw it, non-ASCII escaped: what a test
        // fixture has to be.
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Hollows] zone '{}' -> '{}' at {}, {}, {} (lobby {}, layout says {}) raw='{}'",
                lastZone, zone, x, y, z, lobby.isEmpty() ? "unknown" : lobby, predicted.displayName(),
                escape(raw));
        HollowsRegion named = HollowsRegion.fromZone(zone);
        if (named != null && named != predicted) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hollows] layout disagrees: scoreboard '{}' at {}, {}, {} "
                    + "but HollowsGeometry says '{}'", zone, x, y, z, predicted.displayName());
        }
    }

    /** {@code text} with every non-ASCII character written as {@code \\uXXXX}, for the log. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                out.append(c);
            } else {
                out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            }
        }
        return out.toString();
    }

    /** Hands the lobby's fixes to every listener, then forgets them. */
    private void close(String why) {
        if (!lobby.isEmpty()) {
            List<StructureFix> copies = snapshot();
            for (Listener listener : listeners) {
                try {
                    listener.lobbyClosing(lobby, copies);
                } catch (RuntimeException e) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Hollows] lobby listener failed", e);
                }
            }
        }
        if (!fixes.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hollows] cleared {} structure(s) - {}", fixes.size(), why);
        }
        fixes.clear();
        lastZone = "";
    }

    private void fire(String opened) {
        for (Listener listener : listeners) {
            try {
                listener.lobbyOpened(opened);
            } catch (RuntimeException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Hollows] lobby listener failed", e);
            }
        }
    }

    /**
     * Puts back what was found in this lobby before a restart. Only for the current lobby, and only
     * for structures not already found again since - a live fix always beats a stored one.
     */
    public void restore(String forLobby, Collection<StructureFix> stored) {
        if (forLobby == null || forLobby.isEmpty() || !forLobby.equals(lobby) || stored == null) {
            return;
        }
        for (StructureFix fix : stored) {
            fixes.putIfAbsent(fix.structure(), fix.copy());
        }
    }

    /** The current lobby id, {@code ""} when unknown or off the Hollows. */
    public String lobby() {
        return lobby;
    }

    public boolean onHollows() {
        return onHollows;
    }

    /**
     * The zone line as of the last poll, {@code ""} before one. For display: a screen that wants the
     * zone every frame reads this instead of walking the sidebar itself.
     */
    public String lastZone() {
        return lastZone;
    }

    /** Copies of every fix in the current lobby, in structure order. */
    public List<StructureFix> snapshot() {
        List<StructureFix> out = new ArrayList<>(fixes.size());
        for (StructureFix fix : fixes.values()) {
            out.add(fix.copy());
        }
        return out;
    }

    /** The live fix for one structure, or {@code null}. Read-only by convention: do not sample it. */
    public StructureFix fix(HollowsStructure structure) {
        return fixes.get(structure);
    }

    public boolean isEmpty() {
        return fixes.isEmpty();
    }
}
