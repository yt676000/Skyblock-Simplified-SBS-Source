/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Owns every puzzle solver's lifecycle: which room the player is in, which solver that room belongs
 * to, and - the part this class exists for - when every solver is wiped.
 *
 * <p><b>Reset is central on purpose.</b> Room change, leaving the dungeon and a world/server change
 * all funnel through {@link #resetAll}, so a new solver cannot forget to handle them; the only thing
 * a strategy owns is emptying its own fields. Carrying one room's answer into the next is the
 * failure this feature was asked to design against, and per-solver reset logic is how that failure
 * gets reintroduced one solver at a time.
 *
 * <p><b>Room change is detected by watching {@code activeRoomName()}, not by an event.</b>
 * {@code DungeonEvents.fireRoomEnter} / {@code fireRoomLeave} look like the right mechanism and are
 * dead code - declared, guarded, and with no call site or listener anywhere in the tree (see
 * {@code docs/issues/dungeons.md}). Polling the tracker is what actually works today; wiring the
 * events up properly is a separate change and this class should move onto them when it happens.
 *
 * <p><b>Rendering is gated on the room, chat is not.</b> Room identification can lag the first chat
 * line of a puzzle - Oruo starts talking as you walk in - so a solver that only listened once the
 * room was locked would miss the question it exists to answer. Lines are offered to every solver;
 * only the one whose room is locked draws anything.
 */
public final class PuzzleCoordinator {

    private static final PuzzleCoordinator INSTANCE = new PuzzleCoordinator();

    /**
     * Catch-all: every chat line, filtered inside the handler.
     *
     * <p>A narrow pattern would be better and cannot be written yet - the wording of the lines these
     * solvers key on is exactly what has not been observed, and a pattern guessed now would silently
     * drop the lines needed to write the real one. The handler returns immediately unless the player
     * is in a dungeon and the feature is on, so the cost outside Catacombs is one boolean.
     */
    private static final Pattern EVERY_LINE = Pattern.compile("(?s).*");

    private final Map<PuzzleType, PuzzleStrategy> strategies = new EnumMap<>(PuzzleType.class);

    /** The room name the last tick saw, so a change can be spotted without an event. */
    private String lastRoom;

    /** The level the last tick saw, so a world change or server hop wipes state. */
    private ClientLevel lastLevel;

    private boolean wasInDungeon;

    private PuzzleCoordinator() {
        register(new QuizSolver());
        register(new ThreeWeirdosSolver());
        ChatPatternRegistry.getInstance().register(EVERY_LINE,
                (matcher, component) -> onChat(matcher.group()), "puzzle solvers");
    }

    public static PuzzleCoordinator getInstance() {
        return INSTANCE;
    }

    private void register(PuzzleStrategy strategy) {
        strategies.put(strategy.type(), strategy);
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** Whether the master toggle and this puzzle's own toggle are both on. */
    private static boolean enabled(PuzzleType type) {
        SBSConfig.DungeonsSettings dungeons = cfg();
        if (!dungeons.puzzleSolver) {
            return false;
        }
        return switch (type) {
            case QUIZ -> dungeons.puzzleQuiz;
            case THREE_WEIRDOS -> dungeons.puzzleThreeWeirdos;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /** Called once per client tick from the tracking mixin. */
    public void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            resetAll("no world");
            return;
        }
        if (minecraft.level != lastLevel) {
            lastLevel = minecraft.level;
            resetAll("world change");
        }
        boolean inDungeon = DungeonStateManager.getInstance().inDungeon();
        if (!inDungeon) {
            if (wasInDungeon) {
                resetAll("left the dungeon");
                PuzzleTabReader.getInstance().reset();
            }
            wasInDungeon = false;
            return;
        }
        wasInDungeon = true;
        if (!cfg().puzzleSolver) {
            return;
        }

        PuzzleTabReader.getInstance().tick();

        String room = DungeonRoomTracker.getInstance().activeRoomName();
        if (!java.util.Objects.equals(room, lastRoom)) {
            lastRoom = room;
            resetAll("room change");
            PuzzleStrategy entered = activeStrategy();
            if (entered != null) {
                entered.onEnter();
            }
        }

        PuzzleStrategy active = activeStrategy();
        if (active != null && enabled(active.type())) {
            active.onTick(minecraft);
        }
    }

    private void onChat(String line) {
        if (line == null || !cfg().puzzleSolver || !DungeonStateManager.getInstance().inDungeon()) {
            return;
        }
        for (PuzzleStrategy strategy : strategies.values()) {
            if (enabled(strategy.type())) {
                strategy.onChat(line);
            }
        }
    }

    /** The solver for the room the player is standing in, or {@code null} outside a puzzle room. */
    private PuzzleStrategy activeStrategy() {
        PuzzleType type = PuzzleType.fromName(lastRoom);
        return type == null ? null : strategies.get(type);
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    /**
     * What to draw right now. Empty whenever the player is not in a puzzle room this build solves,
     * the solver is off, or the solver is not certain - the three cases are deliberately
     * indistinguishable from the renderer's side, so there is one way to draw nothing.
     */
    public List<PuzzleHighlight> highlights() {
        if (!cfg().puzzleSolver) {
            return List.of();
        }
        PuzzleStrategy active = activeStrategy();
        if (active == null || !enabled(active.type())) {
            return List.of();
        }
        return active.highlights();
    }

    private void resetAll(String reason) {
        boolean anything = lastRoom != null;
        lastRoom = null;
        for (PuzzleStrategy strategy : strategies.values()) {
            strategy.reset();
        }
        if (anything) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Puzzle] solvers reset: {}", reason);
        }
    }
}
