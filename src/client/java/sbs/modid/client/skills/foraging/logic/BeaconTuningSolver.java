/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import sbs.modid.client.skills.foraging.model.BeaconPanel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which option in a beacon tuning menu matches the beat.
 *
 * <p><b>Mechanic.</b> Galatea's beacon plays a beat, and the menu asks the player to reproduce it by
 * matching three traits: {@link Trait#COLOR}, {@link Trait#SPEED} and {@link Trait#PITCH}. The
 * Frequency Tuning menu tunes one panel; the Signal Enhancer variant ("Upgrade Signal Strength")
 * tunes two at once, and nothing here assumes the count.
 *
 * <p><b>N panels, not one.</b> An earlier version merged every trait it found anywhere in the menu
 * into a single beat, which made the two-panel variant unsolvable by construction: each panel states
 * its own colour, so every trait was "stated twice with different values", every trait was dropped as
 * a conflict, and the menu came out blank. Beats are therefore read <i>per panel</i> - a panel being
 * a cluster of adjacent display slots - and a trait only conflicts with another claim on its own
 * panel.
 *
 * <p><b>Geometry is derived, never hardcoded.</b> No slot index appears in this class. Slots are
 * placed on the container's own grid (its width is passed in, because a hopper is not a chest), and
 * an option belongs to whichever panel's beat is nearest to it. A tie means two panels are equally
 * close, which is not something to guess at, so the option is dropped.
 *
 * <p><b>Ambiguity always loses.</b> Two claims disagreeing about a trait on one panel drop that
 * trait; two options on one panel claiming the same value drop it too. Ringing the wrong slot in a
 * minigame a player spends a Signal Enhancer on is worse than ringing nothing at all.
 *
 * <p><b>Failure is per trait, not per menu.</b> A panel whose beat cannot be read no longer silences
 * the rest of the menu, and a trait with no matching option no longer silences its two neighbours.
 * Whatever can be pinned down is marked; the rest is named in {@link #describe}.
 *
 * <p><b>Status: the wording is still a hypothesis.</b> "Match Colour, Speed and Pitch" is confirmed
 * from the wiki; the {@code Trait: value} shape parsed here, and the click and selected wording, are
 * inferred and have never been checked against a live menu. If the beat is telegraphed by animating
 * panes rather than written out, no single-frame parse can read it at all and this class needs a
 * time-sampled beat source instead. {@code /sbs probe arm} at the beacon settles which it is, and
 * {@code BeaconTuningSolverTest} is where that capture lands.
 */
public final class BeaconTuningSolver {

    /**
     * A trait a beat is made of. The label is matched loosely on its stem: both spellings of colour
     * turn up in Hypixel menus, and a menu is free to write "Colours" or "Pitch (Note)".
     */
    public enum Trait {
        COLOR('C'),
        SPEED('S'),
        PITCH('P');

        private final char letter;

        Trait(char letter) {
            this.letter = letter;
        }

        /** The single character drawn on a marked slot, so three rings stay tellable apart. */
        public char letter() {
            return letter;
        }

        static Trait of(String label) {
            String word = label.toLowerCase(Locale.ROOT);
            if (word.startsWith("col")) {
                return COLOR;
            }
            if (word.startsWith("spe")) {
                return SPEED;
            }
            return word.startsWith("pit") ? PITCH : null;
        }
    }

    /**
     * One marked slot: which trait it sets, to which value, and whether the menu already shows it as
     * the current pick - a satisfied mark is drawn as a quiet ring rather than as "click this".
     */
    public record Mark(Trait trait, String value, boolean satisfied) { }

    /**
     * One tuning panel's target, and the slot its beat was read from. The slot is what makes two
     * panels tellable apart in the log when a two-panel menu only half resolves.
     */
    public record PanelBeat(int anchorSlot, Map<Trait, String> beat) { }

    /**
     * What one scan understood: the slots worth marking, the panels that were found, and a
     * plain-language note naming whatever could not be resolved. The note is the diagnostic -
     * "nothing is highlighted" must always come with a reason.
     */
    public record Solution(Map<Integer, Mark> marks, List<PanelBeat> panels, String note) {

        public static final Solution EMPTY =
                new Solution(Map.of(), List.of(), "no beacon menu read yet");

        public boolean isEmpty() {
            return marks.isEmpty();
        }
    }

    /** {@code Colour: Red}, {@code Speed = Fast}, {@code Pitch: High} - label, separator, value. */
    private static final Pattern STATEMENT = Pattern.compile(
            "(?i)(colou?rs?|speeds?|pitch(?:es)?)\\s*[:=]\\s*([\\p{L}0-9][\\p{L}0-9 '\\-]{0,23})");

    /** Block suffixes stripped to leave the colour word, so a dyed block can answer for a colour. */
    private static final String[] DYED_SUFFIXES = {
            "_stained_glass_pane", "_stained_glass", "_terracotta", "_concrete_powder", "_concrete",
            "_wool", "_carpet", "_candle", "_dye"};

    /** A chest row. Only a default for callers with no menu behind them, i.e. the tests. */
    public static final int CHEST_WIDTH = 9;

    private BeaconTuningSolver() {
    }

    // ------------------------------------------------------------------
    // The scan
    // ------------------------------------------------------------------

    /** Convenience for a chest-shaped menu, which every beacon menu seen so far is. */
    public static Solution solve(List<BeaconPanel> panels) {
        return solve(panels, CHEST_WIDTH);
    }

    /**
     * Reads the whole menu and returns what to mark.
     *
     * <p>Three passes, and the order is forced. The panels have to be found before an option can be
     * assigned to one; the assignment has to happen before an unlabelled option (a bare "Fast", a red
     * pane) can be classified at all, because outside its own panel's stated value there is nothing
     * that makes one word a speed rather than a name.
     *
     * @param width the container's grid width, so "next to" means what it means on screen
     */
    public static Solution solve(List<BeaconPanel> panels, int width) {
        List<String> notes = new ArrayList<>(3);
        int columns = width <= 0 ? CHEST_WIDTH : width;
        List<Panel> found = readPanels(panels, notes, columns);
        if (found.isEmpty()) {
            notes.add("no beat stated in the menu");
            return new Solution(Map.of(), List.of(), String.join("; ", notes));
        }

        assignOptions(panels, found, columns);

        Map<Integer, Mark> marks = new HashMap<>(3 * found.size());
        List<PanelBeat> beats = new ArrayList<>(found.size());
        int wanted = 0;
        for (Panel panel : found) {
            beats.add(new PanelBeat(panel.anchor, Map.copyOf(panel.beat)));
            wanted += panel.beat.size();
            for (Map.Entry<Trait, String> want : panel.beat.entrySet()) {
                List<Option> candidates = new ArrayList<>(2);
                for (Option option : panel.options) {
                    if (option.trait() == want.getKey() && option.value().equals(want.getValue())) {
                        candidates.add(option);
                    }
                }
                if (candidates.size() != 1) {
                    // None: this panel offers no option we recognise for the trait. Several: two of
                    // its slots claim the same value, and picking one would be a coin flip. Neither
                    // is marked - a ring the player cannot trust is worse than no ring. The other
                    // traits, and the other panels, are unaffected.
                    notes.add(label(panel, found.size()) + want.getKey() + "=" + want.getValue()
                            + " -> " + (candidates.isEmpty() ? "no option found"
                            : candidates.size() + " options"));
                    continue;
                }
                Option option = candidates.get(0);
                marks.put(option.slot(), new Mark(want.getKey(), want.getValue(), option.selected()));
            }
        }
        String summary = "matched " + marks.size() + " of " + wanted
                + (found.size() == 1 ? "" : " across " + found.size() + " panels");
        notes.add(0, summary);
        return new Solution(Map.copyOf(marks), List.copyOf(beats), String.join("; ", notes));
    }

    /** Only worth naming which panel a complaint is about when there is more than one. */
    private static String label(Panel panel, int total) {
        return total == 1 ? "" : "panel@" + panel.anchor + " ";
    }

    // ------------------------------------------------------------------
    // Panels
    // ------------------------------------------------------------------

    /** A panel under construction: its beat, the slots that stated it, and the options it owns. */
    private static final class Panel {
        private final Map<Trait, String> beat = new EnumMap<>(Trait.class);
        private final List<Trait> conflicting = new ArrayList<>(1);
        private final List<Integer> slots = new ArrayList<>(3);
        private final List<Option> options = new ArrayList<>(6);
        private int anchor;

        private void state(Map<Trait, String> claims) {
            for (Map.Entry<Trait, String> claim : claims.entrySet()) {
                String known = beat.get(claim.getKey());
                if (known == null) {
                    beat.put(claim.getKey(), claim.getValue());
                } else if (!known.equals(claim.getValue()) && !conflicting.contains(claim.getKey())) {
                    conflicting.add(claim.getKey());
                }
            }
        }
    }

    /**
     * The panels the menu describes, from every slot that describes rather than offers.
     *
     * <p>A slot naming several traits is taken as a beat outright; one naming exactly one trait is
     * only believed when it cannot be clicked, since a clickable single-trait slot is precisely what
     * an option looks like.
     *
     * <p><b>Adjacent describing slots are one panel.</b> A beat may be written across three slots in
     * a row rather than gathered onto one item, and those three are one target, not three. Two beats
     * that are not touching are two panels - which is the Signal Enhancer, and the case the old
     * single-beat model could not represent.
     */
    private static List<Panel> readPanels(List<BeaconPanel> panels, List<String> notes, int width) {
        List<Panel> out = new ArrayList<>(2);
        for (BeaconPanel panel : panels) {
            Map<Trait, String> stated = statements(panel);
            boolean describes = stated.size() > 1 || !clickable(panel);
            if (stated.isEmpty() || !describes) {
                continue;
            }
            Panel host = null;
            for (Panel candidate : out) {
                if (touches(candidate, panel.slot(), width)) {
                    host = candidate;
                    break;
                }
            }
            if (host == null) {
                host = new Panel();
                host.anchor = panel.slot();
                out.add(host);
            }
            host.slots.add(panel.slot());
            host.anchor = Math.min(host.anchor, panel.slot());
            host.state(stated);
        }
        for (Panel panel : out) {
            for (Trait trait : panel.conflicting) {
                panel.beat.remove(trait);
                notes.add(label(panel, out.size()) + trait + " stated twice with different values");
            }
        }
        out.removeIf(panel -> panel.beat.isEmpty());
        return out;
    }

    /** Whether a slot sits against any slot this panel already owns, on the container's grid. */
    private static boolean touches(Panel panel, int slot, int width) {
        for (int owned : panel.slots) {
            if (Math.abs(owned / width - slot / width) <= 1
                    && Math.abs(owned % width - slot % width) <= 1) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------

    /** A clickable slot that sets one trait to one value, on one panel. */
    private record Option(int slot, Trait trait, String value, boolean selected) { }

    /**
     * Hands every clickable slot to the panel it belongs to, and classifies it there.
     *
     * <p>Geometry decides ownership first, wording second, and that order is what makes the
     * two-panel menu work: an unlabelled red pane means "the colour this panel asks for", and which
     * panel that is cannot be read off the pane - only off where it sits. An option equidistant from
     * two panels is dropped rather than assigned to whichever came first.
     */
    private static void assignOptions(List<BeaconPanel> panels, List<Panel> found, int width) {
        for (BeaconPanel panel : panels) {
            if (!clickable(panel)) {
                continue;
            }
            Panel owner = nearest(found, panel.slot(), width);
            if (owner == null) {
                continue;   // equally close to two panels - not something to guess at
            }
            Map<Trait, String> stated = statements(panel);
            Trait trait = null;
            String value = null;
            if (stated.size() == 1) {
                Map.Entry<Trait, String> only = stated.entrySet().iterator().next();
                trait = only.getKey();
                value = only.getValue();
            } else if (stated.isEmpty()) {
                String named = norm(panel.name());
                String dyed = dyeWord(panel.itemId());
                for (Map.Entry<Trait, String> want : owner.beat.entrySet()) {
                    boolean byName = !named.isEmpty() && named.equals(want.getValue());
                    boolean byDye = want.getKey() == Trait.COLOR
                            && !dyed.isEmpty() && dyed.equals(want.getValue());
                    if (byName || byDye) {
                        trait = want.getKey();
                        value = byName ? named : dyed;
                        break;
                    }
                }
            }
            if (trait != null && value != null && !value.isEmpty()) {
                owner.options.add(new Option(panel.slot(), trait, value, selected(panel)));
            }
        }
    }

    /** The panel whose stated beat sits closest to this slot, or {@code null} when two tie. */
    private static Panel nearest(List<Panel> found, int slot, int width) {
        Panel best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tied = false;
        for (Panel panel : found) {
            int distance = Integer.MAX_VALUE;
            for (int owned : panel.slots) {
                int steps = Math.abs(owned / width - slot / width)
                        + Math.abs(owned % width - slot % width);
                distance = Math.min(distance, steps);
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = panel;
                tied = false;
            } else if (distance == bestDistance && panel != best) {
                tied = true;
            }
        }
        return tied ? null : best;
    }

    // ------------------------------------------------------------------
    // Reading one slot
    // ------------------------------------------------------------------

    /** Every {@code Trait: value} the panel states, name first so a name always wins over its lore. */
    private static Map<Trait, String> statements(BeaconPanel panel) {
        Map<Trait, String> found = new EnumMap<>(Trait.class);
        collect(found, panel.name());
        for (String line : panel.lore()) {
            collect(found, line);
        }
        return found;
    }

    private static void collect(Map<Trait, String> into, String line) {
        Matcher matcher = STATEMENT.matcher(line);
        while (matcher.find()) {
            Trait trait = Trait.of(matcher.group(1));
            String value = norm(matcher.group(2));
            if (trait != null && !value.isEmpty()) {
                into.putIfAbsent(trait, value);
            }
        }
    }

    /**
     * Whether the panel invites a click. Hypixel spells this out on every interactive menu item, and
     * it is the only signal separating "here is what you must match" from "press me to match it".
     */
    private static boolean clickable(BeaconPanel panel) {
        for (String line : panel.lore()) {
            if (line.toLowerCase(Locale.ROOT).contains("click")) {
                return true;
            }
        }
        return false;
    }

    /** Whether the option is already the current pick - said in the lore, or shown by the glint. */
    private static boolean selected(BeaconPanel panel) {
        if (panel.glint()) {
            return true;
        }
        for (String line : panel.lore()) {
            String text = line.toLowerCase(Locale.ROOT);
            if (text.contains("selected") || text.contains("currently")) {
                return true;
            }
        }
        return false;
    }

    /** The colour a dyed block carries ({@code light_blue_stained_glass_pane} &rarr; {@code lightblue}). */
    private static String dyeWord(String itemId) {
        for (String suffix : DYED_SUFFIXES) {
            if (itemId.endsWith(suffix)) {
                return norm(itemId.substring(0, itemId.length() - suffix.length()));
            }
        }
        return "";
    }

    /** Values compare on letters and digits only, so "Light Blue" and {@code light_blue} agree. */
    private static String norm(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /** One line describing what a solution understood, for the {@code [SBS][Beacon]} log. */
    public static String describe(Solution solution) {
        StringBuilder out = new StringBuilder();
        if (solution.panels().isEmpty()) {
            out.append("panels=(none) ");
        } else {
            for (PanelBeat panel : solution.panels()) {
                out.append("panel@").append(panel.anchorSlot()).append('[');
                panel.beat().forEach((trait, value) ->
                        out.append(trait).append(':').append(value).append(' '));
                out.append("] ");
            }
        }
        out.append("marks=");
        if (solution.marks().isEmpty()) {
            out.append("(none) ");
        } else {
            solution.marks().forEach((slot, mark) -> out.append(slot).append(':')
                    .append(mark.trait()).append(mark.satisfied() ? "(set) " : " "));
        }
        return out.append("| ").append(solution.note()).toString();
    }
}
