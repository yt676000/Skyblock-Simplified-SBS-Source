/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import sbs.modid.client.core.data.VersionedDocument;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Mythological Ritual's readable constants: which packet shape marks which kind of burrow, what
 * an arrow's colour says about distance, how long a chain runs, where the Hub's walls are, what may
 * grow on top of a burrow, and the curve the spade's ability is drawn along.
 *
 * <h2>Why this is data and not a switch statement</h2>
 *
 * <p>Every value in here is <b>unverified against this client</b>. They describe a server that ran
 * on an older protocol and now reaches a 26.2 client through a translation layer, so a particle
 * name that was right when it was written is not guaranteed to be the name that arrives - and the
 * counts, speeds and offsets are what the match actually turns on. Holding all of it in a bundled
 * document means a real capture ({@code /sbs particleprobe}) corrects it with a JSON edit and a
 * {@link #dataVersion} bump: no recompile, no release, and the same file can later be served from
 * the backend without touching this class.
 *
 * <h2>Schema 2: signatures, not a type map</h2>
 *
 * <p>Schema 1 mapped a particle <b>type</b> to a {@link BurrowKind}. That cannot work, and shipped
 * broken: the critical-hit particle is a mob burrow at one count and a footstep at another, so a
 * type map marks every footstep in the Hub as a burrow; the dripping-lava particle is a treasure
 * burrow at one speed and the spade's flight trail at another, so a type map folds the guess into
 * the marker. Schema 2 carries whole {@link BurrowSignature}s instead.
 *
 * <p><b>A schema-1 file is refused rather than migrated.</b> It cannot express a signature, so
 * migrating it would mean inventing the missing fields - which is the confident-wrong-value failure
 * this document exists to prevent. The bundled copy always ships, so refusing a stale cache costs
 * nothing.
 *
 * <h2>Absence is the safe answer</h2>
 *
 * <p>Every lookup returns null or empty for "not recorded", and no caller substitutes a default. A
 * packet this table does not know is not a burrow; a spade whose chain length is not recorded has an
 * unknown chain length and is reported that way. A wrong constant should cost a missing waypoint,
 * never a confident wrong one.
 *
 * <h2>What is deliberately empty</h2>
 *
 * <p>{@link #arrowBands} ships empty. Three sources describe the arrow colours and no two agree, and
 * a distance band is the worst kind of value to guess because a wrong one sends the player walking.
 * Empty degrades the arrow guess to "no range filter": more candidates, none confidently wrong.
 *
 * <p>Chain lengths past the Ancestral Spade are absent for the same reason. Four is documented, six
 * with the Erudite reforge is documented; that chains also exist at eight and ten is documented, but
 * which spade produces which is not, and guessing it would have the toolkit announce a chain
 * finished while a burrow is still out there.
 */
public final class DianaParticleData implements VersionedDocument {

    /** The schema this file is written in. {@code DianaParticles} refuses anything higher. */
    public int schemaVersion = 2;

    public int dataVersion;

    /** Every recognisable packet shape and what it means. The part nothing else works without. */
    public List<BurrowSignature> signatures = new ArrayList<>();

    /** How far a packet's speed may differ from a signature's and still match. */
    public double speedTolerance = 0.0005;

    /** How far each of a packet's offsets may differ from a signature's and still match. */
    public double offsetTolerance = 0.02;

    /** Arrow colour to distance band. Empty on purpose - see the class note. */
    public List<ArrowBand> arrowBands = new ArrayList<>();

    /** How far each arrow colour channel may differ and still be that band. Channels are 0..255. */
    public double arrowColorTolerance = 0.5;

    /** Chain length per spade tier and reforge. Absent combinations are unknown, not zero. */
    public List<Chain> chains = new ArrayList<>();

    /** The box the arrow guess is allowed to land a candidate in. Null means "do not bound it". */
    public Bounds hubBounds;

    /**
     * Block ids that may sit directly above a burrow.
     *
     * <p>Data because it is a fact about what grows on the Hub's lawn, and the lawn changes. An
     * empty list disables the check rather than rejecting everything: a missing list must not make
     * the whole guess system dark.
     */
    public List<String> groundCover = new ArrayList<>();

    /** Block ids a burrow may be in. Same rule: empty disables the check. */
    public List<String> groundBlocks = new ArrayList<>();

    /** The curve constants the spade guess extrapolates along. */
    public SpadeCurve spadeCurve = new SpadeCurve();

    /** One measured chain length. */
    public static final class Chain {
        public String tier;
        public boolean erudite;
        public int length;
    }

    /** An axis-aligned box, in world coordinates. */
    public static final class Bounds {
        public double minX;
        public double minY;
        public double minZ;
        public double maxX;
        public double maxY;
        public double maxZ;

        public boolean contains(double x, double y, double z) {
            return x > minX && x <= maxX && y > minY && y <= maxY && z > minZ && z <= maxZ;
        }

        public boolean usable() {
            return maxX > minX && maxY > minY && maxZ > minZ;
        }
    }

    /**
     * The constants describing the arc Hypixel draws for the spade's ability.
     *
     * <p><b>Nobody has derived these.</b> They plainly encode a curve whose control point is placed
     * as a function of launch pitch, but no derivation is recorded anywhere they appear, which is
     * exactly why they live in a file with a {@link #certainty} tag beside them rather than as
     * literals in the fitter. A capture of one ability use plus the burrow it actually pointed at
     * checks the whole pipeline end to end without anyone having to understand them.
     */
    public static final class SpadeCurve {

        /** Subtracted from the sine when inverting the observed pitch. */
        public double pitchShift = 0.75;

        /** Scales the sine term of the control-point distance. */
        public double controlScale = 24.0;

        /** Constant term of the control-point distance, under the square root. */
        public double controlOffset = 25.0;

        /** Multiplies the control-point distance when it becomes a curve parameter. */
        public double parameterScale = 3.0;

        /** How far below the fitted landing point the burrow's block sits. */
        public double landingDrop = 0.5;

        /** How much we actually know about the four numbers above. */
        public String certainty = Certainty.ESTIMATED.name();

        public Certainty resolvedCertainty() {
            try {
                return Certainty.valueOf(certainty.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return Certainty.UNKNOWN;
            }
        }
    }

    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    /**
     * A document that carries no usable signature cannot classify anything, so it is not a usable
     * copy however new it claims to be. Empty {@link #arrowBands}, {@link #chains} or
     * {@link #groundCover} are normal, honest states and say so.
     */
    @Override
    public boolean valid() {
        if (signatures == null) {
            return false;
        }
        for (BurrowSignature signature : signatures) {
            if (signature != null && signature.usable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a packet means, or {@code null} for every other particle in the Hub - which is almost all
     * of them, so this stays a short linear scan on a path that runs per packet.
     *
     * <p>A list scan rather than a map because the key is five fields wide and the list is under a
     * dozen entries; a map keyed on the type would still have to scan its bucket for the count and
     * the speed, and would hide the fact that two roles share a type.
     */
    public SignatureRole roleOf(String typeId, int count, double speed,
                                double offX, double offY, double offZ) {
        BurrowSignature match = signatureFor(typeId, count, speed, offX, offY, offZ);
        return match == null ? null : match.resolvedRole();
    }

    /** The matching signature itself, for the debug readout. */
    public BurrowSignature signatureFor(String typeId, int count, double speed,
                                        double offX, double offY, double offZ) {
        if (typeId == null || signatures == null) {
            return null;
        }
        for (BurrowSignature signature : signatures) {
            if (signature != null && signature.usable()
                    && signature.matches(typeId, count, speed, offX, offY, offZ,
                    speedTolerance, offsetTolerance)) {
                return signature;
            }
        }
        return null;
    }

    /** The band an arrow packet's offsets carry, or {@code null} when none is recorded. */
    public ArrowBand bandFor(double offX, double offY, double offZ) {
        if (arrowBands == null) {
            return null;
        }
        for (ArrowBand band : arrowBands) {
            if (band != null && band.matches(offX, offY, offZ, arrowColorTolerance)) {
                return band;
            }
        }
        return null;
    }

    /**
     * How many burrows the chain runs for this spade and reforge, or {@code null} when the
     * combination is not recorded.
     *
     * <p>Callers must surface {@code null} as "unknown" rather than substituting four. A chain
     * reported one step short is the toolkit telling the player they are finished while a burrow is
     * still out there, which is worse than saying nothing.
     */
    public Integer chainLength(SpadeTier tier, boolean erudite) {
        if (tier == null || chains == null) {
            return null;
        }
        for (Chain chain : chains) {
            if (chain != null && chain.erudite == erudite && tier.name().equalsIgnoreCase(chain.tier)) {
                return chain.length > 0 ? chain.length : null;
            }
        }
        return null;
    }

    /** Whether a block id may sit directly above a burrow. An empty list means "no opinion". */
    public boolean allowedAbove(String blockId) {
        return matchesList(groundCover, blockId);
    }

    /** Whether a block id may be a burrow. An empty list means "no opinion". */
    public boolean allowedGround(String blockId) {
        return matchesList(groundBlocks, blockId);
    }

    private static boolean matchesList(List<String> list, String blockId) {
        if (list == null || list.isEmpty()) {
            return true;
        }
        if (blockId == null) {
            return false;
        }
        for (String entry : list) {
            if (entry != null && entry.equalsIgnoreCase(blockId)) {
                return true;
            }
        }
        return false;
    }
}
