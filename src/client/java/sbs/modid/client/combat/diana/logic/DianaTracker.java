/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonObject;
import sbs.modid.client.combat.diana.model.MythCreature;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.tracker.TrackerStore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What this session's ritual has produced: burrows dug, creatures spawned, loot found, and the two
 * "how long since" counters everybody actually watches.
 *
 * <h2>Session here, lifetime in the shared store</h2>
 *
 * <p>The numbers on the card are this session's, held here and thrown away with the client. The
 * lifetime tallies go to {@code core/tracker/TrackerStore} like every other tracker in the mod, so
 * they survive, get flushed on a world change with everybody else's, and do not need a second
 * persistence path invented for one feature.
 *
 * <h2>The "since" counters and why they read the way they do</h2>
 *
 * <p>"Mobs since an Inquisitor" is the number the event is actually played for, and it is a counter
 * that resets rather than an average. Presenting it as a rate would be worse than useless: the drop
 * is memoryless, so an average tells the player nothing about the next one, while the raw count is
 * at least an honest statement of what has happened.
 *
 * <h2>What is counted, and what is guessed</h2>
 *
 * <p>Everything here comes off chat lines whose wording Hypixel owns. A reworded line stops being
 * counted; it never gets counted as the wrong thing, because the classifier only ever promotes a
 * line it recognised. A total that stops moving is a visible failure, which is the failure mode
 * worth having.
 */
public final class DianaTracker {

    private static final DianaTracker INSTANCE = new DianaTracker();

    /** The tracker name every lifetime tally here is filed under. */
    private static final String TRACKER = "diana";

    private long burrows;
    private long creatures;
    private long treasures;

    /** Creature name -> how many this session. Display order is discovery order. */
    private final Map<String, long[]> perCreature = new LinkedHashMap<>();

    /** Loot name -> how many this session. */
    private final Map<String, long[]> perTreasure = new LinkedHashMap<>();

    private long mobsSinceInquisitor;
    private long mobsSinceKing;

    /** The last rare creature a burrow of the player's own spawned, or {@code null}. */
    private volatile MythCreature lastRareSpawn;

    /** When {@link #lastRareSpawn} was announced by chat. */
    private volatile long lastRareSpawnAt;

    /**
     * How long after the chat line a creature still counts as the one it named.
     *
     * <p>The nametag stand does not exist at the instant the message arrives, and it takes a moment
     * to be named after that, so the window has to cover the gap between "you dug it" and "the
     * client can see it". Generous rather than tight: the cost of being late is a spawn that is not
     * shared, and the cost of being early is nothing, because a creature has to be sighted as well.
     */
    private static final long OWN_SPAWN_MS = 15_000L;

    /** When this session's counting started, so the card can say what "session" means. */
    private long startedAt = System.currentTimeMillis();

    private DianaTracker() {
    }

    public static DianaTracker getInstance() {
        return INSTANCE;
    }

    private static boolean on() {
        var cfg = ConfigManager.getInstance().get().diana;
        return cfg.enabled && cfg.tracker;
    }

    /** A burrow was dug out. */
    public void onBurrowDug() {
        if (!on()) {
            return;
        }
        burrows++;
        TrackerStore.record(TRACKER, "BURROWS", 1);
    }

    /**
     * A burrow spawned a creature.
     *
     * <p>The name arrives as whatever the sentence said, so it is tidied for display and matched
     * loosely against the four rare ones. A name that matches none of them is still counted - it is
     * a mythological creature either way, and the tally is what tells us the name table is short.
     */
    public void onCreatureSpawned(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return;
        }
        String name = MythCreature.stripVariantPrefix(rawName.trim());

        // Recorded before the counting gate, and deliberately: "a burrow I dug just spawned this"
        // is the only evidence anywhere that a creature is the player's own, and the party
        // announcement needs it whether or not they also asked for the tallies. Tying the two
        // together would mean switching the tracker off silently stops the sharing working.
        MythCreature spawned = MythMobTracker.creatureFor(name);
        if (spawned != null) {
            lastRareSpawn = spawned;
            lastRareSpawnAt = System.currentTimeMillis();
        }

        if (!on()) {
            return;
        }
        creatures++;
        mobsSinceInquisitor++;
        mobsSinceKing++;
        perCreature.computeIfAbsent(name, k -> new long[1])[0]++;
        TrackerStore.record(TRACKER, "CREATURES", 1);
        TrackerStore.record(TRACKER, key(name), 1);

        MythCreature rare = MythMobTracker.creatureFor(name);
        if (rare == MythCreature.INQUISITOR) {
            DianaDebug.getInstance().note("inquisitor after " + mobsSinceInquisitor + " creature(s)");
            mobsSinceInquisitor = 0;
        } else if (rare == MythCreature.KING) {
            mobsSinceKing = 0;
        } else if (rare == null) {
            DianaDebug.getInstance().onUnknownCreature(name);
        }
    }

    /** A burrow paid out. */
    public void onTreasure(String rawName) {
        if (!on() || rawName == null || rawName.isBlank()) {
            return;
        }
        String name = rawName.trim();
        treasures++;
        perTreasure.computeIfAbsent(name, k -> new long[1])[0]++;
        TrackerStore.record(TRACKER, "TREASURES", 1);
        TrackerStore.record(TRACKER, key(name), 1);
    }

    public long burrows() {
        return burrows;
    }

    public long creatures() {
        return creatures;
    }

    public long treasures() {
        return treasures;
    }

    public long mobsSinceInquisitor() {
        return mobsSinceInquisitor;
    }

    public long mobsSinceKing() {
        return mobsSinceKing;
    }

    /**
     * Whether a burrow the player dug has just spawned {@code creature}.
     *
     * <p>Chat is the only thing that ever says so - Hypixel sends the "you dug up a …" line to the
     * digger and to nobody else - which makes this the one available proof that a creature on screen
     * is the player's own rather than somebody else's forty blocks away.
     */
    public boolean justSpawned(MythCreature creature) {
        return creature != null && creature == lastRareSpawn
                && System.currentTimeMillis() - lastRareSpawnAt <= OWN_SPAWN_MS;
    }

    public long startedAt() {
        return startedAt;
    }

    /** Creature name to session count, in discovery order. */
    public Map<String, Long> creatureCounts() {
        return snapshot(perCreature);
    }

    /** Loot name to session count, in discovery order. */
    public Map<String, Long> treasureCounts() {
        return snapshot(perTreasure);
    }

    /** The session counters and the last own rare spawn, for the guard's error report. */
    public JsonObject snapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("burrows", burrows);
        out.addProperty("creatures", creatures);
        out.addProperty("treasures", treasures);
        out.addProperty("mobsSinceInquisitor", mobsSinceInquisitor);
        out.addProperty("mobsSinceKing", mobsSinceKing);
        out.addProperty("lastRareSpawn", String.valueOf(lastRareSpawn));
        out.addProperty("lastRareSpawnAt", lastRareSpawnAt);
        out.addProperty("startedAt", startedAt);
        JsonObject perCreatureJson = new JsonObject();
        creatureCounts().forEach(perCreatureJson::addProperty);
        out.add("perCreature", perCreatureJson);
        JsonObject perTreasureJson = new JsonObject();
        treasureCounts().forEach(perTreasureJson::addProperty);
        out.add("perTreasure", perTreasureJson);
        return out;
    }

    private static Map<String, Long> snapshot(Map<String, long[]> source) {
        Map<String, Long> out = new LinkedHashMap<>();
        source.forEach((name, count) -> out.put(name, count[0]));
        return out;
    }

    /**
     * Starts the session over.
     *
     * <p>Not called on a world change, deliberately. A session is the player's stint at the event,
     * and hopping lobbies is part of doing it - a counter that reset every hop would answer a
     * question nobody is asking. The lifetime tallies are flushed on the hop by the shared store;
     * these are not the same numbers.
     */
    public void resetSession() {
        burrows = 0;
        creatures = 0;
        treasures = 0;
        perCreature.clear();
        perTreasure.clear();
        mobsSinceInquisitor = 0;
        mobsSinceKing = 0;
        startedAt = System.currentTimeMillis();
    }

    /** "COINS", "GRIFFIN_FEATHER" - the shape the shared tracker store keys on. */
    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_')
                .replaceAll("[^A-Z0-9_]", "");
    }
}
