/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.npc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.PathfindingManager;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.helper.npc.SkyblockNpcs.Npc;
import sbs.modid.client.helper.terrain.FarTerrainManager;

import java.util.List;
import java.util.Locale;

/**
 * Reads the scoreboard's <b>Objective</b> and, when it names an NPC or a catalogued place, marks and
 * routes to it. On by default since 2026-09-25 ({@code npc.objectiveRouting}), so it is quiet by
 * construction: a marker and a route while the objective names a known target, nothing otherwise -
 * no chat, no alerts. Matching lives in {@link ObjectiveTargets}.
 *
 * <p><b>It never travels on its own.</b> A target on another island is only reached by warping, and
 * a warp is a command sent to the server. A map click is a player asking for that; a line on the
 * scoreboard is not. So the cross-island hop runs only on {@code /sbs objective go}, through the same
 * {@link sbs.modid.client.helper.map.logic.MapNavigation} a map click uses.
 *
 * <p><b>Why the scoreboard.</b> Hypixel already tells you what to do next - "Talk to Oringo",
 * "Speak with Jacob" - it just does not tell you where that person stands, which for a new player is
 * the entire difficulty. The objective text is the one place the current task is stated in plain
 * words, so it is what this reads.
 *
 * <p><b>Parsed loosely on purpose.</b> The objective's wording changes per quest and per update
 * ("Talk to", "Speak to", "Visit", "Return to"), so nothing here depends on a verb or a sentence
 * shape: the objective block is located by its header, and its text is then scanned for any name in
 * {@link SkyblockNpcs}. Longest names are tried first so "Rabbit Bro" is not shadowed by a shorter
 * entry. A quest naming nobody catalogued simply produces no target - it never guesses.
 *
 * <p>The raw objective lines are logged once per change under {@code [SBS][Npc]}, because the exact
 * scoreboard layout is the one thing that cannot be verified from outside the game.
 */
public final class ObjectiveNpcTracker {

    private static final ObjectiveNpcTracker INSTANCE = new ObjectiveNpcTracker();

    /** The scoreboard header the objective text follows. */
    private static final String OBJECTIVE_HEADER = "objective";

    /** How many lines after the header may still be part of the objective. */
    private static final int OBJECTIVE_LINES = 3;

    /** Within this many blocks the NPC counts as reached and the route clears itself. */
    private static final double ARRIVE_DIST = 4.0;

    /** The scoreboard is only re-read this often - it changes when a quest step does, not per tick. */
    private static final long SCAN_INTERVAL_MS = 500L;

    private long lastScanAt;

    /** The objective text as last read, for the log and the settings status line. */
    private String objectiveText = "";

    /** What that text names, or {@code null} when it names nothing catalogued. */
    private ObjectiveTargets.Target target;

    /** The objective the player dismissed; its route stays off until the objective changes. */
    private String dismissedText = "";

    /** Every catalogued place, built once from the island maps. */
    private List<ObjectiveTargets.Place> places;
    private List<Npc> npcs;

    /** Whether the published waypoint is currently ours. */
    private boolean published;

    private ObjectiveNpcTracker() {
    }

    public static ObjectiveNpcTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.NpcSettings cfg() {
        return ConfigManager.getInstance().get().npc;
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick from the shared fan-out; throttled internally. */
    public void onClientTick() {
        SBSConfig.NpcSettings cfg = cfg();
        if (!cfg.objectiveRouting) {
            if (published) {
                clearWaypoint();
            }
            target = null;
            objectiveText = "";
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;

        scanObjective();

        if (target == null || !cfg.waypoint || objectiveText.equals(dismissedText)
                || !coordinatesApply(target.island())) {
            if (published) {
                clearWaypoint();
            }
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player != null && player.position().distanceTo(
                new Vec3(target.x(), target.y(), target.z())) <= ARRIVE_DIST) {
            clearWaypoint();
            return;   // arrived; the waypoint returns if the objective still names them next scan
        }
        ensureWaypoint(target);
    }

    /** Turns the route off for the objective showing now; a new objective brings it back. */
    public void dismiss() {
        dismissedText = objectiveText;
        clearWaypoint();
    }

    /**
     * The explicit cross-island hop: hand the target to the map's navigator, which warps as a map
     * click would. Returns what happened, for the command's reply.
     */
    public String travel() {
        if (target == null) {
            return "The objective names nothing SBS knows the way to.";
        }
        var map = mapFor(target.island());
        if (map == null) {
            return "No map for " + target.island() + " - can't travel there.";
        }
        var location = new sbs.modid.client.helper.map.model.MapLocation();
        location.name = target.name();
        location.x = (int) Math.floor(target.x());
        location.y = (int) Math.floor(target.y());
        location.z = (int) Math.floor(target.z());
        location.map = map;
        dismissedText = "";
        sbs.modid.client.helper.map.logic.MapNavigation.getInstance().travelTo(map, location);
        return "Travelling to " + target.name() + ".";
    }

    private static sbs.modid.client.helper.map.model.IslandMap mapFor(String island) {
        for (var map : sbs.modid.client.helper.map.logic.MapDatabase.maps()) {
            if (map.island != null && map.island.equalsIgnoreCase(island)) {
                return map;
            }
        }
        return null;
    }

    /**
     * Whether the NPC's coordinates describe a real place from where the player is standing - the
     * test for showing the <b>marker</b>.
     *
     * <p>Being on the NPC's island is the obvious case. The wider case is the shared map: several
     * islands occupy one coordinate space, so a Park NPC's position is genuinely correct while you
     * stand in the Hub, and with Far Terrain serving that terrain you can actually see the place the
     * marker is pointing at. Requiring the same island would have hidden exactly the markers the far
     * terrain made worth showing.
     *
     * <p>Gated on Far Terrain being active for the cross-island case: without it there is nothing
     * out there to see, and a marker floating in empty fog is noise rather than information.
     */
    private static boolean coordinatesApply(String island) {
        if (SkyBlockLocation.onIsland(island)) {
            return true;
        }
        return FarTerrainManager.active()
                && FarTerrainManager.sameMap(island, SkyBlockLocation.island());
    }

    /**
     * Whether a walking route to the NPC is possible - the test for <b>pathfinding</b>, deliberately
     * stricter than {@link #coordinatesApply}.
     *
     * <p>Islands sharing a coordinate space are still separate Hypixel servers reached by warping,
     * so there is no walkable route from one to the next however clearly you can see it. Routing
     * across that gap would send the pathfinder off on a long search for a path that cannot exist -
     * the marker shows you where to go, the warp gets you there, and the route resumes on arrival.
     */
    private static boolean routable(String island) {
        return SkyBlockLocation.onIsland(island);
    }

    // ------------------------------------------------------------------ parsing

    /** Re-reads the objective block and resolves the NPC it names. */
    private void scanObjective() {
        String text = readObjectiveText();
        if (text.equals(objectiveText)) {
            return;   // unchanged - keep the resolved target as-is
        }
        objectiveText = text;
        if (!text.equals(dismissedText)) {
            dismissedText = "";
        }
        target = text.isEmpty() ? null : ObjectiveTargets.match(text, npcs(), places(),
                cfg().includePlaces);
        // Every change, matched or not, with the full text: this is the only source of real
        // objective wording, and an unmatched line is what the catalogue is extended from.
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Npc] objective '{}' -> {}", text, target == null
                ? "no catalogued NPC or place" : target.kind() + " " + target.name() + " on " + target.island());
    }

    private List<Npc> npcs() {
        if (npcs == null) {
            List<Npc> list = new java.util.ArrayList<>();
            for (String name : SkyblockNpcs.namesLongestFirst()) {
                Npc npc = SkyblockNpcs.find(name);
                if (npc != null) {
                    list.add(npc);
                }
            }
            npcs = list;
        }
        return npcs;
    }

    /** Map locations by name, plus each zone (a location's area) at the first location in it. */
    private List<ObjectiveTargets.Place> places() {
        if (places == null) {
            List<ObjectiveTargets.Place> list = new java.util.ArrayList<>();
            java.util.Set<String> zones = new java.util.HashSet<>();
            for (var map : sbs.modid.client.helper.map.logic.MapDatabase.maps()) {
                for (var location : map.locations) {
                    list.add(new ObjectiveTargets.Place(location.name, map.island, location.x,
                            location.y, location.z));
                    if (location.area != null && !location.area.isBlank()
                            && zones.add(map.island + "/" + location.area)) {
                        list.add(new ObjectiveTargets.Place(location.area, map.island, location.x,
                                location.y, location.z));
                    }
                }
            }
            places = list;
        }
        return places;
    }

    /**
     * The objective block's text, joined into one line. Hypixel puts the wording on the line(s)
     * following an "Objective" header, so the header is found and the next few non-empty lines are
     * taken - stopping at a blank line or at anything that looks like another header.
     */
    private static String readObjectiveText() {
        List<String> lines = SkyBlockLocation.sidebarLines();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String header = clean(lines.get(i));
            if (!header.toLowerCase(Locale.ROOT).startsWith(OBJECTIVE_HEADER)) {
                continue;
            }
            // Some layouts put the text on the header line itself ("Objective: Talk to X").
            int colon = header.indexOf(':');
            if (colon >= 0 && colon + 1 < header.length()) {
                out.append(header.substring(colon + 1).trim());
            }
            for (int n = 1; n <= OBJECTIVE_LINES && i + n < lines.size(); n++) {
                String line = clean(lines.get(i + n));
                if (line.isEmpty() || line.endsWith(":")) {
                    break;   // blank separator, or the next section's header
                }
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(line);
            }
            break;
        }
        return out.toString().trim();
    }

    /** Scoreboard lines carry stray formatting characters; keep letters, digits and basic punctuation. */
    private static String clean(String line) {
        return line == null ? "" : line.replace('⏣', ' ').trim();
    }

    // ------------------------------------------------------------------ waypoint

    private void ensureWaypoint(ObjectiveTargets.Target npc) {
        BlockPos pos = new BlockPos((int) Math.floor(npc.x()), (int) Math.round(npc.y()),
                (int) Math.floor(npc.z()));
        for (Waypoint waypoint : WaypointStore.all()) {
            if (Waypoint.SOURCE_OBJECTIVE.equals(waypoint.source)
                    && waypoint.x == pos.getX() && waypoint.z == pos.getZ()) {
                return;   // already published
            }
        }
        removeOurs();
        Waypoint waypoint = new Waypoint(npc.name(), pos,
                WaypointStore.currentDimension(), Waypoint.SOURCE_OBJECTIVE);
        // Only a routable target is offered to the pathfinder. An off-island marker is still added
        // so it is visible across the shared map, it just carries no route.
        waypoint.routable = routable(npc.island());
        WaypointStore.all().add(waypoint);
        published = true;
        ConfigManager.getInstance().save();
        if (cfg().pathfinding) {
            PathfindingManager.getInstance().invalidate();
        }
    }

    private void clearWaypoint() {
        if (removeOurs()) {
            ConfigManager.getInstance().save();
            PathfindingManager.getInstance().invalidate();
        }
        published = false;
    }

    /** Removes only this module's waypoints, never the Recipe Viewer's or a quest's. */
    private boolean removeOurs() {
        return WaypointStore.all().removeIf(w -> Waypoint.SOURCE_OBJECTIVE.equals(w.source));
    }

    // ------------------------------------------------------------------ settings

    /** The settings page's status line. */
    public String statusLine() {
        if (!cfg().objectiveRouting) {
            return "Off";
        }
        if (objectiveText.isEmpty()) {
            return "No objective on the scoreboard";
        }
        if (target == null) {
            return "\"" + objectiveText + "\" - nothing catalogued named";
        }
        if (objectiveText.equals(dismissedText)) {
            return target.name() + " (dismissed for this objective)";
        }
        String where = SkyBlockLocation.onIsland(target.island())
                ? "routing there" : "on " + target.island() + " - /sbs objective go";
        return target.name() + " (" + where + ")";
    }
}
