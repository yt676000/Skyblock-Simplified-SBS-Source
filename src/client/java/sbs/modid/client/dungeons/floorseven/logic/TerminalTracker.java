/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Goldor-phase progress: how many terminals, devices and levers the team has done, and who did them.
 * Fed entirely by the lines Hypixel already prints ("Rasseur activated a terminal! (3/7)"), so it
 * costs nothing but a regex per chat line and cannot drift out of sync with the server.
 *
 * <p>The totals are taken <b>from the message</b> rather than hard-coded: the counter in brackets is
 * the server's own, so a floor that hands out a different number of terminals stays correct without
 * this class knowing anything about floor layouts.
 *
 * <p>Read by {@code TerminalProgressHud}. Nothing is ever sent; the per-player tally is exactly the
 * information that was already on everyone's screen, only added up.
 */
public final class TerminalTracker {

    private static final TerminalTracker INSTANCE = new TerminalTracker();

    /** One of the three things the gates count. */
    public enum Kind {
        TERMINAL("Terminals"),
        DEVICE("Devices"),
        LEVER("Levers");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** How far one kind has got: {@code done} of {@code total}, as the server last counted it. */
    public record Progress(int done, int total) {
    }

    private final Map<Kind, Progress> progress = new LinkedHashMap<>();
    /** Player name → how many of the three things they personally finished this phase. */
    private final Map<String, Integer> perPlayer = new LinkedHashMap<>();

    private long lastEventMs;

    private TerminalTracker() {
        // "<name> activated a terminal! (3/7)" and its device / lever siblings. The name may carry a
        // rank prefix ("[MVP+] Name"), so the capture is anchored on the word right before the verb.
        register(Kind.TERMINAL, "terminal");
        register(Kind.DEVICE, "device");
        register(Kind.LEVER, "lever");
    }

    public static TerminalTracker getInstance() {
        return INSTANCE;
    }

    private void register(Kind kind, String noun) {
        ChatPatternRegistry.getInstance().register(
                "([A-Za-z0-9_]{1,16})\\s+(?:activated|completed)\\s+an?\\s+" + noun
                        + "!?\\s*\\((\\d+)\\s*/\\s*(\\d+)\\)",
                matcher -> onActivation(kind, matcher.group(1), matcher.group(2), matcher.group(3)),
                "f7 terminals: " + noun);
    }

    // ---- read by the HUD ----------------------------------------------------------------------

    /** The three counters in order, or an empty list before the first activation of the phase. */
    public List<Map.Entry<Kind, Progress>> counters() {
        return new ArrayList<>(progress.entrySet());
    }

    /** Players by how many things they finished, most first. */
    public List<Map.Entry<String, Integer>> contributors() {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(perPlayer.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return sorted;
    }

    /** Whether there is anything to show: at least one activation seen this phase. */
    public boolean active() {
        return !progress.isEmpty();
    }

    /** Whether every counter that has been seen has reached its total - the gate is open. */
    public boolean complete() {
        if (progress.isEmpty()) {
            return false;
        }
        for (Progress value : progress.values()) {
            if (value.done() < value.total()) {
                return false;
            }
        }
        return true;
    }

    public long lastEventMs() {
        return lastEventMs;
    }

    // ---- tick ---------------------------------------------------------------------------------

    /** Only job: forget the phase once the player has left the Catacombs. The counting is chat-driven. */
    public void onClientTick() {
        if (!progress.isEmpty() && !DungeonStateManager.getInstance().inDungeon()) {
            reset();
        }
    }

    private void reset() {
        progress.clear();
        perPlayer.clear();
        lastEventMs = 0;
    }

    // ---- chat ---------------------------------------------------------------------------------

    private void onActivation(Kind kind, String player, String done, String total) {
        if (!onFloorSeven()) {
            return;
        }
        int doneCount = parse(done);
        int totalCount = parse(total);
        if (doneCount <= 0 || totalCount <= 0) {
            return;
        }
        // A counter starting over means the next gate: the previous section's tallies are history.
        Progress previous = progress.get(kind);
        if (previous != null && doneCount <= previous.done()) {
            reset();
        }
        progress.put(kind, new Progress(doneCount, totalCount));
        perPlayer.merge(player, 1, Integer::sum);
        lastEventMs = System.currentTimeMillis();
        // Only the local player's own activation carries a solve time and may be announced - every
        // other name in this stream belongs to somebody else's terminal.
        if (player.toLowerCase(Locale.ROOT).equals(selfName())) {
            sbs.modid.client.dungeons.terminal.TerminalCompletion.getInstance()
                    .onOwnActivation(kind, doneCount, totalCount);
        }
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** F7 or M7 (floor 7 either way); the tracker does nothing on any other floor. */
    private static boolean onFloorSeven() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return state.inDungeon() && state.floorNumber() == 7;
    }

    /** Your own name, so the card can tell your line apart from the rest of the party. */
    public static String selfName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().name().toLowerCase(Locale.ROOT);
    }
}
