/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.diana.model.DianaHudLine;
import sbs.modid.client.combat.diana.model.DianaHudLine.LegacySwitch;
import sbs.modid.client.combat.diana.model.DianaPanel;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Which lines sit on which Diana card, in what order: read from the config, migrated from the four
 * old switches, and edited by those switches' alias toggles.
 *
 * <h2>One reading of "never arranged"</h2>
 *
 * <p>Both stored lists {@code null} means the player has never arranged the cards, and the layout is
 * whatever the old switches say ({@link #fromLegacy}). That is the same answer whether the config is
 * an old one awaiting migration or a fresh install that skipped migration entirely, so the two paths
 * cannot disagree. A single {@code null} list beside a real one is a hand edit, and reads as empty.
 *
 * <h2>A line is on at most one card</h2>
 *
 * <p>{@link #parse} keeps a line's first appearance - the tracker card is read first - and drops
 * unknown ids, so a hand-edited or newer config can rearrange a card but never break one.
 */
public final class DianaHudLayout {

    /** One card's lines in order, and the other's. Immutable. */
    public record Layout(List<DianaHudLine> tracker, List<DianaHudLine> creatures) {

        public Layout {
            tracker = List.copyOf(tracker);
            creatures = List.copyOf(creatures);
        }

        public List<DianaHudLine> lines(DianaPanel panel) {
            return panel == DianaPanel.TRACKER ? tracker : creatures;
        }

        /** The card {@code line} is on, or {@code null} when it is hidden. */
        public DianaPanel panelOf(DianaHudLine line) {
            if (tracker.contains(line)) {
                return DianaPanel.TRACKER;
            }
            return creatures.contains(line) ? DianaPanel.CREATURES : null;
        }

        /** This layout with {@code panel}'s lines replaced - and removed from the other card. */
        public Layout with(DianaPanel panel, List<DianaHudLine> lines) {
            List<DianaHudLine> other = new ArrayList<>(lines(panel.other()));
            other.removeAll(lines);
            List<DianaHudLine> mine = dedupe(lines);
            return panel == DianaPanel.TRACKER ? new Layout(mine, other) : new Layout(other, mine);
        }

        public List<String> ids(DianaPanel panel) {
            List<String> out = new ArrayList<>();
            for (DianaHudLine line : lines(panel)) {
                out.add(line.id());
            }
            return out;
        }
    }

    /** What the cards showed for a player who never touched a switch - and so the shipped layout. */
    private static final Layout DEFAULTS = fromLegacy(null, null, null, null);

    private static List<String> cachedTracker;
    private static List<String> cachedCreatures;
    private static Layout cached;

    private DianaHudLayout() {
    }

    public static Layout defaults() {
        return DEFAULTS;
    }

    /**
     * The layout the four old switches described. {@code null} is the switch's old default - true,
     * false, true, false - because that is what an absent key meant to the build that wrote it.
     *
     * <p>No Shuriken is placed only when the creature card was on as well: it was drawn inside that
     * card, so with the card off it showed nothing, and placing it would change the look.
     */
    public static Layout fromLegacy(Boolean chainsCard, Boolean sessionCard, Boolean creatureCard,
                                    Boolean shuriken) {
        boolean chains = chainsCard == null || chainsCard;
        boolean session = sessionCard != null && sessionCard;
        boolean creatures = creatureCard == null || creatureCard;
        boolean noShuriken = shuriken != null && shuriken && creatures;

        Set<LegacySwitch> on = EnumSet.noneOf(LegacySwitch.class);
        if (chains) {
            on.add(LegacySwitch.CHAINS_CARD);
        }
        if (session) {
            on.add(LegacySwitch.SESSION_CARD);
        }
        if (creatures) {
            on.add(LegacySwitch.CREATURE_CARD);
        }
        if (noShuriken) {
            on.add(LegacySwitch.SHURIKEN);
        }
        List<DianaHudLine> tracker = new ArrayList<>();
        List<DianaHudLine> creatureLines = new ArrayList<>();
        for (DianaHudLine line : DianaHudLine.values()) {
            if (line.legacySwitch() == null || !on.contains(line.legacySwitch())) {
                continue;
            }
            (line.defaultPanel() == DianaPanel.TRACKER ? tracker : creatureLines).add(line);
        }
        return new Layout(tracker, creatureLines);
    }

    /** Stored ids to a layout: unknown ids dropped, a repeated line kept where it first appears. */
    public static Layout parse(List<String> tracker, List<String> creatures) {
        Set<DianaHudLine> seen = EnumSet.noneOf(DianaHudLine.class);
        return new Layout(read(tracker, seen), read(creatures, seen));
    }

    private static List<DianaHudLine> read(List<String> ids, Set<DianaHudLine> seen) {
        List<DianaHudLine> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            DianaHudLine line = DianaHudLine.byId(id);
            if (line != null && seen.add(line)) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<DianaHudLine> dedupe(List<DianaHudLine> lines) {
        Set<DianaHudLine> seen = EnumSet.noneOf(DianaHudLine.class);
        List<DianaHudLine> out = new ArrayList<>();
        for (DianaHudLine line : lines) {
            if (line != null && seen.add(line)) {
                out.add(line);
            }
        }
        return out;
    }

    /** Both cards from a stored appearance, with the old switches standing in for "never arranged". */
    @SuppressWarnings("deprecation")
    public static Layout resolve(SBSConfig.DianaSettings cfg) {
        SBSConfig.DianaAppearanceSettings appearance = cfg.appearance;
        if (appearance == null
                || (appearance.trackerLines == null && appearance.creatureLines == null)) {
            return fromLegacy(cfg.chainsHud, cfg.sessionHud, cfg.creatureHealthHud,
                    cfg.shurikenWarning);
        }
        return parse(appearance.trackerLines, appearance.creatureLines);
    }

    /**
     * {@link #resolve} for the draw path: the same answer, re-parsed only when the stored lists are
     * replaced. Every writer here stores new lists rather than editing them, which is what makes an
     * identity check enough.
     */
    public static Layout current(SBSConfig.DianaSettings cfg) {
        SBSConfig.DianaAppearanceSettings appearance = cfg.appearance;
        if (appearance == null || appearance.trackerLines == null && appearance.creatureLines == null) {
            return resolve(cfg);
        }
        if (cached == null || appearance.trackerLines != cachedTracker
                || appearance.creatureLines != cachedCreatures) {
            cachedTracker = appearance.trackerLines;
            cachedCreatures = appearance.creatureLines;
            cached = resolve(cfg);
        }
        return cached;
    }

    /** Stores {@code layout}, always as new lists - see {@link #current}. */
    public static void write(SBSConfig.DianaSettings cfg, Layout layout) {
        if (cfg.appearance == null) {
            cfg.appearance = new SBSConfig.DianaAppearanceSettings();
        }
        cfg.appearance.trackerLines = layout.ids(DianaPanel.TRACKER);
        cfg.appearance.creatureLines = layout.ids(DianaPanel.CREATURES);
    }

    /**
     * The one-time migration: an unarranged config takes the layout its old switches described, and
     * the switches are nulled so they stop being written. A config that already has lists only loses
     * the switches. Returns whether anything changed.
     */
    @SuppressWarnings("deprecation")
    public static boolean migrate(SBSConfig.DianaSettings cfg) {
        if (cfg == null) {
            return false;
        }
        boolean hadSwitches = cfg.chainsHud != null || cfg.sessionHud != null
                || cfg.creatureHealthHud != null || cfg.shurikenWarning != null;
        boolean unarranged = cfg.appearance == null
                || cfg.appearance.trackerLines == null && cfg.appearance.creatureLines == null;
        if (!hadSwitches && !unarranged) {
            return false;
        }
        if (unarranged) {
            Layout layout = resolve(cfg);
            write(cfg, layout);
            if (hadSwitches) {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Diana] card switches migrated to line layout: tracker {}, creatures {}",
                        layout.tracker(), layout.creatures());
            }
        }
        cfg.chainsHud = null;
        cfg.sessionHud = null;
        cfg.creatureHealthHud = null;
        cfg.shurikenWarning = null;
        return true;
    }

    // ------------------------------------------------------------------ alias toggles

    /** Whether any line the old switch controlled is on either card - what its alias toggle shows. */
    public static boolean switchOn(Layout layout, LegacySwitch which) {
        for (DianaHudLine line : DianaHudLine.values()) {
            if (line.legacySwitch() == which && layout.panelOf(line) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The layout with the old switch's lines taken off both cards, or put back.
     *
     * <p>Put back means: each missing line goes onto its default card, straight after the last line
     * already there that precedes it in the catalogue - so switching the session totals back on puts
     * them under the chains, where they always were, even on a card the player rearranged. A line
     * the player moved to the other card is already placed and stays where they put it.
     */
    public static Layout withSwitch(Layout layout, LegacySwitch which, boolean on) {
        List<DianaHudLine> tracker = new ArrayList<>(layout.tracker());
        List<DianaHudLine> creatures = new ArrayList<>(layout.creatures());
        for (DianaHudLine line : DianaHudLine.values()) {
            if (line.legacySwitch() != which) {
                continue;
            }
            if (!on) {
                tracker.remove(line);
                creatures.remove(line);
            } else if (!tracker.contains(line) && !creatures.contains(line)) {
                List<DianaHudLine> card = line.defaultPanel() == DianaPanel.TRACKER ? tracker : creatures;
                card.add(insertionIndex(card, line), line);
            }
        }
        return new Layout(tracker, creatures);
    }

    private static int insertionIndex(List<DianaHudLine> card, DianaHudLine line) {
        int index = 0;
        for (int i = 0; i < card.size(); i++) {
            if (card.get(i).ordinal() < line.ordinal()) {
                index = i + 1;
            }
        }
        return index;
    }
}
