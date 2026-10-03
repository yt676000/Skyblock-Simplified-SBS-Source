/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;

import java.util.List;

/**
 * One puzzle's solver. {@link PuzzleCoordinator} owns the lifecycle; a strategy only reads state and
 * says what it wants drawn.
 *
 * <p><b>The contract that matters is {@link #highlights()} returning an empty list.</b> Failing a
 * puzzle costs 14 points of Skill score, so a wrong hint is worse than no hint - which means every
 * solver's uncertain path must be the one that draws nothing, not the one that draws its best guess
 * dimmed. There is deliberately no "confidence" field to weaken that into a judgement call.
 *
 * <p><b>{@link #reset()} is called by the coordinator, not by the strategy.</b> Room change, dungeon
 * end, world change and server hop are all handled once, centrally, because a per-solver reset is a
 * per-solver opportunity to forget one - and stale state surfacing in the next room is the failure
 * this feature was asked to design against.
 */
public interface PuzzleStrategy {

    /** The puzzle this solves. The coordinator only renders it inside the matching room. */
    PuzzleType type();

    /** The room was entered. {@link #reset()} has already run. */
    default void onEnter() {
    }

    /**
     * A colour-stripped chat line, while in a dungeon.
     *
     * <p>Offered regardless of which room is locked: room identification can lag the first chat line
     * of a puzzle, and a solver that misses the question because the tracker had not caught up is
     * useless. Rendering is still gated on the room, so accepting a line early costs nothing.
     */
    default void onChat(String line) {
    }

    /** Per-tick work, only while the player is in this puzzle's room. Keep it cheap or throttle it. */
    default void onTick(Minecraft minecraft) {
    }

    /** Everything drawn for this puzzle right now. Empty means "not certain" - see the class note. */
    List<PuzzleHighlight> highlights();

    /** Drop every scrap of per-room state. Must leave the solver as freshly constructed. */
    void reset();
}
