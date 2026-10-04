/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.npc;

import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where SkyBlock's NPCs stand: the lookup table behind the Recipe Viewer's NPC locator and the NPC
 * module's "route me to the objective's NPC".
 *
 * <p><b>Data, not code.</b> Coordinates are researched from the community SkyBlock wiki's NPC lists
 * and the public SkyBlock item repo's NPC entries, and go stale whenever Hypixel reworks an island,
 * so this is a seed set to be corrected in place rather than a source of truth. An NPC that is not here reports "unknown" instead of guessing - a
 * wrong coordinate routes you confidently to the wrong place, which is worse than no route.
 *
 * <p><b>A name can belong to several NPCs.</b> Romero stands in five places, Elle and Pam in a dozen
 * each, and the Auction Agent exists twice in one room. The table therefore maps a name to a
 * <i>list</i>, and {@link #find} resolves it against where the player actually is: an entry on the
 * current island wins, so "route me to Elle" means the Elle you can walk to. Guard-style duplicates
 * that only differ by a few blocks are pruned to one entry - they are interchangeable as a
 * destination.
 *
 * <p>{@link Npc#island} is what the "can I route there" gate matches (coordinates are island-scoped;
 * every island reuses the same coordinate space). {@link Npc#area} is the finer zone, shown to the
 * player so they know where on the island to head.
 */
public final class SkyblockNpcs {

    /** One catalogued NPC: display name, its island, the zone within it, and its coordinates. */
    public record Npc(String name, String island, String area, double x, double y, double z) {
    }

    /** Keyed by {@link #key}(name); several NPCs can share a name (Romero, Elle, Pam, ...). */
    private static final Map<String, List<Npc>> BY_NAME = new LinkedHashMap<>();

    private static void add(String name, String island, String area, double x, double y, double z) {
        BY_NAME.computeIfAbsent(key(name), k -> new ArrayList<>(1))
                .add(new Npc(name, island, area, x, y, z));
    }

    static {
        // ---------------------------------------------------------------- Hub
        add("Oringo", "Hub", "Village", -34.5, 69, 5.5);
        add("Jacob", "Hub", "Farmhouse", 46.5, 73, -128.5);
        add("Anita", "Hub", "Farmhouse", 53.5, 73, -128.5);
        add("Hub Selector", "Hub", "Village", -5.5, 69, -22.5);
        add("Agnes", "Hub", "Village", 10, 75, 2);
        add("Alcina", "Hub", "Village", 17, 75, 1);
        add("Alixer", "Hub", "Village", -2, 78, 4);
        add("Amelia", "Hub", "Village", -15.5, 74, -68.5);
        add("Baker", "Hub", "Village", -37.5, 69, 16.5);
        add("Christopher", "Hub", "Village", -14.5, 75, -75.5);
        add("Chuckleton", "Hub", "Village", 3.5, 78, 4.5);
        add("Bingo", "Hub", "Village", 3.5, 78, 4.5);
        add("Fear Mongerer", "Hub", "Village", -32.5, 69, 6.5);
        add("Jerry", "Hub", "Village", -33.5, 69, 7.5);
        add("Guy", "Hub", "Village", 51.5, 78, 20.5);
        add("Hoppity", "Hub", "Village", 56.5, 70, -2.5);
        add("Piggy", "Hub", "Village", 15.5, 71, -8.5);
        add("Lukas the Aquarist", "Hub", "Village", 16.5, 71, -8.5);
        add("Salesman", "Hub", "Village", -9.5, 71, -15.5);
        add("Security Sloth", "Hub", "Village", 10.5, 71, -15.5);
        add("Fisherwoman Enid", "Hub", "Village", 41.5, 70, -22.25);
        add("Lotus Atoll", "Hub", "Village", 29.5, 70, -21.5);
        add("Romero", "Hub", "Village", 89.5, 75, 15.5);
        add("Auction Master", "Hub", "Auction House", -39.5, 73, -12.5);
        add("Auction Agent", "Hub", "Auction House", -34.5, 73, -8.5);
        add("Bazaar", "Hub", "Bazaar Alley", -33.5, 73, -22.5);
        add("Bazaar Agent", "Hub", "Bazaar Alley", -35.5, 73, -31.5);
        add("Banker", "Hub", "Bank", -29.5, 72, -38);
        add("Curator", "Hub", "Museum", 27.5, 68, 33.5);
        add("Madame Eleanor Q. Goldsworth III", "Hub", "Museum", 33.5, 73, 11.5);
        add("Seymour", "Hub", "Fashion Shop", 29, 66, -40);
        add("Taylor", "Hub", "Taylor's Shop", 37.5, 74, -39.5);
        add("Liz", "Hub", "Trade Center", -35.5, 70, -77.5);
        add("Richard", "Hub", "Trade Center", -32.5, 70, -80.5);
        add("Zarina", "Hub", "Trade Center", -32.5, 70, -75.5);
        add("Builder", "Hub", "Builder's House", -8.5, 71, -61.5);
        add("Mad Redstone Engineer", "Hub", "Builder's House", -8.5, 71, -55.5);
        add("Wool Weaver", "Hub", "Builder's House", -17.5, 71, -57.5);
        add("Librarian", "Hub", "Library", -68.5, 70, -79.5);
        add("Enchantment Nerd", "Hub", "Library", -73.5, 71, -80.5);
        add("Maxwell", "Hub", "Thaumaturgist", -66.5, 70, -66.5);
        add("Jacobus", "Hub", "Thaumaturgist", -49.5, 70, -60.5);
        add("Ozanne", "Hub", "Thaumaturgist", -54.5, 70, -66.5);
        add("Maths Enjoyer", "Hub", "Thaumaturgist", -72.5, 70, -62.5);
        add("Combat Merchant", "Hub", "Thaumaturgist", -49.5, 69, -66.5);
        add("Weaponsmith", "Hub", "Combat Settlement", -50.5, 69, -85.5);
        add("Rosetta", "Hub", "Combat Settlement", -53.5, 69, -88.5);
        add("Bartender", "Hub", "Combat Settlement", -40.5, 69, -72.5);
        add("Jax", "Hub", "Archery Range", -40.5, 69, -92.5);
        add("Blacksmith", "Hub", "Blacksmith", 10.5, 63, -126.5);
        add("Dusk", "Hub", "Blacksmith", 20.5, 63, -135.5);
        add("Smithmonger", "Hub", "Blacksmith", 15.5, 63, -135.5);
        add("Mining Merchant", "Hub", "Mining District", 14.5, 63, -114);
        add("Farm Merchant", "Hub", "Farm", 63.5, 72, -113.5);
        add("Farmer Rigby", "Hub", "Farm", 61.5, 72, -147.5);
        add("Alchemist", "Hub", "Farm", 80.5, 72, -90.5);
        add("Arthur", "Hub", "Farm", 53.5, 72, -111.5);
        add("Shania", "Hub", "Farm", 59.5, 72, -144.5);
        add("Ludleth", "Hub", "Farm", 81.5, 59, -112.5);
        add("Maddox the Slayer", "Hub", "Tavern", -83.5, 68, -129.5);
        add("Pat", "Hub", "Graveyard", -87.5, 73, -94.5);
        add("Mort", "Hub", "Catacombs Entrance", -88.5, 55, -128.5);
        add("Malik", "Hub", "Catacombs Entrance", -80.5, 56, -119.5);
        add("Ophelia", "Hub", "Catacombs Entrance", -85.5, 55, -139.5);
        add("Elise", "Hub", "Wizard Tower", 44.5, 77, 100.5);
        add("Erihann", "Hub", "Wizard Tower", 42.5, 97, 93.5);
        add("Nicole", "Hub", "Wizard Tower", 41.5, 93, 96.5);
        add("Udium", "Hub", "Wizard Tower", 46.5, 101, 100.5);
        add("Grumblefoot", "Hub", "Wizard Tower", 44, 113, 99.5);
        add("Wizard", "Hub", "Wizard Tower", 48.5, 119, 99.5);
        add("Wizard's Assistant", "Hub", "Wizard Tower", 48.5, 119, 99.5);
        add("Kat", "Hub", "Pet Care", 10.5, 72, -53.5);
        add("Bea", "Hub", "Pet Care", 11.5, 72, -58.5);
        add("Fann", "Hub", "Pet Care", 9.5, 73, -62.5);
        add("George", "Hub", "Pet Care", 21.5, 74, -59.5);
        add("Zog", "Hub", "Pet Care", 10, 69, -71);
        add("Marco", "Hub", "Flower House", 90.5, 76, 3.5);
        add("Vincent", "Hub", "Artist's Abode", 79.5, 74, 53.5);
        add("Lumber Jack", "Hub", "Foraging Camp", -123.5, 73, -29.5);
        add("Lumber Merchant", "Hub", "Foraging Camp", -125, 73, -42.5);
        add("Carpenter", "Hub", "Foraging Camp", -137.5, 74, -41.5);
        add("Apprentice", "Hub", "Forest", -152.5, 63, -33.5);
        add("Angler Angus", "Hub", "Fishing Outpost", 125.5, 70, -67.5);
        add("Fisherman Gerald", "Hub", "Fishing Outpost", 118.5, 71, -32.5);
        add("Fishing Merchant", "Hub", "Fishing Outpost", 112.5, 71, -44.5);
        add("Gwynnie", "Hub", "Fishing Outpost", 116.5, 71, -25.5);
        add("Plumber Joe", "Hub", "Fishing Outpost", 123.5, 74, -38.5);
        add("Captain Baha", "Hub", "Fisherman's Hut", 162.5, 69, -65.5);
        add("Gavin", "Hub", "Fisherman's Hut", 147.5, 70.5, -59.5);
        add("Researcher Gilbert", "Hub", "Fisherman's Hut", 160.5, 69, -64.5);
        add("Gladiator", "Hub", "Wilderness", 123.5, 79, 165.5);
        add("Lucius", "Hub", "Wilderness", 125, 73, 165);
        add("Shifty", "Hub", "Wilderness", 114.5, 73, 175);
        add("Sirius", "Hub", "Wilderness", 91.5, 75, 176.5);
        add("Tia the Fairy", "Hub", "Wilderness", 119.5, 65, 147.5);
        add("Scoop", "Hub", "Mountain", 4.5, 180, 56.5);
        add("Lonely Philosopher", "Hub", "Ruins", -250.75, 130, 41.19);
        add("Bob", "Hub", "Dark Auction", 82, 55, 150.5);
        add("Scorpius", "Hub", "Dark Auction", 87.5, 55, 152.5);
        add("Damia", "Hub", "Shen's Auction", -16.5, 55, -10.5);
        add("Shen's Agent", "Hub", "Regalia Room", 6.5, 53, -15.5);
        add("Brigette", "Hub", "Security Hall", -17.5, 46, -118.6);
        add("Jimmy", "Hub", "Security Hall", -17, 45, -97);
        add("The Handler", "Hub", "Hexatorum", 40.5, 72, 1.5);
        add("Alda", "Hub", "Abiphones & Co.", 71, 80, -59);
        add("Karis", "Hub", "Abiphones & Co.", 65.5, 81, -59.5);
        add("Coach Jackrabbit", "Hub", "Rabbit House", 63.5, 68, 3.5);
        add("Rabbit Bro", "Hub", "Rabbit House", 57.5, 70, 5.5);
        add("Rabbit Cousin", "Hub", "Rabbit House", 69.59, 68, 4.5);
        add("Rabbit Daddy", "Hub", "Rabbit House", 76.5, 65, 13.5);
        add("Rabbit Dog", "Hub", "Rabbit House", 63.5, 68, 10.5);
        add("Rabbit Granny", "Hub", "Rabbit House", 58.5, 68, 10.5);
        add("Rabbit Security", "Hub", "Rabbit House", 66, 57, 7.5);
        add("Rabbit Sis", "Hub", "Rabbit House", 68.5, 68, 12.5);
        add("Rabbit Uncle", "Hub", "Rabbit House", 77.5, 65, 9.5);
        add("Feast Baker Scott", "Hub", "Communal Stew", 77.5, 72, -83.5);
        add("Feast Chef Ted", "Hub", "Communal Stew", 84.5, 72, -78.5);
        add("Carnival Leader", "Hub", "Carnival", -89.5, 71, 11.5);
        add("Carnival Cowboy", "Hub", "Carnival", -103.5, 70, 38.5);
        add("Carnival Fisherman", "Hub", "Carnival", -81.5, 72, 29.5);
        add("Carnival Pirateman", "Hub", "Carnival", -107, 73, 28);
        add("Chantelle", "Hub", "Carnival", -83.5, 71, 11.5);
        add("Doug", "Hub", "Carnival", -78.5, 70.5, 22.5);
        add("Biblio", "Hub", "Community Center", 8.5, 79, 10.5);
        add("Elizabeth", "Hub", "Community Center", -6.25, 79, 19.25);
        add("Susan", "Hub", "Community Center", -1.5, 79, 25.5);
        add("Clerk Seraphine", "Hub", "Community Center", -1.5, 79, 10.5);
        add("Aatrox", "Hub", "Community Center", 6.5, 79, 19.5);
        add("Cole", "Hub", "Community Center", 6.5, 79, 19.5);
        add("Diana", "Hub", "Community Center", 6.5, 79, 19.5);
        add("Diaz", "Hub", "Community Center", 6.5, 79, 19.5);
        add("Finnegan", "Hub", "Community Center", 6.5, 79, 19.5);
        add("Foxy", "Hub", "Community Center", 6.5, 79, 19.5);
        add("Marina", "Hub", "Community Center", 6.5, 79, 19.5);
        add("Paul", "Hub", "Community Center", 6.5, 79, 19.5);
        add("Derpy", "Hub", "Community Center", 6.5, 79, 19.5);

        // ---------------------------------------------------------------- The Park
        add("Charlie", "The Park", "Birch Park", -277.5, 80, -17.5);
        add("Vanessa", "The Park", "Birch Park", -304.5, 76, -79.5);
        add("Gustave", "The Park", "Spruce Woods", -363.5, 89, 44.5);
        add("Kelly", "The Park", "Spruce Woods", -350.5, 94, 33.5);
        add("Juliette", "The Park", "Jungle Island", -415.5, 130, -121.5);
        add("Molbert", "The Park", "Jungle Island", -465.5, 120, -42.5);
        add("Melody", "The Park", "Melody's Plateau", -411.5, 109, 72.5);
        add("Old Shaman Nyko", "The Park", "Howling Cave", -370.5, 84, -64.5);
        add("Master Tactician Funk", "The Park", "Savanna Woodland", -452.5, 110, 29.5);
        add("Worker Xavier", "The Park", "Savanna Woodland", -424.5, 110, -15.5);
        add("Ryan", "The Park", "Trials of Fire", -364.5, 102.5, -90.5);
        add("Campfire Adept", "The Park", "Trials of Fire", -360.5, 102.5, -96.5);
        add("Campfire Initiate", "The Park", "Trials of Fire", -358.5, 102.5, -94.5);
        add("Melancholic Viking", "The Park", "Viking Longhouse", -335.5, 92.5, 73.5);

        // ---------------------------------------------------------------- The End
        add("Gregory", "The End", "Dragon's Nest", -607.5, 22, -284.5);
        add("Guber", "The End", "The End", -494.5, 121, -241.5);
        add("Lone Adventurer", "The End", "The End", -524.5, 101, -275.5);
        add("Pearl Dealer", "The End", "The End", -504.5, 101, -284.5);
        add("Tyzzo", "The End", "Void Slate", -597, 5, -272);

        // ---------------------------------------------------------------- Crimson Isle
        add("Elle", "Crimson Isle", "Dragontail", -563.5, 98, -681.5);
        add("Pam", "Crimson Isle", "Dragontail Townsquare", -631.5, 100, -805.5);
        add("Ugo", "Crimson Isle", "Dragontail", -612.5, 107, -688.5);
        add("Barter", "Crimson Isle", "Dragontail Bazaar", -624.5, 101, -794.5);
        add("Bruto", "Crimson Isle", "Dragontail Bank", -612.5, 100, -786.5);
        add("Baar", "Crimson Isle", "Dragontail Auction House", -638, 123, -791);
        add("Bromm", "Crimson Isle", "Dragontail Auction House", -636.5, 103, -784.5);
        add("Igor", "Crimson Isle", "Dragontail Blacksmith", -549.5, 98, -707.5);
        add("Marthos", "Crimson Isle", "Minion Shop", -644.5, 101, -825.5);
        add("Barbarian Emissary", "Crimson Isle", "Dragontail", -580.5, 99, -710.5);
        add("Chief Scorn", "Crimson Isle", "Chief's Hut", -580.5, 115.5, -687.5);
        add("Kuudra Archeologist", "Crimson Isle", "Dragontail", -570.5, 111, -642.5);
        add("Rescue Recruiter", "Crimson Isle", "Dragontail", -581.5, 100, -690.5);
        add("An", "Crimson Isle", "Dragontail", -621.5, 108, -741.5);
        add("Chak", "Crimson Isle", "Dragontail", -602.5, 107, -868.5);
        add("Chihai", "Crimson Isle", "Dragontail", -604.5, 107, -861.5);
        add("Chuk", "Crimson Isle", "Dragontail", -604.5, 107, -869.5);
        add("Deng", "Crimson Isle", "Dragontail", -686, 102, -765);
        add("Grog", "Crimson Isle", "Dragontail", -663.5, 107, -736.5);
        add("Jine", "Crimson Isle", "Dragontail", -545.5, 119, -854.5);
        add("Kaus", "Crimson Isle", "Dragontail", -596, 113, -640);
        add("Keran", "Crimson Isle", "Dragontail", -561.5, 98, -687.5);
        add("Kutral", "Crimson Isle", "Dragontail", -658.5, 107, -741.5);
        add("Lasea", "Crimson Isle", "Dragontail", -566, 108, -657);
        add("Nall", "Crimson Isle", "Dragontail", -657.5, 107, -738.5);
        add("Nuvian", "Crimson Isle", "Dragontail", -693.5, 102, -735.5);
        add("Plenk", "Crimson Isle", "Dragontail", -572.5, 118, -739.5);
        add("Pomtair", "Crimson Isle", "Dragontail", -569.5, 120, -775.5);
        add("Porc", "Crimson Isle", "Dragontail", -577, 118, -857);
        add("Prie", "Crimson Isle", "Dragontail", -612.5, 108, -669.5);
        add("Sirih", "Crimson Isle", "Dragontail", -699.5, 131, -887.5);
        add("Strux", "Crimson Isle", "Dragontail", -596.5, 123, -845.5);
        add("Suus", "Crimson Isle", "Dragontail", -615.5, 115, -706.5);
        add("Truu", "Crimson Isle", "Dragontail", -613.5, 122, -765.5);
        add("Turd", "Crimson Isle", "Dragontail", -616.5, 115, -708.5);
        add("Yoink", "Crimson Isle", "Dragontail", -613.5, 123, -786.5);
        add("Bruuh", "Crimson Isle", "Dragontail", -635.5, 124, -688.5);
        add("Gris", "Crimson Isle", "Dragontail", -684.5, 124, -828.5);
        add("Etc", "Crimson Isle", "Dragontail", -606.5, 107, -868.5);
        add("Arba", "Crimson Isle", "Scarleton", -3.5, 93, -820.5);
        add("Arbadak", "Crimson Isle", "Scarleton", -0.5, 93, -824.5);
        add("Avorius", "Crimson Isle", "Scarleton", -152.5, 128, -809.5);
        add("Carrolyn", "Crimson Isle", "Scarleton", 1, 103, -803);
        add("Colore", "Crimson Isle", "Scarleton", -131, 89, -711);
        add("Edelis", "Crimson Isle", "Scarleton Plaza", -61.5, 107, -812.5);
        add("Ezekiel", "Crimson Isle", "Scarleton", -27.5, 107, -770.5);
        add("Kheharad", "Crimson Isle", "Scarleton", -111.5, 99, -827.5);
        add("Kuudra Loremaster", "Crimson Isle", "Scarleton", -106.5, 126, -817.5);
        add("Lys", "Crimson Isle", "Scarleton", -108.5, 99, -803.5);
        add("Mage Emissary", "Crimson Isle", "Scarleton", -131.5, 89, -722.5);
        add("Mazakala", "Crimson Isle", "Scarleton", -0.5, 93, -824.5);
        add("Mollim", "Crimson Isle", "Scarleton", -132.5, 100, -789.5);
        add("Odexar", "Crimson Isle", "Scarleton", -112.5, 99, -844.5);
        add("Pablo", "Crimson Isle", "Scarleton", -130, 127.5, -815.5);
        add("Rhanora", "Crimson Isle", "Scarleton", -143.5, 100, -828.5);
        add("Rollim", "Crimson Isle", "Scarleton", -147, 106, -854);
        add("Rulenor", "Crimson Isle", "Scarleton", -26.5, 122, -769.5);
        add("Seffea", "Crimson Isle", "Scarleton", -139.13, 99, -816.31);
        add("Ulyn", "Crimson Isle", "Scarleton", -125.5, 99, -783.5);
        add("Velyna", "Crimson Isle", "Scarleton", -86.5, 104.5, -820.5);
        add("Alchemage", "Crimson Isle", "Scarleton Bank", -86, 118, -771);
        add("Belanor", "Crimson Isle", "Scarleton Bank", -83.5, 108, -775.5);
        add("Udel", "Crimson Isle", "Scarleton Bank", -78.5, 108, -787.5);
        add("Ziri", "Crimson Isle", "Scarleton Bank", -74.5, 107, -793.5);
        add("Alwin", "Crimson Isle", "Scarleton Blacksmith", -82.5, 92, -734.5);
        add("Offea", "Crimson Isle", "Scarleton Blacksmith", -87.5, 93, -743.63);
        add("Elmar", "Crimson Isle", "Scarleton Auction House", -60.5, 108, -746.5);
        add("Selenar", "Crimson Isle", "Scarleton Bazaar", -71, 107, -757);
        add("Hilda", "Crimson Isle", "Scarleton Minion Shop", -46.5, 107, -779.5);
        add("Igrupan", "Crimson Isle", "Igrupan's Chicken Coop", -31, 93, -824);
        add("Queen Nyx", "Crimson Isle", "Throne Room", -117.5, 105, -754.5);
        add("Captain Ahone", "Crimson Isle", "Mage Council", -121.5, 82, -756.5);
        add("Counsellor", "Crimson Isle", "Mage Council", -125.5, 82, -756.5);
        add("Grelius", "Crimson Isle", "Mage Council", -119.5, 82, -758.5);
        add("Krevius", "Crimson Isle", "Mage Council", -119.5, 82, -756.5);
        add("Dean", "Crimson Isle", "Cathedral", -16, 123, -882.5);
        add("Eludore", "Crimson Isle", "Cathedral", -101.5, 91, -872.5);
        add("Scholar Alluin", "Crimson Isle", "Cathedral", -15.5, 114, -915.5);
        add("Undercover Agent", "Crimson Isle", "Cathedral", -15, 93, -845);
        add("Aranya", "Crimson Isle", "The Wasteland", -322.5, 152, -1007.5);
        add("Aviar", "Crimson Isle", "The Wasteland", -360.5, 117, -971.5);
        add("Cyndarin", "Crimson Isle", "The Wasteland", -204.5, 112, -956.5);
        add("Desperate Engineer", "Crimson Isle", "The Wasteland", -289.5, 127, -982.7);
        add("Drakuu", "Crimson Isle", "The Wasteland", -324.5, 191, -1015.5);
        add("Gnyl", "Crimson Isle", "The Wasteland", -376.5, 117, -971.5);
        add("Arch", "Crimson Isle", "Stronghold", -360.5, 189, -552.5);
        add("Crag", "Crimson Isle", "Forgotten Skull", -371, 114, -1042);
        add("Vesuvius", "Crimson Isle", "Forgotten Skull", -381, 115, -1031);
        add("Vulcan", "Crimson Isle", "Forgotten Skull", -363, 115, -1031);
        add("Corm", "Crimson Isle", "The Bastion", -668.5, 126, -924.5);
        add("Master Tao", "Crimson Isle", "Dojo", -234, 108, -602);
        add("Odger", "Crimson Isle", "Odger's Hut", -373.5, 207, -810.5);
        add("Kuudra Believer", "Crimson Isle", "Plhlegblast Pool", -390.5, 81, -701.5);

        // ---------------------------------------------------------------- Dwarven Mines
        add("Fetchur", "Dwarven Mines", "Dwarven Mines", 84, 224, -118);
        add("Puzzler", "Dwarven Mines", "Dwarven Mines", 181.5, 196, 135.5);
        add("Fragilis", "Dwarven Mines", "Dwarven Mines", 88, 199, -108);
        add("Geo", "Dwarven Mines", "Dwarven Mines", 87.5, 199, -115.5);
        add("Gwendolyn", "Dwarven Mines", "Dwarven Mines", 88.5, 198, -98.5);
        add("Lumina", "Dwarven Mines", "Dwarven Mines", 77.5, 199, -110.5);
        add("Dirt Guy", "Dwarven Mines", "Dwarven Mines", 43, 108, 175);
        add("Banker Broadjaw", "Dwarven Mines", "Dwarven Village", 13.5, 201, -148.5);
        add("Bednom", "Dwarven Mines", "Dwarven Village", -30, 213.84, -89);
        add("Bubu", "Dwarven Mines", "Dwarven Village", -10.5, 201, -103.5);
        add("Bulvar", "Dwarven Mines", "Dwarven Village", -15.5, 201, -98.5);
        add("Don Expresso", "Dwarven Mines", "Dwarven Village", 37, 202, -123);
        add("Old Man Garry", "Dwarven Mines", "Dwarven Village", 5.38, 200, -109.22);
        add("Rhys", "Dwarven Mines", "Dwarven Village", -38, 200, -119);
        add("Station Master", "Dwarven Mines", "Dwarven Village", 38.5, 201, -85.5);
        add("Bomin", "Dwarven Mines", "Dwarven Tavern", 26.6, 203, -144.8);
        add("Brynmor", "Dwarven Mines", "Dwarven Tavern", 32, 202, -154.5);
        add("Gimley", "Dwarven Mines", "Dwarven Tavern", 29.5, 202.28, -151.5);
        add("Hornum", "Dwarven Mines", "Dwarven Tavern", 35.5, 202.28, -148.5);
        add("Sargwyn", "Dwarven Mines", "Dwarven Tavern", 37.5, 202.28, -150.5);
        add("Tarwen", "Dwarven Mines", "Dwarven Tavern", 33.5, 202.28, -139.5);
        add("Brarnas", "Dwarven Mines", "Cliffside Veins", -47.5, 192, 44.5);
        add("Dalir", "Dwarven Mines", "Cliffside Veins", -44.5, 192, 47.5);
        add("Silnar", "Dwarven Mines", "Cliffside Veins", 53.06, 141.5, 19.5);
        add("Thondin", "Dwarven Mines", "Cliffside Veins", -47, 192, 47);
        add("Bylma", "Dwarven Mines", "Divan's Gateway", -9, 128, 59);
        add("Forger", "Dwarven Mines", "The Forge", -22.5, 151, -49.5);
        add("Fred", "Dwarven Mines", "The Forge", -2.5, 148, -68.5);
        add("Jotraeline Greatforge", "Dwarven Mines", "Forge Basin", -6.5, 145, -18.5);
        add("Lift Operator", "Dwarven Mines", "The Lift", -79.5, 200, -123.5);
        add("King", "Dwarven Mines", "Royal Palace", 129.5, 196, 196.5);
        add("Queen Mismyla", "Dwarven Mines", "Royal Palace", 126.5, 195, 195.5);
        add("Tornora", "Dwarven Mines", "Royal Palace", 136, 196, 167);
        add("Dalbrek", "Dwarven Mines", "Grand Library", 191, 216, 154);
        add("Tal Ker", "Dwarven Mines", "Grand Library", 193.5, 196, 205.5);
        add("Dulin", "Dwarven Mines", "Hanging Court", 80.25, 187.3, 127.5);
        add("Ticket Master", "Dwarven Mines", "Hanging Court", 86.5, 187, 114.5);
        add("Marigold", "Dwarven Mines", "Royal Mines", 181.5, 150, 60.5);
        add("Guard Gornum", "Dwarven Mines", "Abandoned Quarry", -158.5, 149, -14.5);
        add("Ian", "Dwarven Mines", "Ironman's Guild", -67.5, 221, -121.5);
        add("Ivan", "Dwarven Mines", "Ironman's Guild", -62.5, 221, -131.5);
        add("Ivy", "Dwarven Mines", "Ironman's Guild", -72.5, 221, -135.5);
        add("Resident Neighbor", "Dwarven Mines", "Royal Quarters", 165, 202, 279);
        add("Resident Snooty", "Dwarven Mines", "Royal Quarters", 92, 202, 277);
        add("Royal Resident", "Dwarven Mines", "Barracks of Heroes", 62, 204, 200);
        add("Emissary Braum", "Dwarven Mines", "Dwarven Mines", 89.5, 198, -92.5);
        add("Emissary Carlton", "Dwarven Mines", "Rampart's Quarry", -72.5, 153, -10.5);
        add("Emissary Ceanna", "Dwarven Mines", "Cliffside Veins", 42.5, 134.5, 22.5);
        add("Emissary Eliza", "Dwarven Mines", "Dwarven Village", -37.5, 200, -131.5);
        add("Emissary Fraiser", "Dwarven Mines", "Upper Mines", -132.5, 174, -50.5);
        add("Emissary Lilith", "Dwarven Mines", "Lava Springs", 58.5, 198, -8.5);
        add("Emissary Lissandra", "Dwarven Mines", "Dwarven Base Camp", 2.5, 121.13, 237.5);
        add("Emissary Wilson", "Dwarven Mines", "Royal Mines", 171.5, 150, 31.5);
        add("Cold Enjoyer", "Dwarven Mines", "Dwarven Base Camp", -15.25, 136.78, 217.25);
        add("Scout Plinius", "Dwarven Mines", "Dwarven Base Camp", 10, 121, 245);
        add("Scout Scardius", "Dwarven Mines", "Dwarven Base Camp", -16.5, 121, 232.5);
        add("Sor'Hen", "Dwarven Mines", "Dwarven Base Camp", 0.5, 121, 225.5);
        add("Dr. Stone", "Dwarven Mines", "Fossil Research Center", 28, 120, 238.5);
        add("Researcher Beryl", "Dwarven Mines", "Fossil Research Center", 35.5, 128, 236);
        add("Researcher Jade", "Dwarven Mines", "Fossil Research Center", 35.5, 120, 228.5);
        add("Fossil Muncher", "Dwarven Mines", "Glacite Tunnels", -26.5, 125, 299.5);
        add("Grandpa Wolf", "Dwarven Mines", "Grandpa Wolf's Cave", -55.5, 125, 331.5);

        // ================================================================ from the item repo
        // Everything below was added from the public SkyBlock item repo by
        // scripts/npc_coords_import.py: NPCs this table lacked, on the island the repo files them
        // under. The zone is the nearest entry above within 25 blocks, else the island itself.
        // Entries above were never overwritten - where both disagree by more than 8 blocks, ours
        // stands until /sbs npccheck settles it in game.
        // ---------------------------------------------------------------- Hub (item repo)
        add("Billy Joe", "Hub", "Thaumaturgist", -77.5, 71, -69.5);
        add("Bobby Joe", "Hub", "Farm", 63.5, 72, -114.5);
        add("Farmer", "Hub", "Farm", 45.5, 73, -161.5);
        add("Fisherman", "Hub", "Hub", 156.5, 69, 48.5);
        add("Gerald", "Hub", "Hub", 154.5, 70, 56.5);
        add("Hootie", "Hub", "Hub", 38.5, 71, -90.5);
        add("Jim Bob", "Hub", "Abiphones & Co.", 66.5, 72, -60.5);
        add("Mine Merchant", "Hub", "Mining District", 14.5, 63, -113.5);
        add("Minikloon", "Hub", "Farm", 84.5, 73, -116.5);
        add("Security Clerk", "Hub", "Security Hall", -22.5, 46, -119.5);
        add("Tyashoi Alchemist", "Hub", "Taylor's Shop", 41.5, 68, -55.5);
        // ---------------------------------------------------------------- The Rift (item repo)
        add("Alabaster", "The Rift", "The Rift", -129.5, 74, 167.5);
        add("Alatar", "The Rift", "The Rift", -46.5, 117, 70.5);
        add("Alchemist", "The Rift", "The Rift", -48.5, 70, -63.5);
        add("Argofay Bughunter", "The Rift", "The Rift", -84.5, 109, 97.5);
        add("Argofay Bugshopper", "The Rift", "The Rift", -94.5, 111, 95.5);
        add("Argofay Doorholder", "The Rift", "The Rift", -4.5, 181, 25.5);
        add("Argofay Serialbather", "The Rift", "The Rift", -136.5, 120, 116.5);
        add("Argofay Sonfather", "The Rift", "The Rift", -95.5, 101, 156.5);
        add("Argofay Tencounter", "The Rift", "The Rift", -92.5, 100, 95.5);
        add("Argofay Threebrother 1", "The Rift", "The Rift", -92.5, 77, 109.5);
        add("Argofay Threebrother 2", "The Rift", "The Rift", -97.5, 77, 154.5);
        add("Argofay Threebrother 3", "The Rift", "The Rift", -135.5, 76, 113.5);
        add("Argofay Trafficker 1", "The Rift", "The Rift", -91.5, 90, 106.5);
        add("Argofay Trafficker 2", "The Rift", "The Rift", -135.5, 89, 114.5);
        add("Argofay Trafficker 3", "The Rift", "The Rift", -137.5, 98, 113.5);
        add("Argofay Trafficker 4", "The Rift", "The Rift", -150.5, 113, 115.5);
        add("Argofay Trailblazer", "The Rift", "The Rift", -96.5, 67, 148.5);
        add("Argofay Treemerger", "The Rift", "The Rift", -144.5, 94, 140.5);
        add("Arora", "The Rift", "The Rift", -128.5, 74, 169.5);
        add("Ashera", "The Rift", "The Rift", -130.5, 74, 170.5);
        add("Barry", "The Rift", "The Rift", -44.5, 55, -141.5);
        add("Blacksmith", "The Rift", "The Rift", -1.5, 69, -141.5);
        add("Chef", "The Rift", "The Rift", -102.5, 73, -103.5);
        add("Chester", "The Rift", "The Rift", -130.5, 74, 173.5);
        add("Chipper McPickaxe", "The Rift", "The Rift", 17.5, 102, 27.5);
        add("Cosmo", "The Rift", "The Rift", -65.5, 71, -91.5);
        add("Cowboy Nick", "The Rift", "The Rift", -12.5, 71, -6.5);
        add("Cryptosis", "The Rift", "The Rift", 27.5, 72, -76.5);
        add("Dackinoru", "The Rift", "The Rift", -117.5, 74, 173.5);
        add("Damia", "The Rift", "The Rift", 31.5, 73, -93.5);
        add("Deer", "The Rift", "The Rift", 139.5, 71, -11.5);
        add("Defunct Wizardman", "The Rift", "The Rift", -11.5, 187, 33.5);
        add("Detective Amog", "The Rift", "The Rift", -49.5, 69, -36.5);
        add("Dr. Hibble", "The Rift", "The Rift", -78.5, 74, 10.5);
        add("Dr. Phear", "The Rift", "The Rift", -145.5, 37, 28.5);
        add("Election Worker Creed", "The Rift", "The Rift", -51.5, 55, -137.5);
        add("Election Worker Dust", "The Rift", "The Rift", -54.5, 55, -138.5);
        add("Election Worker Harriette", "The Rift", "The Rift", -39.5, 55, -138.5);
        add("Election Worker Soma", "The Rift", "The Rift", -51.5, 55, -140.5);
        add("Election Worker Stain", "The Rift", "The Rift", -57.5, 55, -146.5);
        add("Electrion Worker Frankie", "The Rift", "The Rift", -44.5, 55, -146.5);
        add("Electrion Worker Violetta", "The Rift", "The Rift", -51.5, 55, -147.5);
        add("Elise", "The Rift", "The Rift", -49.5, 123, 69.5);
        add("Fafnir", "The Rift", "The Rift", -127.5, 74, 173.5);
        add("Fairylosopher", "The Rift", "The Rift", 256.5, 131, 76.5);
        add("Finplex", "The Rift", "The Rift", -56.5, 69, -81.5);
        add("Garlacius", "The Rift", "The Rift", -128.5, 80, 171.5);
        add("Grandma", "The Rift", "The Rift", -69.5, 65, -59.5);
        add("Gunther", "The Rift", "The Rift", -68.5, 72, -60.5);
        add("Hound", "The Rift", "The Rift", -69.5, 67, 148.5);
        add("Inverted Sirius", "The Rift", "The Rift", -95.5, 76, 191.5);
        add("Jacquelle", "The Rift", "The Rift", -121.5, 120, 137.5);
        add("Jerry", "The Rift", "The Rift", -85.5, 21, 4.5);
        add("Joey McPizza", "The Rift", "The Rift", -104.5, 73, -101.5);
        add("Kat", "The Rift", "The Rift", -33.5, 71, -89.5);
        add("Kay", "The Rift", "The Rift", -190.5, 69, 59.5);
        add("Lazarus", "The Rift", "The Rift", -127.5, 80, 166.5);
        add("Madame Eleanor Q. Goldsworth I", "The Rift", "The Rift", 0.5, 115, 36.5);
        add("Madame Eleanor Q. Goldsworth II", "The Rift", "The Rift", -222.5, 18, -74.5);
        add("Madame Eleanor Q. Goldsworth IX", "The Rift", "The Rift", 38.5, 130, 42.5);
        add("Madame Eleanor Q. Goldsworth V", "The Rift", "The Rift", 6.5, 151, 23.5);
        add("Madame Eleanor Q. Goldsworth VII", "The Rift", "The Rift", 8.5, 180, 38.5);
        add("Madame Eleanor Q. Goldsworth X", "The Rift", "The Rift", 24.5, 155, 43.5);
        add("Maddox the Slayer", "The Rift", "The Rift", 205.5, 78, 45.5);
        add("Master Tactician Fink", "The Rift", "The Rift", -191.5, 73, -56.5);
        add("Mole", "The Rift", "The Rift", -158.5, 98, -74.5);
        add("Motes Grubber", "The Rift", "The Rift", -91.5, 66, 157.5);
        add("Mushroom Guy", "The Rift", "The Rift", -177.5, 76, 13.5);
        add("Nene", "The Rift", "The Rift", 24.5, 90, -89.5);
        add("Old Timer", "The Rift", "The Rift", 17.5, 98, 19.5);
        add("Phaser", "The Rift", "The Rift", 60.5, 71, -87.5);
        add("Plumber Joe", "The Rift", "The Rift", -62.5, 70, -76.5);
        add("Porhtal", "The Rift", "The Rift", -156.5, 72, 162.5);
        add("Protester Creed", "The Rift", "The Rift", -15.5, 74, -111.5);
        add("Protester Dust", "The Rift", "The Rift", -11.5, 72, -100.5);
        add("Protester Frankie", "The Rift", "The Rift", -4.5, 74, -111.5);
        add("Protester Harriette", "The Rift", "The Rift", -1.5, 75, -108.5);
        add("Protester Soma", "The Rift", "The Rift", -16.5, 75, -107.5);
        add("Protester Stain", "The Rift", "The Rift", -6.5, 72, -100.5);
        add("Protester Violetta", "The Rift", "The Rift", -4.5, 74, -105.5);
        add("Reed", "The Rift", "The Rift", -211.5, 73, 61.5);
        add("Roger", "The Rift", "The Rift", -1.5, 71, -42.5);
        add("Roy", "The Rift", "The Rift", -204.5, 76, 49.5);
        add("Sad Jacquelle", "The Rift", "The Rift", -121.5, 120, 137.5);
        add("Seraphine", "The Rift", "The Rift", -15.5, 72, -100.5);
        add("Seymour", "The Rift", "The Rift", -26.5, 72, -43.5);
        add("Shania", "The Rift", "The Rift", -47.5, 73, -160.5);
        add("Shifted", "The Rift", "The Rift", -120.5, 74, 174.5);
        add("Sorcerer Okron", "The Rift", "The Rift", -69.5, 82, -109.5);
        add("Sun Gecko Essence Shop", "The Rift", "The Rift", 38.5, 90, 18.5);
        add("Taylor", "The Rift", "The Rift", -31.5, 72, -37.5);
        add("Tel Kar", "The Rift", "The Rift", -112.5, 70, 62.5);
        add("Tybalt", "The Rift", "The Rift", -128.5, 80, 169.5);
        add("Ubik Von Neumann", "The Rift", "The Rift", 21.5, 108, 54.5);
        add("Vreike", "The Rift", "The Rift", -123.5, 74, 173.5);
        add("Weird Wizard", "The Rift", "The Rift", -46.5, 124, 78.5);
        add("Yoshua", "The Rift", "The Rift", -68.5, 72, -116.5);
        add("ttemme .rD", "The Rift", "The Rift", -292.5, 56, -86.5);
        // ---------------------------------------------------------------- Crimson Isle (item repo)
        add("Kuudra Archaeologist", "Crimson Isle", "Dragontail", -569.5, 112, -641.5);
        add("Kuudra Gatekeeper", "Crimson Isle", "Forgotten Skull", -370.5, 114, -1037.5);
        // ---------------------------------------------------------------- Gold Mine (item repo)
        add("Gold Forger", "Gold Mine", "Gold Mine", -27.5, 76, -294.5);
        add("Iron Forger", "Gold Mine", "Gold Mine", -1.5, 77, -307.5);
        add("Lazy Miner", "Gold Mine", "Gold Mine", -10.5, 79, -336.5);
        add("Rusty", "Gold Mine", "Gold Mine", -19.5, 79, -325.5);
        // ---------------------------------------------------------------- Deep Caverns (item repo)
        add("Lapis Miner", "Deep Caverns", "Deep Caverns", -9.5, 121, 36.5);
        add("Lift Operator", "Deep Caverns", "Deep Caverns", 45.5, 150, 15.5);
        add("Redstone Miner", "Deep Caverns", "Deep Caverns", 25.5, 105, 17.5);
        add("Walter", "Deep Caverns", "Deep Caverns", 18.5, 158, -36.5);
        // ---------------------------------------------------------------- Dwarven Mines (item repo)
        add("Forge Foreman", "Dwarven Mines", "The Forge", -2.5, 149, -68.5);
        add("Fossil Excavator", "Dwarven Mines", "Fossil Research Center", 18.5, 121, 227.5);
        add("Rornora", "Dwarven Mines", "Royal Palace", 136.5, 197, 167.5);
        // ---------------------------------------------------------------- Crystal Hollows (item repo)
        add("Chunk", "Crystal Hollows", "Crystal Hollows", 797.5, 128, 708.5);
        add("Emissary Sisko", "Crystal Hollows", "Crystal Hollows", 496.5, 107, 557.5);
        add("Gemma", "Crystal Hollows", "Crystal Hollows", 476.5, 107, 514.5);
        add("Geonathan Greatforge", "Crystal Hollows", "Crystal Hollows", 531.5, 107, 557.5);
        // ---------------------------------------------------------------- The Park (item repo)
        add("Joyful Viking", "The Park", "Viking Longhouse", -358.5, 93, 76.5);
        add("Master Tactician", "The Park", "The Park", -462.5, 112, -15.5);
        // ---------------------------------------------------------------- Moonglade Marsh (item repo)
        add("Agatha", "Moonglade Marsh", "Moonglade Marsh", -648.5, 89, -3.5);
        add("Alan", "Moonglade Marsh", "Moonglade Marsh", -625.5, 116, -36.5);
        add("Albert", "Moonglade Marsh", "Moonglade Marsh", -592.5, 113, 8.5);
        add("Amaury", "Moonglade Marsh", "Moonglade Marsh", -578.5, 114, 2.5);
        add("Auryon", "Moonglade Marsh", "Moonglade Marsh", -557.5, 111, 48.5);
        add("Banker Bonsai", "Moonglade Marsh", "Moonglade Marsh", -598.5, 116, -37.5);
        add("Boris", "Moonglade Marsh", "Moonglade Marsh", -684.5, 129, 64.5);
        add("Brian", "Moonglade Marsh", "Moonglade Marsh", -628.5, 117, 47.5);
        add("Coral", "Moonglade Marsh", "Moonglade Marsh", -626.5, 99, -87.5);
        add("David Hunterborough", "Moonglade Marsh", "Moonglade Marsh", -593.5, 114, -13.5);
        add("Dax", "Moonglade Marsh", "Moonglade Marsh", -705.5, 94, 33.5);
        add("Hina", "Moonglade Marsh", "Moonglade Marsh", -553.5, 110, -16.5);
        add("Jaeger", "Moonglade Marsh", "Moonglade Marsh", -651.5, 89, -24.5);
        add("Kiara", "Moonglade Marsh", "Moonglade Marsh", -653.5, 113, -69.5);
        add("Kysha", "Moonglade Marsh", "Moonglade Marsh", -601.5, 115, -11.5);
        add("Loras", "Moonglade Marsh", "Moonglade Marsh", -593.5, 118, 14.5);
        add("Martin", "Moonglade Marsh", "Moonglade Marsh", -579.5, 93, 31.5);
        add("Nemo", "Moonglade Marsh", "Moonglade Marsh", -619.5, 87, -6.5);
        add("Oden", "Moonglade Marsh", "Moonglade Marsh", -584.5, 113, -40.5);
        add("Pounce", "Moonglade Marsh", "Moonglade Marsh", -598.5, 127, -20.5);
        add("Rosemary", "Moonglade Marsh", "Moonglade Marsh", -703.5, 162, 3.5);
        add("Salvage Old Attributes", "Moonglade Marsh", "Moonglade Marsh", -594.5, 114, -17.5);
        add("Sawyer", "Moonglade Marsh", "Moonglade Marsh", -691.5, 121, 81.5);
        add("Swoop", "Moonglade Marsh", "Moonglade Marsh", -579.5, 114, -23.5);
        add("Talia", "Moonglade Marsh", "Moonglade Marsh", -615.5, 108, 2.5);
        add("Teddy", "Moonglade Marsh", "Moonglade Marsh", -656.5, 115, -39.5);
        add("Tilus", "Moonglade Marsh", "Moonglade Marsh", -641.5, 55, 48.5);
        add("Vaeri", "Moonglade Marsh", "Moonglade Marsh", -688.5, 116, -35.5);
        // ---------------------------------------------------------------- Torrhus Canyon (item repo)
        add("Andrea", "Torrhus Canyon", "Torrhus Canyon", -711.5, 108, 178.5);
        add("Ant", "Torrhus Canyon", "Torrhus Canyon", -615.5, 75, 208.5);
        add("Archie", "Torrhus Canyon", "Torrhus Canyon", -706.5, 108, 165.5);
        add("Beekeeper Bartholomew", "Torrhus Canyon", "Torrhus Canyon", -666.5, 95, 166.5);
        add("Dory", "Torrhus Canyon", "Torrhus Canyon", -645.5, 161, 194.5);
        add("Forager Daniel", "Torrhus Canyon", "Torrhus Canyon", -639.5, 95, 246.5);
        add("Forager Rowan", "Torrhus Canyon", "Torrhus Canyon", -725.5, 137, 228.5);
        add("Freddy", "Torrhus Canyon", "Torrhus Canyon", -694.5, 94, 200.5);
        add("Helia", "Torrhus Canyon", "Torrhus Canyon", -690.5, 93, 155.5);
        add("Honey", "Torrhus Canyon", "Torrhus Canyon", -627.5, 99, 226.5);
        add("Hunter Jean", "Torrhus Canyon", "Torrhus Canyon", -665.5, 145, 170.5);
        add("Hunter McNeil", "Torrhus Canyon", "Torrhus Canyon", -652.5, 103, 167.5);
        add("Maxim", "Torrhus Canyon", "Torrhus Canyon", -574.5, 153, 248.5);
        add("Miria", "Torrhus Canyon", "Torrhus Canyon", -654.5, 98, 220.5);
        add("Oen", "Torrhus Canyon", "Torrhus Canyon", -566.5, 80, 271.5);
        add("Safari Manager", "Torrhus Canyon", "Torrhus Canyon", -732.5, 99, 185.5);
        add("Safari Receptionist", "Torrhus Canyon", "Torrhus Canyon", -719.5, 99, 178.5);
        add("Safari Shill", "Torrhus Canyon", "Torrhus Canyon", -696.5, 94, 178.5);
        add("Treehugger Timothy", "Torrhus Canyon", "Torrhus Canyon", -541.5, 110, 276.5);
        // ---------------------------------------------------------------- The Farming Islands (item repo)
        add("Beth", "The Farming Islands", "The Farming Islands", 163.5, 77, -358.5);
        add("Farmer Jon", "The Farming Islands", "The Farming Islands", 168.5, 92, -597.5);
        add("Farmhand", "The Farming Islands", "The Farming Islands", 144.5, 73, -240.5);
        add("Friendly Hiker", "The Farming Islands", "The Farming Islands", 181.5, 77, -380.5);
        add("Hungry Hiker", "The Farming Islands", "The Farming Islands", 148.5, 77, -374.5);
        add("Hunter Ava", "The Farming Islands", "The Farming Islands", 320.5, 103, -469.5);
        add("Jake", "The Farming Islands", "The Farming Islands", 261.5, 186, -565.5);
        add("Mason", "The Farming Islands", "The Farming Islands", 177.5, 77, -355.5);
        add("Moby", "The Farming Islands", "The Farming Islands", 206.5, 44, -499.5);
        add("Shepherd", "The Farming Islands", "The Farming Islands", 389.5, 86, -371.5);
        add("Talbot", "The Farming Islands", "The Farming Islands", 285.5, 104, -548.5);
        add("Tammy", "The Farming Islands", "The Farming Islands", 285.5, 105, -543.5);
        add("Tony", "The Farming Islands", "The Farming Islands", 278.5, 104, -544.5);
        add("Treasure Hunter", "The Farming Islands", "The Farming Islands", 201.5, 93, -436.5);
        add("Trevor", "The Farming Islands", "The Farming Islands", 282.5, 105, -541.5);
        add("Windmill Operator", "The Farming Islands", "The Farming Islands", 102.5, 83, -282.5);
        // ---------------------------------------------------------------- Jerry's Workshop (item repo)
        add("Banker Barry", "Jerry's Workshop", "Jerry's Workshop", 20.5, 77, 44.5);
        add("Einary", "Jerry's Workshop", "Jerry's Workshop", -16.5, 76, 63.5);
        add("Frosty", "Jerry's Workshop", "Jerry's Workshop", -1.5, 76, 92.5);
        add("Frozen Alex", "Jerry's Workshop", "Jerry's Workshop", -42.5, 84, -0.5);
        add("Gary", "Jerry's Workshop", "Jerry's Workshop", 54.5, 104, 57.5);
        add("Gulliver", "Jerry's Workshop", "Jerry's Workshop", 69.5, 106, 34.5);
        add("Hendrik", "Jerry's Workshop", "Jerry's Workshop", 17.5, 63, -29.5);
        add("Maaike", "Jerry's Workshop", "Jerry's Workshop", 49.5, 81, 50.5);
        add("Sherry", "Jerry's Workshop", "Jerry's Workshop", 6.5, 77, 95.5);
        add("St. Jerry", "Jerry's Workshop", "Jerry's Workshop", -22.5, 76, 92.5);
        add("Terry", "Jerry's Workshop", "Jerry's Workshop", -92.5, 78, 25.5);
        // ---------------------------------------------------------------- Spider's Den (item repo)
        add("Archaeologist", "Spider's Den", "Spider's Den", -359.5, 112, -289.5);
        add("Bestiary", "Spider's Den", "Spider's Den", -271.5, 113, -196.5);
        add("Bramass Beastslayer", "Spider's Den", "Spider's Den", -271.5, 113, -196.5);
        add("Grandma Wolf", "Spider's Den", "Spider's Den", -280.5, 123, -189.5);
        add("Haymitch", "Spider's Den", "Spider's Den", -201.5, 84, -235.5);
        add("Ike", "Spider's Den", "Spider's Den", -260.5, 94, -284.5);
        add("Michael", "Spider's Den", "Spider's Den", -264.5, 94, -284.5);
        add("Rick", "Spider's Den", "Spider's Den", -264.5, 71, -323.5);
        add("Shaggy", "Spider's Den", "Spider's Den", -277.5, 122, -181.5);
        add("Spider Tamer", "Spider's Den", "Spider's Den", -298.5, 64, -194.5);
        // ---------------------------------------------------------------- Dungeon Hub (item repo)
        add("Adventurer Saul", "Dungeon Hub", "Dungeon Hub", -51.5, 120, -8.5);
        add("Croesus", "Dungeon Hub", "Dungeon Hub", -20.5, 121, -12.5);
        add("Guildford", "Dungeon Hub", "Dungeon Hub", -18.5, 86, 4.5);
        add("Malik", "Dungeon Hub", "Dungeon Hub", -62.5, 122, 6.5);
        add("Mort", "Dungeon Hub", "Dungeon Hub", -61.5, 123, 0.5);
        add("Ophelia", "Dungeon Hub", "Dungeon Hub", -63.5, 123, -6.5);
        // ---------------------------------------------------------------- Backwater Bayou (item repo)
        add("Carly", "Backwater Bayou", "Backwater Bayou", 38.5, 71, 6.5);
        add("Hattie", "Backwater Bayou", "Backwater Bayou", 18.5, 72, 15.5);
        add("Jonesy", "Backwater Bayou", "Backwater Bayou", -9.5, 74, -9.5);
        add("Junker Joel", "Backwater Bayou", "Backwater Bayou", 7.5, 73, 5.5);
        add("Researcher Gilbert", "Backwater Bayou", "Backwater Bayou", 27.5, 59, -25.5);
        add("Roddy", "Backwater Bayou", "Backwater Bayou", 22.5, 79, -22.5);
        // ---------------------------------------------------------------- Lotus Atoll (item repo)
        add("Researcher Betty", "Lotus Atoll", "Lotus Atoll", -13.5, 71, 3.5);
        add("Researcher Jessica", "Lotus Atoll", "Lotus Atoll", 75.5, 64, -10.5);
        add("Researcher Kay", "Lotus Atoll", "Lotus Atoll", 23.5, 83, -50.5);
        add("Researcher Lily", "Lotus Atoll", "Lotus Atoll", 23.5, 66, 14.5);
        add("Researcher Ribery", "Lotus Atoll", "Lotus Atoll", -11.5, 71, -6.5);
        add("Researcher Tay", "Lotus Atoll", "Lotus Atoll", 26.5, 141, -60.5);
        add("Researcher Timmy", "Lotus Atoll", "Lotus Atoll", 11.5, 75, 30.5);
        add("Researcher Tina", "Lotus Atoll", "Lotus Atoll", 43.5, 70, 29.5);
        add("The Lotus Eater", "Lotus Atoll", "Lotus Atoll", 99.5, 68, -5.5);
        // ---------------------------------------------------------------- Critter Safari (item repo)
        add("\"Hunter\" Tobias", "Critter Safari", "Critter Safari", -49.5, 66, 2.5);
        add("Birdwatcher Tim", "Critter Safari", "Critter Safari", 7.5, 88, 46.5);
        // ---------------------------------------------------------------- The Garden (item repo)
        add("Pesthunter Phillip", "The Garden", "The Garden", -25.5, 72, -13.5);
    }

    private SkyblockNpcs() {
    }

    /** Every catalogued NPC (for the Recipe Viewer's supplemental search entries). */
    public static Collection<Npc> all() {
        List<Npc> out = new ArrayList<>(BY_NAME.size());
        for (List<Npc> entries : BY_NAME.values()) {
            out.addAll(entries);
        }
        return java.util.Collections.unmodifiableCollection(out);
    }

    /** Whether a (possibly decorated) display name is an NPC entry – Hypixel tags them "... (NPC)". */
    public static boolean isNpcEntry(String displayName) {
        return displayName != null && strip(displayName).toLowerCase(Locale.ROOT).contains("(npc)");
    }

    /**
     * The catalogued NPC for a display name, or {@code null} when it is not in the seed table.
     *
     * <p>When several NPCs share the name, the one on the island the player is standing on wins -
     * that is the one they can actually walk to. Off-island, the first entry is returned so the
     * locator can still say "go to Crimson Isle".
     */
    public static Npc find(String displayName) {
        List<Npc> entries = displayName == null ? null : BY_NAME.get(key(displayName));
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        for (Npc npc : entries) {
            if (SkyBlockLocation.onIsland(npc.island())) {
                return npc;
            }
        }
        return entries.getFirst();
    }

    /** Every NPC sharing a name, in table order (empty when the name is not catalogued). */
    public static List<Npc> findAll(String displayName) {
        List<Npc> entries = displayName == null ? null : BY_NAME.get(key(displayName));
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /** Every catalogued name, longest first - so a scan matches "Rabbit Bro" before "Bro". */
    public static List<String> namesLongestFirst() {
        List<String> names = new ArrayList<>(BY_NAME.size());
        for (List<Npc> entries : BY_NAME.values()) {
            names.add(entries.getFirst().name());
        }
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return names;
    }

    /** The clean NPC name a display name resolves to (colours, the "(NPC)" tag and stars removed). */
    public static String cleanName(String displayName) {
        String clean = strip(displayName);
        clean = clean.replaceAll("(?i)\\(npc\\)", "");
        clean = clean.replaceAll("[✪✦⚚➊➋➌➍➎★]+", "");
        return clean.trim();
    }

    /** Lookup key: the clean name, lower-cased. */
    private static String key(String displayName) {
        return cleanName(displayName).toLowerCase(Locale.ROOT);
    }

    /** Removes §-colour codes. */
    private static String strip(String text) {
        return text == null ? "" : text.replaceAll("(?i)§.", "");
    }
}
