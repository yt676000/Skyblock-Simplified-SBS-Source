/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three Weirdos: three NPCs, three chests - boxes the chest that holds the reward.
 *
 * <p>Display only. It reads the chat lines the player's own clicks on the NPCs produce and draws a
 * box; opening the chest is the player's click, and nothing here clicks, aims or moves.
 *
 * <p>Three parts, each with one job:
 * <ol>
 *   <li><b>Statements</b> - every {@code [NPC]} line is classified by {@link WeirdoLogic}, which
 *       names the reward NPC as soon as it has spoken. Lines it does not recognise are logged once,
 *       so new wording shows up in the log rather than as a silently dark solver.</li>
 *   <li><b>Chests</b> - on entering the room, the room's footprint from
 *       {@link DungeonRoomTracker#borders()} is scanned for chests, a fixed number of blocks per
 *       tick with a cursor. The scan runs once per visit.</li>
 *   <li><b>Pairing</b> - each speaker's NPC is found by its name tag inside the same footprint and
 *       paired with the chest beside it by {@link WeirdoChests}. The pairing is logged.</li>
 * </ol>
 *
 * <p>The box appears only when all three agree: a determined winner, a located NPC, and an
 * unambiguous chest beside it. Missing any of them draws nothing - failing the room costs Skill
 * score, so a missing box is the safe failure. It clears on the server's {@code PUZZLE SOLVED!} line
 * and, through {@link PuzzleCoordinator}, on leaving the room.
 */
public final class ThreeWeirdosSolver implements PuzzleStrategy {

    /** {@code "PUZZLE SOLVED! <player> wasn't fooled by <Name>! Good job!"}, colour-stripped. */
    private static final Pattern SOLVED =
            Pattern.compile("^PUZZLE SOLVED! \\S+ wasn't fooled by ([A-Za-z]+)! Good job!$");

    /** Blocks checked per tick by the chest scan; a 1x1 room is about three ticks of it. */
    private static final int SCAN_BUDGET = 4096;
    /** Scanned layers relative to the room's door-floor Y: the chests stand on the floor. */
    private static final int SCAN_BELOW = 3;
    private static final int SCAN_ABOVE = 6;
    /** Ticks between NPC look-ups while a speaker is not yet located. */
    private static final int LOCATE_INTERVAL = 10;

    private static final int DEFAULT_COLOR = 0x55FF55;
    private static final int WRONG_COLOR = 0x60FF5555;

    /** Bound on recorded statements; a room's worth is three speakers, a few repeats each. */
    private static final int MAX_STATEMENTS = 24;

    private final List<WeirdoLogic.Statement> heard = new ArrayList<>();
    /** Unrecognised NPC lines already logged this visit. */
    private final List<String> loggedUnknown = new ArrayList<>();
    private String winner;
    private boolean solved;

    /** The room being scanned; a different object means a different room, so the scan restarts. */
    private DungeonRoomBorders.Borders scanBorders;
    /** Next column to scan, as an index into the footprint's bounding box; -1 once done. */
    private int scanCursor;
    private final List<BlockPos> chests = new ArrayList<>();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    private final Map<String, BlockPos> npcs = new HashMap<>();
    private Map<String, BlockPos> pairs = Map.of();
    private int locateCountdown;
    /** A speaker was located or the scan finished since {@link #pairs} was computed. */
    private boolean pairsStale;

    /** What {@link #highlights()} returns; rebuilt on the tick, never on the render thread. */
    private volatile List<PuzzleHighlight> drawn = List.of();
    /** The inputs {@link #drawn} was built from, so it is rebuilt only when one changes. */
    private String drawnWinner;
    private Map<String, BlockPos> drawnPairs;
    private String drawnColorHex;
    private boolean drawnMarkWrong;

    @Override
    public PuzzleType type() {
        return PuzzleType.THREE_WEIRDOS;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    @Override
    public void onChat(String line) {
        if (line == null || solved) {
            return;
        }
        if (line.startsWith("PUZZLE SOLVED!")) {
            onSolved(line);
            return;
        }
        if (!WeirdoLogic.isNpcLine(line)) {
            return;
        }
        WeirdoLogic.Statement statement = WeirdoLogic.parse(line);
        if (statement == null) {
            logUnknown(line);
            return;
        }
        if (heard.size() >= MAX_STATEMENTS) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdos: statement cap reached, ignoring '{}'", line);
            return;
        }
        heard.add(statement);
        String decided = WeirdoLogic.solve(heard);
        // One line per statement (three per room): proves the chat reaches the solver at all, which
        // a 2026-10-16 room could not show - three statements were spoken and nothing was logged.
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdos: heard {} as {} ({} statement(s)) -> {}",
                statement.speaker(), statement.role(), heard.size(), decided == null ? "undecided" : decided);
        if (decided != null && !decided.equals(winner)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdos: reward is with '{}' (from '{}')",
                    decided, line);
        } else if (decided == null && winner != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdos: '{}' contradicts the earlier "
                    + "lines - showing nothing", line);
        }
        winner = decided;
    }

    private void onSolved(String line) {
        Matcher matcher = SOLVED.matcher(line);
        if (!matcher.matches()) {
            return;
        }
        solved = true;
        drawn = List.of();
        String server = matcher.group(1);
        if (winner == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdos solved - server named '{}', "
                    + "solver had no answer", server);
        } else {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdos solved - server named '{}', "
                    + "solver said '{}' ({})", server, winner, server.equals(winner) ? "match" : "MISMATCH");
        }
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (solved || minecraft.level == null) {
            return;
        }
        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        if (borders == null) {
            return;
        }
        if (borders != scanBorders) {
            scanBorders = borders;
            scanCursor = 0;
            chests.clear();
            npcs.clear();
            pairs = Map.of();
        }
        if (scanCursor >= 0) {
            scanStep(minecraft.level, borders);
        }
        if (heard.isEmpty()) {
            return;
        }
        if (--locateCountdown <= 0) {
            locateCountdown = LOCATE_INTERVAL;
            if (locateSpeakers(minecraft.level, borders)) {
                pairsStale = true;
            }
        }
        if (pairsStale && scanCursor < 0) {
            pairsStale = false;
            pairs = WeirdoChests.pair(npcs, chests);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdos: npcs={} chests={} paired={}",
                    npcs, chests, pairs);
        }
        rebuild();
    }

    /**
     * Scans up to {@link #SCAN_BUDGET} blocks of the footprint's bounding box for chests, resuming
     * from {@link #scanCursor}. Positions outside the footprint (the empty corners of an L) are
     * skipped by {@link DungeonRoomBorders.Borders#contains}.
     */
    private void scanStep(ClientLevel level, DungeonRoomBorders.Borders borders) {
        int minX = borders.min().getX();
        int minZ = borders.min().getZ();
        int sizeX = borders.max().getX() - minX + 1;
        int sizeZ = borders.max().getZ() - minZ + 1;
        int floor = borders.min().getY();
        int layers = SCAN_BELOW + SCAN_ABOVE + 1;
        int columns = sizeX * sizeZ;
        int budget = SCAN_BUDGET;
        while (scanCursor < columns && budget > 0) {
            int x = minX + scanCursor % sizeX;
            int z = minZ + scanCursor / sizeX;
            scanCursor++;
            probe.set(x, floor, z);
            if (!borders.contains(probe)) {
                continue;
            }
            for (int dy = -SCAN_BELOW; dy <= SCAN_ABOVE; dy++) {
                probe.set(x, floor + dy, z);
                BlockState state = level.getBlockState(probe);
                if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) {
                    chests.add(probe.immutable());
                }
            }
            budget -= layers;
        }
        if (scanCursor >= columns) {
            scanCursor = -1;
            pairsStale = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdos: room scan found {} chest(s) {}",
                    chests.size(), chests);
        }
    }

    /**
     * Finds the entity carrying each speaker's name inside the room and records the block it stands
     * in. When several entities carry the name (an NPC body and a floating name tag), the lowest is
     * taken - the body stands on the floor beside its chest.
     *
     * @return whether a new speaker was located
     */
    private boolean locateSpeakers(ClientLevel level, DungeonRoomBorders.Borders borders) {
        boolean missing = false;
        for (WeirdoLogic.Statement statement : heard) {
            if (!npcs.containsKey(statement.speaker())) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            return false;
        }
        int floor = borders.min().getY();
        AABB room = new AABB(borders.min().getX(), floor - SCAN_BELOW, borders.min().getZ(),
                borders.max().getX() + 1, floor + SCAN_ABOVE + 4, borders.max().getZ() + 1);
        boolean found = false;
        for (Entity entity : level.getEntities((Entity) null, room, e -> !e.isRemoved())) {
            String name = nameOf(entity);
            if (name == null || !isSpeaker(name)) {
                continue;
            }
            BlockPos at = entity.blockPosition();
            BlockPos known = npcs.get(name);
            if (known == null || at.getY() < known.getY()) {
                npcs.put(name, at);
                found = true;
            }
        }
        return found;
    }

    private boolean isSpeaker(String name) {
        for (WeirdoLogic.Statement statement : heard) {
            if (statement.speaker().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String nameOf(Entity entity) {
        Component custom = entity.getCustomName();
        if (custom == null) {
            return null;
        }
        String stripped = ChatFormatting.stripFormatting(custom.getString());
        return stripped == null ? null : stripped.trim();
    }

    /** Rebuilds {@link #drawn} when the winner, the pairs or the settings changed. */
    private void rebuild() {
        SBSConfig.DungeonsSettings dungeons = cfg();
        String colorHex = dungeons.puzzleThreeWeirdosColorHex;
        boolean markWrong = dungeons.puzzleWeirdosMarkWrong;
        if (Objects.equals(winner, drawnWinner) && pairs == drawnPairs
                && Objects.equals(colorHex, drawnColorHex) && markWrong == drawnMarkWrong) {
            return;
        }
        drawnWinner = winner;
        drawnPairs = pairs;
        drawnColorHex = colorHex;
        drawnMarkWrong = markWrong;
        Integer custom = OverlayColor.parseHex(colorHex);
        int color = 0xFF000000 | (custom != null ? custom : DEFAULT_COLOR);
        BlockPos chest = winner == null ? null : pairs.get(winner);
        if (chest == null) {
            drawn = List.of();
            return;
        }
        List<PuzzleHighlight> out = new ArrayList<>(3);
        out.add(PuzzleHighlight.answer(new AABB(chest).inflate(0.02), color, "§aREWARD"));
        if (markWrong) {
            for (Map.Entry<String, BlockPos> pair : pairs.entrySet()) {
                if (!pair.getKey().equals(winner)) {
                    out.add(PuzzleHighlight.context(new AABB(pair.getValue()), WRONG_COLOR, null));
                }
            }
        }
        drawn = List.copyOf(out);
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        return drawn;
    }

    @Override
    public void reset() {
        heard.clear();
        loggedUnknown.clear();
        winner = null;
        solved = false;
        scanBorders = null;
        scanCursor = 0;
        chests.clear();
        npcs.clear();
        pairs = Map.of();
        locateCountdown = 0;
        pairsStale = false;
        drawn = List.of();
        drawnWinner = null;
        drawnPairs = null;
        drawnColorHex = null;
        drawnMarkWrong = false;
    }

    /**
     * Logs an {@code [NPC]} line that is not a known statement, once per distinct line and visit -
     * the "Talk to all of us first!" opener lands here too, which is expected. New wording from the
     * server shows up in this log line before anyone has to wonder why the box is missing.
     */
    private void logUnknown(String line) {
        if (WeirdoLogic.isOpener(line) || loggedUnknown.size() >= 12 || loggedUnknown.contains(line)) {
            return;
        }
        loggedUnknown.add(line);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdos: unrecognised NPC line '{}'", line);
    }
}
