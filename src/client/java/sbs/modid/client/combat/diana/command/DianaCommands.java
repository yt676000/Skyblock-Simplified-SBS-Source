/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.command;

import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.diana.logic.ArrowGuess;
import sbs.modid.client.combat.diana.logic.BurrowChat;
import sbs.modid.client.combat.diana.logic.BurrowDetector;
import sbs.modid.client.combat.diana.logic.BurrowStore;
import sbs.modid.client.combat.diana.logic.ChainTracker;
import sbs.modid.client.combat.diana.logic.DianaDebug;
import sbs.modid.client.combat.diana.logic.DianaGuard;
import sbs.modid.client.combat.diana.logic.DianaEvent;
import sbs.modid.client.combat.diana.logic.DianaParticles;
import sbs.modid.client.combat.diana.logic.DianaPrompts;
import sbs.modid.client.combat.diana.logic.DianaTracker;
import sbs.modid.client.combat.diana.logic.MythMobTracker;
import sbs.modid.client.combat.diana.logic.SpadeGuess;
import sbs.modid.client.combat.diana.logic.SphinxAnswers;
import sbs.modid.client.combat.diana.model.DianaParticleData;
import sbs.modid.client.combat.diana.render.BurrowMarkers;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.Locale;

/**
 * {@code /sbs diana} - what the toolkit currently believes, and how to make it forget.
 *
 * <h2>Why a status command and not just a log</h2>
 *
 * <p>The commonest thing to go wrong with this feature is that it does nothing, and "nothing" has
 * half a dozen causes that look identical from the outside: the module is off, the event is not
 * running, the player is not in the Hub, the particle table's numbers do not match what this client
 * receives. A feature that draws nothing without saying why is the one reported as broken while it
 * is working exactly as told, so the bare form answers that question first and in the player's
 * terms.
 *
 * <p>{@code clear} exists because every marker here is inferred. When the inference is wrong the
 * player needs to be able to say so and start again, without relogging.
 */
public final class DianaCommands {

    private DianaCommands() {
    }

    /** {@code /sbs diana [status|clear|debug]}. Bare form reports. */
    public static void handle(String argument) {
        try {
            switch (argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT)) {
                case "clear", "reset" -> clear();
                case "debug" -> say("§7" + DianaDebug.getInstance().report());
                default -> status();
            }
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            fenced("/sbs diana", failure);
        }
    }

    /**
     * A Diana command threw. {@code /sbs} runs from the chat screen's input handler, which catches
     * nothing, so an uncaught throw here is a crash - and the moment someone runs {@code /sbs diana} is
     * typically just after the guard tripped, while the state these readouts walk is the state that
     * threw. Logged with the stack, said in one line, never re-thrown.
     */
    static void fenced(String command, Throwable failure) {
        SkyblockSimplifiedSBS.LOGGER.error("[SBS][Diana] {} threw", command, failure);
        say("§c" + command + " failed (" + failure + ") - details are in the game log.");
    }

    private static void status() {
        DianaParticleData data = DianaParticles.data();
        say("§bDiana toolkit");
        say("§7  event: §f" + DianaEvent.state()
                + "§7 · location: §f" + SkyBlockLocation.describe());
        say("§7  detector: §f" + BurrowDetector.getInstance().status());
        say("§7  burrows: §f" + BurrowStore.getInstance().status());
        say("§7  spade guess: §f" + SpadeGuess.getInstance().status());
        say("§7  arrow guess: §f" + ArrowGuess.getInstance().status());
        say("§7  chains: §f" + ChainTracker.getInstance().count()
                + "§7 · burrows dug this session: §f" + DianaTracker.getInstance().burrows());
        say("§7  creatures: §f" + MythMobTracker.getInstance().status());
        String stoodDown = DianaGuard.status();
        if (!stoodDown.isEmpty()) {
            say("§c  tracker " + stoodDown + " §7(/sbs diana clear)");
        }
        if (data == null) {
            say("§c  constants: not loaded - the toolkit cannot classify anything");
        } else {
            say("§7  constants: §f" + data.signatures.size() + " signature(s), "
                    + data.arrowBands.size() + " arrow band(s), v" + data.dataVersion()
                    + " (" + DianaParticles.source() + ")");
            if (data.arrowBands.isEmpty()) {
                say("§8  no arrow range bands are recorded, so guesses are not filtered by distance");
            }
        }
        say("§8  /sbs diana clear · /sbs diana debug · /sbs particleprobe arm");
    }

    /**
     * Forgets everything inferred.
     *
     * <p>Every marker, every guess, the chain count and the near-miss tally. Deliberately not the
     * session totals: those are a record of what the player did, and throwing them away because a
     * waypoint was in the wrong place would lose something they cannot get back.
     */
    private static void clear() {
        forget();
        say("§aCleared every burrow, guess and chain. Session totals are kept.");
    }

    /**
     * Forgets everything inferred and re-enables a tracker the guard disabled - {@code /sbs diana clear}
     * and {@code /sbs devlog diana rearm}. Rearming clears the state too, because the state the failure
     * left behind is the state that just threw, and because the world-change reset does not run while
     * the tracker is disabled.
     */
    public static void forget() {
        BurrowStore.getInstance().clear();
        ArrowGuess.getInstance().reset();
        SpadeGuess.getInstance().reset();
        ChainTracker.getInstance().reset();
        MythMobTracker.getInstance().reset();
        BurrowChat.getInstance().reset();
        SphinxAnswers.getInstance().reset();
        DianaPrompts.reset();
        DianaDebug.getInstance().reset();
        BurrowDetector.getInstance().reset();
        BurrowMarkers.clear();
        // A tracker the guard disabled comes back too - the player asked for a fresh start.
        DianaGuard.clear();
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
