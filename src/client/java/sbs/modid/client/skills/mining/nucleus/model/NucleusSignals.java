/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.model;

import sbs.modid.client.helper.rift.model.Certainty;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Every chat line the Nucleus Run tracker reads, and every window it waits, with how sure we are.
 *
 * <p>The wording was taken 2026-10-03 from the maintainer's own chat logs - 95 bundles between
 * 2025-04 and 2025-12, logged by the old 1.8.9 client on the same Hypixel server. The text itself is
 * the server's and is {@link Certainty#CONFIRMED}; what that client lost is every non-ASCII glyph
 * (logged as a literal {@code ?}) and most {@code §} codes. So every pattern here runs on
 * colour-stripped, trimmed text and tolerates exactly one leading glyph plus a space, whatever that
 * glyph is. Re-capture on this client with the {@code [SBS][Nucleus]} log and tighten if wanted.
 */
public final class NucleusSignals {

    private NucleusSignals() {
    }

    /** One non-letter, non-digit glyph and a space before a name: {@code ✦ }, {@code ❈ }, log {@code ? }. */
    public static final Pattern GLYPH_PREFIX = Pattern.compile("^[^\\p{L}\\p{N}\\s+\\[(]\\s+");

    // ------------------------------------------------------------------ crystals

    /** {@code ✦ CRYSTAL FOUND (3/5)} - the header of a found block; the crystal's name follows. */
    public static final Pattern CRYSTAL_FOUND = Pattern.compile("^CRYSTAL FOUND \\((\\d)/5\\)$");
    /** {@code Sapphire Crystal} - the second line of a found block. */
    public static final Pattern CRYSTAL_NAME =
            Pattern.compile("^(Jade|Amber|Amethyst|Sapphire|Topaz) Crystal$");
    /** {@code ✦ You placed the Sapphire Crystal!} - one per crystal at the Nucleus. */
    public static final Pattern CRYSTAL_PLACED =
            Pattern.compile("^You placed the (Jade|Amber|Amethyst|Sapphire|Topaz) Crystal!$");
    public static final Certainty CRYSTAL_CERTAINTY = Certainty.CONFIRMED;

    /**
     * The scoreboard zone around the Nucleus itself, as {@code SkyBlockLocation.zone()} reports it
     * (seeded in {@code core/keybind/IslandCatalog}). Arriving there with a crystal in hand is the Mole
     * reminder's early trigger.
     */
    public static final String NUCLEUS_ZONE = "Crystal Nucleus";

    /** How long after a found header its crystal-name line may still arrive. */
    public static final long FOUND_NAME_WINDOW_MS = 2_000L;

    // ------------------------------------------------------------------ reward blocks

    /** The bundle header, printed when the fifth crystal is placed. Centred with spaces. */
    public static final Pattern BUNDLE_HEADER = Pattern.compile("^CRYSTAL NUCLEUS LOOT BUNDLE$");
    /** Crystal Hollows treasure chest headers - the run-loot bucket's explicit source. */
    public static final Pattern CHEST_HEADER =
            Pattern.compile("^(?:CHEST LOCKPICKED|LOOT CHEST COLLECTED)$");
    /** The sub-header inside both blocks; carries no item. */
    public static final Pattern REWARDS = Pattern.compile("^REWARDS$");
    /** A separator rule (▬ in game, {@code ?} in the old log) closes a block once it has content. */
    public static final Pattern SEPARATOR = Pattern.compile("^[\\u25AC\\-=_?\\s]{20,}$");
    public static final Certainty REWARD_BLOCK_CERTAINTY = Certainty.CONFIRMED;

    /** A block with no separator closes this long after its last line. */
    public static final long BLOCK_IDLE_MS = 3_000L;

    /** {@code +800 HOTM Exp} / {@code +1,200 HOTM Experience} - not coins. */
    public static final Pattern HOTM_XP =
            Pattern.compile("^\\+([\\d,.]+[kKmM]?) HOTM Exp(?:erience)?$");
    /** {@code Gemstone Powder x1,315} - the name-first powder shape both blocks use. */
    public static final Pattern POWDER_NAME_FIRST =
            Pattern.compile("^(Gemstone|Mithril|Glacite) Powder(?: x([\\d,]+))?$");
    /** {@code +1,315 Gemstone Powder} - tolerated, not seen in the logs. */
    public static final Pattern POWDER_AMOUNT_FIRST =
            Pattern.compile("^\\+?([\\d,]+) (Gemstone|Mithril|Glacite) Powder$");
    /** {@code Flawed Ruby Gemstone x30}, {@code Pickonimbus 2000}, {@code Enchanted Book (Lapidary I)}. */
    public static final Pattern ITEM = Pattern.compile("^(.+?)(?: x([\\d,]+))?$");

    // ------------------------------------------------------------------ sacks and stash

    /** {@code [Sacks] +155 items. (Last 7s.)} - the breakdown is in the hover. */
    public static final Pattern SACKS = Pattern.compile("^\\[Sacks] ");
    /** Items pulled out of the stash land in sacks without any container being open. */
    public static final Pattern STASH_PICKUP = Pattern.compile(
            "^You picked up (?:[\\d,]+|all) items from your (?:item|material) stash!$");
    public static final Certainty SACKS_CERTAINTY = Certainty.CONFIRMED;

    /** Chest items expected to reach a sack, held this long for the matching {@code [Sacks]} gain. */
    public static final long CHEST_ARRIVAL_MS = 120_000L;
    /** Bundle items wait for the pickup, which can be much later - even after a relog. */
    public static final long BUNDLE_ARRIVAL_MS = 6L * 60 * 60 * 1000;
    /** Slack around a {@code [Sacks]} message's own {@code (Last Ns.)} window. */
    public static final long SACK_WINDOW_SLACK_MS = 1_500L;

    // ------------------------------------------------------------------ Jungle Temple guardian

    /** The guardian takes a Jungle Key - also the {@code jungle_key} cost line. */
    public static final Pattern GUARDIAN_KEY =
            Pattern.compile("^\\[NPC] Kalhuiki Door Guardian: A Jungle Key! I will open the door for you\\.$");
    /** Said after the key, when the door opens. */
    public static final Pattern GUARDIAN_DOOR_OPEN =
            Pattern.compile("^\\[NPC] Kalhuiki Door Guardian: The door is open, go in!$");
    /** Said when this lobby's temple has nothing to give. */
    public static final Pattern GUARDIAN_NO_TREASURE = Pattern.compile(
            "^\\[NPC] Kalhuiki Door Guardian: This temple does not have the treasure you are seeking at this time\\.$");
    /** Every guardian line: receiving one means the player is standing at the temple door. */
    public static final List<Pattern> GUARDIAN_LINES =
            List.of(GUARDIAN_KEY, GUARDIAN_DOOR_OPEN, GUARDIAN_NO_TREASURE);
    public static final Certainty GUARDIAN_LINE_CERTAINTY = Certainty.CONFIRMED;
    /**
     * The guardian's name as an entity or its nametag shows it, colour-stripped. The words are
     * confirmed from chat; that the nametag reads the same has not been captured, hence the tolerant
     * match.
     */
    public static final Pattern GUARDIAN_NAME = Pattern.compile("(?i)\\bkalhuiki\\s+door\\s+guardian\\b");

    // ------------------------------------------------------------------ costs

    /** How far either side of a cost line an inventory decrease still belongs to it. */
    public static final long COST_WINDOW_MS = 3_000L;

    /** How long after a bundle the capture log records inventory and sack changes. */
    public static final long BUNDLE_CAPTURE_MS = 5_000L;

    /**
     * What the capture log records on the Crystal Hollows: anything that may belong to a run. Broad on
     * purpose - it only ever logs.
     */
    public static final Pattern CAPTURE = Pattern.compile(
            "(?i)crystal|nucleus|bundle|placed|chest|treasure|loot|\\[sacks]|stash|gwendolyn|\\bpass\\b"
                    + "|goblin egg|jungle key|automaton|professor robot|king yolkar|kalhuiki|keeper of"
                    + "|wishing compass|powder|hotm");
}
