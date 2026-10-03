/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.render;

import net.minecraft.core.BlockPos;
import sbs.modid.client.combat.diana.logic.ArrowGuess;
import sbs.modid.client.combat.diana.logic.BurrowStore;
import sbs.modid.client.combat.diana.logic.GuessChain;
import sbs.modid.client.combat.diana.logic.MythMobTracker;
import sbs.modid.client.combat.diana.logic.SpadeGuess;
import sbs.modid.client.combat.diana.logic.WarpSuggestion;
import sbs.modid.client.combat.diana.model.BurrowRecord;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the Diana toolkit puts in the world, published as three marker sets.
 *
 * <h2>One publisher, three sources</h2>
 *
 * <p>{@code WaypointStore.setTransient} replaces a source's <b>whole</b> set in one call, which is
 * what makes a stale marker structurally impossible - and also what makes two publishers sharing one
 * source erase each other. So burrows, guesses and creatures each get their own tag, and all three
 * are published from this one class. Splitting the publishing across the three logic classes would
 * be the obvious arrangement and would be the bug.
 *
 * <h2>Republished on a tick, mutated in between</h2>
 *
 * <p>Publishing invalidates the pathfinder, so doing it every frame would mean a search that
 * restarts forever. The sets are rebuilt on the tick and only when something has actually changed;
 * the live distance readout on each marker is the renderer's job and costs nothing.
 *
 * <h2>Nothing here is persisted</h2>
 *
 * <p>Every set is transient. A burrow belongs to one Hub on one server and has usually been dug out
 * within the minute - it has no business in {@code config.json}, and a marker surviving a restart
 * would be a marker pointing at a hole that was filled in yesterday.
 */
public final class BurrowMarkers {

    /** How much fainter a runner-up guess is drawn than the live one. */
    private static final int ALTERNATIVE_OPACITY = 35;

    /** Guesses fade out as the player arrives, because by then the marker is in the way. */
    private static final int GUESS_FADE_WITHIN = 4;

    /** Creature markers are worth seeing from anywhere in the Hub. */
    private static final int CREATURE_MAX_DISTANCE = 0;

    /** What was last published, so an unchanged set is not republished at tick rate. */
    private static String lastBurrowSignature = "";
    private static String lastGuessSignature = "";
    private static String lastCreatureSignature = "";

    private BurrowMarkers() {
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /** Called once per client tick. Cheap when nothing has changed, which is most ticks. */
    public static void onClientTick() {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled) {
            clear();
            return;
        }
        String dimension = WaypointStore.currentDimension();
        publishBurrows(cfg, dimension);
        publishGuesses(cfg, dimension);
        publishCreatures(cfg, dimension);
    }

    private static void publishBurrows(SBSConfig.DianaSettings cfg, String dimension) {
        if (!cfg.burrowWaypoints || !cfg.detectBurrows) {
            if (!lastBurrowSignature.isEmpty()) {
                WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_BURROW);
                lastBurrowSignature = "";
            }
            return;
        }
        List<BurrowRecord> records = BurrowStore.getInstance().classified();
        StringBuilder signature = new StringBuilder(records.size() * 16);
        List<Waypoint> out = new ArrayList<>(records.size());
        for (BurrowRecord record : records) {
            signature.append(record.pos).append(record.kind).append(record.timesDug).append(';');
            Waypoint waypoint = new Waypoint(
                    cfg.showBurrowKind ? record.label() : "Burrow",
                    record.pos, dimension, Waypoint.SOURCE_DIANA_BURROW);
            waypoint.colorHex = colorFor(cfg, record);
            waypoint.showDistance = true;
            waypoint.throughWalls = true;
            if (record.timesDug > 0) {
                waypoint.subLabel = record.timesDug + "/" + record.digsNeeded() + " dug";
            }
            out.add(waypoint);
        }
        if (!signature.toString().equals(lastBurrowSignature)) {
            lastBurrowSignature = signature.toString();
            WaypointStore.setTransient(Waypoint.SOURCE_DIANA_BURROW, out);
        }
    }

    /**
     * The guesses: every chain's live candidate, the spade's, and the runners-up if asked for.
     *
     * <p>The runners-up are drawn much fainter and are labelled as possibilities rather than as
     * places, because the difference between "the burrow is here" and "the burrow might be here" is
     * the only thing standing between this feature and a player who has stopped trusting it.
     */
    private static void publishGuesses(SBSConfig.DianaSettings cfg, String dimension) {
        if (!cfg.spadeGuess && !cfg.arrowGuess) {
            if (!lastGuessSignature.isEmpty()) {
                WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_GUESS);
                lastGuessSignature = "";
            }
            return;
        }
        List<Waypoint> out = new ArrayList<>();
        StringBuilder signature = new StringBuilder(64);

        if (cfg.arrowGuess) {
            for (GuessChain chain : ArrowGuess.getInstance().chains()) {
                BlockPos live = chain.current();
                if (live == null) {
                    continue;
                }
                signature.append(live).append('!');
                out.add(guess(cfg, "Guess", live, dimension, 100));
                if (!cfg.showAlternativeGuesses) {
                    continue;
                }
                for (BlockPos alternative : chain.remaining()) {
                    signature.append(alternative).append('?');
                    out.add(guess(cfg, cfg.labelAlternatives ? "Possible" : "",
                            alternative, dimension, ALTERNATIVE_OPACITY));
                }
            }
        }
        if (cfg.spadeGuess) {
            BlockPos spade = SpadeGuess.getInstance().guess();
            if (spade != null) {
                signature.append(spade).append('s');
                out.add(guess(cfg, "Spade Guess", spade, dimension, 100));
            }
        }
        if (!signature.toString().equals(lastGuessSignature)) {
            lastGuessSignature = signature.toString();
            WaypointStore.setTransient(Waypoint.SOURCE_DIANA_GUESS, out);
        }
    }

    private static Waypoint guess(SBSConfig.DianaSettings cfg, String label, BlockPos pos,
                                  String dimension, int opacity) {
        Waypoint waypoint = new Waypoint(label, pos, dimension, Waypoint.SOURCE_DIANA_GUESS);
        waypoint.colorHex = cfg.guessColorHex;
        waypoint.opacity = opacity;
        waypoint.showDistance = true;
        waypoint.throughWalls = true;
        waypoint.fadeWithin = GUESS_FADE_WITHIN;
        // A guess is a place nobody has stood on. Handing it to the pathfinder as a destination
        // would have it route confidently to a coordinate that may be a hillside, so the marker and
        // its distance are the honest amount to claim - the same call the preset publishers make.
        waypoint.routable = false;
        if (opacity == 100) {
            String warp = WarpSuggestion.labelFor(pos);
            if (!warp.isEmpty()) {
                waypoint.subLabel = "warp " + warp.toLowerCase(Locale.ROOT);
            }
        }
        return waypoint;
    }

    private static void publishCreatures(SBSConfig.DianaSettings cfg, String dimension) {
        boolean wanted = cfg.markVisibleCreatures || cfg.receiveSharedCreatures;
        if (!wanted) {
            if (!lastCreatureSignature.isEmpty()) {
                WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_CREATURE);
                lastCreatureSignature = "";
            }
            return;
        }
        List<MythMobTracker.Sighting> sightings = MythMobTracker.getInstance().sightings();
        List<Waypoint> out = new ArrayList<>(sightings.size());
        StringBuilder signature = new StringBuilder(64);
        for (MythMobTracker.Sighting sighting : sightings) {
            // A creature we can see is only marked when the player asked for that; one somebody
            // shared is marked whenever receiving is on. Two switches because they are two different
            // things - one is about what our client does with its own eyes, the other about whether
            // we believe the party.
            if (!sighting.shared() && !cfg.markVisibleCreatures) {
                continue;
            }
            signature.append(sighting.pos()).append(sighting.creature()).append(';');
            Waypoint waypoint = new Waypoint(sighting.label(), sighting.pos(), dimension,
                    Waypoint.SOURCE_DIANA_CREATURE);
            waypoint.colorHex = String.format(Locale.ROOT, "%06X", sighting.creature().color() & 0xFFFFFF);
            waypoint.showDistance = true;
            waypoint.throughWalls = true;
            waypoint.routable = false;
            waypoint.maxDistance = CREATURE_MAX_DISTANCE;
            if (sighting.shared()) {
                waypoint.subLabel = sighting.sharedBy();
            } else if (sighting.hits() != null) {
                waypoint.subLabel = sighting.hits() + " hits";
            } else if (sighting.health() >= 0) {
                waypoint.subLabel = MythMobTracker.format(sighting.health());
            }
            out.add(waypoint);
        }
        if (!signature.toString().equals(lastCreatureSignature)) {
            lastCreatureSignature = signature.toString();
            WaypointStore.setTransient(Waypoint.SOURCE_DIANA_CREATURE, out);
        }
    }

    private static String colorFor(SBSConfig.DianaSettings cfg, BurrowRecord record) {
        if (record.kind == null) {
            return "";
        }
        return switch (record.kind) {
            case START -> cfg.startColorHex;
            case MOB -> cfg.mobColorHex;
            case TREASURE -> cfg.treasureColorHex;
        };
    }

    /** World change, module switched off, or the player asking. */
    public static void clear() {
        if (!lastBurrowSignature.isEmpty()) {
            WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_BURROW);
            lastBurrowSignature = "";
        }
        if (!lastGuessSignature.isEmpty()) {
            WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_GUESS);
            lastGuessSignature = "";
        }
        if (!lastCreatureSignature.isEmpty()) {
            WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_CREATURE);
            lastCreatureSignature = "";
        }
    }
}
