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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Silverfish;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
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

    /**
     * What the diagnostic line last described: the room name, or the locked-but-unidentified
     * footprint. Separate from {@link #lastRoom} because that one is only tracked while the master
     * switch is on, and the line matters most when it is off.
     */
    private Object diagKey;

    /** Whether this room's "waiting for borders" line was written. */
    private boolean waitingLogged;

    /** Whether this room's "chat reaches the solvers" line was written. */
    private boolean npcChatLogged;

    /** Solvers whose chat failure was logged at WARN; once per session each. */
    private final java.util.Set<PuzzleType> chatFailures = java.util.EnumSet.noneOf(PuzzleType.class);

    /** Ticks between looks for a blaze in an unidentified purple room. */
    private static final int GUESS_EVERY = 10;

    /** The unidentified footprint {@link #guessedRoom} belongs to, and the look counter for it. */
    private DungeonRoomBorders.Borders guessBorders;
    private String guessedRoom;
    private int guessTicks;

    /** Block reads per tick for the lever count of an unidentified room. */
    private static final int LEVER_BUDGET = 4096;
    /** Next column of the lever scan; -1 when the scan of {@link #guessBorders} is done. */
    private int leverCursor = -1;
    private int levers;
    private final net.minecraft.core.BlockPos.MutableBlockPos leverProbe = new net.minecraft.core.BlockPos.MutableBlockPos();

    // Declared after every static field: the constructor registers patterns, and a field
    // initialised below this line is still null when it runs (the puzzle chat was dead for it).
    private static final PuzzleCoordinator INSTANCE = new PuzzleCoordinator();

    private PuzzleCoordinator() {
        register(new QuizSolver());
        register(new ThreeWeirdosSolver());
        register(new BlazeSolver());
        register(new TicTacToeRoom());
        register(new CreeperBeamsSolver());
        register(new WaterBoardSolver());
        register(new BoulderSolver());
        register(new IceFillSolver());
        register(new IcePathSolver());
        register(new PadMazeSolver());
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
        return PuzzleSettings.enabled(type);
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
            ArchitectDraftReminder.getInstance().clear();
        }
        boolean inDungeon = DungeonStateManager.getInstance().inDungeon();
        if (!inDungeon) {
            if (wasInDungeon) {
                resetAll("left the dungeon");
                PuzzleTabReader.getInstance().reset();
                ArchitectDraftReminder.getInstance().clear();
            }
            wasInDungeon = false;
            diagKey = null;
            return;
        }
        wasInDungeon = true;
        // The tab section feeds the map checklist as well as the solvers, so it is read when either
        // wants it - and only when the tab changed, which the reader checks itself.
        if (cfg().puzzleSolver || cfg().puzzleChecklist || cfg().architectDraftReminder) {
            PuzzleTabReader.getInstance().tick();
        }
        // Independent of the solvers: a failed room is worth a reminder whether or not SBS solved it.
        ArchitectDraftReminder.getInstance().tick(minecraft);
        String room = resolveRoom();
        logRoomChange(room);
        if (!cfg().puzzleSolver) {
            return;
        }

        if (!java.util.Objects.equals(room, lastRoom)) {
            // Reset first: resetAll() clears lastRoom, and doing it after the assignment left
            // lastRoom null for good - no solver ever ran its onTick (fixed 2026-10-15).
            resetAll("room change");
            lastRoom = room;
            waitingLogged = false;
            npcChatLogged = false;
            PuzzleStrategy entered = activeStrategy();
            if (entered != null) {
                entered.onEnter();
            }
        }

        PuzzleStrategy active = activeStrategy();
        if (active != null && enabled(active.type())) {
            if (!waitingLogged && DungeonRoomTracker.getInstance().borders() == null) {
                // Every probe returns silently without borders; say so once per room instead.
                waitingLogged = true;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] {} waiting for borders ({})",
                        active.type(), DungeonRoomTracker.getInstance().bordersState());
            }
            active.onTick(minecraft);
        }
    }

    /**
     * The room name the solvers key on: the database match, or - for a locked purple room the
     * database did not match - what {@link UnidentifiedPuzzleRoom} can tell from inside it. The
     * entity look runs every {@link #GUESS_EVERY} ticks per unidentified footprint, and a guess
     * sticks for that footprint once made.
     */
    private String resolveRoom() {
        DungeonRoomTracker tracker = DungeonRoomTracker.getInstance();
        String name = tracker.activeRoomName();
        DungeonRoomBorders.Borders borders = tracker.borders();
        String color = tracker.mapColor();
        if (name != null || borders == null || (color != null && !"purple".equals(color))) {
            return name;
        }
        if (!borders.equals(guessBorders)) {
            guessBorders = borders;
            guessedRoom = null;
            guessTicks = 0;
            leverCursor = 0;
            levers = 0;
        }
        if (guessedRoom == null && leverCursor >= 0) {
            scanLevers(borders);
            if (leverCursor < 0) {
                PuzzleType type = UnidentifiedPuzzleRoom.guessByLevers(color, borders.shape(), levers);
                if (type != null) {
                    guessedRoom = type.roomKey();
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] unidentified {} room at {} (map colour {}) has "
                            + "{} levers - treating it as {}", borders.shape(), borders.min().toShortString(),
                            color, levers, type);
                }
            }
        }
        if (guessedRoom == null && "purple".equals(color) && guessTicks++ % GUESS_EVERY == 0) {
            boolean blaze = false;
            boolean creeper = false;
            boolean silverfish = false;
            List<Tracked> living = EntityIndex.bucket(EntityKind.LIVING);
            for (int i = 0, n = living.size(); i < n; i++) {
                Entity entity = living.get(i).entity;
                if (!entity.isAlive() || !borders.contains(entity.blockPosition())) {
                    continue;
                }
                blaze |= entity instanceof Blaze;
                creeper |= entity instanceof Creeper;
                silverfish |= entity instanceof Silverfish;
            }
            PuzzleType type = UnidentifiedPuzzleRoom.guess(tracker.mapColor(), blaze, creeper, silverfish);
            if (type != null) {
                guessedRoom = type.roomKey();
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] unidentified purple room at {} (blaze={} "
                                + "creeper={} silverfish={}) - treating it as {}", borders.min().toShortString(),
                        blaze, creeper, silverfish, type);
            }
        }
        return guessedRoom;
    }

    /**
     * Counts the levers in the unidentified footprint, {@link #LEVER_BUDGET} block reads per tick,
     * floor - 20 .. floor + 20 like the Water Board scan. Sets {@link #leverCursor} to -1 when done.
     */
    private void scanLevers(DungeonRoomBorders.Borders borders) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        int bx = borders.min().getX();
        int bz = borders.min().getZ();
        int sizeX = borders.max().getX() - bx + 1;
        int columns = sizeX * (borders.max().getZ() - bz + 1);
        int floor = borders.min().getY();
        int budget = LEVER_BUDGET;
        while (leverCursor < columns && budget > 0) {
            int x = bx + leverCursor % sizeX;
            int z = bz + leverCursor / sizeX;
            leverCursor++;
            leverProbe.set(x, floor, z);
            if (!borders.contains(leverProbe)) {
                continue;
            }
            for (int y = floor - 20; y <= floor + 20; y++) {
                leverProbe.set(x, y, z);
                if (level.getBlockState(leverProbe).is(net.minecraft.world.level.block.Blocks.LEVER)) {
                    levers++;
                }
            }
            budget -= 41;
        }
        if (leverCursor >= columns) {
            leverCursor = -1;
        }
    }

    /**
     * One line per room change saying whether a solver will run there and, if not, what stops it -
     * so "nothing happened" in a log can be told apart from "it was switched off". Runs before the
     * master-switch check on purpose. A room that is locked but not identified gets a line too:
     * that is the case where a puzzle room passes without any solver noticing it.
     */
    private void logRoomChange(String room) {
        DungeonRoomTracker tracker = DungeonRoomTracker.getInstance();
        DungeonRoomBorders.Borders borders = tracker.borders();
        Object key = room != null ? room : borders;
        if (key == diagKey || (key != null && key.equals(diagKey))) {
            return;
        }
        diagKey = key;
        if (room != null) {
            PuzzleType type = PuzzleType.fromName(room);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] room '{}' -> {}", room, PuzzleSettings.describeRoom(
                    type, cfg().puzzleSolver, type != null && PuzzleSettings.toggle(type),
                    type != null && strategies.containsKey(type), tracker.bordersState()));
        } else if (borders != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] room unidentified: {} map colour {} at {} - "
                            + "no solver until the room database matches it", borders.shape(),
                    tracker.mapColor(), borders.min().toShortString());
        }
    }

    private void onChat(String line) {
        if (line == null) {
            return;
        }
        boolean inDungeon = DungeonStateManager.getInstance().inDungeon();
        if (!npcChatLogged && lastRoom != null && line.startsWith("[NPC] ")) {
            // Once per puzzle room: whether NPC chat arrives here and passes the gates below.
            npcChatLogged = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] chat reaches the solvers in '{}': master={} "
                    + "inDungeon={} line='{}'", lastRoom, cfg().puzzleSolver, inDungeon, line);
        }
        if (!cfg().puzzleSolver || !inDungeon) {
            return;
        }
        line = PuzzleResultLine.strip(line);
        for (PuzzleStrategy strategy : strategies.values()) {
            if (!enabled(strategy.type())) {
                continue;
            }
            try {
                strategy.onChat(line);
            } catch (RuntimeException e) {
                // One solver's failure must not cost the others the line (they run in enum order).
                if (chatFailures.add(strategy.type())) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Puzzle] {} failed on chat line '{}'",
                            strategy.type(), line, e);
                }
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
     * the solver is off, the solver is not certain, or the tab says the puzzle is already solved or
     * failed - the cases are deliberately indistinguishable from the renderer's side, so there is one
     * way to draw nothing. A failed room is included: the penalty is taken and an answer drawn over it
     * only invites a teammate to click a finished room.
     */
    public List<PuzzleHighlight> highlights() {
        if (!cfg().puzzleSolver) {
            return List.of();
        }
        PuzzleStrategy active = activeStrategy();
        if (active == null || !enabled(active.type())
                || PuzzleTabReader.getInstance().finished(active.type())) {
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
