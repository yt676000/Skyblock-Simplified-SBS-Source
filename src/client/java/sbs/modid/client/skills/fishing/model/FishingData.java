/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import sbs.modid.client.skills.fishing.logic.FishingTracker;

/**
 * What the fishing trackers know about SkyBlock: which chat lines announce a sea creature, and which
 * items are worth counting as fishing loot.
 *
 * <p>Hypixel has no "you caught X" API – a sea creature is only ever announced by its spawn message
 * in chat (sent only to the fisher who hooked it), so that message <b>is</b> the detection.
 *
 * <p>Spawn lines are matched by <b>exact full-sentence equality</b> on the colour-stripped text.
 * Every catch is one fixed sentence, so equality makes false positives structurally impossible:
 * combat lines ("You were thrown by the Alligator!"), immunity spam, death messages and players
 * named after creatures simply are not these sentences. The earlier fragment matching needed a
 * growing pile of guard heuristics and still miscounted in both directions. A reworded line fails
 * quietly, so an exact miss that still smells like a spawn sentence is logged for retabling.
 */
public final class FishingData {

    private FishingData() {
    }

    /** Exact spawn sentence (colour-stripped, trimmed) -> the sea creature it announces. */
    private static final Map<String, String> SPAWN_LINES = new LinkedHashMap<>();

    private static void line(String creature, String message) {
        SPAWN_LINES.put(message, creature);
    }

    static {
        // Water (everywhere)
        line("Squid", "A Squid appeared.");
        line("Sea Walker", "You caught a Sea Walker.");
        line("Sea Guardian", "You stumbled upon a Sea Guardian.");
        line("Sea Archer", "You reeled in a Sea Archer.");
        line("Rider of the Deep", "The Rider of the Deep has emerged.");
        line("Sea Witch", "It looks like you've disrupted the Sea Witch's brewing session. Watch out, she's furious!");
        line("Catfish", "Huh? A Catfish!");
        line("Sea Leech", "Gross! A Sea Leech!");
        line("Guardian Defender", "You've discovered a Guardian Defender of the sea.");
        line("Deep Sea Protector", "You have awoken the Deep Sea Protector, prepare for a battle!");
        line("Water Hydra", "The Water Hydra has come to test your strength.");
        line("Carrot King", "Is this even a fish? It's the Carrot King!");
        line("Night Squid", "Pitch darkness reveals a Night Squid.");
        line("Agarimoo", "Your Chumcap Bucket trembles, it's an Agarimoo.");
        // Retired from the game as far as current data shows - exact lines cannot false-positive,
        // so keeping them costs nothing and covers a return.
        line("Sea Emperor", "The Sea Emperor arises from the depths.");
        line("Monster of the Deep", "The Monster of the Deep has emerged.");
        // Winter Island
        line("Frozen Steve", "Frozen Steve fell into the pond long ago, never to resurface...until now!");
        line("Frosty", "It's a snowman! He looks harmless.");
        line("Grinch", "The Grinch stole Jerry's Gifts...get them back!");
        line("Nutcracker", "You found a forgotten Nutcracker laying beneath the ice.");
        line("Yeti", "What is this creature!?");
        line("Reindrake", "A Reindrake forms from the depths.");
        // Spooky Festival
        line("Scarecrow", "Phew! It's only a Scarecrow.");
        line("Nightmare", "You hear trotting from beneath the waves, you caught a Nightmare.");
        line("Werewolf", "It must be a full moon, a Werewolf appears.");
        line("Phantom Fisher", "The spirit of a long lost Phantom Fisher has come to haunt you.");
        line("Grim Reaper", "This can't be! The manifestation of death himself!");
        line("Jumpin' Jack", "Watch out! It's Jumpin' Jack.");
        // Fishing Festival sharks
        line("Nurse Shark", "A tiny fin emerges from the water, you've caught a Nurse Shark.");
        line("Blue Shark", "You spot a fin as blue as the water it came from, it's a Blue Shark.");
        line("Tiger Shark", "A striped beast bounds from the depths, the wild Tiger Shark!");
        line("Great White Shark", "Hide no longer, a Great White Shark has tracked your scent and thirsts for your blood!");
        // Oasis (Farming Islands)
        line("Oasis Sheep", "An Oasis Sheep appears from the water.");
        line("Oasis Rabbit", "An Oasis Rabbit appears from the water.");
        // Abandoned Quarry (Mithril fishing)
        line("Small Mithril Grubber", "A leech of the mines surfaces... you've caught a Mithril Grubber.");
        line("Medium Mithril Grubber", "A leech of the mines surfaces... you've caught a Medium Mithril Grubber.");
        line("Large Mithril Grubber", "A leech of the mines surfaces... you've caught a Large Mithril Grubber.");
        line("Bloated Mithril Grubber", "A leech of the mines surfaces... you've caught a Bloated Mithril Grubber.");
        // Magma Fields / Precursor / Goblin Burrows / Crystal Hollows water
        line("Lava Blaze", "A Lava Blaze has surfaced from the depths!");
        line("Lava Pigman", "A Lava Pigman arose from the depths!");
        line("Flaming Worm", "A Flaming Worm surfaces from the depths!");
        line("Water Worm", "A Water Worm surfaces!");
        line("Poisoned Water Worm", "A Poisoned Water Worm surfaces!");
        line("Abyssal Miner", "An Abyssal Miner breaks out of the water!");
        // Crimson Isle lava
        line("Moogma", "You hear a faint Moo from the lava... A Moogma appears.");
        line("Magma Slug", "From beneath the lava appears a Magma Slug.");
        line("Pyroclastic Worm", "You feel the heat radiating as a Pyroclastic Worm surfaces.");
        line("Lava Flame", "A Lava Flame flies out from beneath the lava.");
        line("Fire Eel", "A Fire Eel slithers out from the depths.");
        line("Lava Leech", "A small but fearsome Lava Leech emerges.");
        line("Taurus", "Taurus and his steed emerge.");
        line("Thunder", "You hear a massive rumble as Thunder emerges.");
        line("Lord Jawbus", "You have angered a legendary creature... Lord Jawbus has arrived.");
        line("Plhlegblast", "WOAH! A Plhlegblast appeared.");
        // Backwater Bayou
        line("Trash Gobbler", "The Trash Gobbler is hungry for you!");
        line("Banshee", "The desolate wail of a Banshee breaks the silence.");
        line("Alligator", "A long snout breaks the surface of the water. It's an Alligator!");
        line("Dumpster Diver", "A Dumpster Diver has emerged from the swamp!");
        line("Bayou Sludge", "A swampy mass of slime emerges, the Bayou Sludge!");
        line("Titanoboa", "A massive Titanoboa surfaces. Its body stretches as far as the eye can see.");
        // Lava Hotspots
        line("Ragnarok", "The sky darkens and the air thickens. The end times are upon us: Ragnarok is here.");
        line("Volcanic Snail", "You feel a burning sensation as you reel in a Volcanic Snail!");
        line("Fireproof Witch", "Trouble's brewing, it's a Fireproof Witch!");
        line("Fried Chicken", "Smells of burning. Must be a Fried Chicken.");
        line("Magma Pillar", "A Magma Pillar rises from the lava.");
        line("Fiery Scuttler", "A Fiery Scuttler inconspicuously waddles up to you, friends in tow.");
        // Water Hotspots
        line("Wiki Tiki", "The water bubbles and froths. A massive form emerges- you have disturbed the Wiki Tiki! You shall pay the price.");
        line("Blue Ringed Octopus", "A garish set of tentacles arise. It's a Blue Ringed Octopus!");
        line("Snapping Turtle", "A Snapping Turtle is coming your way, and it's ANGRY!");
        line("Frog Man", "Is it a frog? Is it a man? Well, yes, sorta, IT'S FROG MAN!!!!!!");
        line("Inkling", "You get an inkling that you've caught... an Inkling!");
        line("Manta Ray", "A majestic creature rises from the water. It's a Manta Ray.");
        // Galatea (Loch)
        line("Wetwing", "Look! A Wetwing emerges!");
        line("Ent", "You've hooked an Ent, as ancient as the forest itself.");
        line("Tadgang", "A gang of Liltads!");
        line("Bogged", "You've hooked a Bogged!");
        line("Stridersurfer", "You caught a Stridersurfer.");
        line("The Loch Emperor", "The Loch Emperor arises from the depths.");
        line("Nessie", "You've caused a disturbance in the loch. Could it be... Nessie?");
        // Lotus Atoll
        line("Atoll Croaker", "An inquisitive Atoll Croaker takes the bait!");
        line("Lotus Guardian", "A Lotus Guardian emerges, ready to protect the Atoll.");
        line("gorF", "What even is that?! A... gorF?");
        line("Drowned Captain", "A Drowned Captain takes hold of your bobber!");
        line("Puddle Jumper", "A Puddle Jumper is preparing for liftoff—cast your rod into it and hold on tight!");
        line("Frog Prince", "Bow down before the Frog Prince... or pay the hefty price!");
    }

    /**
     * Words that only appear inside genuine spawn sentences (lowercase). Used purely as a
     * <b>diagnostic</b>: an exact miss that still contains one of these is most likely a spawn line
     * Hypixel reworded, and is logged so the table can be fixed from the log – it never counts.
     */
    private static final List<String> SPAWNISH_FRAGMENTS = List.of(
            "sea walker", "sea guardian", "sea archer", "sea witch", "sea leech", "guardian defender",
            "deep sea protector", "water hydra", "carrot king", "night squid", "agarimoo",
            "mithril grubber", "lava blaze", "lava pigman", "flaming worm", "water worm",
            "abyssal miner", "moogma", "magma slug", "pyroclastic worm", "lava flame", "fire eel",
            "lava leech", "jawbus", "plhlegblast", "trash gobbler", "banshee", "alligator",
            "dumpster diver", "bayou sludge", "titanoboa", "ragnarok", "volcanic snail",
            "fireproof witch", "fried chicken", "magma pillar", "fiery scuttler", "wiki tiki",
            "blue ringed octopus", "snapping turtle", "frog man", "inkling", "manta ray", "wetwing",
            "tadgang", "bogged", "stridersurfer", "loch emperor", "nessie", "atoll croaker",
            "lotus guardian", "drowned captain", "puddle jumper", "frog prince", "reindrake",
            "nutcracker", "phantom fisher", "grim reaper", "nurse shark", "blue shark", "tiger shark",
            "great white shark", "oasis sheep", "oasis rabbit", "squid appeared", "catfish");

    /**
     * Sea creatures with no spawn sentence of their own (spawned by other means): part of
     * {@link #allSeaCreatures()} for the mob highlight, but the catch tracker cannot count them.
     */
    private static final List<String> NAMETAG_ONLY = List.of("Chill", "Lotum", "Baby Magma Slug");

    /**
     * How rare each sea creature is – the in-game tiers. Anything not listed (incl. the game's
     * UNCOMMON tier) is {@link SeaCreatureRarity#COMMON}: filler below every alert threshold.
     */
    private static final Map<String, SeaCreatureRarity> RARITIES = new LinkedHashMap<>();

    static {
        rarity(SeaCreatureRarity.MYTHIC,
                "Thunder", "Lord Jawbus", "Plhlegblast", "Reindrake", "Nessie", "Ragnarok",
                "Titanoboa", "Wiki Tiki", "Frog Prince", "Sea Emperor");
        rarity(SeaCreatureRarity.LEGENDARY,
                "Water Hydra", "Yeti", "Great White Shark", "Phantom Fisher", "Grim Reaper",
                "Abyssal Miner", "Alligator", "Blue Ringed Octopus", "Fiery Scuttler",
                "Puddle Jumper", "The Loch Emperor", "Vanquisher");
        rarity(SeaCreatureRarity.EPIC,
                "Deep Sea Protector", "Guardian Defender", "Nutcracker", "Werewolf", "Tiger Shark",
                "Manta Ray", "Bayou Sludge", "Drowned Captain", "Ent", "Magma Pillar");
        rarity(SeaCreatureRarity.RARE,
                "Carrot King", "Catfish", "Sea Leech", "Nightmare", "Taurus", "Fire Eel",
                "Fireproof Witch", "Flaming Worm", "Lava Blaze", "Lava Flame", "Lava Leech",
                "Lava Pigman", "Magma Slug", "Baby Magma Slug", "Moogma", "Pyroclastic Worm",
                "Water Worm", "Poisoned Water Worm", "Agarimoo", "Banshee", "Snapping Turtle",
                "Stridersurfer", "Tadgang", "gorF", "Monster of the Deep");
    }

    private static void rarity(SeaCreatureRarity tier, String... creatures) {
        for (String creature : creatures) {
            RARITIES.put(creature, tier);
        }
    }

    /** The tier of {@code creature}; unlisted creatures are {@link SeaCreatureRarity#COMMON}. */
    public static SeaCreatureRarity rarityOf(String creature) {
        return RARITIES.getOrDefault(creature, SeaCreatureRarity.COMMON);
    }

    /** The exact double-hook lines (colour-stripped); a spacing variant has been seen in the wild. */
    private static final Set<String> DOUBLE_HOOK_LINES = Set.of(
            "It's a Double Hook! Woot woot!",
            "It's a Double Hook ! Woot woot!",
            "Double Hook! Woot woot!");

    /**
     * The line Hypixel sends when one cast hooks two sea creatures at once – exact match, so a
     * "double hook" mention in normal chat can never double a catch. It announces no creature of
     * its own; it doubles whichever spawn line follows it.
     */
    public static boolean isDoubleHook(String message) {
        return message != null && DOUBLE_HOOK_LINES.contains(stripCodes(message).trim());
    }

    /**
     * The sea creature announced by {@code message}, or null. Exact full-sentence equality on the
     * trimmed, colour-stripped line – no fragments, no guard heuristics needed: only the genuine
     * spawn sentence ever equals a table entry.
     *
     * <p>Colour codes are stripped here too: {@code Component.getString()} drops style-based
     * formatting, but Hypixel sometimes embeds legacy §-codes mid-sentence in the literal text,
     * and those must not break the equality.
     */
    public static String seaCreatureFor(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        String trimmed = stripCodes(message).trim();
        String creature = SPAWN_LINES.get(trimmed);
        if (creature != null) {
            return creature;
        }
        warnIfRewordedSpawnLine(trimmed);
        return null;
    }

    /** Removes legacy §-formatting sequences (char loop – no regex on the chat hot path). */
    private static String stripCodes(String text) {
        int section = text.indexOf((char) 0x00A7);
        if (section < 0) {
            return text;   // common case: nothing to strip, no allocation
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 0x00A7 && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static long lastRewordWarnAt;

    /**
     * Diagnostic only: an exact miss that still contains a creature-specific phrase is most likely a
     * spawn sentence Hypixel reworded – log it (throttled) so the table can be fixed from the log.
     * Chat/death/shard lines are cheaply excluded; a wrong warn costs a log line, never a count.
     */
    private static void warnIfRewordedSpawnLine(String line) {
        long now = System.currentTimeMillis();
        if (now - lastRewordWarnAt < 5_000L || line.length() > 140 || line.indexOf(':') >= 0) {
            return;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("shard") || lower.contains("you died") || lower.contains("slain by")
                || lower.contains("thrown by") || lower.contains("immune")) {
            return;
        }
        for (String fragment : SPAWNISH_FRAGMENTS) {
            if (lower.contains(fragment)) {
                lastRewordWarnAt = now;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Fishing] possible reworded spawn line (not counted): '{}'", line);
                return;
            }
        }
    }

    /** Every sea creature this build knows (chat-detected and nametag-only alike), de-duplicated. */
    public static List<String> allSeaCreatures() {
        Set<String> all = new LinkedHashSet<>(SPAWN_LINES.values());
        all.addAll(NAMETAG_ONLY);
        return List.copyOf(new ArrayList<>(all));
    }

    /**
     * Every known creature keyed by its lower-cased name, built once from {@link #allSeaCreatures()}.
     * The nametag path needs "is this string one of ours, whatever its casing" as a map lookup, not
     * as a scan of a hundred names on every armor stand in range.
     */
    private static final Map<String, String> BY_LOWER_NAME = new LinkedHashMap<>();

    static {
        for (String creature : allSeaCreatures()) {
            BY_LOWER_NAME.put(creature.toLowerCase(Locale.ROOT), creature);
        }
    }

    /**
     * The table's own spelling of {@code name}, or null when this build has never heard of it.
     *
     * <p>Case-insensitive, because the source is a nametag rather than a chat sentence: the name a
     * stand carries is styled and occasionally cased differently from the spawn line's. Returning
     * the <b>table's</b> spelling rather than what was read is the point – ids, counters and the
     * per-creature settings are all keyed on the canonical name, so a nametag must resolve to it or
     * to nothing at all.
     */
    public static String canonicalSeaCreature(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return BY_LOWER_NAME.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether {@code itemId} is a fishing bait – consumed by casting, so cost rather than loot. */
    public static boolean isBait(String itemId) {
        return itemId != null && itemId.toUpperCase(Locale.ROOT).endsWith("_BAIT");
    }

    /**
     * One trophy fish: its base id (the fragment every tier's item id contains) and the name the
     * game prints for it. This is the only trophy list in the mod - the profit filter, the Trophy
     * Fish tracker's grid and its chat parser all read it.
     *
     * <p><b>Unverified:</b> the 18 names are the wiki's, not read off a live menu or chat line, and
     * the Golden Fish base id is a guess (the other 17 predate the tracker). The tracker's spec lists
     * the probe that settles them.
     */
    public record TrophyFish(String baseId, String name) {

        /** The key the Hypixel profile API and the Player Viewer use: the base id, lower case. */
        public String apiKey() {
            return baseId.toLowerCase(Locale.ROOT);
        }
    }

    /** Every trophy fish, in the Crimson Isle's usual rarity order (commonest first). */
    public static final List<TrophyFish> TROPHY_FISH_LIST = List.of(
            new TrophyFish("BLOBFISH", "Blobfish"),
            new TrophyFish("FLYFISH", "Flyfish"),
            new TrophyFish("GOLDEN_FISH", "Golden Fish"),
            new TrophyFish("GUSHER", "Gusher"),
            new TrophyFish("KARATE_FISH", "Karate Fish"),
            new TrophyFish("LAVAHORSE", "Lavahorse"),
            new TrophyFish("MANA_RAY", "Mana Ray"),
            new TrophyFish("MOLDFIN", "Moldfin"),
            new TrophyFish("OBFUSCATED_FISH_1", "Obfuscated 1"),
            new TrophyFish("OBFUSCATED_FISH_2", "Obfuscated 2"),
            new TrophyFish("OBFUSCATED_FISH_3", "Obfuscated 3"),
            new TrophyFish("SKELETON_FISH", "Skeleton Fish"),
            new TrophyFish("SLUGFISH", "Slugfish"),
            new TrophyFish("SOUL_FISH", "Soul Fish"),
            new TrophyFish("STEAMING_HOT_FLOUNDER", "Steaming-Hot Flounder"),
            new TrophyFish("SULPHUR_SKITTER", "Sulphur Skitter"),
            new TrophyFish("VANILLE", "Vanille"),
            new TrophyFish("VOLCANIC_STONEFISH", "Volcanic Stonefish"));

    /**
     * The trophy fish a printed name refers to, or {@code null}. Case, hyphens and spacing are
     * ignored, so "Steaming Hot Flounder" and "steaming-hot flounder" both resolve.
     */
    public static TrophyFish trophyByName(String name) {
        if (name == null) {
            return null;
        }
        String key = trophyNameKey(name);
        for (TrophyFish fish : TROPHY_FISH_LIST) {
            if (trophyNameKey(fish.name()).equals(key)) {
                return fish;
            }
        }
        return null;
    }

    private static String trophyNameKey(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Whether {@code itemId} is a trophy fish, in any tier. Trophy fish are their own collection
     * (Odger / the Trophy Fishing menu), not something you sell, so the profit tracker leaves them
     * out entirely – counting a Diamond Mana Ray as "profit" would just be noise. Fragment-matched so
     * a tiered id ({@code BRONZE_MANA_RAY}, {@code GOLD_GUSHER}) is still recognised.
     */
    public static boolean isTrophyFish(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = itemId.toUpperCase(Locale.ROOT);
        for (TrophyFish fish : TROPHY_FISH_LIST) {
            if (id.contains(fish.baseId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code itemId} is a fishing rod – the tool you fish <b>with</b>, never something you
     * fish <b>up</b>. Suffix-matched ({@code FISHING_ROD}, {@code PRISMARINE_ROD}, {@code YETI_ROD},
     * ...) plus the {@code ROD_OF_*} line (Rod of the Sea / Legends / Champions). The two vanilla
     * "rod" <i>materials</i> are excepted: a Blaze Rod is what a Lava Blaze drops and an End Rod is
     * a block – both are loot, not tools.
     *
     * <p>Rods that genuinely DROP from sea creatures (Yeti Rod, Phantom Rod, the Shredder) still
     * count: their "RARE DROP!" chat line books them through the chat channel, which does not
     * consult this – only the physical-arrival channels (inventory diff, sack summary) refuse rods,
     * so the held rod streaming in after a lobby swap, a crafted or claimed rod can never book.
     */
    public static boolean isRod(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = itemId.toUpperCase(Locale.ROOT);
        if ("BLAZE_ROD".equals(id) || "END_ROD".equals(id)) {
            return false;   // vanilla drop / block, not a fishing tool
        }
        return id.endsWith("_ROD") || id.startsWith("ROD_OF");
    }

    /**
     * Head-slot skull items that double as sea-creature drops: worn on the head, they stream back in
     * with the rest of the inventory after every lobby swap, and their names match the loot
     * fragments ("HYDRA", "EMPEROR"), which booked the <i>worn</i> piece as a fresh catch. As drops
     * they are always announced ("RARE DROP! Water Hydra Head"), so the chat channel still books
     * the genuine catch.
     */
    private static final List<String> WORN_SKULLS = List.of(
            "WATER_HYDRA_HEAD", "EMPEROR_SKULL", "EMPERORS_SKULL", "TIKI_MASK");

    /**
     * Whether {@code itemId} is an armor piece or other wearable. Worn gear re-streams into the
     * inventory on every lobby swap, and fishing armor is named after the creatures it comes from
     * (Thunder, Shark Scale, Squid Boots) – so the fragment match read the player's own suit as
     * loot. Genuine armor drops are all chat-announced ("RARE DROP! Squid Boots") and keep booking
     * through the chat channel.
     */
    public static boolean isWearable(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = itemId.toUpperCase(Locale.ROOT);
        return id.endsWith("_HELMET") || id.endsWith("_CHESTPLATE") || id.endsWith("_LEGGINGS")
                || id.endsWith("_BOOTS") || WORN_SKULLS.contains(id);
    }

    /**
     * Items the physical-arrival channels (inventory diff, sack summary) must never book as loot:
     * <ul>
     *   <li>the SkyBlock Menu item – opening the menu re-hands it to the inventory, which the diff
     *       would otherwise read as a catch;</li>
     *   <li>baits – they are the cost side of a session ({@link FishingTracker#baitsUsed()}), and a
     *       {@code /gfs} refill or a stack stashed into a sack must not become "profit";</li>
     *   <li>fishing rods ({@link #isRod}) and wearables ({@link #isWearable}) – equipment is used,
     *       worn and carried, never fished up; the held rod or worn armor streaming back in after a
     *       lobby swap, or a crafted/claimed rod, booked as a catch. Rod and armor <b>drops</b>
     *       (Yeti Rod, Squid Boots) are always chat-announced and book from that line instead;</li>
     *   <li>minions and sack items – a Fishing Minion or a Fishing Sack contains "FISH" and matched
     *       the loot fragments, but neither is ever fished up (the Ink Sack, which IS fished, stays
     *       loot).</li>
     * </ul>
     *
     * <p>The chat channel ("RARE DROP!" lines) deliberately bypasses this: an announced drop is
     * proof of a genuine catch, whatever kind of item it is.
     */
    public static boolean isNeverLoot(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = itemId.toUpperCase(Locale.ROOT);
        return isBait(id) || "SKYBLOCK_MENU".equals(id)
                || isRod(id) || isWearable(id)
                || id.endsWith("_MINION")
                || (id.endsWith("_SACK") && !id.contains("INK"));
    }

    /**
     * Whether {@code itemId} is an attribute shard ({@code SHARD_*} is the Bazaar id convention;
     * {@code *_SHARD} catches older shard-style item ids). Drives the Shard section of the HUD.
     */
    public static boolean isShard(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = itemId.toUpperCase(Locale.ROOT);
        return id.startsWith("SHARD_") || id.endsWith("_SHARD");
    }

    /**
     * Whether {@code itemId} is fishing loot worth tracking – i.e. anything a rod, a sea creature or
     * a shard can put in your inventory.
     *
     * <p>Deliberately a prefix/suffix rule rather than a fixed list: the fishing item pool changes
     * every update, and a list would quietly stop counting the new drops.
     */
    public static boolean isFishingLoot(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = itemId.toUpperCase(Locale.ROOT);
        if (isShard(id)) {
            return true;
        }
        for (String fragment : LOOT_FRAGMENTS) {
            if (id.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Id fragments that mark an item as fishing loot. Equipment is filtered BEFORE these are
     * consulted ({@link #isNeverLoot}), so a fragment may safely share a name with a rod or an
     * armor set – "FISH" no longer books a FISHING_ROD.
     */
    private static final List<String> LOOT_FRAGMENTS = List.of(
            "MAGMA_FISH", "LAVA", "SEA_", "SHARK", "SPONGE", "PEARL", "CLAY",
            "PRISMARINE", "INK_SACK", "WATER_LILY", "FISH", "SQUID", "GUARDIAN",
            "WORM", "CATFISH", "FLYFISH", "GUSHER", "KARATE", "BLOBFISH",
            "SULPHUR", "MOOGMA", "NUTCRACKER", "REINDRAKE", "JAWBUS", "THUNDER",
            "TAURUS", "AGARIMOO", "HYDRA", "EMPEROR", "WITCH", "ARCHER",
            // Galatea treasure: announced as "GOOD CATCH! You caught a Flexbone!" - that line is
            // recognition only, so the physical arrival must qualify as fishing loot to book (both
            // id spellings covered, whichever the NBT uses).
            "FLEXBONE", "FLEX_BONE",
            // Classic water loot the old list missed: Raw Salmon, and the zombie-flavoured sea
            // creatures' (Sea Walker, Drowned) Rotten Flesh.
            "SALMON", "ROTTEN_FLESH",
            // Crimson Isle sea creatures: Magma Cream (Magma Slug line), Blaze Rods (Lava Blaze -
            // the one "rod" that is loot, see isRod), Lord Jawbus' Radioactive Vial.
            "MAGMA_CREAM", "BLAZE_ROD", "RADIOACTIVE_VIAL",
            // Backwater Bayou: Titanoboa Shed / Snake Eyes (Titanoboa), Bobbin' Scriptures
            // (Titanoboa / Wiki Tiki), Troubled Bubble (Wiki Tiki), Bronze Bowl (Dumpster Diver),
            // Torn Cloth (Banshee), and the Junk Sinker junk that is still fished-up value.
            "TITANOBOA", "SNAKE_EYE", "BOBBIN", "TROUBLED_BUBBLE", "BRONZE_BOWL",
            "TORN_CLOTH", "MOBY_DUCK", "RUSTY_COIN", "BELT_BUCKLE", "LEATHER_BOOT",
            // Titanoboa's Enchanted / Condensed Lily Pad ids (WATER_LILY covers the vanilla-id form).
            "LILY_PAD",
            // Galatea / Loch: Sea Lumies are covered by SEA_; Gill Membrane, the Loch Emperor's
            // Mangcore, Sturdy Bone, and the fished RNGesus dyes (ids run DYE_AQUAMARINE-style).
            "GILL_MEMBRANE", "MANGCORE", "STURDY_BONE", "AQUAMARINE", "CARMINE",
            // Oasis sea creatures (Oasis Rabbit / Sheep) drop their animal's vanilla loot.
            "MUTTON", "RABBIT_FOOT", "RABBIT_HIDE");
}
