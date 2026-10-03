/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.floordrop.logic;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The green particles around a floor drop - as far as a client can actually know about them.
 *
 * <p><b>The emitter is not an entity and is not attached to one.</b> That was the open question in
 * the request, and the protocol answers it flatly:
 * {@link ClientboundLevelParticlesPacket} carries a position, a spread, a speed, a count and the
 * particle type - <b>and no entity id at all</b>. Nothing links a particle to the display entity it
 * surrounds. So a client cannot ask "does this drop have particles"; it can only ask "did particles
 * of this type arrive near this point recently", which is what {@link #near} does.
 *
 * <p><b>That is why requiring them is off by default.</b> A spatial match is a guess twice over -
 * once because the link is inferred, and once because which particle Hypixel uses is unverified.
 * Requiring a guess would make the highlight silently dark, which is the worst failure a finder has.
 * What is <i>not</i> optional is the tally: every particle type arriving while the player is in one
 * of the three areas is counted, and {@link FloorDropTracker} writes the counts to the log. One trip
 * replaces the guess with a config edit rather than a release.
 *
 * <p><b>Plain collections, deliberately.</b> The packet reaches this class from
 * {@code ParticleProbeMixin} at {@code TAIL}, which is the main-thread invocation - the netty thread
 * re-dispatches before the body runs. The sweep and the renderer are main-thread too. That is the
 * same property {@code ParticleProbe} leans on, for the same reason.
 *
 * <p>Bounded on every axis: {@value #MAX_POINTS} positions in a ring that overwrites its oldest
 * entry, {@value #MAX_TALLY} distinct type names, and every point expiring after
 * {@value #MAX_AGE_MS} ms. Even if {@link #clear()} were never reached, nothing here can grow.
 */
public final class FloorDropParticles {

    private static final FloorDropParticles INSTANCE = new FloorDropParticles();

    /**
     * Read once per particle packet, so the cost while the feature is off is a static boolean read -
     * the same bargain {@code ParticleProbe.ARMED} strikes. Set by the sweep, which is the only
     * thing that knows whether the feature is on <i>and</i> the player is in a gated area.
     */
    public static volatile boolean LISTENING;

    /** Positions kept. A drop emits continuously, so a short ring is plenty. */
    private static final int MAX_POINTS = 256;

    /** A position older than this says nothing about what is on the floor now. */
    private static final long MAX_AGE_MS = 3_000L;

    /** Distinct type names tallied. A cap, because the tally is a diagnostic and not a dataset. */
    private static final int MAX_TALLY = 64;

    private final double[] xs = new double[MAX_POINTS];
    private final double[] ys = new double[MAX_POINTS];
    private final double[] zs = new double[MAX_POINTS];
    private final long[] at = new long[MAX_POINTS];

    /** Next slot to write; the ring is full once it has wrapped. */
    private int next;

    /** Every particle type seen while listening, and how many packets of it arrived. */
    private final Map<String, int[]> tally = new HashMap<>();

    /** The compiled wanted set, and the config string it was compiled from. */
    private Set<String> wanted = Set.of();
    private String compiledFrom;

    private FloorDropParticles() {
    }

    public static FloorDropParticles getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FloorDropSettings cfg() {
        return ConfigManager.getInstance().get().floorDrops;
    }

    // ---- fed by the packet mixin ----------------------------------------------------------------

    /**
     * One particle packet. Tallies its type always, and keeps its position only when the type is one
     * of the configured ones - the tally is what tells the player which type to configure, so it has
     * to see the ones nobody asked for.
     */
    public void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (!LISTENING || packet == null) {
            return;
        }
        String type = typeId(packet.getParticle());
        int[] count = tally.get(type);
        if (count != null) {
            count[0]++;
        } else if (tally.size() < MAX_TALLY) {
            tally.put(type, new int[] {1});
        }
        if (!compiled().contains(type)) {
            return;
        }
        xs[next] = packet.getX();
        ys[next] = packet.getY();
        zs[next] = packet.getZ();
        at[next] = System.currentTimeMillis();
        next = (next + 1) % MAX_POINTS;
    }

    // ---- asked by the sweep ---------------------------------------------------------------------

    /**
     * Whether a configured particle arrived within {@code radius} of this point in the last
     * {@value #MAX_AGE_MS} ms.
     *
     * <p>Compared squared and against the whole ring: at 256 entries this is a few hundred
     * multiplications for a question asked once per candidate per sweep, which is not worth a
     * spatial index.
     */
    public boolean near(double x, double y, double z, double radius) {
        long now = System.currentTimeMillis();
        double limit = radius * radius;
        for (int i = 0; i < MAX_POINTS; i++) {
            if (at[i] == 0 || now - at[i] > MAX_AGE_MS) {
                continue;
            }
            double dx = xs[i] - x;
            double dy = ys[i] - y;
            double dz = zs[i] - z;
            if (dx * dx + dy * dy + dz * dz <= limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * The particle types seen since the last clear, busiest first, as one log-ready line.
     *
     * <p>This is the line that answers the question the client cannot: "green particles" is a
     * description of a colour, and what arrives on the wire is a registry id. Nothing in this
     * repository records which one Hypixel uses around a floor drop.
     */
    public String tallyLine() {
        if (tally.isEmpty()) {
            return "none";
        }
        List<Map.Entry<String, int[]>> entries = new ArrayList<>(tally.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder out = new StringBuilder(120);
        for (int i = 0; i < entries.size() && i < 8; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(entries.get(i).getKey()).append('=').append(entries.get(i).getValue()[0]);
        }
        return out.toString();
    }

    /** Whether any configured particle has been seen at all - the page's "is this working" line. */
    public boolean sawWanted() {
        for (long stamp : at) {
            if (stamp != 0) {
                return true;
            }
        }
        return false;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /**
     * Called on a world change and whenever the sweep stops listening. Both the positions and the
     * tally describe one instance: a position from the previous server is a coordinate in a world
     * that no longer exists, and carrying the tally over would attribute another area's particles to
     * this one.
     */
    public void clear() {
        java.util.Arrays.fill(at, 0L);
        next = 0;
        tally.clear();
    }

    // ---- the configured type set ----------------------------------------------------------------

    /**
     * The wanted types, compiled once per edit of the setting.
     *
     * <p>A bare name is qualified to {@code minecraft:} so the player can write
     * {@code happy_villager} without knowing that the registry spells it
     * {@code minecraft:happy_villager} - and a fully qualified name is left alone, so a namespaced
     * type still works if one ever turns up.
     */
    private Set<String> compiled() {
        String raw = cfg().particleTypes == null ? "" : cfg().particleTypes;
        if (raw.equals(compiledFrom)) {
            return wanted;
        }
        Set<String> compiledSet = new HashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            compiledSet.add(name.indexOf(':') >= 0 ? name : "minecraft:" + name);
        }
        wanted = Set.copyOf(compiledSet);
        compiledFrom = raw;
        return wanted;
    }

    private static String typeId(ParticleOptions options) {
        if (options == null) {
            return "null";
        }
        Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        return key != null ? key.toString() : options.getType().toString();
    }
}
