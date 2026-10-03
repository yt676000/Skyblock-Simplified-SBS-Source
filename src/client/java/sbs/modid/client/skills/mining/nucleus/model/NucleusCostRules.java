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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The items a Nucleus run uses up, keyed by the chat line that says it happened.
 *
 * <p>Every line here is in the maintainer's chat logs (see {@link NucleusSignals}); matched on
 * colour-stripped, trimmed text. A rule books its cost one of three ways, in this order:
 * <ol>
 *   <li>{@link Rule#fixedItemId}: the line itself proves exactly one of that item went;</li>
 *   <li>{@link Rule#nameFromLine}: the line names the item (group 1), one of it went;</li>
 *   <li>otherwise an inventory decrease of one of {@link Rule#candidates} within
 *       {@link NucleusSignals#COST_WINDOW_MS} either side of the line - an empty set means any item -
 *       falling back to {@link Rule#fallbackItemId} x1 when none was seen.</li>
 * </ol>
 *
 * <p>Adding a cost is adding a rule. The Crystal Hollows pass is not here: no purchase line with a
 * price has been captured yet.
 */
public final class NucleusCostRules {

    private NucleusCostRules() {
    }

    /**
     * One cost line.
     *
     * @param id             stable id, used in logs and stored on each cost line of a run
     * @param line           the chat line, colour-stripped
     * @param fixedItemId    the one item the line proves was used, or {@code null}
     * @param nameFromLine   whether group 1 of {@code line} is the item's display name
     * @param candidates     ids an inventory decrease may be booked from; empty means any item
     * @param fallbackItemId booked x1 when no decrease was seen, or {@code null} to book nothing
     * @param certainty      how sure the booking is - the line is confirmed, the pairing may not be
     */
    public record Rule(String id, Pattern line, String fixedItemId, boolean nameFromLine,
                       Set<String> candidates, String fallbackItemId, Certainty certainty) {

        /** Whether this rule needs an inventory decrease to know what was used. */
        public boolean needsInventory() {
            return fixedItemId == null && !nameFromLine;
        }
    }

    public static final Set<String> GOBLIN_EGGS = Set.of(
            "GOBLIN_EGG", "GOBLIN_EGG_RED", "GOBLIN_EGG_YELLOW", "GOBLIN_EGG_GREEN", "GOBLIN_EGG_BLUE");

    public static final Set<String> ROBOT_PARTS = Set.of(
            "ELECTRON_TRANSMITTER", "FTX_3070", "ROBOTRON_REFLECTOR", "SUPERLITE_MOTOR",
            "CONTROL_SWITCH", "SYNTHETIC_HEART");

    public static final List<Rule> RULES = List.of(
            // Amethyst: the Jungle Temple door takes the key.
            new Rule("jungle_key",
                    NucleusSignals.GUARDIAN_KEY,
                    "JUNGLE_KEY", false, Set.of(), null, Certainty.CONFIRMED),
            // Amber: King Yolkar takes a Goblin Egg ("of any type", one or several) and then covers
            // you in his stench - this line follows every hand-in, the first and the repeat ones.
            new Rule("goblin_egg",
                    Pattern.compile("^\\[NPC] King Yolkar: I'm covering you in my foul stench\\b.*$"),
                    null, false, GOBLIN_EGGS, "GOBLIN_EGG", Certainty.ESTIMATED),
            // Sapphire: each robot part but the last is thanked by name.
            new Rule("robot_part",
                    Pattern.compile("^\\[NPC] Professor Robot: Thanks for bringing me the (.+?)!(?: .*)?$"),
                    null, true, Set.of(), null, Certainty.CONFIRMED),
            // ...the last one is not named.
            new Rule("robot_part_last",
                    Pattern.compile("^\\[NPC] Professor Robot: You've brought me all of the components\\b.*$"),
                    null, false, ROBOT_PARTS, null, Certainty.ESTIMATED),
            // ...or one item is handed in instead of the six. Which item has not been captured, so
            // whatever single item left the inventory around the line is booked.
            new Rule("robot_substitute",
                    Pattern.compile("^\\[NPC] Professor Robot: Wait a minute\\. This will work just fine\\.$"),
                    null, false, Set.of(), null, Certainty.ESTIMATED),
            // Finding the crystal areas: a compass breaks on its last use.
            new Rule("wishing_compass",
                    Pattern.compile("^Your Wishing Compass shattered into pieces!$"),
                    "WISHING_COMPASS", false, Set.of(), null, Certainty.CONFIRMED));
}
