/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.scoreboard;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import sbs.modid.client.skills.farming.model.FarmingText;

import java.util.ArrayList;
import java.util.List;

/**
 * The sidebar as it stood at one moment: immutable, and shared by every reader until the sidebar
 * changes. {@link ScoreboardSnapshots} owns the current one and decides when to rebuild it.
 *
 * <p>{@link #lines()} keep the order {@link Scoreboard#listPlayerScores} hands them out in - the raw
 * order every reader used to get - and {@link #texts()} is the same rows colour-stripped, so a reader
 * of plain text gets its list without allocating one. A reader that wants vanilla's on-screen order
 * sorts by {@link Line#score()} itself.
 *
 * <p>{@link #revision()} goes up by one each time the content actually changes, never on a rebuild
 * that produced the same rows. A caller can key a cache on it (or on the snapshot instance, which is
 * replaced exactly when the revision moves).
 */
public record ScoreboardSnapshot(List<Line> lines, List<String> texts, int revision) {

    /** No sidebar: no world, no display objective, or an objective without rows. */
    public static final ScoreboardSnapshot EMPTY = new ScoreboardSnapshot(List.of(), List.of(), 0);

    /**
     * One sidebar row.
     *
     * @param owner   the score holder's name - on Hypixel a fake, invisible entry
     * @param score   the row's score; only its order matters (vanilla sorts by it)
     * @param display {@code prefix + owner + suffix} as vanilla composes it, colours intact
     * @param text    {@code display} with every {@code §} code removed
     */
    public record Line(String owner, int score, Component display, String text) {
    }

    public ScoreboardSnapshot {
        lines = List.copyOf(lines);
        texts = List.copyOf(texts);
    }

    /** True when a sidebar objective is shown and has at least one row. */
    public boolean hasSidebar() {
        return !lines.isEmpty();
    }

    /**
     * Reads {@code board}'s sidebar and returns {@code previous} itself when the rows are unchanged,
     * otherwise a new snapshot one revision on. Comparison is by value - the composed components and
     * the stripped strings - never by the identity of a team or its prefix: vanilla mutates the
     * {@link PlayerTeam} in place when a team packet arrives, so an identity check would freeze.
     */
    public static ScoreboardSnapshot build(Scoreboard board, ScoreboardSnapshot previous) {
        List<Line> lines = read(board);
        if (lines.equals(previous.lines())) {
            return previous;
        }
        List<String> texts = new ArrayList<>(lines.size());
        for (Line line : lines) {
            texts.add(line.text());
        }
        return new ScoreboardSnapshot(lines, texts, previous.revision() + 1);
    }

    private static List<Line> read(Scoreboard board) {
        if (board == null) {
            return List.of();
        }
        Objective sidebar = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return List.of();
        }
        List<Line> lines = new ArrayList<>();
        for (PlayerScoreEntry entry : board.listPlayerScores(sidebar)) {
            Component display = compose(board, entry.owner());
            lines.add(new Line(entry.owner(), entry.value(), display, FarmingText.strip(display.getString())));
        }
        return lines;
    }

    /**
     * Hypixel renders each row as a fake entry whose visible text lives in the team prefix/suffix, so
     * a row is {@code prefix + owner + suffix} - the composite vanilla's sidebar draws.
     */
    private static Component compose(Scoreboard board, String owner) {
        PlayerTeam team = board.getPlayersTeam(owner);
        if (team == null) {
            return Component.literal(owner);
        }
        MutableComponent line = Component.empty();
        line.append(team.getPlayerPrefix());
        line.append(Component.literal(owner));
        line.append(team.getPlayerSuffix());
        return line;
    }
}
