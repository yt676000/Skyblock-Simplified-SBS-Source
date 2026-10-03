/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import sbs.modid.client.core.config.ConfigManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The islands and areas a keybind can be limited to.
 *
 * <p>Two sources feed the dropdowns:
 * <ul>
 *   <li>a <b>seeded</b> table of every SkyBlock island and its {@code ⏣} sub-areas, taken from the
 *       Hypixel SkyBlock wiki's Locations page, so the lists are complete on a fresh install without
 *       having to visit every corner of the game first;</li>
 *   <li>a <b>learned</b> set – every {@code ⏣} area the player actually stands in is recorded, along
 *       with the island the tab list said it belonged to, so a place the seed missed (or a new
 *       island) still becomes selectable the moment it is visited.</li>
 * </ul>
 * The keybind stores a single filter string (an area name, or an island name) that
 * {@link sbs.modid.client.core.location.SkyBlockLocation#matches} tests the live location against, so
 * nothing downstream changes.
 *
 * <p>This class is a <b>catalog</b>, not a location source: it knows which names exist and how they
 * relate, never where the player is. That question belongs to
 * {@link sbs.modid.client.core.location.SkyBlockLocation}, which is also what feeds
 * {@link #record} - the dependency only ever points that way.
 */
public final class IslandCatalog {

    private IslandCatalog() {
    }

    /** Matches any area on an island – the first entry of the second dropdown. */
    public static final String ANY_AREA = "(any area)";

    /** No island filter at all – the first entry of the first dropdown. */
    public static final String ANY_ISLAND = "(anywhere)";

    /**
     * Seeded island → its {@code ⏣} sub-areas (Hypixel wiki Locations page). Islands with no
     * scoreboard sub-areas (Private Island, The Garden, …) seed an empty list; the learned set still
     * fills anything the seed does not cover.
     */
    private static final Map<String, List<String>> SEEDED_AREAS = new LinkedHashMap<>();

    private static void seed(String island, String... areas) {
        SEEDED_AREAS.put(island, List.of(areas));
    }

    static {
        // "Your Island" is what the scoreboard calls the private island; the tab list says
        // "Private Island". Seeding it makes the scoreboard name resolve to the island too.
        seed("Private Island", "Your Island");
        // The tab list's Info widget says "Area: Garden" - without the article the scoreboard and
        // every warp use. Unseeded, "Garden" resolved to nothing, so the location service reported an
        // island it could not match to this one and every Garden overlay hid itself while the player
        // was standing on the Garden. Confirmed from a tab-list screenshot, not inferred.
        seed("The Garden", "Garden");
        seed("Hub",
                "Village", "Forest", "Ruins", "Graveyard", "Coal Mine", "Colosseum", "Mountain",
                "Wilderness", "Community Center", "Auction House", "Bank", "Bazaar Alley", "Blacksmith",
                "Builder's House", "Canvas Room", "Election Room", "Farm", "Farmhouse", "Fashion Shop",
                "Fisherman's Hut", "Flower House", "Library", "Museum", "Tavern", "Trade Center",
                "Wizard Tower", "Archery Range", "Crypts", "Dark Auction");
        seed("The Farming Islands",
                "The Barn", "Windmill", "Mushroom Desert", "Desert Settlement", "Oasis", "Mushroom Gorge",
                "Glowing Mushroom Cave", "Overgrown Mushroom Cave", "Jake's House", "Shepherd's Keep",
                "Trapper's Den");
        seed("The Park",
                "Birch Park", "Spruce Woods", "Dark Thicket", "Jungle Island", "Savanna Woodland",
                "Howling Cave", "Trials of Fire", "Spirit Cave", "Soul Cave", "Melody's Plateau");
        seed("Spider's Den",
                "Spider Mound", "Gravel Mines", "Grandma's House", "Archaeologist's Camp",
                "Arachne's Burrow", "Arachne's Sanctuary");
        seed("The End",
                "Dragon's Nest", "Void Sepulture", "Void Slate", "Zealot Bruiser Hideout");
        seed("Crimson Isle",
                "Crimson Fields", "The Wasteland", "Dragontail", "Scarleton", "Burning Desert",
                "Blazing Volcano", "Mystic Marsh", "Barbarian Outpost", "Mage Outpost", "Dojo",
                "The Bastion", "Belly of the Beast", "Ruins of Ashfang", "Stronghold");
        seed("Gold Mine",
                "Gold Mine");
        seed("Deep Caverns",
                "Deep Caverns", "Gunpowder Mines", "Lapis Quarry", "Pigmen's Den", "Slimehill",
                "Diamond Reserve", "Obsidian Sanctuary");
        seed("Dwarven Mines",
                "Dwarven Village", "Dwarven Base Camp", "The Lift", "Gates to the Mines", "Palace Bridge",
                "Royal Palace", "Royal Mines", "The Forge", "Forge Basin", "Cliffside Veins",
                "Rampart's Quarry", "Upper Mines", "Far Reserve", "Lava Springs", "The Mist",
                "Aristocrat Passage", "Goblin Burrows", "Divan's Gateway", "Fossil Research Center",
                "Great Ice Wall", "Glacite Tunnels");
        seed("Crystal Hollows",
                "Crystal Nucleus", "Jungle", "Jungle Temple", "Mithril Deposits", "Mines of Divan",
                "Goblin Holdout", "Goblin Queen's Den", "Precursor Remnants", "Lost Precursor City",
                "Magma Fields", "Khazad-dûm", "Fairy Grotto", "Dragon's Lair");
        // A Mineshaft is a private instance whose area line just reads "Mineshaft" - without it the
        // island resolves to nothing at all, since the two shared areas below are listed under the
        // Dwarven Mines above and that entry wins the lookup.
        seed("Glacite Mineshafts",
                "Mineshaft", "Glacite Tunnels", "Fossil Research Center");
        seed("Dungeon Hub",
                "Dungeon Hub");
        // "Catacombs" (no article) is what the tab list's dungeon line calls it, the scoreboard says
        // "The Catacombs (F6)" - both spellings have to resolve to the island.
        seed("The Catacombs",
                "Entrance", "The Catacombs", "Catacombs");
        seed("The Rift",
                "Wyld Woods", "Village Plaza", "West Village", "Otherside", "Mirrorverse", "Dreadfarm",
                "Living Cave", "Black Lagoon", "Lagoon Cave", "Colosseum", "Stillgore Château",
                "Enigma's Crib", "Great Beanstalk", "The Bastion", "The Mountaintop", "Half-Eaten Cave",
                "Infested House", "Déjà Vu Alley", "Taylor's", "Pumpgrotto", "Wizard Tower");
        seed("Jerry's Workshop",
                "Jerry Pond", "Mount Jerry", "Glacial Cave", "Hot Springs", "Gary's Shack",
                "Terry's Shack", "Reflective Pond", "Sunken Jerry Pond");
        seed("Backwater Bayou");
        // Galatea is the REGION, not an island: the tab list's "Area:" line says "Moonglade Marsh"
        // or "Torrhus Canyon" and never "Galatea", which itself only ever turns up as a scoreboard
        // zone within the marsh. Seeding it as an island is what left the region's Fairy Souls
        // undrawable - nothing ever asked for an island by that name. It stays listed, with no
        // zones of its own, because it is a real place other features name.
        seed("Galatea");
        seed("Moonglade Marsh",
                "Galatea", "Moonglade Marsh", "Moonglade's Edge", "Tangleburg", "Tangleburg's Path",
                "Tangleburg Library", "Tangleburg Bank", "Murkwater Loch", "Murkwater Shallows",
                "Murkwater Depths", "Murkwater Outpost", "Evergreen Plateau", "Verdant Summit",
                "Forest Temple", "Wyrmgrove Tomb", "Tomb Floodway", "Ancient Ruins", "Fusion House",
                "Bubbleboost Column", "Dive-Ember Pass", "Side-Ember Way", "Stride-Ember Fissure",
                "Driptoad Delve", "Driptoad Pass", "Drowned Reliquary", "Kelpwoven Tunnels",
                "North Reaches", "North Wetlands", "South Reaches", "South Wetlands",
                "West Reaches", "Westbound Wetlands", "Red House", "Squid Cave", "Reefguard Pass",
                "SwampCut Inc.", "Tranquil Pass", "Tranquility Sanctum", "Lunarise");
        seed("Torrhus Canyon",
                "Torrhus Canyon", "Torrhus Heights", "Torrhus Springs", "Spring Path",
                "Spring Shallows", "Spring Depths", "Miria's Hut", "Pangolin Hideaway",
                "Ant's Cave", "Hotspot Haven", "Desert Temple", "Critter Safari Entrance");
        // Its own instance around its own origin, entered from the Torrhus Canyon - not a canyon
        // zone, so its coordinates must never be mixed in with that island's.
        seed("Critter Safari",
                "Critter Safari", "Safari Zone");
        seed("Lotus Atoll",
                "Lotus Highlands", "Lotus Eater's Cave", "Tewtil Tunnel");
        seed("Kuudra's Hollow",
                "Kuudra's Hollow");
    }

    /** Every seeded island, in the order above. */
    public static final List<String> ISLANDS = List.copyOf(SEEDED_AREAS.keySet());

    /**
     * Islands that are a separate copy per player or per run: your own (or a visited) Private
     * Island, the Garden, and dungeon / Kuudra instances. Anything placed there belongs to that one
     * copy, so nothing seen on one may be assumed on another. Compared without "The " and case.
     */
    private static final java.util.Set<String> INSTANCED = java.util.Set.of(
            "private island", "your island", "garden", "catacombs", "dungeon", "kuudra's hollow", "kuudra");

    /** Whether {@code island} (or the scoreboard zone) names a per-player / per-run instance. */
    public static boolean isInstanced(String island) {
        if (island == null || island.isBlank()) {
            return false;
        }
        String key = island.trim().toLowerCase(java.util.Locale.ROOT);
        if (key.startsWith("the ")) {
            key = key.substring(4);
        }
        return INSTANCED.contains(key);
    }

    /** The live, persisted set of areas the player has stood in ({@code ⏣} names, verbatim). */
    private static Set<String> seen() {
        var config = ConfigManager.getInstance().get();
        if (config.keybindAreas == null) {
            config.keybindAreas = new LinkedHashSet<>();
        }
        return config.keybindAreas;
    }

    /** The live, persisted zone → island pairs, as the tab list reported them. */
    /**
     * The learned zone-to-island pairs, or an empty map when the config cannot be reached.
     *
     * <p>Reaching the config means reaching the game directory, which does not exist outside a
     * launched client. {@link #islandForArea} is on render paths and is the single source every
     * location gate in the mod consults, so letting this throw would turn "I have not learned that
     * zone yet" into an exception thrown several times a frame. Empty is the honest answer: nothing
     * has been learned, and the seeds above have already had their say.
     */
    private static Map<String, String> seenIslands() {
        try {
            var config = ConfigManager.getInstance().get();
            if (config.keybindAreaIslands == null) {
                config.keybindAreaIslands = new LinkedHashMap<>();
            }
            return config.keybindAreaIslands;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    /**
     * Records a zone and the island it belongs to, as observed live. Called on a timer, not per tick –
     * a location only changes when you travel, and writing the config that often would be absurd.
     *
     * <p>{@code island} may be blank (the tab list has not been served yet, or a dungeon is hiding the
     * {@code Area:} line); the zone is still recorded, just without a pairing to go with it.
     *
     * @param zone   the scoreboard's {@code ⏣} name, verbatim
     * @param island the tab list's {@code Area:} name, or {@code ""} when it could not be read
     */
    public static void record(String zone, String island) {
        if (zone == null || zone.isBlank()) {
            return;
        }
        boolean changed = seen().add(zone.trim());
        if (island != null && !island.isBlank()) {
            String previous = seenIslands().put(zone.trim(), island.trim());
            changed |= !island.trim().equals(previous);
        }
        if (changed) {
            ConfigManager.getInstance().save();   // only on a genuinely new pairing
        }
    }

    /**
     * The first dropdown: every island to offer – all seeded islands, every island the player has
     * actually been told they were on, plus any learned area that maps to no island at all (offered as
     * its own island so it stays selectable).
     */
    public static List<String> islands() {
        Set<String> out = new LinkedHashSet<>();
        out.add(ANY_ISLAND);
        out.addAll(ISLANDS);
        for (String island : seenIslands().values()) {
            if (islandForArea(island) == null) {
                out.add(island);   // an island Hypixel added after this build's seed table
            }
        }
        for (String area : seen()) {
            if (islandForArea(area) == null) {
                out.add(area);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * The second dropdown: {@link #ANY_AREA} first, then the seeded sub-areas of {@code island} plus
     * any learned area on it – de-duplicated case-insensitively so a visited area the seed already
     * lists is not shown twice.
     */
    public static List<String> areasOf(String island) {
        List<String> out = new ArrayList<>();
        out.add(ANY_AREA);
        if (island == null || island.equals(ANY_ISLAND)) {
            return out;
        }
        Set<String> lower = new LinkedHashSet<>();
        for (String area : SEEDED_AREAS.getOrDefault(island, List.of())) {
            if (lower.add(area.toLowerCase(Locale.ROOT))) {
                out.add(area);
            }
        }
        for (String area : seen()) {
            if (!area.equalsIgnoreCase(island) && island.equals(islandForArea(area))
                    && lower.add(area.toLowerCase(Locale.ROOT))) {
                out.add(area);
            }
        }
        return out;
    }

    /**
     * The island a name belongs to: the island itself when the name is one, else the seeded island
     * that lists it as a sub-area, else the island the player was actually told it belonged to, else
     * the seeded island whose name prefixes it ("The Catacombs (F6)"), else {@code null} (a name this
     * build has no island for).
     *
     * <p><b>This is a lookup, not a location.</b> It answers "which island owns a name", and several
     * names are owned by two islands (a Colosseum, a Wizard Tower and a Bastion exist twice over) - it
     * returns the first seeded owner and cannot do better, which is exactly why
     * {@link sbs.modid.client.core.location.SkyBlockLocation} asks the tab list who the island is and
     * only falls back to this.
     *
     * <p>The learned pairs sit <i>after</i> the seed on purpose: they are correct for wherever the
     * player last stood, so letting them win would make a duplicated name flip islands behind the
     * caller's back. Their job is filling the seed's gaps, not overruling it.
     */
    public static String islandForArea(String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) {
            return null;
        }
        // Trimmed before anything is compared. Every comparison below is an exact equalsIgnoreCase
        // against a seed, so one stray space made a known island resolve to nothing - and "resolves
        // to nothing" is what the Garden gate reads as "somewhere else".
        String filter = rawFilter.trim();
        for (String island : ISLANDS) {
            if (island.equalsIgnoreCase(filter)) {
                return island;
            }
        }
        for (Map.Entry<String, List<String>> entry : SEEDED_AREAS.entrySet()) {
            for (String area : entry.getValue()) {
                if (area.equalsIgnoreCase(filter)) {
                    return entry.getKey();
                }
            }
        }
        for (Map.Entry<String, String> pair : seenIslands().entrySet()) {
            if (pair.getKey().equalsIgnoreCase(filter)) {
                return pair.getValue();
            }
        }
        String lower = filter.toLowerCase(Locale.ROOT);
        for (String island : ISLANDS) {
            if (lower.startsWith(island.toLowerCase(Locale.ROOT))) {
                return island;
            }
        }
        if (isGardenPlotZone(filter)) {
            return "The Garden";
        }
        return null;
    }

    /** The decision alone - the part worth testing without a game behind it. */
    static boolean isGardenPlotZone(String area) {
        return area != null && GARDEN_PLOT.matcher(area).find();
    }

    /**
     * A Garden plot's zone, which no seed list can hold: the scoreboard stops naming the island the
     * moment you step onto a plot and names the plot instead ("Plot - 5"), and there are 24 of them.
     * Without this, every island-gated farming feature switched itself off on the plots - which is
     * the whole Garden except the middle - whenever the tab list's {@code Area:} line was not
     * carrying the answer, and the failure looked like "the feature only works on the centre plot".
     *
     * <p>Anchored to the start of the zone on purpose. A {@code contains("plot")} test is how the
     * Pests widget ended up claiming another island entirely; nothing else in the game reports a zone
     * <i>beginning</i> with the word, and this is reached only after every exact, seeded and learned
     * match above has already failed.
     */
    private static final Pattern GARDEN_PLOT = Pattern.compile("(?i)^\\s*plot\\b");

    /**
     * The text stored on the keybind for an island/area choice – i.e. what
     * {@link sbs.modid.client.core.location.SkyBlockLocation#matches} tests the live location against.
     *
     * <p>The area wins when one is picked: it is the more specific of the two.
     */
    public static String toFilter(String island, String area) {
        if (area != null && !area.equals(ANY_AREA) && !area.isBlank()) {
            return area;
        }
        if (island == null || island.equals(ANY_ISLAND)) {
            return "";
        }
        return island;
    }
}
