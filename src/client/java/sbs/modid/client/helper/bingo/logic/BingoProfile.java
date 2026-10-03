/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.bingo.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.tab.TabWidgets;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether the player is on a Bingo profile, from the marker Hypixel puts after a Bingo player's name
 * in the tab list's Players section: {@code [17] <name> Ⓑ}.
 *
 * <p>The marker is {@code CONFIRMED} for other players (2574 tab rows in the instance logs, beside
 * {@code ♲} for Ironman). That the player's <b>own</b> row carries it too is {@code ESTIMATED} - it
 * is the same list, but no Bingo profile has been played here. The verdict is logged whenever it
 * changes, with the row it was read from, so the first Bingo session settles it.
 *
 * <p>Read every two seconds: a profile's type cannot change without a server switch, and the tab
 * scan is a list walk.
 */
public final class BingoProfile {

    private static final BingoProfile INSTANCE = new BingoProfile();

    private static final long CHECK_MS = 2_000;
    private static final String MARKER = "Ⓑ";   // Ⓑ

    private long lastCheck;
    private boolean bingo;
    private boolean logged;

    private BingoProfile() {
    }

    public static BingoProfile getInstance() {
        return INSTANCE;
    }

    public boolean onBingo() {
        return bingo;
    }

    /** Game tick; does nothing but a clock comparison between checks, and nothing while switched off. */
    public void tick(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_MS) {
            return;
        }
        lastCheck = now;
        if (minecraft.player == null || !ConfigManager.getInstance().get().bingo.enabled) {
            bingo = false;
            return;
        }
        String own = minecraft.player.getGameProfile().name();
        String row = ownRow(TabWidgets.lines(), own);
        boolean next = marksBingo(row);
        if (next != bingo || !logged) {
            logged = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bingo] profile marker {} - own tab row: {}",
                    next ? "seen" : "not seen", row == null ? "(none found)" : row);
        }
        bingo = next;
    }

    /** The Players-section row for {@code ownName} - {@code [level] name …} - or null. */
    static String ownRow(List<String> lines, String ownName) {
        if (ownName == null || ownName.isEmpty()) {
            return null;
        }
        Pattern row = Pattern.compile("^\\[\\d+\\]\\s+" + Pattern.quote(ownName) + "(?:\\s.*)?$");
        for (String line : lines) {
            if (row.matcher(line).matches()) {
                return line;
            }
        }
        return null;
    }

    /** Whether a Players-section row carries the Bingo marker. */
    static boolean marksBingo(String row) {
        return row != null && row.contains(MARKER);
    }
}
