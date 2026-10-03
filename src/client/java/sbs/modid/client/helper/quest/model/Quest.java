/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import sbs.modid.client.helper.quest.logic.QuestTracker;

/**
 * A quest definition, exactly as the bundled JSON spells it out.
 *
 * <p>Plain Gson POJOs: the whole definition – the step order, the waypoints, the required items –
 * is <b>data, not code</b>, read from {@code assets/skyblock-simplified-sbs/quests/} by
 * {@link sbs.modid.client.helper.quest.logic.QuestDatabase}. Adding or correcting a quest is
 * therefore a data edit, and it needs no licence token and no network.
 *
 * <p>Everything that happens <i>with</i> the definition (progress, chat parsing, item counting) is
 * client-side too – see {@link QuestTracker}.
 */
public final class Quest {

    public String id;
    public String name;
    public String description;
    public List<QuestStep> steps = new ArrayList<>();

    /**
     * Island id -> the {@code ⏣} sidebar areas that belong to it (Hub sub-areas all map to "hub",
     * because they share the Hub's coordinate space).
     *
     * <p>Part of the quest file rather than a table in code: Hypixel has dozens of sub-areas and
     * renames them, and a wrong name hardcoded in a class is a far worse place to correct it than a
     * JSON file next to the steps it belongs to. See {@link QuestIslands}.
     */
    public java.util.Map<String, List<String>> islands = new java.util.LinkedHashMap<>();

    /** Total steps, for the progress bar. */
    public int stepCount() {
        return steps == null ? 0 : steps.size();
    }

    /** One step, or {@code null} when the index is out of range (e.g. the quest is finished). */
    public QuestStep step(int index) {
        return steps != null && index >= 0 && index < steps.size() ? steps.get(index) : null;
    }

    /**
     * The position of a step by its {@linkplain QuestStep#id id}, or {@code -1} when this quest has
     * no such step.
     *
     * <p>This is the one direction the lookup is allowed to go. Stored progress names a step by id
     * and the position is derived on demand, so reordering or inserting steps moves the number
     * without moving anybody's progress. Going the other way - storing the position and looking up
     * the id - would reintroduce exactly the bug the ids exist to remove.
     */
    public int indexOfStep(String stepId) {
        if (steps == null || stepId == null) {
            return -1;
        }
        for (int i = 0; i < steps.size(); i++) {
            QuestStep step = steps.get(i);
            if (step != null && stepId.equals(step.id)) {
                return i;
            }
        }
        return -1;
    }

    /** The id of the step at a position, or {@code null} when the position is past the end. */
    public String stepIdAt(int index) {
        QuestStep step = step(index);
        return step == null ? null : step.id;
    }

    /** The id of the first step, or {@code null} when the quest has none. */
    public String firstStepId() {
        return stepIdAt(0);
    }

    /** One step of a quest. */
    public static final class QuestStep {

        /**
         * This step's stable identity. Assigned once and <b>never renumbered</b>.
         *
         * <p>A step used to be identified by its position in {@link Quest#steps}, which meant
         * inserting a step in the middle silently moved every stored player onto a different one -
         * the repository's standing identity rule, broken in the one place where breaking it costs
         * the player their progress rather than throwing. Progress now stores this string.
         *
         * <p>The shipped ids happen to run in step order ({@code rj_01}…{@code rj_30}) because that
         * is what the migration off the old bare index had to map through. <b>That is a fact about
         * when they were assigned, not a rule about what they mean.</b> A step inserted between
         * {@code rj_04} and {@code rj_05} takes the next unused number; it does not become a new
         * {@code rj_05}.
         */
        public String id;

        /** The line shown in the overlay checklist. Already English, straight from the server. */
        public String title;

        /** Optional extra context ("do NOT use Silk Touch"). */
        public String hint;

        /** Optional place to route to. */
        public QuestWaypoint waypoint;

        /** Optional item that must be collected for this step. */
        public QuestItem item;

        /** Optional non-item requirement (intelligence, worn armour). */
        public QuestRequirement requirement;

        /**
         * Chat lines that complete this step. Matched as a <b>substring</b> of the incoming line so
         * the {@code [NPC] Romero:} prefix, rank colours and the player's own name do not have to be
         * reproduced.
         */
        public List<String> advance_on = new ArrayList<>();

        /** Whether an incoming chat line completes this step. */
        public boolean advancedBy(String chatLine) {
            if (advance_on == null || chatLine == null) {
                return false;
            }
            String haystack = chatLine.toLowerCase(Locale.ROOT);
            for (String trigger : advance_on) {
                if (trigger != null && !trigger.isBlank()
                        && haystack.contains(trigger.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A place the quest points at; fed to the pathfinding module as a waypoint. */
    public static final class QuestWaypoint {
        public int x;
        public int y;
        public int z;
        public String label;

        /**
         * The island these coordinates belong to (see {@link Quest#islands}). Every SkyBlock island
         * shares one Minecraft dimension but has its own coordinate space, so without this the
         * route would happily lead to the Crimson Isle's numbers while standing on the Hub.
         */
        public String island;
    }

    /** An item the player has to bring. */
    public static final class QuestItem {
        /** SkyBlock item id, used for both the icon and counting the inventory. */
        public String id;
        /** Display name, shown when the id is unreadable ("ENCHANTED_HUGE_MUSHROOM_2"). */
        public String name;
        public int amount = 1;
    }

    /** A requirement that is not an item (a stat, or worn gear). */
    public static final class QuestRequirement {
        /** {@code intelligence} or {@code armor}. */
        public String type;
        public int amount;
        public String name;
    }
}
