/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.scoreboard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Owns the one {@link ScoreboardSnapshot} every sidebar reader shares, and rebuilds it only when the
 * sidebar may have changed.
 *
 * <p><b>Change detection is a dirty flag, not a comparison of objects.</b> Vanilla mutates a
 * {@code PlayerTeam} in place when a team packet arrives (the prefix is swapped on the same team), so
 * nothing about the objects tells you the text moved. Instead {@code SidebarTabPacketsMixin} marks the
 * snapshot dirty after every scoreboard packet the client applies - objective, score, reset, display
 * slot, team - and the scoreboard itself being replaced (a new connection, or no world) counts as
 * dirty too. Those packets are the only way the client's scoreboard changes.
 *
 * <p><b>When it rebuilds:</b> on the client tick, and also on the first read after a packet when
 * that read is on the client thread. The second half keeps the old "always live" behaviour: packets
 * are applied between frames, not on tick boundaries, so a tick-only rebuild would leave a reader
 * one tick behind the sidebar it is looking at. A read from another thread never rebuilds; it gets
 * the last published snapshot.
 *
 * <p>The snapshot is immutable and published through a volatile field, so any thread may hold and
 * read one. A clean read costs a volatile load and an identity check - nothing is allocated.
 */
public final class ScoreboardSnapshots {

    private static volatile ScoreboardSnapshot current = ScoreboardSnapshot.EMPTY;
    private static volatile boolean dirty = true;
    /** The scoreboard {@link #current} was read from; a different one (or none) means rebuild. */
    private static Scoreboard source;

    private ScoreboardSnapshots() {
    }

    /** Called after the client applies a scoreboard packet (any thread; cheap). */
    public static void markDirty() {
        dirty = true;
    }

    /**
     * The teams and score holders behind the current sidebar rows, refilled on every rebuild. Render
     * thread only. A team packet that names neither cannot change a row - see {@link #markTeamDirty}.
     */
    private static final Set<String> sidebarTeams = new HashSet<>();
    private static final Set<String> sidebarOwners = new HashSet<>();

    /**
     * Called after a team packet. Hypixel keeps a team per nametag (every NPC and player in the Hub),
     * and their prefix updates arrive many times a second; marking the sidebar dirty for each one
     * re-read and re-composed the whole sidebar on the next frame for nothing. A team packet can only
     * change a row when it names a team a row is drawn from, or moves one of the rows' score holders
     * between teams - anything else is left alone. A row that does not exist yet arrives with its own
     * score packet, which marks dirty unconditionally.
     */
    public static void markTeamDirty(String team, Collection<String> players) {
        if (dirty) {
            return;
        }
        if (team != null && sidebarTeams.contains(team)) {
            dirty = true;
            return;
        }
        if (players != null && !sidebarOwners.isEmpty()) {
            for (String player : players) {
                if (sidebarOwners.contains(player)) {
                    dirty = true;
                    return;
                }
            }
        }
    }

    /** Rebuilds when dirty. Called from the client tick. */
    public static void onClientTick() {
        refresh();
    }

    /**
     * The current sidebar. Rebuilt first when a packet changed it since the last read and this is the
     * client thread; otherwise the published snapshot as is.
     */
    public static ScoreboardSnapshot current() {
        Minecraft minecraft = Minecraft.getInstance();
        // No client at all is the offline-tooling case; there is no scoreboard to read.
        if (minecraft != null && minecraft.isSameThread()) {
            refresh();
        }
        return current;
    }

    private static void refresh() {
        ClientLevel level = Minecraft.getInstance().level;
        refreshFrom(level == null ? null : level.getScoreboard());
    }

    /**
     * The rebuild rule on its own, without a client: rebuild when dirty or when {@code board} is not
     * the scoreboard the current snapshot came from. Package-visible for the tests.
     */
    static ScoreboardSnapshot refreshFrom(Scoreboard board) {
        if (dirty || board != source) {
            // Cleared before the read, so a packet landing mid-read leaves it set for the next one.
            dirty = false;
            source = board;
            current = ScoreboardSnapshot.build(board, current);
            indexTeams(board, current);
        }
        return current;
    }

    /** Refills {@link #sidebarTeams} / {@link #sidebarOwners} from the rows just read. */
    private static void indexTeams(Scoreboard board, ScoreboardSnapshot snapshot) {
        sidebarTeams.clear();
        sidebarOwners.clear();
        if (board == null) {
            return;
        }
        for (ScoreboardSnapshot.Line line : snapshot.lines()) {
            sidebarOwners.add(line.owner());
            PlayerTeam team = board.getPlayersTeam(line.owner());
            if (team != null) {
                sidebarTeams.add(team.getName());
            }
        }
    }
}
