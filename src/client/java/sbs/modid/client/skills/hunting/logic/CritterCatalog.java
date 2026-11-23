/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every Critter Safari critter with Hypixel's own "how to catch" hint, copied from Critterdex menu
 * captures of a 37/37 profile (2026-11-22, mod 1.0.0-beta.14).
 *
 * <p>The menu prints the hint only on a critter you have already caught, which is the one time you
 * no longer need it. Shipping the text lets the Critterdex tooltip show it before the first catch.
 *
 * <p><b>Slot positions</b> are each biome page's own layout: 10-16, then 19 onward. They are how a
 * critter the menu has not revealed yet (no name to read) is still identified - the biome is read
 * from the header in slot 4, and the slot says which critter of that biome it is.
 */
public final class CritterCatalog {

    /** One critter: its Critterdex name, biome, page slot, rarity line and hint lines. */
    public record Critter(String name, String biome, int slot, String rarity, List<String> howTo) {
    }

    private static final List<Critter> ALL = List.of(
            // Cavern Biome
            c("Cavernfish", "Cavern", 10, "COMMON", "Find them in an underwater pond", "deep into the biome."),
            c("Flitter", "Cavern", 11, "COMMON", "Catch them as they fly around."),
            c("Shyworm", "Cavern", 12, "COMMON", "Sneak up on them when they surface."),
            c("Driftling", "Cavern", 13, "UNCOMMON", "Found floating around on the outside", "winds."),
            c("Chuckwalla", "Cavern", 14, "RARE", "Corner it when it tries to run away."),
            c("Rockmite", "Cavern", 15, "RARE", "Break Rockmite Mounds with your fist."),
            c("Scrappy", "Cavern", 16, "RARE", "Feed it its favorite food to coax it", "out of its shell."),
            c("Snoozle", "Cavern", 19, "RARE", "Destroy Breakable Walls by running", "into them headfirst."),
            c("Gemzie", "Cavern", 20, "EPIC", "Place the Orange Gem, Purple Gem,",
                    "and Lime Gem on their respective", "pedestals to open the sealed", "chamber door."),
            // Forest Biome
            c("Foxtrot", "Forest", 10, "COMMON", "Found running around the forest", "floor."),
            c("Bluebird", "Forest", 11, "UNCOMMON", "Can be attracted to the Birdfeeder.", "It prefers Yogi Berry."),
            c("Honeybug", "Forest", 12, "UNCOMMON", "Punch a Beehive!"),
            c("Treefrog", "Forest", 13, "UNCOMMON", "Hops away if you get too close!"),
            c("Woodchucker", "Forest", 14, "UNCOMMON", "They live on the trees and try to",
                    "blend in, but their tendency to throw", "wood gives them away."),
            c("Fluffling", "Forest", 15, "RARE", "Found snoozing underground."),
            c("Hideonfloor", "Forest", 16, "RARE", "Watch your step - they hide on the", "floor!"),
            c("Parakeet", "Forest", 19, "RARE", "Can be attracted to the Birdfeeder.", "It prefers Wriggleworms."),
            c("Macaw", "Forest", 20, "LEGENDARY", "Can be attracted to the Birdfeeder.", "It prefers a Bag of Seeds."),
            // Haunted Biome
            c("Areita", "Haunted", 10, "UNCOMMON", "The Areita are a strange species of",
                    "arachnid that are only known to", "inhabit one other mansion."),
            c("Bloodbat", "Haunted", 11, "UNCOMMON", "Sleeps on ceilings, usually inside the", "Haunted House."),
            c("Duplico", "Haunted", 12, "UNCOMMON", "Look out for any blocks that seem", "out of place!"),
            c("Gazer", "Haunted", 13, "UNCOMMON", "Wakes you up when you try to sleep", "in a bed."),
            c("Litterbug", "Haunted", 14, "UNCOMMON", "Scuttles about in the grosser rooms."),
            c("Solsnatcher", "Haunted", 15, "UNCOMMON", "Flies high up in the sky - take aim", "and shoot!"),
            c("Gimmiegold", "Haunted", 16, "RARE", "Throw a Shining Coin into the water in", "the basement."),
            c("Hideonwall", "Haunted", 19, "RARE", "Found living within hollow walls, such", "as behind paintings."),
            c("Hideyho", "Haunted", 20, "RARE", "Will invite you to play a game of Hide", "'n Seek. Weird."),
            c("Doomspiral", "Haunted", 21, "LEGENDARY", "Complete the Ritual to summon it into", "this world."),
            // Icy Biome
            c("Strongarm", "Icy", 10, "COMMON", "Found on the hills of the icy mountain."),
            c("Tepid", "Icy", 11, "COMMON", "Found in icy lakes!"),
            c("Polaris", "Icy", 12, "UNCOMMON", "Found living in caves."),
            c("Shuddersquid", "Icy", 13, "UNCOMMON", "Found in the hidden ice pool behind", "the waterfall."),
            c("Billygoat", "Icy", 14, "RARE", "Lives atop the icy mountain - the", "tallest part of the zone."),
            c("Mantis Shrimp", "Icy", 15, "RARE", "Frequents the deepest depths of", "the icy lakes."),
            c("Nozzlenose", "Icy", 16, "RARE", "Found deep down in the icy lakes.", "Hides in tunnels made with its nose."),
            c("Troodon", "Icy", 19, "RARE", "Use Icebreaker to free it from its", "icy encasement."),
            c("Wumpa", "Icy", 20, "LEGENDARY", "Will appear when you've caught at",
                    "least one of every other mob in the", "biome."));

    private static final Map<String, Critter> BY_NAME = new HashMap<>();
    private static final Map<String, Critter> BY_SLOT = new HashMap<>();

    static {
        for (Critter critter : ALL) {
            BY_NAME.put(critter.name().toLowerCase(Locale.ROOT), critter);
            BY_SLOT.put(slotKey(critter.biome(), critter.slot()), critter);
        }
    }

    private CritterCatalog() {
    }

    private static Critter c(String name, String biome, int slot, String rarity, String... howTo) {
        return new Critter(name, biome + " Biome", slot, rarity + " CRITTER", List.of(howTo));
    }

    private static String slotKey(String biome, int slot) {
        return biome.toLowerCase(Locale.ROOT) + "#" + slot;
    }

    /** All critters, in Critterdex order. */
    public static List<Critter> all() {
        return ALL;
    }

    /** The critter with this exact Critterdex name, ignoring case, or {@code null}. */
    public static Critter byName(String name) {
        return name == null ? null : BY_NAME.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** The critter at this slot of this biome's page (e.g. "Icy Biome"), or {@code null}. */
    public static Critter bySlot(String biome, int slot) {
        return biome == null ? null : BY_SLOT.get(slotKey(biome.trim(), slot));
    }
}
