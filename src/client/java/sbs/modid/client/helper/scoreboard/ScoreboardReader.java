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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

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
    private static final Comparator<PlayerScoreEntry> BY_SCORE_DESC =
            Comparator.comparingInt(PlayerScoreEntry::value).reversed();

    private ScoreboardReader() {
    }

    /** True when a sidebar objective is currently displayed. */
    public static boolean hasSidebar() {
        Scoreboard scoreboard = scoreboard();
        if (scoreboard == null) {
            return false;
        }
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        return sidebar != null && !scoreboard.listPlayerScores(sidebar).isEmpty();
    }

    /** The sidebar's title component, or {@code null} when there is no sidebar. */
    public static Component title() {
        Objective sidebar = sidebarObjective();
        return sidebar == null ? null : sidebar.getDisplayName();
    }

    /** The sidebar rows top-to-bottom (highest score first), colours intact; empty when none. */
    public static List<ScoreboardLine> lines() {
        List<ScoreboardLine> lines = new ArrayList<>();
        Scoreboard scoreboard = scoreboard();
        if (scoreboard == null) {
            return lines;
        }
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return lines;
        }
        List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(sidebar));
        entries.sort(BY_SCORE_DESC);
        for (PlayerScoreEntry entry : entries) {
            if (lines.size() >= MAX_LINES) {
                break;
            }
            Component display = lineComponent(scoreboard, entry);
            String stripped = strip(display.getString());
            lines.add(new ScoreboardLine(display, stripped, signatureOf(stripped)));
        }
        return lines;
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

    /**
     * Rebuilds a line's coloured component from its team prefix/suffix around the owner – the same
     * {@code prefix + owner + suffix} composite vanilla renders (and that
     * {@link sbs.modid.client.dungeons.run.logic.DungeonScoreboard} strips for its area checks), so what the SBS
     * panel shows matches the vanilla sidebar character for character.
     */
    private static Component lineComponent(Scoreboard scoreboard, PlayerScoreEntry entry) {
        String owner = entry.owner();
        PlayerTeam team = scoreboard.getPlayersTeam(owner);
        if (team == null) {
            return Component.literal(owner);
        }
        MutableComponent line = Component.empty();
        line.append(team.getPlayerPrefix());
        line.append(Component.literal(owner));
        line.append(team.getPlayerSuffix());
        return line;
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
