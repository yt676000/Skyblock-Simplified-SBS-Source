/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.mining.nucleus.model.NucleusSignals;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The Jungle Temple cheese spot as a fixed offset from the Kalhuiki Door Guardian. Pure: the caller
 * hands it chat text, the guardian's block position, the lobby id and the player's position.
 *
 * <p>The anchor belongs to one lobby. It is set once, from a guardian the player can see or has just
 * heard, and dropped on a lobby change or when the player leaves the island ({@link #reset()}).
 */
public final class TempleCheese {

    /** Offset from the guardian's feet block to the cheese spot, as the maintainer gave it. */
    public static final int OFFSET_X = 29;
    public static final int OFFSET_Y = -32;
    public static final int OFFSET_Z = 48;
    /** Until a run's {@code [SBS][Temple]} lines confirm the base point and the orientation. */
    public static final Certainty OFFSET_CERTAINTY = Certainty.ESTIMATED;

    /** How far away the guardian may stand and still count as seen. */
    public static final double SIGHT_RANGE = 8.0;
    /** How long after a guardian chat line the player counts as standing at the door. */
    public static final long CHAT_WINDOW_MS = 30_000L;
    /** How close to the target the player has to come for the "reached" log line. */
    public static final double REACHED_WITHIN = 3.0;

    /** How the guardian was accepted as the anchor. */
    public enum Via { SIGHT, CHAT }

    /** A block position. */
    public record Pos(int x, int y, int z) {
        Pos plus(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }
    }

    /** The guardian's block, how it was seen, and the lobby it was seen in. */
    public record Anchor(Pos guardian, Via via, String lobby) {
        public Pos target() {
            return TempleCheese.target(guardian);
        }
    }

    private Anchor anchor;
    private long guardianLineAt = -1L;
    private boolean reachedLogged;

    /** The cheese spot for a guardian standing in {@code guardian}. */
    public static Pos target(Pos guardian) {
        return guardian.plus(OFFSET_X, OFFSET_Y, OFFSET_Z);
    }

    /** Whether a colour-stripped, trimmed chat line is one of the guardian's own lines. */
    public static boolean isGuardianLine(String plain) {
        if (plain == null) {
            return false;
        }
        for (Pattern line : NucleusSignals.GUARDIAN_LINES) {
            if (line.matcher(plain).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a colour-stripped entity or nametag name is the guardian's. */
    public static boolean isGuardianName(String name) {
        return name != null && NucleusSignals.GUARDIAN_NAME.matcher(name).find();
    }

    /** A chat line. Returns whether it was a guardian line, which opens the chat window. */
    public boolean onChat(String plain, long now) {
        if (!isGuardianLine(plain)) {
            return false;
        }
        guardianLineAt = now;
        return true;
    }

    /** Whether a guardian line arrived within {@link #CHAT_WINDOW_MS}. */
    public boolean nearGuardian(long now) {
        return guardianLineAt >= 0 && now - guardianLineAt <= CHAT_WINDOW_MS;
    }

    /**
     * Whether a guardian at {@code distance} blocks counts: within {@link #SIGHT_RANGE}, and either in
     * line of sight or just heard. {@code null} when it does not.
     */
    public Via accept(double distance, boolean lineOfSight, long now) {
        if (distance > SIGHT_RANGE) {
            return null;
        }
        if (lineOfSight) {
            return Via.SIGHT;
        }
        return nearGuardian(now) ? Via.CHAT : null;
    }

    public boolean wantsAnchor() {
        return anchor == null;
    }

    public Anchor anchor() {
        return anchor;
    }

    /** Sets the anchor if there is none yet. Returns the new anchor, or {@code null} when one was already set. */
    public Anchor setAnchor(Pos guardian, Via via, String lobby) {
        if (anchor != null || guardian == null || via == null) {
            return null;
        }
        anchor = new Anchor(guardian, via, lobby);
        reachedLogged = false;
        return anchor;
    }

    /**
     * The current lobby id. A known lobby that differs from the anchor's drops the anchor; an unknown
     * one ({@code null}, the tab not served yet) changes nothing. Returns whether it reset.
     */
    public boolean onLobby(String lobby) {
        if (anchor == null || lobby == null || anchor.lobby() == null || Objects.equals(lobby, anchor.lobby())) {
            return false;
        }
        reset();
        return true;
    }

    /** A new run in the same lobby: the "reached" line may be logged again. */
    public void newRun() {
        reachedLogged = false;
    }

    /** Lobby change or island leave: nothing carries over. */
    public void reset() {
        anchor = null;
        guardianLineAt = -1L;
        reachedLogged = false;
    }

    /**
     * Whether the player at {@code x,y,z} has just come within {@link #REACHED_WITHIN} blocks of the
     * target's block centre. True once per anchor and run.
     */
    public boolean reached(double x, double y, double z) {
        if (anchor == null || reachedLogged) {
            return false;
        }
        Pos target = anchor.target();
        double dx = x - (target.x() + 0.5);
        double dy = y - target.y();
        double dz = z - (target.z() + 0.5);
        if (dx * dx + dy * dy + dz * dz > REACHED_WITHIN * REACHED_WITHIN) {
            return false;
        }
        reachedLogged = true;
        return true;
    }

    /** The player's block relative to the anchor, for the probe lines; {@code null} without one. */
    public Pos offsetFromAnchor(Pos player) {
        if (anchor == null || player == null) {
            return null;
        }
        Pos g = anchor.guardian();
        return new Pos(player.x() - g.x(), player.y() - g.y(), player.z() - g.z());
    }
}
