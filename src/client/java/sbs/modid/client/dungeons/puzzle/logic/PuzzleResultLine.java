/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

/**
 * The server's puzzle result lines ({@code PUZZLE SOLVED!} / {@code PUZZLE FAIL!} / {@code PUZZLE
 * FAILED!}) arrive in 26.x behind a bold yellow {@code "> "}:
 * {@code §e§l> §rPUZZLE SOLVED! rt606 tied Tic Tac Toe! Good job!} - CONFIRMED in the maintainer's
 * log, 2026-10-15. The 1.8.9 captures every pattern was written from had no prefix, so everything
 * that checks for those lines goes through here and accepts both.
 *
 * <p>Only a leading {@code "> "} is dropped. A relayed line ({@code Party > name: PUZZLE ...}) does
 * not start with it and stays rejected by the anchored patterns.
 */
public final class PuzzleResultLine {

    /** The optional prefix, for patterns anchored with {@code ^}. */
    public static final String PREFIX = "(?:> )?";

    private PuzzleResultLine() {
    }

    /** The colour-stripped line without the leading {@code "> "}, or unchanged when it has none. */
    public static String strip(String line) {
        return line != null && line.startsWith("> ") ? line.substring(2) : line;
    }
}
