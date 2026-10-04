/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3f;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.texture.logic.SkyblockItemModels;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A <b>capture-only</b> probe for the particles Hypixel sends, written for the Mythological Ritual
 * (Diana) work and general enough for anything else particle-driven.
 *
 * <p><b>Why it exists.</b> Every question that feature turns on is a question about what the server
 * actually sends, and none of them can be answered from outside the game: whether the Echo ability
 * encodes its direction in one packet or in a trail of them, whether a burrow's colour is the
 * particle's own type or a colour channel, what the arrow particles look like. A triangulation
 * written against a guess about that is a triangulation written twice. One Hub session with this
 * armed answers all of it from the real packets.
 *
 * <p><b>It reads the packet, not the particle.</b> {@code ParticleEngine#createParticle} - where
 * {@code ParticleCleanupMixin} already sits - is the wrong place to learn any of this. By then the
 * packet's {@code count} and its grouping are gone, and that funnel is exactly where the Particles
 * module cancels types the player switched off and where vanilla's particle-count setting drops the
 * rest. A capture taken there would be silently incomplete in a way nothing in the file would show.
 *
 * <p><b>The count field is the thing to look for</b> in the output. Vanilla's handler treats
 * {@code count == 0} as "one particle, exactly here, with the three offset fields used as its
 * velocity" - that form carries an exact direction. {@code count > 0} instead scatters that many
 * particles through the offset box, and a direction can then only be fitted across successive
 * packets. Which of the two Hypixel uses decides how Echo triangulation has to be written.
 *
 * <p><b>What it does not do.</b> Nothing is cancelled, nothing is drawn, no command is sent, no
 * ability is triggered. The player uses Echo; this writes down what arrived. While disarmed it costs
 * one static boolean read per packet.
 *
 * <p><b>Developer mode only</b>, like every probe: the command is DEV_ONLY in CommandRegistry and
 * arming checks {@link DevMode} again. A tester who has to capture something needs dev mode on.
 */
public final class ParticleProbe {

    private static final ParticleProbe INSTANCE = new ParticleProbe();

    /**
     * Read once per particle packet by {@code ParticleProbeMixin}, so the disarmed cost is a single
     * static field read on a path that runs thousands of times a minute. Static rather than an
     * instance field for the same reason.
     */
    public static volatile boolean ARMED;

    /** Raw records kept. Past this the tally keeps counting but the detail stops - see {@link #truncated}. */
    private static final int MAX_RECORDS = 20_000;

    /** A forgotten arm stops itself rather than growing a list for the rest of the session. */
    private static final long MAX_DURATION_MS = 15 * 60 * 1000L;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** One line per packet, in arrival order. */
    private final List<String> records = new ArrayList<>();

    /** Particle type id -> how many packets carried it. Unbounded, and cheap: one map hit per packet. */
    private final Map<String, int[]> tally = new LinkedHashMap<>();

    private long armedAt;
    private long packets;

    /** True once {@link #MAX_RECORDS} was hit, so the file can say the detail is partial. */
    private boolean truncated;

    private ParticleProbe() {
    }

    public static ParticleProbe getInstance() {
        return INSTANCE;
    }

    /** {@code /sbs particleprobe [arm|off|status]} - bare form reports what it is doing. */
    public void handleCommand(String argument) {
        if (!DevMode.ACTIVE) { // DEV-ONLY: defence in depth behind the command gate
            return;
        }
        switch (argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT)) {
            case "arm", "on", "watch" -> arm();
            case "off", "stop", "disarm" -> disarm();
            default -> status();
        }
    }

    private void arm() {
        records.clear();
        tally.clear();
        packets = 0;
        truncated = false;
        armedAt = System.currentTimeMillis();
        ARMED = true;
        say("§aParticle probe armed §7- every particle packet is recorded until §f/sbs particleprobe off§7.");
        say("§7Use Echo a few times from different spots, then dig a burrow, then disarm.");
    }

    private void disarm() {
        if (!ARMED && records.isEmpty()) {
            say("§7The particle probe was not armed.");
            return;
        }
        ARMED = false;
        if (packets == 0) {
            say("§7Disarmed. No particle packets arrived, so nothing was written.");
            return;
        }
        write();
    }

    private void status() {
        if (!ARMED) {
            say("§7Particle probe is off. §f/sbs particleprobe arm§7 starts a capture.");
            return;
        }
        say("§aArmed §7for §f" + ((System.currentTimeMillis() - armedAt) / 1000) + "s§7, §f"
                + packets + "§7 packet(s), §f" + tally.size() + "§7 type(s)"
                + (truncated ? " §e(detail truncated)" : "") + ".");
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    /**
     * One particle packet. Called from the TAIL of the packet handler, which is the main-thread
     * invocation - the netty thread re-dispatches before the body runs - so nothing here needs to be
     * thread-safe. {@link TimeUpdateMixin} relies on the same property.
     *
     * <p>The player's own position and facing go into every record on purpose: an Echo direction is
     * only meaningful relative to where the player was standing when they used it, and reconstructing
     * that afterwards from a timestamp is guesswork.
     */
    public void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (!ARMED || packet == null) {
            return;
        }
        if (System.currentTimeMillis() - armedAt > MAX_DURATION_MS) {
            ARMED = false;
            say("§7Particle probe stopped itself after " + (MAX_DURATION_MS / 60_000) + " minutes.");
            write();
            return;
        }
        packets++;

        ParticleOptions options = packet.getParticle();
        String type = typeId(options);
        tally.computeIfAbsent(type, k -> new int[1])[0]++;

        if (records.size() >= MAX_RECORDS) {
            truncated = true;
            return;
        }
        records.add(record(packet, options, type));
    }

    private String record(ClientboundLevelParticlesPacket packet, ParticleOptions options, String type) {
        StringBuilder line = new StringBuilder(160);
        line.append("t=").append(System.currentTimeMillis() - armedAt)
                .append(" type=").append(type)
                .append(" n=").append(packet.getCount())
                .append(String.format(Locale.ROOT, " pos=%.3f,%.3f,%.3f",
                        packet.getX(), packet.getY(), packet.getZ()))
                .append(String.format(Locale.ROOT, " off=%.4f,%.4f,%.4f",
                        packet.getXDist(), packet.getYDist(), packet.getZDist()))
                .append(String.format(Locale.ROOT, " spd=%.4f", packet.getMaxSpeed()));
        if (packet.getCount() == 0) {
            // The directional form: the offsets are a velocity, so spell the unit vector out rather
            // than leaving whoever reads this file to normalise three columns by hand.
            appendDirection(line, packet);
        }
        if (packet.isOverrideLimiter()) {
            line.append(" limiter=override");
        }
        if (packet.alwaysShow()) {
            line.append(" alwaysShow");
        }
        appendColour(line, options);
        appendPlayer(line);
        return line.toString();
    }

    /** The normalised offset vector, which for a {@code count == 0} packet is the direction it points. */
    private static void appendDirection(StringBuilder line, ClientboundLevelParticlesPacket packet) {
        double dx = packet.getXDist();
        double dy = packet.getYDist();
        double dz = packet.getZDist();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0E-6) {
            line.append(" dir=none");
            return;
        }
        line.append(String.format(Locale.ROOT, " dir=%.4f,%.4f,%.4f yawDeg=%.2f",
                dx / length, dy / length, dz / length,
                Math.toDegrees(Math.atan2(-dx, dz))));
    }

    /**
     * Whatever colour the options carry. Three shapes carry one in this version; anything else is
     * named by its class instead, so a capture says "there is more data here to decode" rather than
     * quietly dropping it.
     */
    private static void appendColour(StringBuilder line, ParticleOptions options) {
        if (options instanceof DustColorTransitionOptions transition) {
            line.append(" colourFrom=").append(hex(transition.getFromColor()))
                    .append(" colourTo=").append(hex(transition.getToColor()));
            return;
        }
        String colour = colour(options);
        if (colour != null) {
            line.append(" colour=").append(colour);
        } else if (options != null && !options.getClass().getSimpleName().startsWith("SimpleParticleType")) {
            line.append(" opts=").append(options.getClass().getSimpleName());
        }
    }

    /**
     * The colour a particle's options carry, as {@code #RRGGBB} (with {@code alpha=} for the colour
     * option, and {@code #from->#to} for a transition), or {@code null} for options with no colour.
     * Public because Diana Log Mode records the same thing per packet.
     */
    public static String colour(ParticleOptions options) {
        if (options instanceof DustParticleOptions dust) {
            return hex(dust.getColor());
        }
        if (options instanceof DustColorTransitionOptions transition) {
            return hex(transition.getFromColor()) + "->" + hex(transition.getToColor());
        }
        if (options instanceof ColorParticleOption colour) {
            return String.format(Locale.ROOT, "#%02X%02X%02X alpha=%.2f",
                    channel(colour.getRed()), channel(colour.getGreen()), channel(colour.getBlue()),
                    colour.getAlpha());
        }
        return null;
    }

    /** Where the player stood and what they faced - without it an Echo direction cannot be placed. */
    private static void appendPlayer(StringBuilder line) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        line.append(String.format(Locale.ROOT, " player=%.3f,%.3f,%.3f yaw=%.2f pitch=%.2f",
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        String held = SkyblockItemModels.skyblockId(player.getMainHandItem());
        if (held != null && !held.isBlank()) {
            line.append(" held=").append(held);
        }
    }

    private static String hex(Vector3f colour) {
        return colour == null ? "?" : String.format(Locale.ROOT, "#%02X%02X%02X",
                channel(colour.x()), channel(colour.y()), channel(colour.z()));
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }

    private static String typeId(ParticleOptions options) {
        if (options == null) {
            return "null";
        }
        Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        return key != null ? key.toString() : options.getType().toString();
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private void write() {
        try {
            Path file = freeFile();
            SBSFiles.ensureParent(file);
            Files.writeString(file, render());
            say("§aWrote §f" + records.size() + "§a record(s) of §f" + packets + "§a packet(s) to");
            say("§7" + file);
        } catch (IOException | RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Probe] Writing the particle capture failed", e);
            say("§cWriting the capture failed - see the log.");
        }
    }

    /**
     * The capture. The tally comes before the raw records deliberately: it is the part that stays
     * complete when the detail is truncated, and "which types appeared at all" is the first question
     * anyone opening this file has.
     */
    private String render() {
        StringBuilder out = new StringBuilder(1 << 16);
        out.append("SkyBlock Simplified - particle capture\n");
        out.append("written ").append(LocalDateTime.now()).append('\n');
        out.append("armed for ").append((System.currentTimeMillis() - armedAt) / 1000).append("s, ")
                .append(packets).append(" packet(s), ").append(records.size()).append(" recorded\n");
        if (truncated) {
            out.append("DETAIL TRUNCATED at ").append(MAX_RECORDS)
                    .append(" records - the tally below still covers every packet\n");
        }
        out.append("location: ").append(sbs.modid.client.core.location.SkyBlockLocation.describe()).append('\n');
        out.append("\nA count of 0 means the packet carries a direction (the offsets are a velocity);\n")
                .append("above 0 the offsets are a random spread box and no single packet points anywhere.\n");

        out.append("\n--- particle types seen ---\n");
        tally.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed())
                .forEach(e -> out.append(String.format(Locale.ROOT, "%8d  %s%n", e.getValue()[0], e.getKey())));

        // The tab widget lines ride along because they are the other unverified reading in this
        // feature - which rows name the Mayor and the Minister, and how they are worded.
        out.append("\n--- tab list widget lines ---\n");
        List<String> tab = TabWidgets.lines();
        if (tab.isEmpty()) {
            out.append("(none - not on Hypixel, or the tab carried no widget lines)\n");
        } else {
            tab.forEach(line -> out.append(line).append('\n'));
        }

        out.append("\n--- packets ---\n");
        records.forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    private static Path freeFile() {
        String name = "particles-" + LocalDateTime.now().format(STAMP);
        Path file = SBSFiles.probeFile(name);
        for (int i = 2; Files.exists(file) && i < 100; i++) {
            file = SBSFiles.probeFile(name + "-" + i);
        }
        return file;
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
