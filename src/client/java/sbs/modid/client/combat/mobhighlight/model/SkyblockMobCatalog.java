/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.mobhighlight.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalogue of SkyBlock mobs the highlighter can pick from – the mob list of the Hypixel
 * SkyBlock wiki's <a href="https://hypixelskyblock.minecraft.wiki/w/Mob">Mob page</a>, grouped by
 * the area each mob spawns in.
 *
 * <p><b>Why a name table and not the entity registry.</b> SkyBlock mobs are not their own
 * {@code EntityType}s – "Lapis Zombie", "Crypt Ghoul" and "Zealot" are all ordinary vanilla entities
 * that Hypixel dresses up with a custom floating nametag ("§8[§7Lv5§8] §cLapis Zombie §a30/30§c❤").
 * The nametag <b>is</b> the mob's identity, exactly as the sea-creature and starred-mob detectors
 * already rely on, so the catalogue is a table of names rather than a registry query.
 *
 * <p>Extending it is a one-liner: add a name to the right area in {@link #register()}. A mob that
 * appears in several areas is listed once (first area wins) – the area is only a hint for the picker.
 */
public final class SkyblockMobCatalog {

    /** One selectable SkyBlock mob: the name shown/matched, and the area it belongs to. */
    public record SkyblockMob(String name, String area) {

        /** Case-insensitive match on the mob name or its area, for the picker's search box. */
        public boolean matches(String needle) {
            return needle.isEmpty()
                    || name.toLowerCase(Locale.ROOT).contains(needle)
                    || area.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    /** Name (lower-cased) -> entry, deduped across areas, in registration order. */
    private static final Map<String, SkyblockMob> BY_NAME = new LinkedHashMap<>();
    /** The same entries as an immutable list sorted by name, for the picker. */
    private static final List<SkyblockMob> SORTED;

    static {
        register();
        List<SkyblockMob> sorted = new ArrayList<>(BY_NAME.values());
        sorted.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        SORTED = List.copyOf(sorted);
    }

    private SkyblockMobCatalog() {
    }

    /** Every catalogued mob, sorted by name (deduped across areas). */
    public static List<SkyblockMob> all() {
        return SORTED;
    }

    /** The cached list filtered to those matching {@code query} (name or area, case-insensitive). */
    public static List<SkyblockMob> search(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return SORTED;
        }
        List<SkyblockMob> out = new ArrayList<>();
        for (SkyblockMob mob : SORTED) {
            if (mob.matches(needle)) {
                out.add(mob);
            }
        }
        return out;
    }

    /** Whether {@code name} is a known SkyBlock mob (case-insensitive). */
    public static boolean isKnown(String name) {
        return name != null && BY_NAME.containsKey(name.toLowerCase(Locale.ROOT));
    }

    private static void area(String area, String... names) {
        for (String name : names) {
            BY_NAME.putIfAbsent(name.toLowerCase(Locale.ROOT), new SkyblockMob(name, area));
        }
    }

    /** The wiki's per-area mob lists. Add a mob by adding its name to the matching area. */
    private static void register() {
        // Hub
        area("Graveyard", "Zombie", "Zombie Villager");
        area("Crypts", "Crypt Ghoul", "Golden Ghoul");
        area("Ruins", "Wolf", "Old Wolf");
        area("Howling Cave", "Pack Spirit", "Howling Spirit", "Soul of the Alpha");
        // Mythological Ritual (Diana). The four rare ones are UNVERIFIED - they appear in no other
        // data in this tree - so they are listed here as pickable names and nothing keys detection
        // to them; the Diana module carries its own tolerant, overridable match. A wrong name here
        // costs one entry in a picker, which is the mild version of getting it wrong.
        area("Mythological Ritual", "Minos Inquisitor", "King Minos", "Sphinx", "Manticore",
                "Minos Hunter", "Minos Champion", "Minotaur", "Siamese Lynx", "Gaia Construct");
        // Spider's Den
        area("Spider's Den", "Splitter Spider", "Dasher Spider", "Weaver Spider", "Spider Jockey",
                "Jockey Skeleton", "Skeleton", "Voracious Spider", "Silverfish", "Rain Slime");
        area("Arachne's Burrow", "Arachne's Keeper", "Arachne's Brood", "Arachne");
        // The End
        area("The End", "Enderman", "Endermite", "Nest Endermite");
        area("Void Sepulture", "Voidling Fanatic", "Voidling Extremist");
        area("Zealot Bruiser Hideout", "Zealot Bruiser", "Zealot");
        area("Dragon's Nest", "Seer");
        // Crimson Isle
        area("Mystic Marsh", "Exe", "Wai", "Zee", "Mushroom Bull");
        area("Magma Chamber", "Flare", "Magma Boss");
        area("The Wasteland", "Dive Ghast", "Ghast");
        area("Crimson Isle", "Hellwisp", "Vanquisher", "Magma Cube Rider", "Magma Cube",
                "Pack Magma Cube", "The Matriarch", "Blaze", "Kada Knight", "Matcho",
                "Millennia-Aged Blaze", "Tentacle", "Wither Spectre");
        area("Burning Desert", "Flaming Spider");
        area("Barbarian Outpost", "Barbarian", "Goliath Barbarian");
        area("Mage Outpost", "Fire Mage", "Krondor Necromancer");
        area("Courtyard", "Mage Outlaw");
        area("The Dukedom", "Barbarian Duke X");
        area("Ruins of Ashfang", "Ashfang");
        area("Smoldering Tomb", "Smoldering Blaze");
        // The Barn / farming
        area("The Barn", "Cow", "Pig", "Chicken");
        area("Shepherd's Keep", "Sheep");
        area("Oasis", "Rabbit");
        area("Mushroom Gorge", "Mushroom Cow");
        // Deep Caverns
        area("Gunpowder Mines", "Sneaky Creeper");
        area("Lapis Quarry", "Lapis Zombie");
        area("Pigmen's Den", "Redstone Pigman");
        area("Slimehill", "Emerald Slime");
        area("Diamond Reserve", "Miner Zombie", "Miner Skeleton");
        area("Obsidian Sanctuary", "Obsidian Defender");
        // Dwarven Mines / Glacite
        area("Great Ice Wall", "Glacite Walker");
        area("Upper Mines", "Treasure Hoarder");
        area("Goblin Holdout", "Goblin", "Knifethrower", "Fireslinger", "Weakling", "Pitfighter",
                "Goblin Flamethrower", "Creeperlobber", "Murderlover");
        area("Mithril Deposits", "Grunt", "Executive Sebastian", "Executive Wendy", "Executive Viper",
                "Boss Corleone", "Star Sentry", "Powder Ghast");
        area("Precursor Remnants", "Automaton");
        // Glacite Mineshafts (the rare mineshaft mobs, Littlefoot among them)
        area("Glacite Mineshafts", "Glacite Bowman", "Glacite Caver", "Glacite Mage", "Glacite Mutt",
                "Littlefoot");
        // Crystal Hollows
        area("Magma Fields", "Yog");
        area("Khazad-dûm", "Sludge");
        area("Jungle", "Kalhuiki Tribe Member");
        area("Jungle Village", "Kalhuiki Elder", "Kalhuiki Youngling");
        area("Key Guardian Temple", "Jungle Key Guardian");
        area("Crystal Hollows", "Thyst", "Butterfly", "Worm", "Scatha");
        area("The Mist", "Ghost");
        // The Park
        area("The Park", "Wolf", "Old Wolf", "Pack Spirit", "Howling Spirit", "Soul of the Alpha");
        // The Garden (pests)
        area("The Garden", "Fly", "Cricket", "Locust", "Rat", "Mosquito", "Earthworm", "Mite",
                "Moth", "Slug", "Beetle", "Dragonfly", "Firefly", "Praying Mantis", "Lunar Moth",
                "Field Mouse");
        // The Catacombs (Dungeons) - exactly the wiki Bestiary's "The Catacombs" list.
        area("The Catacombs", "Angry Archeologist", "Bat", "Cellar Spider", "Crypt Dreadlord",
                "Crypt Lurker", "Crypt Souleater", "Fels", "Golem", "King Midas", "Lonely Spider",
                "Lost Adventurer", "Mimic", "Scared Skeleton", "Shadow Assassin", "Skeleton Grunt",
                "Skeleton Lord", "Skeleton Master", "Skeleton Soldier", "Skeletor", "Sniper",
                "Super Archer", "Super Tank Zombie", "Tank Zombie", "Terracotta", "Undead",
                "Undead Skeleton", "Wither Guard", "Wither Husk", "Wither Miner", "Withermancer",
                "Zombie Commander", "Zombie Grunt", "Zombie Knight", "Zombie Lord", "Zombie Soldier");
        area("Catacombs Boss", "Bonzo", "Scarf", "The Professor", "Thorn", "Livid", "Sadan", "Maxor",
                "Storm", "Goldor", "Necron", "The Wither King");
        // Kuudra
        area("Kuudra", "Kuudra", "Kuudra Follower", "Kuudra Landmine", "Inferno Magma Cube",
                "Kuudra Berserker", "Explosive Imp", "Blight", "Kuudra Knocker", "Wandering Blaze",
                "Dropship", "Kuudra Slasher", "Wither Sentry", "Magma Follower", "Blazing Golem",
                "Chaosmite", "Omegagma", "Magma Bacteria", "Kuudra Tentacle");
        // Galatea
        area("Galatea", "Bogged", "Chill", "Ent", "Nessie", "Stridersurfer", "Tadgang",
                "The Loch Emperor", "Tidetot", "Wetwing");
        // Spooky Festival
        area("Spooky Festival", "Crazy Witch", "Headless Horseman", "Phantom Spirit", "Scary Jerry",
                "Trick or Treater", "Wither Gourd", "Wraith");
        // Jerry's Workshop / Winter Island
        area("Jerry's Workshop", "Blue Jerry", "Golden Jerry", "Green Jerry", "Purple Jerry");
        area("Jerry Island", "Frosty", "Frozen Steve", "Grinch", "Nutcracker", "Reindrake", "Yeti");
        // Lotus Atoll
        area("Lotus Atoll", "Atoll Croaker", "Drowned Captain", "Flipflopper", "Frog Prince", "Lotum",
                "Lotus Guardian", "Lotusfish", "Puddle Jumper", "Seashine", "Tewtil", "gorF");
        // Fishing - sea creatures. The wiki Bestiary "Fishing" list, then every sea creature the
        // fishing tracker already knows (lava, winter and spooky ones the Bestiary section omits).
        area("Sea Creature", "Abyssal Miner", "Agarimoo", "Blue Ringed Octopus", "Carrot King",
                "Catfish", "Deep Sea Protector", "Frog Man", "Guardian Defender", "Inkling",
                "Manta Ray", "Mithril Grubber", "Oasis Rabbit", "Oasis Sheep", "Poisoned Water Worm",
                "Rider of the Deep", "Sea Archer", "Sea Leech", "Sea Walker", "Sea Witch",
                "Snapping Turtle", "Squid", "Water Hydra", "Water Worm", "Wiki Tiki");
        for (String creature : sbs.modid.client.skills.fishing.model.FishingData.allSeaCreatures()) {
            BY_NAME.putIfAbsent(creature.toLowerCase(Locale.ROOT),
                    new SkyblockMob(creature, "Sea Creature"));
        }
    }
}
