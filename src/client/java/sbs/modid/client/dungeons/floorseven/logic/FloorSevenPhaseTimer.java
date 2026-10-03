/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase splits for the F7 / M7 boss fight: how long Maxor, Storm, Goldor (the terminals), Necron and
 * – on M7 – the dragons each took, plus the running total. Read by {@code FloorSevenPhaseHud}; the
 * finished run is also printed once to chat.
 *
 * <p><b>How a phase change is recognised.</b> By <i>who speaks</i>, not by what they say. Every
 * phase has exactly one boss talking in it ({@code [BOSS] Storm:} …), and the sentences are the part
 * Hypixel rewords - pinning the timer to "I should have known that I would need to defeat you
 * myself." makes it die silently on the next tweak, while the speaker line has been stable for
 * years.
 *
 * <p>The match is also <b>monotonic and expectation-gated</b>: only a line from the speaker of the
 * <i>next</i> phase advances the timer. So a Necron taunt thrown in mid-terminals cannot jump the
 * clock from Storm straight to Necron, and no phase is ever entered twice or out of order.
 */
public final class FloorSevenPhaseTimer {

    private static final FloorSevenPhaseTimer INSTANCE = new FloorSevenPhaseTimer();

    /** The five phases in order, each with the boss whose chat lines mark its start. */
    public enum Phase {
        MAXOR("Maxor"),
        STORM("Storm"),
        GOLDOR("Goldor"),
        NECRON("Necron"),
        /** M7 only: the four dragons + the Wither King. */
        DRAGONS("Wither King");

        private final String speaker;

        Phase(String speaker) {
            this.speaker = speaker;
        }

        /** The boss name as it appears in "[BOSS] &lt;name&gt;:". */
        public String speaker() {
            return speaker;
        }

        /** The label shown on the HUD ("Dragons" reads better than "Wither King" as a split). */
        public String label() {
            return this == DRAGONS ? "Dragons" : speaker;
        }
    }

    /** One finished phase and how long it took, in milliseconds. */
    public record Split(Phase phase, long durationMs) {
    }

    private final List<Split> splits = new ArrayList<>();

    private Phase current;
    private long currentStartMs;
    private long runStartMs;
    /** Set when the run ends: the HUD then shows the frozen totals instead of a running clock. */
    private boolean finished;

    private FloorSevenPhaseTimer() {
        // One pattern per phase: the speaker prefix. Registration is process-lifetime, and each
        // handler is a no-op unless that phase is the one actually expected next.
        for (Phase phase : Phase.values()) {
            ChatPatternRegistry.getInstance().register(
                    "\\[BOSS\\]\\s+" + phase.speaker() + "\\s*:",
                    matcher -> onSpeaker(phase), "f7 phase timer: " + phase.label());
        }
        // The end-of-run summary: freeze the clock and print the splits. Same signals the room
        // scanner stands down on - the run is over, whatever the boss said last.
        ChatPatternRegistry.getInstance().register(
                "(?i)(Team Score:|>\\s*EXTRA STATS\\s*<|Dungeon Cleared:)",
                matcher -> finish(), "f7 phase timer: run end");
    }

    public static FloorSevenPhaseTimer getInstance() {
        return INSTANCE;
    }

    // ---- read by the HUD ----------------------------------------------------------------------

    /** The finished phases in order (empty before the first phase change). */
    public List<Split> splits() {
        return List.copyOf(splits);
    }

    /** The phase currently being timed, or {@code null} when the fight is not running. */
    public Phase current() {
        return finished ? null : current;
    }

    /** Milliseconds spent in the current phase so far (0 when none is running). */
    public long currentMs() {
        return current == null || finished ? 0 : System.currentTimeMillis() - currentStartMs;
    }

    /** Total fight time so far, or the final time once the run ended. */
    public long totalMs() {
        if (runStartMs == 0) {
            return 0;
        }
        long ended = 0;
        for (Split split : splits) {
            ended += split.durationMs();
        }
        return finished ? ended : ended + currentMs();
    }

    /** Whether there is anything to show: a phase is running, or a finished fight is on screen. */
    public boolean active() {
        return runStartMs != 0;
    }

    public boolean finished() {
        return finished;
    }

    /**
     * Whether the fight has reached Necron (or the M7 dragons that follow him) – the window in which
     * the Necron-phase helpers are wanted. False before Goldor is done and false once the run ended.
     */
    public boolean necronOrLater() {
        return current == Phase.NECRON || current == Phase.DRAGONS;
    }

    // ---- tick -------------------------------------------------------------------------------

    /**
     * Called every client tick. Only job: forget the fight once the player has left the Catacombs,
     * so the card from the last run does not greet the next lobby. The timing itself is chat-driven.
     */
    public void onClientTick() {
        if (runStartMs != 0 && !DungeonStateManager.getInstance().inDungeon()) {
            reset();
        }
    }

    private void reset() {
        splits.clear();
        current = null;
        currentStartMs = 0;
        runStartMs = 0;
        finished = false;
    }

    // ---- phase transitions --------------------------------------------------------------------

    /** A boss spoke. Starts the fight on Maxor, else advances only if this is the expected speaker. */
    private void onSpeaker(Phase phase) {
        // Tracked whether or not the HUD is on: the phase is also what gates the Necron healer
        // waypoint, and a feature must not depend on an unrelated card being switched on.
        if (!onFloorSeven()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (current == null) {
            if (phase != Phase.MAXOR || finished) {
                return; // the fight starts with Maxor; a later run must reset first
            }
            runStartMs = now;
            current = Phase.MAXOR;
            currentStartMs = now;
            return;
        }
        if (phase.ordinal() != current.ordinal() + 1) {
            return; // the same phase talking again, or an out-of-order taunt: ignore
        }
        splits.add(new Split(current, now - currentStartMs));
        current = phase;
        currentStartMs = now;
    }

    /** Freezes the clock at the end-of-run summary and prints the splits once. */
    private void finish() {
        if (runStartMs == 0 || finished) {
            return;
        }
        splits.add(new Split(current, System.currentTimeMillis() - currentStartMs));
        finished = true;
        current = null;
        printSplits();
    }

    /** F7 or M7 (floor 7 either way); the timer does nothing on any other floor. */
    private static boolean onFloorSeven() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return state.inDungeon() && state.floorNumber() == 7;
    }

    /** One client-side summary line block; nothing is ever sent to the server. */
    private void printSplits() {
        if (!ConfigManager.getInstance().get().dungeons.phaseTimerChat) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        StringBuilder line = new StringBuilder("§8[§bSBS§8]§r §dPhases:");
        for (Split split : splits) {
            line.append(" §7").append(split.phase().label()).append(" §f").append(time(split.durationMs()));
        }
        line.append("  §7total §f").append(time(totalMs()));
        player.sendSystemMessage(Component.literal(line.toString()));
    }

    /** {@code m:ss.t} – tenths matter when a phase is measured against a personal best. */
    public static String time(long ms) {
        long totalTenths = Math.max(0, ms) / 100L;
        long minutes = totalTenths / 600L;
        long seconds = (totalTenths / 10L) % 60L;
        long tenths = totalTenths % 10L;
        return String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths);
    }
}
