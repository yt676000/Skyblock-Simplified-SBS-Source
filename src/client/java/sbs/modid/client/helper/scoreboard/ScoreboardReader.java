/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import sbs.modid.client.core.render.CachedModel;
import sbs.modid.client.core.scoreboard.ScoreboardSnapshot;
import sbs.modid.client.core.scoreboard.ScoreboardSnapshots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the live sidebar scoreboard into a form the Custom Scoreboard can style – the title plus one
 * {@link ScoreboardLine} per row, colours intact.
 *
 * <p>This is the colour-preserving companion to {@link sbs.modid.client.dungeons.run.logic.DungeonScoreboard},
 * which only needs the stripped text for its area checks. Here each line keeps its original component
 * (rebuilt from the team prefix/suffix around the owner, exactly as vanilla renders it), so
 * "keep original colours" is free and the custom colour mode just uses the stripped text instead.
 *
 * <p>Order matches vanilla: entries are sorted by score descending and capped at
 * {@value #MAX_LINES} rows, so what the SBS panel shows lines up with what Hypixel intended.
 */
public final class ScoreboardReader {

    /** Vanilla renders at most 15 sidebar rows; we mirror that so nothing is shown vanilla would hide. */
    public static final int MAX_LINES = 15;

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** Highest score at the top, matching the vanilla sidebar. */
    private static final Comparator<ScoreboardSnapshot.Line> BY_SCORE_DESC =
            Comparator.comparingInt(ScoreboardSnapshot.Line::score).reversed();

    /** The display rows, derived once per sidebar change rather than once per frame. */
    private static final CachedModel<ScoreboardSnapshot, List<ScoreboardLine>> LINES =
            new CachedModel<>("scoreboard.lines", ScoreboardReader::displayLines);

    private ScoreboardReader() {
    }

    /** True when a sidebar objective is currently displayed and has rows. */
    public static boolean hasSidebar() {
        return ScoreboardSnapshots.current().hasSidebar();
    }

    /** The sidebar's title component, or {@code null} when there is no sidebar. */
    public static Component title() {
        Objective sidebar = sidebarObjective();
        return sidebar == null ? null : sidebar.getDisplayName();
    }

    /**
     * The sidebar rows top-to-bottom (highest score first), colours intact; empty when none.
     *
     * <p>Built from the shared {@link ScoreboardSnapshot} and cached on it, so a frame on which the
     * sidebar has not changed costs a lookup. The list is immutable and shared - copy it to change it.
     */
    public static List<ScoreboardLine> lines() {
        return LINES.get(ScoreboardSnapshots.current());
    }

    /**
     * The snapshot's rows in vanilla's order - score descending, capped at {@value #MAX_LINES}. The
     * sort is stable over the snapshot's own order, which is the order {@code listPlayerScores} gave,
     * so ties land exactly where the old per-call sort put them.
     */
    private static List<ScoreboardLine> displayLines(ScoreboardSnapshot snapshot) {
        List<ScoreboardSnapshot.Line> entries = new ArrayList<>(snapshot.lines());
        entries.sort(BY_SCORE_DESC);
        List<ScoreboardLine> lines = new ArrayList<>(Math.min(entries.size(), MAX_LINES));
        for (ScoreboardSnapshot.Line entry : entries) {
            if (lines.size() >= MAX_LINES) {
                break;
            }
            // entry.text() is strip(display.getString()) - the same stripping this class does.
            lines.add(new ScoreboardLine(entry.display(), entry.text(), signatureOf(entry.text())));
        }
        return List.copyOf(lines);
    }

    /**
     * The value-independent signature of a stripped line: colour codes gone, every live number turned
     * into a single {@code #}, whitespace normalised. "Purse: 1,234" and "Purse: 5,678" both become
     * "purse: #", so a hidden / reordered line stays put as its number changes.
     */
    public static String signatureOf(String stripped) {
        // One normaliser for the mod (core/dev/LayoutSignature), so this and the Layout Recorder
        // cannot drift. Its numbers() rule is this method's old behaviour exactly: saved hide/reorder
        // choices are keyed on it, and the recorder's richer rule would re-key every one of them.
        return sbs.modid.client.core.dev.LayoutSignature.numbers(stripped);
    }

    private static Scoreboard scoreboard() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.getScoreboard();
    }

    private static Objective sidebarObjective() {
        Scoreboard scoreboard = scoreboard();
        return scoreboard == null ? null : scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
    }

    /** Removes {@code §x} colour / format codes from a string. */
    static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++; // skip the code character too
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
