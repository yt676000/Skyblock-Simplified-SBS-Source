/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import sbs.modid.client.skills.mining.nucleus.model.Crystal;
import sbs.modid.client.skills.mining.nucleus.model.NucleusSignals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides when to remind the player that the Mole is not their active pet during a Crystal Nucleus
 * run. Pure - the run's crystal state, the pet and the parser's events in, reminders and log lines out.
 *
 * <p>The Mole's perk adds a chance of one extra item to the loot bundle ({@code WIKI}: level percent).
 * The reward list is already printed when the fifth crystal goes in, so the roll is assumed to happen
 * there rather than at the vault pickup (unverified). That is why the reminder speaks up before the
 * last placement and not only at it:
 *
 * <ul>
 *   <li><b>Early warning</b>, once per run: on arriving in the Crystal Nucleus with a crystal still to
 *       place, or when the fifth crystal is found, whichever comes first.</li>
 *   <li><b>Each placement</b> without the Mole: one reminder, urgent at 4/5.</li>
 *   <li><b>Pet unknown</b> (nothing has told the pet tracker): one reminder per run, never per
 *       placement - the pet may well be the Mole.</li>
 *   <li><b>Mole below level 100</b>: its chance, once per run, only when asked for.</li>
 * </ul>
 *
 * Nothing is said once the bundle header has arrived. The bundle's close resets everything for the next
 * run, and logs the bundle's line count with the pet seen at the fifth placement, so runs with and
 * without the Mole can be compared.
 */
public final class MoleReminder {

    /** The pet's name as the pet tracker reports it, compared case-insensitively. */
    public static final String MOLE = "Mole";
    public static final int MAX_LEVEL = 100;

    /** What is known about the active pet. */
    public enum PetKind {
        /** The pet tracker has no active pet - not the same as "not the Mole". */
        UNKNOWN,
        MOLE,
        OTHER
    }

    public record Pet(PetKind kind, String name, int level) {

        public static final Pet UNKNOWN = new Pet(PetKind.UNKNOWN, "", 0);

        /** From the pet tracker's {@code hasPet()}, {@code name()} and {@code level()}. */
        public static Pet of(boolean hasPet, String name, int level) {
            if (!hasPet || name == null || name.isBlank()) {
                return UNKNOWN;
            }
            String trimmed = name.trim();
            return new Pet(trimmed.equalsIgnoreCase(MOLE) ? PetKind.MOLE : PetKind.OTHER, trimmed, level);
        }

        String describe() {
            return kind == PetKind.UNKNOWN ? "unknown" : name + " lvl " + level;
        }
    }

    /** One reminder: the headline and the detail line, as an alert carries them. */
    public record Reminder(String title, String detail) {
    }

    /** What one call produced. */
    public record Output(List<Reminder> reminders, List<String> log) {
        static final Output NONE = new Output(List.of(), List.of());
    }

    private boolean earlyDone;
    private boolean unknownWarned;
    private boolean chanceShown;
    private boolean bundleOpen;
    private int bundleLines;
    private Pet petAtFifth;

    /**
     * The events one {@link NucleusRunSession} call applied, after the ledger has taken them.
     *
     * @param showChance whether a Mole below level 100 gets its chance mentioned
     */
    public Output onEvents(List<NucleusChatParser.Event> events, NucleusRunLedger ledger, Pet pet,
                           boolean showChance) {
        if (events.isEmpty()) {
            return Output.NONE;
        }
        List<Reminder> reminders = new ArrayList<>();
        List<String> log = new ArrayList<>();
        for (NucleusChatParser.Event event : events) {
            switch (event) {
                case NucleusChatParser.CrystalFound found -> {
                    if (found.count() >= Crystal.values().length || collected(ledger) >= Crystal.values().length) {
                        early(pet, showChance, reminders);
                    }
                }
                case NucleusChatParser.CrystalPlaced placed -> placed(placed.crystal(), placedCount(ledger), pet,
                        showChance, reminders);
                case NucleusChatParser.BlockOpened opened -> {
                    if (opened.source() == NucleusChatParser.Source.BUNDLE) {
                        bundleOpen = true;
                        bundleLines = 0;
                    }
                }
                case NucleusChatParser.Loot loot -> countBundleLine(loot.source());
                case NucleusChatParser.NonCoin nonCoin -> countBundleLine(nonCoin.source());
                case NucleusChatParser.BlockClosed closed -> {
                    if (closed.source() == NucleusChatParser.Source.BUNDLE) {
                        log.add("bundle: " + bundleLines + " lines, pet="
                                + (petAtFifth == null ? "not seen" : petAtFifth.describe())
                                + " at 5th placement");
                        reset();
                    }
                }
                default -> {
                }
            }
        }
        return reminders.isEmpty() && log.isEmpty() ? Output.NONE : new Output(reminders, log);
    }

    /** The scoreboard zone changed to {@code zone}. */
    public Output onZone(String zone, NucleusRunLedger ledger, Pet pet, boolean showChance) {
        if (zone == null || !zone.trim().equalsIgnoreCase(NucleusSignals.NUCLEUS_ZONE) || held(ledger) == 0) {
            return Output.NONE;
        }
        List<Reminder> reminders = new ArrayList<>();
        early(pet, showChance, reminders);
        return reminders.isEmpty() ? Output.NONE : new Output(reminders, List.of());
    }

    /** A new run, a reset or another profile: every once-per-run flag is armed again. */
    public void reset() {
        earlyDone = false;
        unknownWarned = false;
        chanceShown = false;
        bundleOpen = false;
        bundleLines = 0;
        petAtFifth = null;
    }

    /** Lobby change: the parser drops an open block, so the bundle count stops with it. */
    public void onWorldChange() {
        bundleOpen = false;
    }

    private void early(Pet pet, boolean showChance, List<Reminder> out) {
        if (bundleOpen || earlyDone) {
            return;
        }
        switch (pet.kind()) {
            case MOLE -> chance(pet, showChance, out);
            case OTHER -> {
                earlyDone = true;
                out.add(new Reminder("Mole not active",
                        "Summon it before placing the last crystal"));
            }
            case UNKNOWN -> {
                earlyDone = true;
                unknown(out);
            }
        }
    }

    private void placed(Crystal crystal, int placed, Pet pet, boolean showChance, List<Reminder> out) {
        if (placed >= Crystal.values().length) {
            petAtFifth = pet;
        }
        if (bundleOpen) {
            return;
        }
        earlyDone = true;   // from the first placement on, the placement reminders take over
        String name = crystal == null ? "a" : crystal.displayName();
        switch (pet.kind()) {
            case MOLE -> chance(pet, showChance, out);
            case OTHER -> out.add(placed == Crystal.values().length - 1
                    ? new Reminder("Mole is not active!", "Placed " + name + " crystal (" + placed
                            + "/5) - next crystal opens the bundle")
                    : new Reminder("Mole is not active", "Placed " + name + " crystal (" + placed + "/5)"));
            case UNKNOWN -> unknown(out);
        }
    }

    private void unknown(List<Reminder> out) {
        if (!unknownWarned) {
            unknownWarned = true;
            out.add(new Reminder("Can't see your pet", "Check that the Mole is active"));
        }
    }

    private void chance(Pet pet, boolean showChance, List<Reminder> out) {
        if (!showChance || chanceShown || pet.level() <= 0 || pet.level() >= MAX_LEVEL) {
            return;
        }
        chanceShown = true;
        out.add(new Reminder("Mole Lvl " + pet.level(), String.format(Locale.ROOT,
                "%d%% chance of one extra bundle drop (wiki)", pet.level())));
    }

    private void countBundleLine(NucleusChatParser.Source source) {
        if (bundleOpen && source == NucleusChatParser.Source.BUNDLE) {
            bundleLines++;
        }
    }

    /** Crystals found or placed this run. */
    private static int collected(NucleusRunLedger ledger) {
        int n = 0;
        for (Crystal crystal : Crystal.values()) {
            if (ledger.crystalState(crystal) != Crystal.State.NONE) {
                n++;
            }
        }
        return n;
    }

    /** Crystals found and not yet placed - the ones in hand. */
    private static int held(NucleusRunLedger ledger) {
        int n = 0;
        for (Crystal crystal : Crystal.values()) {
            if (ledger.crystalState(crystal) == Crystal.State.FOUND) {
                n++;
            }
        }
        return n;
    }

    private static int placedCount(NucleusRunLedger ledger) {
        int n = 0;
        for (Crystal crystal : Crystal.values()) {
            if (ledger.crystalState(crystal) == Crystal.State.PLACED) {
                n++;
            }
        }
        return n;
    }
}
