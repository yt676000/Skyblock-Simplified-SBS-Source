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
import net.minecraft.world.entity.player.PlayerSkin;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.run.logic.DungeonScoreboard;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import sbs.modid.client.core.scoreboard.ScoreboardSnapshots;
import sbs.modid.client.core.tab.TabWidgets;
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
    private static final Pattern FORMATTING = Pattern.compile(SECTION_SIGN + ".");


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
    /**
     * Class initial per name exactly as callers pass it ({@code 0} = none), filled on first lookup and
     * replaced on every refresh. Callers ask with the same few names every frame, so the lower-casing
     * and prefix scan run once per name per refresh instead of once per call.
     */
    private static volatile Map<String, Character> resolved = new ConcurrentHashMap<>();
    /**
     * Tab entry per IGN, under both the profile's own spelling and its lower-cased form (the run
     * roster is lower-cased). Replaced wholesale on refresh. Holds the {@link PlayerInfo}, not its
     * skin, so a skin that finishes downloading shows on the next frame.
     */
    private static volatile Map<String, PlayerInfo> infoByName = Map.of();
    private static volatile boolean loggedSelf;

    private DungeonTeamClasses() {
    }

    /** Whether any dungeon classes are known right now (live parse OR the per-run latch). */
    public static boolean hasClasses() {
        return !byName.isEmpty() || !latched.isEmpty();
    }

    /**
     * The lower-cased IGNs of everyone latched into this run (parsed from the tab list's
     * "(Class level)" tags, local player included), or an empty set while none are known yet.
     * This is the run's authoritative player roster – the map uses it to drop ghost markers and to
     * name unnamed ones.
     */
    public static java.util.Set<String> runRoster() {
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
        Map<String, Character> cache = resolved;
        Character known = cache.get(playerName);
        if (known == null) {
            String lower = playerName.toLowerCase(Locale.ROOT);
            Character live = lookup(byName, lower);
            Character held = live != null ? live : lookup(latched, lower);
            known = held != null ? held : Character.valueOf((char) 0);
            cache.put(playerName, known);
        }
        return known;
    }

    /**
     * A player's skin from the tab list by IGN, in any letter case (marker names can be the
     * lower-cased roster names), or {@code null} when no tab entry matches. Read from the table
     * {@link #refresh()} builds, so no per-call scan of the tab list.
     */
    public static PlayerSkin skinOf(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        Map<String, PlayerInfo> table = infoByName;
        PlayerInfo info = table.get(playerName);
        if (info == null) {
            info = table.get(playerName.toLowerCase(Locale.ROOT));   // mixed-case query only
        }
        return info != null ? info.getSkin() : null;
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

    /**
     * Re-parses on the client tick when the tab list, the tab's player entries, the sidebar, the
     * connection or the in-dungeon state changed, or a caller asked for it - never from the map
     * renderer, which only reads the published tables.
     *
     * <p>The player-entry revision is its own key because {@link TabWidgets#revision()} deliberately
     * ignores entries without a display name; a class tag landing on such an entry would otherwise
     * never trigger a re-parse.
     */
    public static void onClientTick() {
        RefreshKey key = new RefreshKey(TabWidgets.revision(), playerListRevision,
                ScoreboardSnapshots.current().revision(), Minecraft.getInstance().getConnection(),
                DungeonStateManager.getInstance().inDungeon());
        String requested = refreshRequest;
        String reason = key.changedFrom(seen);
        if (reason == null && requested == null) {
            return;
        }
        refreshRequest = null;
        seen = key;
        lastRefreshReason = reason != null ? reason : requested;
        refresh();
        refreshCount++;
    }

    /** Called (client thread) after a player-info add/update/remove packet. */
    public static void markPlayersChanged() {
        playerListRevision++;
    }

    /** Asks for one re-parse on the next client tick (e.g. a Spirit Leap menu just opened). */
    public static void requestRefresh(String reason) {
        refreshRequest = reason;
    }

    /** Why the last re-parse ran, for the leap-menu probe log. */
    public static String lastRefreshReason() {
        return lastRefreshReason;
    }

    /** Goes up by one after every re-parse (lets the leap-menu probe re-log once classes land). */
    public static int refreshCount() {
        return refreshCount;
    }

    /** Size of the live parse table (probe log). */
    public static int liveCount() {
        return byName.size();
    }

    /** Size of the per-run latch (probe log). */
    public static int latchedCount() {
        return latched.size();
    }

    /** Everything a re-parse depends on. Pure, so the change test is unit-testable. */
    public record RefreshKey(int tab, int players, int sidebar, Object connection, boolean inDungeon) {
        /** The first input that differs from {@code before} (null = first run), or null if none. */
        public String changedFrom(RefreshKey before) {
            if (before == null) {
                return "first";
            }
            if (tab != before.tab) {
                return "tab";
            }
            if (players != before.players) {
                return "players";
            }
            if (sidebar != before.sidebar) {
                return "sidebar";
            }
            if (connection != before.connection) {
                return "connection";
            }
            if (inDungeon != before.inDungeon) {
                return "inDungeon";
            }
            return null;
        }
    }

    private static RefreshKey seen;
    private static volatile int playerListRevision;
    private static volatile String refreshRequest;
    private static volatile String lastRefreshReason = "none";
    private static volatile int refreshCount;

    private static void refresh() {

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
        if (DungeonStateManager.getInstance().inDungeon()) {
            parsed.forEach(latched::putIfAbsent);   // first class seen per player wins (can't change)
            logSelfClassOnce();
        } else if (!latched.isEmpty()) {
            latched.clear();
            loggedSelf = false;
        }
        resolved = new ConcurrentHashMap<>();   // after the latch update - lookups read both tables
    }

    /** Reads every tab entry's styled name for the "(Class level)" tag Hypixel writes in dungeons. */
    private static void parseTabList(Map<String, Character> out) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            infoByName = Map.of();
            return;
        }
        Map<String, PlayerInfo> infos = new HashMap<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            String profileName = info.getProfile() != null ? info.getProfile().name() : null;
            if (profileName != null && !profileName.isEmpty()) {
                infos.putIfAbsent(profileName, info);
                infos.putIfAbsent(profileName.toLowerCase(Locale.ROOT), info);
            }
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
        infoByName = infos;
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
        return text == null ? "" : FORMATTING.matcher(text).replaceAll("");
    }
}
