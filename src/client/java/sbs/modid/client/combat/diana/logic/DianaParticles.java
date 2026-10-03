/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.diana.model.ArrowBand;
import sbs.modid.client.combat.diana.model.BurrowSignature;
import sbs.modid.client.combat.diana.model.DianaParticleData;
import sbs.modid.client.combat.diana.model.SignatureRole;
import sbs.modid.client.combat.diana.model.SpadeTier;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;

/**
 * The Diana constants table, loaded through the shared versioned store.
 *
 * <p>Nothing but wiring lives here: every lookup is answered by {@link DianaParticleData} itself, so
 * the rules stay testable without a game directory - this class touches {@link SBSFiles} at class
 * load and a unit test that so much as mentions it would die on the spot.
 *
 * <p><b>Bundled-only for now</b> ({@code null} endpoint). Every value in the shipped file is
 * community-documented and unverified against this client, so the sequence is: capture with
 * {@code /sbs particleprobe} during a live event, correct the file, bump {@code dataVersion}. When
 * the backend grows an endpoint for it, adding the path here is the whole change - the store already
 * prefers a newer copy over the bundled one and already falls back when the request fails.
 */
public final class DianaParticles {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/diana/particles.json";

    /**
     * The highest schema this build reads.
     *
     * <p>Schema 1 mapped a particle type to a burrow kind and could not tell a mob burrow from a
     * footstep. It is not migrated: a v1 file cannot express a packet signature, so reading one
     * would mean inventing the fields it lacks. The store refuses anything it cannot read, and the
     * bundled copy always ships, so a stale v1 cache on disk costs nothing.
     */
    private static final int SUPPORTED_SCHEMA = 2;

    private static final VersionedDataStore<DianaParticleData> STORE = new VersionedDataStore<>(
            "DianaParticles", RESOURCE,
            SBSFiles.root().resolve("data").resolve("diana-particles.json"),
            null, DianaParticleData.class, SUPPORTED_SCHEMA);

    private DianaParticles() {
    }

    /** Loads the bundled and cached copies. Call on client init. */
    public static void load() {
        STORE.load();
        DianaParticleData document = STORE.get();
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Diana] Constants: {} signature(s), {} arrow band(s), data v{} ({})",
                document == null ? 0 : document.signatures.size(),
                document == null ? 0 : document.arrowBands.size(),
                document == null ? 0 : document.dataVersion(), STORE.source());
    }

    /**
     * What a particle packet means, or {@code null} for every other particle in the Hub - which is
     * almost all of them, so this stays cheap on a path that runs per packet.
     */
    public static SignatureRole roleOf(String typeId, int count, double speed,
                                       double offX, double offY, double offZ) {
        DianaParticleData document = STORE.get();
        return document == null ? null : document.roleOf(typeId, count, speed, offX, offY, offZ);
    }

    /** The signature that matched, for the debug readout. */
    public static BurrowSignature signatureFor(String typeId, int count, double speed,
                                               double offX, double offY, double offZ) {
        DianaParticleData document = STORE.get();
        return document == null ? null : document.signatureFor(typeId, count, speed, offX, offY, offZ);
    }

    /**
     * The distance band an arrow's colour stands for, or {@code null} when none is recorded.
     *
     * <p>{@code null} is the shipped state and is not a failure: with no band the arrow guess keeps
     * every candidate instead of filtering by range. More candidates, none confidently wrong.
     */
    public static ArrowBand bandFor(double offX, double offY, double offZ) {
        DianaParticleData document = STORE.get();
        return document == null ? null : document.bandFor(offX, offY, offZ);
    }

    /**
     * The chain length for a spade and reforge, or {@code null} when it is not recorded.
     *
     * <p>{@code null} means "unknown" and must be shown that way. Only the Ancestral Spade's four,
     * and its six with Erudite, are documented; substituting either for a tier we have no figure for
     * would have the toolkit call a chain finished with a burrow still out there.
     */
    public static Integer chainLength(SpadeTier tier, boolean erudite) {
        DianaParticleData document = STORE.get();
        return document == null ? null : document.chainLength(tier, erudite);
    }

    /** The live document, for anything needing more than the lookups above. May be {@code null}. */
    public static DianaParticleData data() {
        return STORE.get();
    }

    /** Where the loaded copy came from - bundled, cache or backend. For the debug readout. */
    public static String source() {
        return STORE.source();
    }
}
