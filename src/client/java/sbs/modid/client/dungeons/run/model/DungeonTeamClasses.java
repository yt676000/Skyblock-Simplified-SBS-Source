/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.run.logic.DungeonScoreboard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves each dungeon teammate's class into a name → class-initial table, mapping every initial to
 * its display colour: <b>A</b>rcher red, <b>B</b>erserk orange, <b>T</b>ank green, <b>H</b>ealer
 * purple, <b>M</b>age blue (user-chosen scheme). Consumed by the Party highlight (box/nametag colour), the
 * SBS dungeon map (teammate marker colour) and the Tank-range bubble.
 *
 * <p><b>Source: the TAB list.</b> Hypixel writes each party member's class into their tab entry
 * ({@code "[212] Name (Tank 50)"}) – including the <b>local player's own</b> entry, which the old
 * sidebar-only parse ({@code "[A] Name"}) never carried, so the tank never saw their own range. The
 * tab list is primary; the sidebar stays a fallback.
 *
 * <p><b>Latched per run.</b> A dungeon class cannot change once the run starts, so the first class
 * seen for each player is kept for the whole run and only cleared on leaving The Catacombs. A
 * momentary tab/sidebar read miss can therefore never blank a class mid-run (which is what made the
 * local tank's own bubble flicker off). The re-parse is throttled to once a second and cached.
 */
public final class DungeonTeamClasses {

    /** Tab entry "… Name (Tank 50)" – the full class name in parentheses right after the player name. */
    private static final Pattern TAB_CLASS_LINE =
            Pattern.compile("(\\w{2,16})\\W*\\((Healer|Mage|Berserk|Archer|Tank)\\b");

    /** Sidebar fallback "[T] Name" – the class initial in brackets before the teammate's name. */
    private static final Pattern CLASS_LINE =
            Pattern.compile("\\[([ABTHM])\\]\\s+([A-Za-z0-9_]{1,16})");

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private static final long REFRESH_MS = 1000;

    // Class colours (user spec): A red, B orange, T green, H purple, M blue.
    public static final int COLOR_ARCHER = 0xFFFF5555;
    public static final int COLOR_BERSERK = 0xFFFFAA00;
    public static final int COLOR_TANK = 0xFF55FF55;
    public static final int COLOR_HEALER = 0xFFB566FF;
    public static final int COLOR_MAGE = 0xFF5599FF;

    /** Lower-cased name → class initial from the latest parse. Replaced wholesale on refresh. */
    private static volatile Map<String, Character> byName = Map.of();
    /** Per-run latch: first class seen per player, held for the whole run (class can't change). */
    private static final Map<String, Character> latched = new ConcurrentHashMap<>();
    private static volatile long lastRefreshAt;
    private static volatile boolean loggedSelf;

    private DungeonTeamClasses() {
    }

    /** Whether any dungeon classes are known right now (live parse OR the per-run latch). */
    public static boolean hasClasses() {
        refresh();
        return !byName.isEmpty() || !latched.isEmpty();
    }

    /**
     * The lower-cased IGNs of everyone latched into this run (parsed from the tab list's
     * "(Class level)" tags, local player included), or an empty set while none are known yet.
     * This is the run's authoritative player roster – the map uses it to drop ghost markers and to
     * name unnamed ones.
     */
    public static java.util.Set<String> runRoster() {
        refresh();
        return latched.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(latched.keySet());
    }

    /**
     * The class initial for a player ({@code 'A','B','T','H','M'}), or {@code 0} when none is known.
     * Falls back to the per-run latch so a class stays resolved through a tab/sidebar read gap; names
     * can be truncated, so a prefix match backs up the exact one.
     */
    public static char classOf(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return 0;
        }
        refresh();
        String lower = playerName.toLowerCase(Locale.ROOT);
        Character live = lookup(byName, lower);
        if (live != null) {
            return live;
        }
        Character held = lookup(latched, lower);
        return held != null ? held : 0;
    }

    /** Exact then prefix (≥6 chars, for truncated names) lookup in a name → class table. */
    private static Character lookup(Map<String, Character> table, String lower) {
        Character exact = table.get(lower);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Character> entry : table.entrySet()) {
            if (entry.getKey().length() >= 6 && lower.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** The class colour for a player, or {@code fallback} when the sidebar names no class. */
    public static int colorOf(String playerName, int fallback) {
        return switch (classOf(playerName)) {
            case 'A' -> COLOR_ARCHER;
            case 'B' -> COLOR_BERSERK;
            case 'T' -> COLOR_TANK;
            case 'H' -> COLOR_HEALER;
            case 'M' -> COLOR_MAGE;
            default -> fallback;
        };
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshAt < REFRESH_MS) {
            return;
        }
        lastRefreshAt = now;

        Map<String, Character> parsed = new HashMap<>();
        parseTabList(parsed);   // primary: "[212] Name (Tank 50)" - carries the local player too
        for (String line : DungeonScoreboard.sidebarLines()) {   // fallback: "[T] Name"
            Matcher matcher = CLASS_LINE.matcher(line);
            if (matcher.find()) {
                parsed.putIfAbsent(matcher.group(2).toLowerCase(Locale.ROOT), matcher.group(1).charAt(0));
            }
        }
        byName = parsed.isEmpty() ? Map.of() : Map.copyOf(parsed);

        // Latch for the run, or drop it once the run is over.
        if (DungeonScoreboard.isInCatacombs()) {
            parsed.forEach(latched::putIfAbsent);   // first class seen per player wins (can't change)
            logSelfClassOnce();
        } else if (!latched.isEmpty()) {
            latched.clear();
            loggedSelf = false;
        }
    }

    /** Reads every tab entry's styled name for the "(Class level)" tag Hypixel writes in dungeons. */
    private static void parseTabList(Map<String, Character> out) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;   // an unstyled entry carries no class tag
            }
            Matcher matcher = TAB_CLASS_LINE.matcher(strip(display.getString()));
            if (matcher.find()) {
                // Class initials are unique across the five names (Archer/Berserk/Tank/Healer/Mage).
                out.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT),
                        Character.toUpperCase(matcher.group(2).charAt(0)));
            }
        }
    }

    /** One-shot log per run confirming the local player's own latched class (drives their tank range). */
    private static void logSelfClassOnce() {
        if (loggedSelf) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Character self = latched.get(player.getGameProfile().name().toLowerCase(Locale.ROOT));
        if (self != null) {
            loggedSelf = true;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Dungeon] Your class this run: {} ({} teammate class(es) known).",
                    self, latched.size());
        }
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "");
    }
}
