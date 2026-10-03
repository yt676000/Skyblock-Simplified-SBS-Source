/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.diana.model.BurrowSignature;
import sbs.modid.client.combat.diana.model.DianaParticleData;
import sbs.modid.client.combat.diana.model.SignatureRole;
import sbs.modid.client.core.config.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the toolkit actually saw, as opposed to what it recognised.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Not one constant this feature runs on has been observed arriving on this client. They describe
 * a server reached through a translation layer, written down by someone reading a different client
 * generation. So the interesting output is not "a burrow was found" - it is <b>the packet that came
 * close and was refused</b>, because that is the one that tells you the count is 4 where the table
 * says 5, and it is the difference between a session of guessing and a one-line fix to a JSON file.
 *
 * <h2>A tally, not a transcript</h2>
 *
 * <p>An unmatched-particle log in a populated Hub is thousands of lines a minute and is unreadable
 * by the time it is written. So near-misses are <b>aggregated by shape</b>: one entry per distinct
 * (type, count, speed) triple with a count beside it. Anything genuinely interesting is a shape that
 * repeats, and a tally makes that obvious where a transcript buries it.
 *
 * <p>The full transcript already exists and is better than anything this could write:
 * {@code /sbs particleprobe} records every packet with its offsets, its direction and the player's
 * position, and {@code /sbs chatprobe} does the same for chat. This is the live readout for the
 * moment-to-moment case - "why is nothing happening right now" - not a replacement for either.
 *
 * <h2>Cost while off</h2>
 *
 * <p>One boolean read per packet. The tally is only touched once the switch is on, and it is capped
 * so a forgotten debug session cannot grow a map for the rest of the evening.
 */
public final class DianaDebug {

    private static final DianaDebug INSTANCE = new DianaDebug();

    /** Distinct near-miss shapes remembered. Past this the counting stops rather than the map grows. */
    private static final int MAX_SHAPES = 200;

    /** How often a shape is allowed to reach the log, however often it arrives. */
    private static final long LOG_THROTTLE_MS = 10_000L;

    /**
     * Particle types worth recording as near-misses, beyond the ones the document already names.
     *
     * <p>The document's own types are added to this at match time, so correcting the JSON widens the
     * log automatically. This list is the other half: the types a signature might have got
     * <b>wrong</b>, which by definition are not in the document. Without it, a table naming
     * {@code dust} for the arrow when the game sends {@code dust_color_transition} would show
     * nothing at all here - the one shape that mattered, filtered out by the filter meant to find it.
     */
    private static final String[] INTERESTING_TYPES = {
            "minecraft:crit", "minecraft:enchant", "minecraft:enchanted_hit", "minecraft:crit_magic",
            "minecraft:dripping_lava", "minecraft:falling_lava", "minecraft:landing_lava",
            "minecraft:lava", "minecraft:drip_lava",
            "minecraft:large_smoke", "minecraft:smoke", "minecraft:campfire_cosy_smoke",
            "minecraft:dust", "minecraft:dust_color_transition", "minecraft:trail",
            "minecraft:entity_effect", "minecraft:instant_effect", "minecraft:effect",
            "minecraft:witch", "minecraft:happy_villager", "minecraft:angry_villager",
            "minecraft:enchanted_hit_magic", "minecraft:damage_indicator", "minecraft:magic_crit",
    };

    /** Shape -> how often it arrived and when it was last logged. */
    private final Map<String, long[]> nearMisses = new LinkedHashMap<>();

    /** Role -> how often a packet matched it. The positive half of the same question. */
    private final Map<SignatureRole, long[]> matched = new LinkedHashMap<>();

    private DianaDebug() {
    }

    public static DianaDebug getInstance() {
        return INSTANCE;
    }

    private static boolean on() {
        return ConfigManager.getInstance().get().diana.debugLog;
    }

    /**
     * A packet that matched no signature.
     *
     * <p>Filtered to the particle types any Diana signature mentions. Every other particle in the
     * Hub is a torch, a villager or somebody's aura, and recording those would bury the one shape
     * that matters under ten thousand that do not.
     */
    public void onUnmatchedParticle(String type, int count, double speed,
                                    double offX, double offY, double offZ) {
        if (!on() || !interesting(type)) {
            return;
        }
        String shape = String.format(Locale.ROOT, "%s n=%d spd=%.4f off=%.3f,%.3f,%.3f",
                type, count, speed, offX, offY, offZ);
        long[] entry = nearMisses.get(shape);
        if (entry == null) {
            if (nearMisses.size() >= MAX_SHAPES) {
                return;
            }
            entry = new long[2];
            nearMisses.put(shape, entry);
        }
        entry[0]++;
        long now = System.currentTimeMillis();
        if (now - entry[1] >= LOG_THROTTLE_MS) {
            entry[1] = now;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Diana] Unmatched particle x{}: {} - no signature claims this shape",
                    entry[0], shape);
        }
    }

    /** A packet that matched. Counted always; logged never - the tally is the useful part. */
    public void onMatchedParticle(SignatureRole role, String type, int count, double speed) {
        if (!on() || role == null) {
            return;
        }
        matched.computeIfAbsent(role, r -> new long[1])[0]++;
    }

    /** Something worth one line in the log while debugging: a burrow found, a chain popped. */
    public void note(String message) {
        if (on()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Diana] {}", message);
        }
    }

    /**
     * A chat line the ritual parser looked at and did not claim.
     *
     * <p>Deliberately narrower than the particle equivalent: only lines that mention the event at
     * all reach here, because logging every chat line is what {@code /sbs chatprobe} is for.
     */
    public void onUnmatchedChat(String line) {
        if (on() && line != null && !line.isBlank()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Diana] Unclaimed ritual line: {}", line);
        }
    }

    /** A nametag that looked mythological but named no creature we know. The names are hypotheses. */
    public void onUnknownCreature(String name) {
        if (on() && name != null && !name.isBlank()) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Diana] Mythological nametag naming no creature this build knows: {}", name);
        }
    }

    /**
     * Whether a packet is worth recording as a near-miss.
     *
     * <p>Two sources, and both are needed. A type the document names is interesting because the
     * shape around it is what we got wrong; a type on the fallback list is interesting because the
     * document may have named the wrong type entirely. Everything else in the Hub is a torch, a
     * villager or somebody's aura, and recording those buries the one shape that matters under ten
     * thousand that do not.
     */
    private static boolean interesting(String type) {
        if (type == null || type.isEmpty()) {
            return false;
        }
        for (String candidate : INTERESTING_TYPES) {
            if (candidate.equals(type)) {
                return true;
            }
        }
        DianaParticleData document = DianaParticles.data();
        if (document == null || document.signatures == null) {
            return false;
        }
        for (BurrowSignature signature : document.signatures) {
            if (signature == null || signature.types == null) {
                continue;
            }
            for (String named : signature.types) {
                if (type.equalsIgnoreCase(named)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The near-miss tally, worst offender first, for {@code /sbs diana debug}. */
    public String report() {
        if (nearMisses.isEmpty() && matched.isEmpty()) {
            return on()
                    ? "nothing recorded yet - stand in the Hub during the event"
                    : "off - switch on \"Log Unrecognised Signals\" in the Diana settings";
        }
        StringBuilder out = new StringBuilder(256);
        out.append("matched: ");
        if (matched.isEmpty()) {
            out.append("nothing");
        } else {
            matched.forEach((role, count) -> out.append(role).append('=').append(count[0]).append(' '));
        }
        out.append("| near misses: ").append(nearMisses.size()).append(" shape(s)");
        nearMisses.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(5)
                .forEach(e -> out.append("\n  ").append(e.getValue()[0]).append("x  ").append(e.getKey()));
        return out.toString();
    }

    /** World change, or the player asking. */
    public void reset() {
        nearMisses.clear();
        matched.clear();
    }
}
