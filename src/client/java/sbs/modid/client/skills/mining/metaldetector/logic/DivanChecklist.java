/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector.logic;

import sbs.modid.client.skills.mining.metaldetector.model.DivanTool;

import java.util.EnumMap;
import java.util.Map;

/**
 * Where one lobby's Mines of Divan run stands: each scavenged tool missing, found or returned, and
 * then the Jade Crystal waiting to be punched. Pure - fed events by {@link DivanTracker}, tested
 * without a game.
 *
 * <p><b>Per lobby.</b> The Keepers belong to one Crystal Hollows instance, so {@link #syncLobby}
 * drops everything when the lobby changes. Whether the server itself keeps progress across lobbies is
 * unverified; assuming it does not is the safe direction, because a Keeper line in the new lobby puts
 * the row straight back.
 *
 * <p><b>The last step is the Nucleus tracker's.</b> The Jade Crystal being collected is a
 * {@code CRYSTAL FOUND} block, which {@code NucleusRunTracker} already reads; {@link #observeJade}
 * takes its answer rather than parsing that block a second time.
 */
public final class DivanChecklist {

    /** One tool's progress, in order. */
    public enum Step {
        MISSING, FOUND, RETURNED
    }

    /** The Jade Crystal's progress, in order. */
    public enum Jade {
        /** Not every tool is back yet. */
        WAITING,
        /** All four returned; the crystal stands in front of the Keepers. */
        READY,
        /** Collected - the Nucleus tracker saw the crystal found. */
        COLLECTED
    }

    private final Map<DivanTool, Step> steps = new EnumMap<>(DivanTool.class);
    private Jade jade = Jade.WAITING;
    private String lobby = "";
    /**
     * What the Nucleus tracker said about the Jade crystal when it became ready, or {@code null}
     * before the first observation. Only a change away from "not found" counts as collecting it, so
     * a Jade found earlier in the same Nucleus run cannot clear the row by itself.
     */
    private Boolean jadeSeenAtReady;

    public DivanChecklist() {
        clear();
    }

    public Step step(DivanTool tool) {
        return steps.get(tool);
    }

    public Jade jade() {
        return jade;
    }

    /** Tools found or returned - the count the alert shows as {@code (3/4)}. */
    public int obtained() {
        int n = 0;
        for (Step step : steps.values()) {
            if (step != Step.MISSING) {
                n++;
            }
        }
        return n;
    }

    /** Any progress at all in this lobby. */
    public boolean started() {
        return jade != Jade.WAITING || obtained() > 0;
    }

    /** The Jade Crystal is collected; nothing left to do in this lobby. */
    public boolean finished() {
        return jade == Jade.COLLECTED;
    }

    /** In the Mines always; elsewhere on the Hollows only while a run is open. */
    public boolean visible(boolean inMines) {
        return inMines || (started() && !finished());
    }

    /**
     * Keys the state to {@code server}. A different lobby drops everything; {@code null} (the tab
     * list not read yet) changes nothing, so a slow tab cannot wipe a run.
     *
     * @return whether the state was reset
     */
    public boolean syncLobby(String server) {
        if (server == null || server.equals(lobby)) {
            return false;
        }
        boolean had = started();
        lobby = server;
        clear();
        return had;
    }

    /**
     * Applies one chat event.
     *
     * <p>A tool line after the Jade Crystal was collected starts a new run in the same lobby rather
     * than being read as a step back in the old one.
     *
     * @return whether anything changed
     */
    public boolean apply(DivanChat.Event event) {
        if (event == null) {
            return false;
        }
        return switch (event) {
            case DivanChat.ToolFound found -> setStep(found.tool(), Step.FOUND);
            case DivanChat.ToolReturned returned -> setStep(returned.tool(), Step.RETURNED);
            case DivanChat.AllReturned all -> allReturned();
            case DivanChat.JadeNotCollected notYet -> allReturned();
            case DivanChat.ChestLoot loot -> false;
        };
    }

    /**
     * One reading of the Nucleus tracker: does its open run have the Jade crystal found or placed.
     * The first reading after the crystal became ready is the baseline; a change from "no" to "yes"
     * after that is the crystal being collected.
     *
     * @return whether this collected the crystal
     */
    public boolean observeJade(boolean nucleusHasJade) {
        if (jade != Jade.READY) {
            jadeSeenAtReady = null;
            return false;
        }
        if (jadeSeenAtReady == null) {
            jadeSeenAtReady = nucleusHasJade;
            return false;
        }
        if (!jadeSeenAtReady && nucleusHasJade) {
            jade = Jade.COLLECTED;
            jadeSeenAtReady = null;
            return true;
        }
        return false;
    }

    /** The alert line for a found tool: {@code return it to the Keeper of Diamond (3/4)}. */
    public static String returnHint(DivanTool tool, int obtained) {
        return "return it to the Keeper of " + tool.keeper() + " (" + obtained + "/"
                + DivanTool.values().length + ")";
    }

    private boolean setStep(DivanTool tool, Step step) {
        if (tool == null) {
            return false;
        }
        if (jade == Jade.COLLECTED) {
            clear();
        }
        return steps.put(tool, step) != step;
    }

    private boolean allReturned() {
        boolean changed = jade != Jade.READY;
        for (DivanTool tool : DivanTool.values()) {
            changed |= steps.put(tool, Step.RETURNED) != Step.RETURNED;
        }
        if (jade != Jade.READY) {
            jadeSeenAtReady = null;
        }
        jade = Jade.READY;
        return changed;
    }

    private void clear() {
        for (DivanTool tool : DivanTool.values()) {
            steps.put(tool, Step.MISSING);
        }
        jade = Jade.WAITING;
        jadeSeenAtReady = null;
    }
}
