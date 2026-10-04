/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import sbs.modid.client.combat.diana.model.BurrowKind;
import sbs.modid.client.combat.diana.model.BurrowRecord;
import sbs.modid.client.combat.diana.model.DianaParticleData;
import sbs.modid.client.combat.diana.model.SignatureRole;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;

/**
 * The particle half of the toolkit: every level-particles packet, matched against the shipped
 * signatures, and turned into a burrow, a removal, or a point for one of the two guesses.
 *
 * <h2>The hot path</h2>
 *
 * <p>This runs once per particle packet - thousands of times a minute in a populated Hub - so the
 * order of the tests is the design. {@link #armed()} is a cached boolean recomputed twice a second;
 * a packet arriving while the toolkit is off or the player is elsewhere costs one comparison and a
 * field read. Only a packet that gets past that is looked up against the signature table, and only a
 * packet that matches a signature does any work at all.
 *
 * <h2>Why the packet and not the particle</h2>
 *
 * <p>{@code ParticleEngine#createParticle} carries no count and no grouping, and it sits downstream
 * of both the Particles module's per-type filter and vanilla's particle-count setting. Anything
 * reading there reads what survived rather than what was sent - and the count and the speed are
 * exactly the fields that tell a mob burrow from a footstep and a treasure burrow from the spade's
 * flight trail.
 *
 * <h2>Existence and kind arrive separately</h2>
 *
 * <p>Two of the roles say a burrow is at a block without saying what it is. Those only flag the
 * record and draw nothing; three others name the kind and are what promotes a block to a marker.
 * That split is not an implementation detail - it is why a burrow can be known about for several
 * seconds before anyone can say whether it is worth walking to.
 */
public final class BurrowDetector {

    private static final BurrowDetector INSTANCE = new BurrowDetector();

    /**
     * How long the arming decision is reused.
     *
     * <p>Short enough that switching the module on takes effect immediately as far as anyone can
     * tell, long enough to keep the location and event lookups off a path that runs per packet.
     */
    private static final long ARM_CACHE_MS = 500L;

    /** The island this whole feature lives on. */
    private static final String HUB = "Hub";

    private volatile boolean armed;
    private volatile long armedAt;

    /** Packets seen while armed, and how many matched something. For the debug readout. */
    private long packetsSeen;
    private long packetsMatched;

    private BurrowDetector() {
    }

    public static BurrowDetector getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /**
     * Whether the toolkit may read particles right now: switched on, in the Hub, during the event.
     *
     * <p>Recomputed at most twice a second. The three questions it asks are all cheap on their own
     * and none of them is cheap enough to ask per packet.
     */
    public boolean armed() {
        long now = System.currentTimeMillis();
        if (now - armedAt < ARM_CACHE_MS) {
            return armed;
        }
        armedAt = now;
        SBSConfig.DianaSettings cfg = cfg();
        armed = cfg.enabled
                && (cfg.detectBurrows || cfg.spadeGuess || cfg.arrowGuess)
                && DianaEvent.awake()
                && SkyBlockLocation.onIsland(HUB);
        return armed;
    }

    /**
     * One particle packet.
     *
     * <p>Called from the TAIL of the packet handler, which is the main-thread invocation - netty
     * re-dispatches before the body runs - so nothing here needs to be thread-safe against itself.
     * {@link BurrowStore} is still concurrent, because the render pass reads it.
     */
    public void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (packet == null || !armed()) {
            return;
        }
        packetsSeen++;

        String type = typeId(packet.getParticle());
        int count = packet.getCount();
        double speed = packet.getMaxSpeed();
        double offX = packet.getXDist();
        double offY = packet.getYDist();
        double offZ = packet.getZDist();

        SignatureRole role = DianaParticles.roleOf(type, count, speed, offX, offY, offZ);
        if (role == null) {
            // Near-misses are the whole reason the debug switch exists: nothing in the shipped
            // signature table has been verified against this client, so a packet that came close
            // and was refused is the evidence that corrects it.
            DianaDebug.getInstance().onUnmatchedParticle(type, count, speed, offX, offY, offZ);
            return;
        }
        packetsMatched++;
        DianaDebug.getInstance().onMatchedParticle(role, type, count, speed);

        switch (role) {
            case SPADE_TRAIL -> SpadeGuess.getInstance()
                    .onTrailParticle(packet.getX(), packet.getY(), packet.getZ());
            case ARROW -> ArrowGuess.getInstance()
                    .onArrowParticle(packet.getX(), packet.getY(), packet.getZ(), offX, offY, offZ);
            case REMOVED -> onRemoved(BurrowStore.blockOf(packet.getX(), packet.getY(), packet.getZ()));
            default -> onBurrowSignal(role,
                    BurrowStore.blockOf(packet.getX(), packet.getY(), packet.getZ()));
        }
    }

    /**
     * A burrow at this block is gone.
     *
     * <p>The only authoritative removal there is, and it deletes everything at the block - the
     * record, the confirmed marker, and any guess that happened to land there. Guesses included
     * because a guess pointing at a burrow that has just been dug out is not a guess worth walking
     * to, whoever dug it.
     */
    private void onRemoved(BlockPos pos) {
        BurrowStore store = BurrowStore.getInstance();
        store.remove(pos);
        ArrowGuess.getInstance().onBlockResolved(pos);
        SpadeGuess.getInstance().onBlockResolved(pos);
        DianaDebug.getInstance().note("burrow removed at " + describe(pos));
    }

    /**
     * A burrow signal at this block: either "something is here" or "here is what it is".
     *
     * <p>The suppression check comes first and applies to both. A block that was dug out a moment
     * ago is not allowed to become a burrow again on the strength of particles that were already in
     * flight when it went.
     */
    private void onBurrowSignal(SignatureRole role, BlockPos pos) {
        if (!cfg().detectBurrows) {
            return;
        }
        BurrowStore store = BurrowStore.getInstance();
        if (store.suppressed(pos)) {
            return;
        }

        if (!role.namesKind()) {
            BurrowRecord record = store.recordAt(pos);
            if (role == SignatureRole.MARKER) {
                record.seenMarker = true;
            } else {
                record.seenFootstep = true;
            }
            record.touch();
            return;
        }

        BurrowKind kind = role.kind();
        BurrowRecord record = store.peek(pos);
        boolean fresh = record == null || !record.classified();
        record = store.classify(pos, kind);

        // A guess at this block has just been proven right. Fold it in rather than leaving two
        // markers on one hole - and carry its dig count across, because the player may already have
        // dug it while it was still only a guess.
        int carried = ArrowGuess.getInstance().onBlockResolved(pos);
        carried = Math.max(carried, SpadeGuess.getInstance().onBlockResolved(pos));
        if (carried > record.timesDug) {
            record.timesDug = carried;
        }

        if (fresh) {
            DianaDebug.getInstance().note("burrow classified " + kind.displayName()
                    + " at " + describe(pos));
        }
    }

    /**
     * One sound packet. Only the Echo trail's note matters here - its pitch is how far away the
     * burrow is (see {@link SpadeGuess}) - and only while armed; everything else returns after one id
     * comparison. Main-thread invocation, after the packet handler's thread hop.
     */
    public void onSoundPacket(ClientboundSoundPacket packet) {
        if (packet == null || !armed()) {
            return;
        }
        DianaParticleData data = DianaParticles.data();
        if (data == null || data.echo == null || data.echo.sound == null) {
            return;
        }
        String id = String.valueOf(packet.getSound().value().location());
        if (data.echo.sound.equals(id)) {
            SpadeGuess.getInstance().onEchoNote(packet.getX(), packet.getY(), packet.getZ(), packet.getPitch());
        }
    }

    /**
     * The player left-clicked a block with a spade. Called from the attack mixin, not from a packet.
     *
     * <p>A click, not yet a dig: the chat line that follows decides which block was dug (see
     * {@link BurrowChat#dugBurrow}), and only a confirmed dig restarts the arrow guess. Here the
     * click is remembered for that decision, and for the arrow guess's "air is ground if the player
     * just broke it" rule.
     */
    public void onBlockDug(BlockPos pos) {
        if (!armed() || pos == null) {
            return;
        }
        BurrowChat.getInstance().onBlockDug(pos);
        ArrowGuess.getInstance().onBlockClicked(pos);
    }

    /** World change, server hop, island change, or the player asking. */
    public void reset() {
        armedAt = 0L;
        packetsSeen = 0;
        packetsMatched = 0;
    }

    /** The namespaced particle type, lower case, or {@code ""} when it cannot be resolved. */
    private static String typeId(ParticleOptions options) {
        if (options == null) {
            return "";
        }
        Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        return key == null ? "" : key.toString();
    }

    private static String describe(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /**
     * The detector's own fields, for the guard's error report. Reads the cached arming decision
     * rather than recomputing it, so the report shows what the detector was acting on.
     */
    public JsonObject snapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("armed", armed);
        out.addProperty("armedAt", armedAt);
        out.addProperty("packetsSeen", packetsSeen);
        out.addProperty("packetsMatched", packetsMatched);
        return out;
    }

    /** One line for the debug readout. */
    public String status() {
        if (!armed()) {
            return "not armed - " + why();
        }
        return "armed; " + packetsSeen + " packet(s) seen, " + packetsMatched + " matched";
    }

    /** Why the detector is asleep, in the player's terms. The commonest support question. */
    private String why() {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled) {
            return "the Diana module is switched off";
        }
        if (!cfg.detectBurrows && !cfg.spadeGuess && !cfg.arrowGuess) {
            return "detection and both guesses are switched off";
        }
        if (!DianaEvent.awake()) {
            return "the Mythological Ritual is not running (" + DianaEvent.state() + ")";
        }
        if (!SkyBlockLocation.onIsland(HUB)) {
            return "not in the Hub (" + SkyBlockLocation.describe() + ")";
        }
        return "the arming cache has not caught up yet";
    }
}
