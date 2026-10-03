/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.model;

/**
 * What kind of thing an Enigma Soul asks of the player.
 *
 * <p>Deliberately coarse. This is not a taxonomy of Rift puzzles - it is the handful of buckets a
 * player actually filters on ("hide the ones I need a friend for", "hide the ones that cost motes"),
 * plus enough shape for the marker to say what sort of thing it is in two words. Anything finer
 * belongs in {@link EnigmaSoul#instruction}, which is prose and can say anything.
 *
 * <p>Adding a constant is safe; renaming one is not, because the data file spells them out.
 */
public enum SoulRequirement {

    /** Walk to it and right-click. The simple case, and the default. */
    PICKUP("Pickup", "Right-click it"),

    /** A parkour course, a jump, a fall - it is where it looks, you just have to get there. */
    TRAVERSAL("Traversal", "Getting there is the puzzle"),

    /** A specific item must be used on something ({@link EnigmaSoul#requiredItem} names it). */
    ITEM("Needs an item", "Bring the item first"),

    /** Bought from an NPC, usually for motes plus materials. */
    PURCHASE("Purchase", "Bought, not found"),

    /** Talk to somebody, or do what they ask. */
    NPC("NPC", "Talk to somebody"),

    /** Stand, sit or wait somewhere for a while. */
    WAIT("Wait", "Takes time in one spot"),

    /** A puzzle or minigame with a right answer. */
    PUZZLE("Puzzle", "There is a right answer"),

    /** Follow a thing or a trail to where it ends. */
    FOLLOW("Follow", "Follow it to the end"),

    /** More than one player has to be there. Filtered separately via {@link EnigmaSoul#minPlayers}. */
    COOPERATION("Needs 2 players", "Cannot be done alone"),

    /** Something else has to be finished first. */
    PREREQUISITE("Prerequisite", "Finish something else first");

    private final String displayName;
    private final String hint;

    SoulRequirement(String displayName, String hint) {
        this.displayName = displayName;
        this.hint = hint;
    }

    public String displayName() {
        return displayName;
    }

    /** The one-line "what sort of thing is this" the marker shows when there is no instruction. */
    public String hint() {
        return hint;
    }

    /** Whether this kind is collectable by a player on their own, all else being equal. */
    public boolean soloable() {
        return this != COOPERATION;
    }
}
