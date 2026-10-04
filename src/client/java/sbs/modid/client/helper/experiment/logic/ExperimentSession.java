/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import java.util.Objects;

/**
 * Decides when the solvers' memory belongs to a different game and must be dropped.
 *
 * <p>Not on a new {@code Screen} object: the recordings show ONE open per game (the server never
 * reopens the menu), yet something client-side swaps the Screen object mid-game, and resetting on
 * that wiped Superpairs' memory in the middle of a game. A game is identified by the menu's
 * container id, the game and its tier (from the title). The memory is dropped when any of them
 * changes, or when no game screen has been open for {@link #CLOSED_MS}.
 */
final class ExperimentSession {

    /** No game screen for this long = the game was closed for good. */
    static final long CLOSED_MS = 1_000L;

    private int containerId = Integer.MIN_VALUE;
    private ExperimentationTable.Game game = ExperimentationTable.Game.NONE;
    private String tier = "";
    private long lastSeenAt;
    private boolean open;

    /**
     * One tick. {@code game == NONE} means no game screen is open. Returns the reason the memory
     * must be dropped now, or {@code null} to keep it.
     */
    String tick(int containerId, ExperimentationTable.Game game, String tier, long now) {
        if (game == ExperimentationTable.Game.NONE) {
            if (open && now - lastSeenAt > CLOSED_MS) {
                open = false;
                this.game = ExperimentationTable.Game.NONE;
                this.containerId = Integer.MIN_VALUE;
                return "closed";
            }
            return null;
        }
        lastSeenAt = now;
        String reason = null;
        if (!open || this.game == ExperimentationTable.Game.NONE) {
            reason = "opened " + game;
        } else if (game != this.game) {
            reason = "game " + this.game + " -> " + game;
        } else if (!Objects.equals(tier, this.tier)) {
            reason = "tier " + this.tier + " -> " + tier;
        } else if (containerId != this.containerId) {
            reason = "containerId " + this.containerId + " -> " + containerId;
        }
        open = true;
        this.game = game;
        this.tier = tier == null ? "" : tier;
        this.containerId = containerId;
        return reason;
    }

    /** The tier in a game title: "Superpairs (Metaphysical)" -> "Metaphysical", else "". */
    static String tierOf(String title) {
        int open = title.lastIndexOf('(');
        int close = title.lastIndexOf(')');
        return open >= 0 && close > open ? title.substring(open + 1, close).trim() : "";
    }
}
