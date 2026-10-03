/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three Weirdos: three NPCs, three chests, statements of which one or more are lies.
 *
 * <p><b>This ships as the deduction plus a line recorder, and draws nothing yet. That is deliberate
 * and it is the instruction the feature was requested under</b> - collect real statements before
 * writing patterns rather than guessing the phrasings. The half that does not depend on wording is
 * built and unit-tested in {@link WeirdoLogic}; the half that does is {@link #PATTERNS}, which is
 * empty. Filling it in is a data change against a solver already known to be correct.
 *
 * <p><b>What this does today:</b> while the player is standing in the Three Weirdos room, every
 * chat line is logged verbatim under {@code [SBS][Puzzle]}, once per distinct line. One visit
 * produces the transcript the patterns are written from. The room gate is what keeps this from
 * being a chat dump - it only records where the statements actually are.
 *
 * <p><b>Why it draws nothing rather than something dim.</b> Failing this room costs 14 points of
 * Skill score, so a guess presented quietly is still a guess presented. With no patterns there are
 * no claims, with no claims {@link WeirdoLogic} returns undetermined, and undetermined renders as
 * nothing. The safe path is the one that needs no code.
 */
public final class ThreeWeirdosSolver implements PuzzleStrategy {

    /**
     * Statement wording → claim, <b>empty until real lines have been read</b>.
     *
     * <p>Each entry would map one phrasing to a {@link WeirdoLogic.Kind}, with a capture group for
     * the named weirdo where the claim is about someone else. Nothing goes in here from memory or
     * from a wiki: a pattern that half-matches produces a claim set that is wrong rather than
     * absent, and a wrong claim set is exactly what {@link WeirdoLogic} cannot protect against - it
     * will confidently deduce the wrong chest from confidently wrong inputs.
     */
    private static final Map<Pattern, WeirdoLogic.Kind> PATTERNS = new LinkedHashMap<>();

    /** "[NPC] Wizard: ..." - the shape a weirdo's line is expected to take. Unverified. */
    private static final Pattern NPC_LINE = Pattern.compile("^\\[NPC]\\s*([^:]{2,24}):\\s*(.+)$");

    /** Whether the player is standing in this room, which is what gates the recorder. */
    private boolean inRoom;

    /** Lines already logged this visit, so a repeated line is recorded once. */
    private final List<String> logged = new ArrayList<>();

    @Override
    public PuzzleType type() {
        return PuzzleType.THREE_WEIRDOS;
    }

    @Override
    public void onEnter() {
        inRoom = true;
    }

    @Override
    public void onChat(String line) {
        if (!inRoom || line == null || line.isBlank() || logged.contains(line)) {
            return;
        }
        // Bounded so a room sat in for ten minutes cannot grow this without limit; a transcript
        // longer than this is not a puzzle statement set, it is party chat.
        if (logged.size() >= 40) {
            return;
        }
        logged.add(line);
        Matcher npc = NPC_LINE.matcher(line);
        if (npc.matches()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdo statement - speaker='{}' says='{}'",
                    npc.group(1).trim(), npc.group(2).trim());
        } else {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] weirdo room line (not the expected "
                    + "[NPC] shape): '{}'", line);
        }
    }

    /**
     * Always empty for now - see the class note. Kept as a real method rather than left out so the
     * shape the solver returns is fixed before the parser lands on top of it.
     */
    @Override
    public List<PuzzleHighlight> highlights() {
        if (PATTERNS.isEmpty()) {
            return List.of();
        }
        // Unreachable while PATTERNS is empty. The remaining work when it is not: turn the recorded
        // statements into claims, call WeirdoLogic.solve, and map the winning index onto the room's
        // three chests. The chest lookup is deliberately not written yet - it would be code no test
        // and no run could exercise.
        return List.of();
    }

    @Override
    public void reset() {
        inRoom = false;
        logged.clear();
    }
}
