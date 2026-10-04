/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import sbs.modid.client.combat.diana.logic.ArrowGuess;
import sbs.modid.client.combat.diana.logic.BurrowStore;
import sbs.modid.client.combat.diana.logic.DianaMarkerStyles;
import sbs.modid.client.combat.diana.logic.DianaPreview;
import sbs.modid.client.combat.diana.logic.GuessChain;
import sbs.modid.client.combat.diana.logic.MythMobTracker;
import sbs.modid.client.combat.diana.logic.SpadeGuess;
import sbs.modid.client.combat.diana.logic.WarpSuggestion;
import sbs.modid.client.combat.diana.model.BurrowRecord;
import sbs.modid.client.combat.diana.model.DianaMarker;
import sbs.modid.client.combat.diana.model.MythCreature;
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
 * be the obvious arrangement and would be the bug. The appearance preview's samples are a fourth
 * tag, so clearing them can never touch a real marker.
 *
 * <h2>Republished on a tick, mutated in between</h2>
 *
 * <p>Publishing invalidates the pathfinder, so doing it every frame would mean a search that
 * restarts forever. The sets are rebuilt on the tick and only when something has actually changed -
 * including the marker styles, whose {@link DianaMarkerStyles#stamp} is part of every signature; the
 * live distance readout on each marker is the renderer's job and costs nothing.
 *
 * <h2>Nothing here is persisted</h2>
 *
 * <p>Every set is transient. A burrow belongs to one Hub on one server and has usually been dug out
 * within the minute - it has no business in {@code config.json}, and a marker surviving a restart
 * would be a marker pointing at a hole that was filled in yesterday.
 */
public final class BurrowMarkers {

    /** How much fainter a runner-up guess is drawn than the live one, in percent of the guess's own. */
    private static final int ALTERNATIVE_OPACITY = 35;

    /** Guesses fade out as the player arrives, because by then the marker is in the way. */
    private static final int GUESS_FADE_WITHIN = 4;

    /** Creature markers are worth seeing from anywhere in the Hub. */
    private static final int CREATURE_MAX_DISTANCE = 0;

    /** How far in front of the player the preview row of samples starts, and their spacing. */
    private static final int PREVIEW_DISTANCE = 6;
    private static final int PREVIEW_SPACING = 3;

    /** What was last published, so an unchanged set is not republished at tick rate. */
    private static String lastBurrowSignature = "";
    private static String lastGuessSignature = "";
    private static String lastCreatureSignature = "";
    private static String lastPreviewSignature = "";

    private BurrowMarkers() {
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /** Called once per client tick. Cheap when nothing has changed, which is most ticks. */
    public static void onClientTick() {
        SBSConfig.DianaSettings cfg = cfg();
        publishPreview(cfg);
        if (!cfg.enabled) {
            clearLive();
            return;
        }
        String dimension = WaypointStore.currentDimension();
        int stamp = DianaMarkerStyles.stamp(cfg);
        publishBurrows(cfg, dimension, stamp);
        publishGuesses(cfg, dimension, stamp);
        publishCreatures(cfg, dimension, stamp);
    }

    private static void publishBurrows(SBSConfig.DianaSettings cfg, String dimension, int stamp) {
        if (!cfg.burrowWaypoints || !cfg.detectBurrows) {
            if (!lastBurrowSignature.isEmpty()) {
                WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_BURROW);
                lastBurrowSignature = "";
            }
            return;
        }
        List<BurrowRecord> records = BurrowStore.getInstance().classified();
        StringBuilder signature = new StringBuilder(records.size() * 16 + 12).append(stamp).append('|');
        List<Waypoint> out = new ArrayList<>(records.size());
        for (BurrowRecord record : records) {
            signature.append(record.pos).append(record.kind).append(record.timesDug).append(';');
            Waypoint waypoint = new Waypoint(
                    cfg.showBurrowKind ? record.label() : "Burrow",
                    record.pos, dimension, Waypoint.SOURCE_DIANA_BURROW);
            DianaMarker type = markerFor(record);
            if (type != null) {
                apply(waypoint, DianaMarkerStyles.resolve(cfg, type, ""), 100);
            } else {
                // A burrow whose kind particle has not arrived yet has no type to style it by, so it
                // keeps the look every burrow had before styles existed: the global preset colour.
                waypoint.colorHex = "";
                waypoint.showDistance = true;
                waypoint.throughWalls = true;
            }
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
    private static void publishGuesses(SBSConfig.DianaSettings cfg, String dimension, int stamp) {
        if (!cfg.spadeGuess && !cfg.arrowGuess) {
            if (!lastGuessSignature.isEmpty()) {
                WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_GUESS);
                lastGuessSignature = "";
            }
            return;
        }
        List<Waypoint> out = new ArrayList<>();
        StringBuilder signature = new StringBuilder(64).append(stamp).append('|');
        DianaMarkerStyles.Resolved style = DianaMarkerStyles.resolve(cfg, DianaMarker.GUESS, "");

        if (cfg.arrowGuess) {
            for (GuessChain chain : ArrowGuess.getInstance().chains()) {
                BlockPos live = chain.current();
                if (live == null) {
                    continue;
                }
                signature.append(live).append('!');
                out.add(guess(style, "Guess", live, dimension, true));
                if (!cfg.showAlternativeGuesses) {
                    continue;
                }
                for (BlockPos alternative : chain.remaining()) {
                    signature.append(alternative).append('?');
                    out.add(guess(style, cfg.labelAlternatives ? "Possible" : "",
                            alternative, dimension, false));
                }
            }
        }
        if (cfg.spadeGuess) {
            BlockPos spade = SpadeGuess.getInstance().guess();
            if (spade != null) {
                signature.append(spade).append('s');
                out.add(guess(style, "Spade Guess", spade, dimension, true));
            }
        }
        if (!signature.toString().equals(lastGuessSignature)) {
            lastGuessSignature = signature.toString();
            WaypointStore.setTransient(Waypoint.SOURCE_DIANA_GUESS, out);
        }
    }

    private static Waypoint guess(DianaMarkerStyles.Resolved style, String label, BlockPos pos,
                                  String dimension, boolean live) {
        Waypoint waypoint = new Waypoint(label, pos, dimension, Waypoint.SOURCE_DIANA_GUESS);
        apply(waypoint, style, live ? 100 : ALTERNATIVE_OPACITY);
        waypoint.fadeWithin = GUESS_FADE_WITHIN;
        // A guess is a place nobody has stood on. Handing it to the pathfinder as a destination
        // would have it route confidently to a coordinate that may be a hillside, so the marker and
        // its distance are the honest amount to claim - the same call the preset publishers make.
        waypoint.routable = false;
        if (live) {
            String warp = WarpSuggestion.labelFor(pos);
            if (!warp.isEmpty()) {
                waypoint.subLabel = "warp " + warp.toLowerCase(Locale.ROOT);
            }
        }
        return waypoint;
    }

    private static void publishCreatures(SBSConfig.DianaSettings cfg, String dimension, int stamp) {
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
        StringBuilder signature = new StringBuilder(64).append(stamp).append('|');
        for (MythMobTracker.Sighting sighting : sightings) {
            // A creature we can see is only marked when the player asked for that; one somebody
            // shared is marked whenever receiving is on. Two switches because they are two different
            // things - one is about what our client does with its own eyes, the other about whether
            // we believe the party.
            if (!sighting.shared() && !cfg.markVisibleCreatures) {
                continue;
            }
            signature.append(sighting.pos()).append(sighting.creature()).append(';');
            out.add(creature(cfg, sighting.creature(), sighting.label(), sighting.pos(), dimension,
                    sighting.shared(), creatureSubLabel(sighting), Waypoint.SOURCE_DIANA_CREATURE));
        }
        if (!signature.toString().equals(lastCreatureSignature)) {
            lastCreatureSignature = signature.toString();
            WaypointStore.setTransient(Waypoint.SOURCE_DIANA_CREATURE, out);
        }
    }

    private static Waypoint creature(SBSConfig.DianaSettings cfg, MythCreature creature, String label,
                                     BlockPos pos, String dimension, boolean shared, String subLabel,
                                     String source) {
        Waypoint waypoint = new Waypoint(label, pos, dimension, source);
        String own = String.format(Locale.ROOT, "%06X", creature.color() & 0xFFFFFF);
        DianaMarker type = shared ? DianaMarker.SHARED_CREATURE : DianaMarker.RARE_CREATURE;
        apply(waypoint, DianaMarkerStyles.resolve(cfg, type, own), 100);
        waypoint.routable = false;
        waypoint.maxDistance = CREATURE_MAX_DISTANCE;
        waypoint.subLabel = subLabel;
        return waypoint;
    }

    private static String creatureSubLabel(MythMobTracker.Sighting sighting) {
        if (sighting.shared()) {
            return sighting.sharedBy();
        }
        if (sighting.hits() != null) {
            return sighting.hits() + " hits";
        }
        return sighting.health() >= 0 ? MythMobTracker.format(sighting.health()) : "";
    }

    /** Copies a resolved style onto a waypoint; {@code percent} scales the style's own opacity. */
    private static void apply(Waypoint waypoint, DianaMarkerStyles.Resolved style, int percent) {
        waypoint.colorHex = style.colorHex();
        waypoint.box = style.box();
        waypoint.beam = style.beam();
        waypoint.labelSize = style.labelSize();
        waypoint.showDistance = style.showDistance();
        waypoint.opacity = Math.max(1, style.opacity() * percent / 100);
        waypoint.throughWalls = style.throughWalls();
    }

    private static DianaMarker markerFor(BurrowRecord record) {
        if (record.kind == null) {
            return null;
        }
        return switch (record.kind) {
            case START -> DianaMarker.START_BURROW;
            case MOB -> DianaMarker.MOB_BURROW;
            case TREASURE -> DianaMarker.TREASURE_BURROW;
        };
    }

    // ------------------------------------------------------------------ preview

    /**
     * One sample marker of each type in a row in front of the player, while the appearance preview
     * runs. Anchored where the player stood when it started and republished only when a style
     * changes, so the row stays put while the player turns to look at it.
     */
    private static void publishPreview(SBSConfig.DianaSettings cfg) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!DianaPreview.active(cfg) || minecraft.player == null) {
            if (!lastPreviewSignature.isEmpty()) {
                WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_PREVIEW);
                lastPreviewSignature = "";
            }
            return;
        }
        int stamp = DianaMarkerStyles.stamp(cfg);
        if (lastPreviewSignature.startsWith(stamp + "|")) {
            return;
        }
        String dimension = WaypointStore.currentDimension();
        BlockPos feet = minecraft.player.blockPosition();
        Direction facing = minecraft.player.getDirection();
        Direction side = facing.getClockWise();
        BlockPos first = feet.relative(facing, PREVIEW_DISTANCE)
                .relative(side.getOpposite(), PREVIEW_SPACING * (DianaMarker.values().length - 1) / 2);
        if (!lastPreviewSignature.isEmpty()) {
            // Restyled while already showing: keep the row where it is rather than chasing the player.
            first = previewAnchor;
            facing = previewFacing;
            side = facing.getClockWise();
        }
        previewAnchor = first;
        previewFacing = facing;

        List<Waypoint> out = new ArrayList<>();
        int i = 0;
        for (DianaMarker type : DianaMarker.values()) {
            BlockPos pos = first.relative(side, PREVIEW_SPACING * i++);
            String label = "Sample " + type.displayName();
            Waypoint waypoint;
            switch (type) {
                case RARE_CREATURE -> waypoint = creature(cfg, MythCreature.INQUISITOR, label, pos,
                        dimension, false, "18.4M", Waypoint.SOURCE_DIANA_PREVIEW);
                case SHARED_CREATURE -> waypoint = creature(cfg, MythCreature.MANTICORE, label, pos,
                        dimension, true, "shared", Waypoint.SOURCE_DIANA_PREVIEW);
                default -> {
                    waypoint = new Waypoint(label, pos, dimension, Waypoint.SOURCE_DIANA_PREVIEW);
                    apply(waypoint, DianaMarkerStyles.resolve(cfg, type, ""), 100);
                    waypoint.routable = false;
                    if (type == DianaMarker.GUESS) {
                        waypoint.subLabel = "warp hub";
                    }
                }
            }
            out.add(waypoint);
        }
        lastPreviewSignature = stamp + "|" + first;
        WaypointStore.setTransient(Waypoint.SOURCE_DIANA_PREVIEW, out);
    }

    private static BlockPos previewAnchor;
    private static Direction previewFacing;

    // ------------------------------------------------------------------ clearing

    /** Everything the toolkit itself publishes; the preview answers to its own switch. */
    private static void clearLive() {
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

    /** World change, module switched off, or the player asking. */
    public static void clear() {
        clearLive();
        if (!lastPreviewSignature.isEmpty()) {
            WaypointStore.clearTransient(Waypoint.SOURCE_DIANA_PREVIEW);
            lastPreviewSignature = "";
        }
    }
}
