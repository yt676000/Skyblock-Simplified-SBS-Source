/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a saved layout - a list of element ids - into the rows the panel actually draws.
 *
 * <p>The one place ordering happens, shared by the renderer and by the editor's live preview, so what
 * the editor shows and what the HUD draws can never drift apart.
 *
 * <h2>The rules, in the order they matter</h2>
 * <ul>
 *   <li><b>No layout means no reordering.</b> An empty order is a player who never opened the editor;
 *       the server's own order is left exactly as it arrived.</li>
 *   <li><b>An element that is not on screen right now keeps its slot.</b> The walk is over the saved
 *       ids, not over the live rows, so a dungeon row absent in the Hub simply contributes nothing -
 *       no gap, and nothing below it shifts.</li>
 *   <li><b>Everything unplaced lands at one predictable place</b>, the {@link
 *       ScoreboardElements#UNRECOGNIZED} slot the player positioned once. This is the bug this whole
 *       mechanism exists for: a row whose id the layout has never heard of used to be appended at the
 *       bottom, every single time its text changed.</li>
 *   <li><b>Spacing is the player's.</b> Once a layout exists, the server's own blank rows are dropped
 *       and only the blanks and rules the player placed are drawn - otherwise Hypixel's padding
 *       fights the spacing the layout was built around.</li>
 * </ul>
 */
public final class ScoreboardLayout {

    /** The signature stamped on a blank row the layout produced, so nothing else collapses it. */
    public static final String SPACER_SIGNATURE = ScoreboardElements.SBS_PREFIX + "spacer";

    /** The signature stamped on a rule row; the renderer draws a line instead of text for these. */
    public static final String SEPARATOR_SIGNATURE = ScoreboardElements.SBS_PREFIX + "separator";

    /** Local aliases, so {@link #defaultOrder} reads as a layout rather than as a wall of prefixes. */
    private static final String SPACER_ID = ScoreboardElements.SPACER;
    private static final String SEPARATOR_ID = ScoreboardElements.SEPARATOR;

    private ScoreboardLayout() {
    }

    /** Whether a row is one of the rules the player placed. */
    public static boolean isSeparator(ScoreboardLine line) {
        return SEPARATOR_SIGNATURE.equals(line.signature());
    }

    /** Whether a row is a blank the layout produced (as opposed to one of Hypixel's own spacers). */
    public static boolean isPlacedSpacer(ScoreboardLine line) {
        return SPACER_SIGNATURE.equals(line.signature());
    }

    /**
     * The rows to draw, in the player's order.
     *
     * @param rows   every row the panel could show right now, in the server's own order
     * @param order  the saved element ids, or empty for "leave the server's order alone"
     * @param hidden element ids the player removed from the layout
     */
    public static List<ScoreboardLine> apply(List<ScoreboardLine> rows, List<String> order,
                                             Collection<String> hidden) {
        List<String> ids = ScoreboardElements.idsFor(rows);
        Set<String> removed = hidden == null ? Set.of() : new HashSet<>(hidden);

        boolean customised = order != null && !order.isEmpty();
        List<ScoreboardLine> kept = new ArrayList<>(rows.size());
        List<String> keptIds = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            String id = ids.get(i);
            if (removed.contains(id)) {
                continue;
            }
            // A custom layout owns the panel's spacing; Hypixel's own padding would fight it.
            if (customised && rows.get(i).isBlank()) {
                continue;
            }
            kept.add(rows.get(i));
            keptIds.add(id);
        }
        if (!customised) {
            return kept;
        }

        // Insertion order matters twice over: it keeps a multi-line block (an objective, a party
        // list) together, and it is the order the leftovers are re-inserted in.
        Map<String, List<ScoreboardLine>> buckets = new LinkedHashMap<>();
        for (int i = 0; i < kept.size(); i++) {
            buckets.computeIfAbsent(keptIds.get(i), key -> new ArrayList<>()).add(kept.get(i));
        }

        List<ScoreboardLine> out = new ArrayList<>(kept.size() + order.size());
        Set<String> placed = new HashSet<>();
        int anchor = -1;
        for (String id : order) {
            if (ScoreboardElements.SPACER.equals(id)) {
                out.add(new ScoreboardLine(Component.empty(), "", SPACER_SIGNATURE));
            } else if (ScoreboardElements.SEPARATOR.equals(id)) {
                out.add(new ScoreboardLine(Component.empty(), "", SEPARATOR_SIGNATURE));
            } else if (ScoreboardElements.UNRECOGNIZED.equals(id)) {
                anchor = out.size();
            } else if (placed.add(id)) {
                // Every row under the id at once - a block pulled out a line at a time would scatter.
                List<ScoreboardLine> bucket = buckets.get(id);
                if (bucket != null) {
                    out.addAll(bucket);
                }
            }
        }

        List<ScoreboardLine> leftovers = new ArrayList<>();
        for (Map.Entry<String, List<ScoreboardLine>> entry : buckets.entrySet()) {
            if (!placed.contains(entry.getKey())) {
                leftovers.addAll(entry.getValue());
            }
        }
        if (!leftovers.isEmpty()) {
            out.addAll(anchor >= 0 ? anchor : out.size(), leftovers);
        }
        return out;
    }

    /**
     * A starting layout for the editor when the player has saved none: the live panel's own order,
     * or - away from SkyBlock, where there is no panel to copy - the catalogue's.
     *
     * <p>Falling back to the catalogue is what makes the editor usable from the main menu. The
     * request that a player be able to position an element that is not on screen right now cannot be
     * met by a screen that starts from what happens to be on screen right now.
     */
    public static List<String> seedOrder(List<ScoreboardLine> liveRows) {
        List<String> seed = new ArrayList<>();
        if (liveRows != null && !liveRows.isEmpty()) {
            List<String> ids = ScoreboardElements.idsFor(liveRows);
            for (String id : ids) {
                // Blanks and rules may repeat; everything else appears once.
                if (ScoreboardElements.repeatable(id) || !seed.contains(id)) {
                    seed.add(id);
                }
            }
        } else {
            for (ScoreboardElement element : ScoreboardElements.catalog()) {
                if (element.kind() == ScoreboardElement.Kind.SERVER) {
                    seed.add(element.id());
                }
            }
        }
        if (!seed.contains(ScoreboardElements.UNRECOGNIZED)) {
            seed.add(ScoreboardElements.UNRECOGNIZED);
        }
        return seed;
    }

    /**
     * The layout the mod ships with - what a fresh install draws, and what the editor's Reset
     * restores.
     *
     * <p>Hypixel's own order with the rows it hides folded in where they belong: the bank under the
     * purse, the interest under the bank, the gems beside the bits, and the client-info rows as a
     * block at the bottom. The blanks and the rule are placed deliberately rather than inherited,
     * which is the point of shipping a layout at all - the panel is grouped by what a row is for.
     *
     * <p>{@link ScoreboardElements#UNRECOGNIZED} sits <b>after the objective</b>, not at the end.
     * That is the slot Hypixel itself uses for whatever the situation is - the objective block in the
     * open world, the run stats in a dungeon, a countdown during an event - so the rows this build
     * does not recognise land where their kind belongs instead of underneath the buff timers.
     */
    public static List<String> defaultOrder() {
        return new ArrayList<>(List.of(
                "date_server",
                "sbs:time",
                SEPARATOR_ID,
                "skyblock_date",
                "skyblock_time",
                "location",
                SPACER_ID,
                "purse",
                "sbs:bank",
                "sbs:interest",
                "sbs:gems",
                "bits",
                SPACER_ID,
                "objective",
                ScoreboardElements.UNRECOGNIZED,
                SPACER_ID,
                "sbs:sb_level",
                "sbs:profile",
                "sbs:cookie_buff",
                "sbs:god_potion",
                // Placed although their switches ship off, so that turning one on puts the row in
                // the block it belongs to rather than dropping it at the unrecognised slot.
                "sbs:vote",
                "sbs:fps",
                "sbs:ping"));
    }

    /**
     * What the shipped layout leaves out: the server's advertising line, the one sidebar row that
     * tells you nothing you did not already know and costs a line doing it.
     */
    public static List<String> defaultHidden() {
        return new ArrayList<>(List.of("website"));
    }

    /** Every catalogue id the layout does not place - what the editor's palette lists. */
    public static List<String> unplaced(List<String> order) {
        Set<String> placed = order == null ? Set.of() : new HashSet<>(order);
        List<String> out = new ArrayList<>();
        for (ScoreboardElement element : ScoreboardElements.catalog()) {
            // Blanks and rules always stay on offer - placing one must not use it up.
            if (element.repeatable() || !placed.contains(element.id())) {
                out.add(element.id());
            }
        }
        return out;
    }
}
