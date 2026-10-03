/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.location;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import sbs.modid.client.core.keybind.IslandCatalog;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.skills.farming.model.FarmingText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place in the mod that answers "where is the player right now".
 *
 * <p><b>Hypixel publishes the answer twice, and the two halves say different things.</b>
 * <ul>
 *   <li>The <b>sidebar scoreboard</b> line marked {@code ⏣} is the <i>exact</i> spot - the zone:
 *       "{@code ⏣ Stronghold}", "{@code ⏣ Coal Mine}", "{@code ⏣ Plot - 4}". It never names the
 *       island it belongs to, and several islands own a zone of the same name (a Colosseum, a Wizard
 *       Tower and a Bastion exist both in the Hub/Crimson Isle and in the Rift), so a zone alone
 *       cannot tell you which island you are on.</li>
 *   <li>The <b>tab list</b>'s "{@code Area: Crimson Isle}" line is the <i>island</i>, and it keeps
 *       saying the island everywhere on it - including on Garden plots, where the scoreboard has
 *       switched to the plot name.</li>
 * </ul>
 * Features kept getting this wrong because each one picked whichever half it happened to know about:
 * an island-wide feature reading the scoreboard died the moment you walked into a named zone, and a
 * zone-specific feature reading the tab list fired across the whole island.
 *
 * <p><b>The Catacombs are the exception.</b> Inside a dungeon the tab list serves the Dungeon Stats
 * widget instead of the Info widget, so there is no "{@code Area:}" line at all - the island is named
 * by a "{@code Dungeon: Catacombs}" line instead. {@link #island()} reads that, and falls back to
 * resolving the zone through {@link IslandCatalog} for the frames where the tab list is not populated
 * yet, so no caller has to know about any of this.
 *
 * <p><b>The Critter Safari is the other exception, and the opposite one.</b> It is an instance with
 * its own island name and its own origin, entered from a Torrhus Canyon zone called "Critter Safari
 * Entrance" - a name that contains the island's in full. Every loose test written for the Safari
 * therefore fired on the canyon and drew the instance's coordinates onto it. {@link #inCritterSafari()}
 * is the exact answer, and features whose coordinates belong to that instance ask it rather than
 * {@link #onIsland(String)}.
 *
 * <p>Everything is re-read on a {@value #REFRESH_MS} ms timer rather than per call: the callers sit in
 * render paths that run several times a frame, while the answer only changes when you travel.
 */
public final class SkyBlockLocation {

    /** The marker Hypixel prefixes the sidebar's location line with. */
    private static final String ZONE_MARKER = "⏣";

    /**
     * The location line's shape, for when {@link #ZONE_MARKER} is not the glyph any more: a leading
     * symbol that is no letter, digit or bracket, a space, then the place name in group 1.
     */
    private static final Pattern ZONE_SHAPE =
            Pattern.compile("^\\s*[^\\p{L}\\p{N}\\s\\[(]\\s+(\\p{L}.*)$");

    /** The tab-list key naming the island ("Area: Crimson Isle"). */
    private static final String TAB_ISLAND_KEY = "area";

    /** The tab-list key that replaces it inside a dungeon ("Dungeon: Catacombs"). */
    private static final String TAB_DUNGEON_KEY = "dungeon";

    // ------------------------------------------------------------------ the Critter Safari

    /**
     * The Critter Safari, as the tab list's {@code Area:} row spells it.
     *
     * <p><b>Its own island, and the only one reached from another island's zone.</b> It is entered
     * from {@link #CRITTER_SAFARI_ENTRANCE}, a zone of Torrhus Canyon, and it is an instance around
     * its own origin - the Safari's {@code x=109} is nowhere near the canyon's {@code x=109}. The
     * distinction is therefore not cosmetic: publishing a Safari coordinate while the player stands
     * on the canyon puts a marker hundreds of blocks into unrelated terrain, which is what "the
     * waypoints render in a completely different area" turns out to be.
     *
     * <p>A named constant rather than a literal per feature because it is asked in three places - the
     * preset island scope, the hunting Safari gate and the location debug command - and three
     * spellings of one place is how one of them ends up matching something else.
     */
    public static final String CRITTER_SAFARI = "Critter Safari";

    /**
     * The Torrhus Canyon zone the Safari is entered from. <b>On the canyon, never inside the
     * Safari</b> - and its name contains the Safari's in full, so it matches every loose test written
     * for the Safari. Naming that trap is what this constant is for.
     */
    public static final String CRITTER_SAFARI_ENTRANCE = "Critter Safari Entrance";

    /**
     * What the alpha's sidebar called the Safari's own zone line, before it became "Critter Safari".
     * Still answered - a client standing in an older instance is not one to draw nothing for - but
     * never assumed, because it is not what the live game says.
     */
    public static final String CRITTER_SAFARI_LEGACY_ZONE = "Safari Zone";

    /** The location is only re-read this often; warping is not a per-frame event. */
    private static final long REFRESH_MS = 250L;

    private static long readAt;
    private static String zone = "";
    private static String tabIsland = "";
    private static String dungeon = "";

    private SkyBlockLocation() {
    }

    // ------------------------------------------------------------------ the two halves

    /**
     * The exact spot the scoreboard names, without the {@code ⏣} marker ("Stronghold", "Coal Mine",
     * "The Catacombs (F6)"), or {@code ""} when there is no sidebar - i.e. not on SkyBlock.
     */
    public static String zone() {
        refresh();
        return zone;
    }

    /**
     * The island the player is on ("Crimson Isle", "The Garden", "The Catacombs"), or {@code ""} when
     * nothing can be read.
     *
     * <p>Preference order: the tab list's {@code Area:} line (authoritative and island-level), then the
     * dungeon line that replaces it in the Catacombs, then the zone resolved up to its island through
     * {@link IslandCatalog} - the last of which is a guess from a seed table and only used while the
     * tab list has not been served yet.
     */
    public static String island() {
        refresh();
        if (!tabIsland.isEmpty()) {
            return tabIsland;
        }
        if (!dungeon.isEmpty()) {
            String named = IslandCatalog.islandForArea(dungeon);
            return named == null ? dungeon : named;
        }
        String resolved = IslandCatalog.islandForArea(zone);
        return resolved == null ? "" : resolved;
    }

    /**
     * The kind of dungeon the tab list reports ("Catacombs"), or {@code ""} outside one. This is the
     * <i>tab list's</i> view; dungeon features gate on
     * {@link sbs.modid.client.dungeons.run.logic.DungeonScoreboard} instead, which reads the floor off
     * the scoreboard and is what tells an actual run apart from the entrance.
     */
    public static String dungeonType() {
        refresh();
        return dungeon;
    }

    /** Whether the tab list is serving the dungeon widget in place of the {@code Area:} line. */
    public static boolean inDungeon() {
        return !dungeonType().isEmpty();
    }

    /**
     * Whether the player is inside the Critter Safari instance <b>right now</b> - the island-level
     * question for every feature whose coordinates belong to that instance and to nothing else.
     *
     * <p>Deliberately not {@link #onIsland(String)} with the Safari's name. That method resolves the
     * live zone up through {@code IslandCatalog} as a <i>fallback</i>, which is right for an ordinary
     * island and wrong for an instance: the fallback is a guess from a seed table, and a guess that
     * puts you in the Safari when you are on the canyon draws the whole marker set into another
     * island's coordinate space. Here every comparison is exact and there is no fallback at all.
     *
     * @see #isCritterSafari(String, String)
     */
    public static boolean inCritterSafari() {
        refresh();
        return isCritterSafari(zone, tabIsland);
    }

    /**
     * The decision alone - the part worth testing without a game behind it, and the form a caller uses
     * when it already holds both halves ({ sbs.modid.client.skills.hunting.logic.SafariTracker}
     * reads them once per poll and passes them on rather than re-reading them per question).
     *
     * <p>Three rules, in this order, and the order is the fix:
     * <ol>
     *   <li><b>The entrance is not the Safari.</b> {@link #CRITTER_SAFARI_ENTRANCE} is a Torrhus
     *       Canyon zone carrying the Safari's whole name, so it answered yes to every loose test and
     *       is refused first, before anything else gets a chance to say otherwise.</li>
     *   <li><b>The scoreboard naming the Safari settles it.</b> The {@code ⏣} line is the exact spot;
     *       when it says the Safari, the player is in it even while the tab list's {@code Area:} row
     *       is still catching up on the island it was entered from.</li>
     *   <li><b>Otherwise the tab list decides, and silence means no.</b> Inside the Safari's inner
     *       zones the sidebar names the biome ("Cavern") and says nothing about the island, so the
     *       {@code Area:} row is the only evidence there is. When it has not been served yet, the
     *       answer is "not confirmed" - which draws nothing, rather than drawing the last island's
     *       worth of markers into whatever this one is.</li>
     * </ol>
     *
     * @param zone      the sidebar's {@code ⏣} name, as {@link #zone()} reports it
     * @param tabIsland the tab list's {@code Area:} row, or {@code ""} when it has not been served.
     *                  {@link #island()} may be passed instead: the two differ only when the island
     *                  was resolved up from the zone, and every zone that resolves to the Safari is
     *                  already decided by the two rules above it.
     */
    public static boolean isCritterSafari(String zone, String tabIsland) {
        String spot = zone == null ? "" : zone.trim();
        String area = tabIsland == null ? "" : tabIsland.trim();
        if (spot.equalsIgnoreCase(CRITTER_SAFARI_ENTRANCE)) {
            return false;
        }
        if (spot.equalsIgnoreCase(CRITTER_SAFARI) || spot.equalsIgnoreCase(CRITTER_SAFARI_LEGACY_ZONE)) {
            return true;
        }
        return area.equalsIgnoreCase(CRITTER_SAFARI);
    }

    /**
     * The tab list's {@code Area:} row exactly as it was read, or {@code ""} when it has not been
     * served. Raw on purpose and only for the location debug command: it is the input every gate in
     * the mod is derived from, and "what did the row actually say" is the first question when a gate
     * answers something nobody expected. Features ask {@link #island()}, never this.
     */
    public static String tabArea() {
        refresh();
        return tabIsland;
    }

    // ------------------------------------------------------------------ asking questions

    /**
     * Whether the player is on {@code island} - the island-level question, true anywhere on it no
     * matter which zone they are standing in. A blank name matches everywhere.
     *
     * <p>Both spellings of an island answer yes: the tab list says "Private Island" where the
     * scoreboard says "Your Island", and either resolves through {@link IslandCatalog}.
     */
    public static boolean onIsland(String island) {
        if (island == null || island.isBlank()) {
            return true;
        }
        refresh();
        String want = island.trim();
        String live = island();
        if (live.equalsIgnoreCase(want)) {
            return true;
        }
        String resolvedLive = IslandCatalog.islandForArea(live);
        if (resolvedLive != null && resolvedLive.equalsIgnoreCase(want)) {
            return true;
        }
        String resolvedZone = IslandCatalog.islandForArea(zone);
        return resolvedZone != null && resolvedZone.equalsIgnoreCase(want);
    }

    /**
     * Whether {@code filter} - an island name <i>or</i> a zone name - describes where the player is.
     * This is the general "does my location entry apply here" test, and the one a stored filter (a
     * keybind's island condition, a module's island whitelist) is matched with.
     *
     * <p>A zone filter contains-matches the live zone, so "Farm" still covers "Farmhouse" and the
     * Rift's "Dreadfarm" the way hand-typed filters always did. An island filter has to resolve
     * exactly, so "Coal Mine" does not quietly light up the rest of the Hub. A blank filter passes.
     */
    public static boolean matches(String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        refresh();
        if (zone.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return onIsland(filter);
    }

    /**
     * Both halves in one line for chat and debug output - "Crimson Isle - Stronghold", or whichever
     * half is readable, or "unknown". Never returns {@code ""}: its whole job is explaining to the
     * player why a location-gated feature is not running.
     */
    public static String describe() {
        refresh();
        String live = island();
        if (live.isEmpty() && zone.isEmpty()) {
            return "unknown";
        }
        if (live.isEmpty()) {
            return zone;
        }
        if (zone.isEmpty() || zone.equalsIgnoreCase(live)) {
            return live;
        }
        return live + " - " + zone;
    }

    // ------------------------------------------------------------------ learning

    /**
     * Records the current zone and the island it actually belongs to. Called on a timer from the
     * client tick - this is the only source of a <i>correct</i> zone-to-island mapping, because the
     * pairing is exactly what neither half publishes on its own.
     */
    public static void learn() {
        refresh();
        IslandCatalog.record(zone, tabIsland.isEmpty() ? "" : tabIsland);
    }

    // ------------------------------------------------------------------ reading

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - readAt < REFRESH_MS) {
            return;
        }
        readAt = now;
        zone = readZone();
        tabIsland = "";
        dungeon = "";
        for (String line : TabWidgets.lines()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            if (tabIsland.isEmpty() && key.equals(TAB_ISLAND_KEY)) {
                tabIsland = value;
            } else if (dungeon.isEmpty() && key.equals(TAB_DUNGEON_KEY)) {
                dungeon = value;
            }
        }
    }

    private static String readZone() {
        List<String> lines = sidebarLines();
        for (String line : lines) {
            String zone = zoneByMarker(line);
            if (zone != null) {
                return zone;
            }
        }
        for (String line : lines) {
            String zone = zoneByShape(line);
            if (zone != null) {
                return zone;
            }
        }
        return "";
    }

    /**
     * The zone a colour-stripped sidebar line names by the {@link #ZONE_MARKER}, or {@code null}.
     * Package-visible and pure so the line formats can be tested without a scoreboard.
     */
    static String zoneByMarker(String line) {
        if (line == null) {
            return null;
        }
        int marker = line.indexOf(ZONE_MARKER);
        return marker < 0 ? null : line.substring(marker + ZONE_MARKER.length()).trim();
    }

    /**
     * The zone a colour-stripped sidebar line names by its {@link #ZONE_SHAPE}, or {@code null}.
     *
     * <p>Written as the fallback for the day Hypixel changed the glyph, and that day has come: the
     * live sidebar's glyph is U+E067, not the marker, so this is the path every zone read takes
     * today. {@link #readZone} only tries it once no line carries the marker, so it can never
     * override an answer the marker gave. The shape is "a symbol that is not a letter, digit or
     * bracket, a space, then a name" - which is the location row and, on a SkyBlock sidebar,
     * nothing else.
     */
    static String zoneByShape(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = ZONE_SHAPE.matcher(line);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * The zone one sidebar line names, marker first and shape second, or {@code null}. For a
     * feature that logs or tests a single captured line; live callers ask {@link #zone()}.
     */
    public static String zoneOfLine(String line) {
        String zone = zoneByMarker(line);
        return zone != null ? zone : zoneByShape(line);
    }

    /**
     * The sidebar line the current zone was read from, colour-stripped, or {@code ""}. For the
     * capture log only: it is what lets a test fixture be the exact text the parser saw.
     */
    public static String zoneLine() {
        for (String line : sidebarLines()) {
            if (zoneByMarker(line) != null) {
                return line;
            }
        }
        for (String line : sidebarLines()) {
            if (zoneByShape(line) != null) {
                return line;
            }
        }
        return "";
    }

    /**
     * Whether the tab list's {@code Area:} row says {@code Crystal Hollows}, exactly - with no
     * fallback to the zone.
     *
     * <p>For Crystal Hollows Structure Sharing, whose "yes" sends a position to other players. A
     * gate that publishes coordinates must be exact (see {@code docs/issues/core.md}), so this
     * answers {@code false} until the tab list has been served, rather than guessing from a zone
     * name the way {@link #onIsland(String)} does.
     */
    public static boolean inCrystalHollows() {
        refresh();
        return "Crystal Hollows".equalsIgnoreCase(tabIsland.trim());
    }

    /**
     * The current sidebar's lines, colour-stripped (empty when there is no sidebar / no world).
     *
     * <p>Hypixel renders each sidebar line as a fake scoreboard entry whose visible text lives in the
     * entry's team prefix/suffix, so a line is rebuilt as {@code prefix + owner + suffix}. Uncached on
     * purpose - dungeon features read the floor and the run stats off these lines every tick and want
     * the live text, while the location fields above are what the timer covers.
     */
    public static List<String> sidebarLines() {
        List<String> lines = new ArrayList<>();
        // No client at all is the offline-tooling case; no level is the main-menu case. Both mean
        // "nowhere", which every caller already handles - they ask this to find out where they are.
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null) {
            return lines;
        }
        Scoreboard scoreboard = level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return lines;
        }
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            lines.add(FarmingText.strip(lineText(scoreboard, entry)));
        }
        return lines;
    }

    private static String lineText(Scoreboard scoreboard, PlayerScoreEntry entry) {
        String owner = entry.owner();
        PlayerTeam team = scoreboard.getPlayersTeam(owner);
        if (team == null) {
            return owner;
        }
        return team.getPlayerPrefix().getString() + owner + team.getPlayerSuffix().getString();
    }
}
