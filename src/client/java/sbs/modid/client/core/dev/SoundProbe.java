/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.sound.NotePitch;
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
 * A <b>capture-only</b> probe for the sounds the server plays, written for the Moonglade Beacon
 * Tuner and general enough for anything sound-driven.
 *
 * <p><b>Why it exists.</b> The beacon feature turns on one question nothing outside the game can
 * answer: does Hypixel drive the beat with a note block carrying a real pitch value, or with a
 * resource-pack sample whose pitch is baked into the audio? If it is the latter, {@code getPitch()}
 * returns a constant and the pitch third of that solver cannot be built at all. Guessing either way
 * means writing it twice. One session at the beacon with this armed settles it.
 *
 * <p><b>It answers the speed question in the same file.</b> Every record carries the gap since the
 * previous sound of the same id, so the beat interval falls out of the capture instead of needing a
 * second session with a stopwatch - and the summary reports the median gap per id, which is what a
 * "measure rather than assume a fixed set of speeds" requirement actually needs.
 *
 * <p><b>Pitch is decoded, not just dumped.</b> A raw {@code 1.0594631} tells a reader nothing;
 * {@link NotePitch} turns it into the note-block note it encodes, and the summary says whether an id
 * ever varied its pitch at all. That single column is the answer to the question above.
 *
 * <p><b>What it does not do.</b> Nothing is cancelled, nothing is muted, nothing is played. While
 * disarmed it costs one static boolean read per sound. That matters more here than for
 * {@link ParticleProbe}: {@code SoundEngine.play} is the funnel for <i>every</i> sound in the game,
 * so the disarmed path has to stay free of work and free of allocation.
 *
 * <p><b>Developer mode only</b>, like every probe: the command is DEV_ONLY in CommandRegistry and
 * arming checks {@link DevMode} again. A tester who has to capture something needs dev mode on.
 */
public final class SoundProbe {

    private static final SoundProbe INSTANCE = new SoundProbe();

    /**
     * Read once per sound by {@code SoundProbeMixin}. Static, and checked before anything else, so a
     * disarmed probe costs one field read on a path that runs many times a second.
     */
    public static volatile boolean ARMED;

    /** Raw records kept. Past this the tally keeps counting but the detail stops. */
    private static final int MAX_RECORDS = 20_000;

    /** A forgotten arm stops itself rather than growing a list for the rest of the session. */
    private static final long MAX_DURATION_MS = 15 * 60 * 1000L;

    /** Sounds further than this from the player are not recorded - the beacon is a local thing. */
    private static final double MAX_DISTANCE = 40.0;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** One line per sound, in arrival order. */
    private final List<String> records = new ArrayList<>();

    /** Sound id -> what was seen of it. Unbounded, and cheap: one map hit per sound. */
    private final Map<String, Seen> tally = new LinkedHashMap<>();

    private long armedAt;
    private long sounds;
    private boolean truncated;

    /**
     * What one sound id did over the capture.
     *
     * <p>The pitch set and the gap list are the two columns the whole exercise is for: a set of size
     * one means that id never varies its pitch (a pre-pitched sample, or simply a fixed cue), and the
     * gaps are the beat interval nobody should be timing by hand.
     */
    private static final class Seen {
        int count;
        long lastAtMs = -1;
        double minPitch = Double.MAX_VALUE;
        double maxPitch = -Double.MAX_VALUE;
        final java.util.Set<Integer> notes = new java.util.TreeSet<>();
        final List<Long> gaps = new ArrayList<>();
    }

    private SoundProbe() {
    }

    public static SoundProbe getInstance() {
        return INSTANCE;
    }

    /** {@code /sbs soundprobe [arm|off|status]} - bare form reports what it is doing. */
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
        sounds = 0;
        truncated = false;
        armedAt = System.currentTimeMillis();
        ARMED = true;
        say("§aSound probe armed §7- every sound within " + (int) MAX_DISTANCE
                + " blocks is recorded until §f/sbs soundprobe off§7.");
        say("§7Stand at the beacon and let the beat run for a minute, then disarm.");
    }

    private void disarm() {
        if (!ARMED && records.isEmpty()) {
            say("§7The sound probe was not armed.");
            return;
        }
        ARMED = false;
        if (sounds == 0) {
            say("§7Disarmed. No sounds arrived in range, so nothing was written.");
            return;
        }
        write();
    }

    private void status() {
        if (!ARMED) {
            say("§7Sound probe is off. §f/sbs soundprobe arm§7 starts a capture.");
            return;
        }
        say("§aArmed §7for §f" + ((System.currentTimeMillis() - armedAt) / 1000) + "s§7, §f"
                + sounds + "§7 sound(s), §f" + tally.size() + "§7 id(s)"
                + (truncated ? " §e(detail truncated)" : "") + ".");
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    /**
     * One sound, from the head of {@code SoundEngine.play}.
     *
     * <p>Called on the client thread (the sound engine is driven from it), so plain collections are
     * safe here for the same reason {@link ParticleProbe} can use them.
     */
    public void onSound(SoundInstance instance) {
        if (!ARMED || instance == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - armedAt > MAX_DURATION_MS) {
            ARMED = false;
            say("§7Sound probe stopped itself after " + (MAX_DURATION_MS / 60_000) + " minutes.");
            write();
            return;
        }
        Player player = Minecraft.getInstance().player;
        double distance = -1;
        if (player != null) {
            double dx = instance.getX() - player.getX();
            double dy = instance.getY() - player.getY();
            double dz = instance.getZ() - player.getZ();
            distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > MAX_DISTANCE) {
                return;   // out of range: not the thing being studied
            }
        }
        sounds++;

        Identifier id = instance.getIdentifier();
        String key = id == null ? "null" : id.toString();
        float pitch = instance.getPitch();
        Integer note = NotePitch.noteOf(pitch);

        Seen seen = tally.computeIfAbsent(key, k -> new Seen());
        seen.count++;
        seen.minPitch = Math.min(seen.minPitch, pitch);
        seen.maxPitch = Math.max(seen.maxPitch, pitch);
        if (note != null) {
            seen.notes.add(note);
        }
        long gap = seen.lastAtMs < 0 ? -1 : now - seen.lastAtMs;
        seen.lastAtMs = now;
        if (gap > 0) {
            seen.gaps.add(gap);
        }

        if (records.size() >= MAX_RECORDS) {
            truncated = true;
            return;
        }
        records.add(record(instance, key, pitch, note, gap, distance, now));
    }

    private String record(SoundInstance instance, String key, float pitch, Integer note, long gap,
                          double distance, long now) {
        StringBuilder line = new StringBuilder(160);
        line.append("t=").append(now - armedAt)
                .append(" id=").append(key)
                .append(String.format(Locale.ROOT, " pitch=%.6f", pitch))
                .append(" note=").append(note == null ? "-" : NotePitch.name(note) + "(" + note + ")")
                .append(String.format(Locale.ROOT, " vol=%.3f", instance.getVolume()))
                .append(" gapMs=").append(gap < 0 ? "-" : String.valueOf(gap))
                .append(String.format(Locale.ROOT, " pos=%.2f,%.2f,%.2f",
                        instance.getX(), instance.getY(), instance.getZ()));
        if (distance >= 0) {
            line.append(String.format(Locale.ROOT, " dist=%.2f", distance));
        }
        line.append(" src=").append(instance.getSource());
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            line.append(String.format(Locale.ROOT, " player=%.2f,%.2f,%.2f",
                    player.getX(), player.getY(), player.getZ()));
        }
        return line.toString();
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private void write() {
        try {
            Path file = freeFile();
            SBSFiles.ensureParent(file);
            Files.writeString(file, render());
            say("§aWrote §f" + records.size() + "§a record(s) of §f" + sounds + "§a sound(s) to");
            say("§7" + file);
        } catch (IOException | RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Probe] Writing the sound capture failed", e);
            say("§cWriting the capture failed - see the log.");
        }
    }

    /**
     * The capture. The per-id summary comes first because it carries the answer: an id whose pitch
     * never varied is a pre-pitched sample, and one whose gaps cluster is the beat.
     */
    private String render() {
        StringBuilder out = new StringBuilder(1 << 16);
        out.append("SkyBlock Simplified - sound capture\n");
        out.append("written ").append(LocalDateTime.now()).append('\n');
        out.append("armed for ").append((System.currentTimeMillis() - armedAt) / 1000).append("s, ")
                .append(sounds).append(" sound(s) in range, ").append(records.size()).append(" recorded\n");
        if (truncated) {
            out.append("DETAIL TRUNCATED at ").append(MAX_RECORDS)
                    .append(" records - the summary below still covers every sound\n");
        }
        out.append("location: ")
                .append(sbs.modid.client.core.location.SkyBlockLocation.describe()).append('\n');
        out.append("""

                Reading this file:
                  pitch varies  -> the server sends a real pitch value and it can be read numerically.
                  pitch fixed   -> one constant for every play: either a pre-pitched sample, or a cue
                                   with no pitch meaning. A pitch solver cannot be built on that.
                  note          -> the note-block note the pitch encodes (2^((note-12)/12), notes 0-24).
                                   A dash means the pitch is not on that grid, so it is not a note block.
                  gapMs         -> milliseconds since the previous sound OF THE SAME ID. Clustered gaps
                                   are a repeating beat and their median is its interval.
                """);

        out.append("\n--- sound ids seen ---\n");
        tally.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Seen> e) -> e.getValue().count).reversed())
                .forEach(e -> out.append(summary(e.getKey(), e.getValue())));

        out.append("\n--- sounds ---\n");
        records.forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    private static String summary(String id, Seen seen) {
        StringBuilder out = new StringBuilder(200);
        out.append(String.format(Locale.ROOT, "%8d  %s%n", seen.count, id));
        boolean varies = seen.maxPitch - seen.minPitch > 1.0E-4;
        out.append(String.format(Locale.ROOT, "          pitch %s (%.6f .. %.6f)%n",
                varies ? "VARIES" : "fixed", seen.minPitch, seen.maxPitch));
        if (!seen.notes.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (int note : seen.notes) {
                if (names.length() > 0) {
                    names.append(' ');
                }
                names.append(NotePitch.name(note));
            }
            out.append("          notes ").append(names).append('\n');
        }
        if (!seen.gaps.isEmpty()) {
            List<Long> sorted = new ArrayList<>(seen.gaps);
            java.util.Collections.sort(sorted);
            long median = sorted.get(sorted.size() / 2);
            out.append(String.format(Locale.ROOT, "          gap median %d ms (%d .. %d over %d gap(s))%n",
                    median, sorted.get(0), sorted.get(sorted.size() - 1), sorted.size()));
        }
        return out.toString();
    }

    private static Path freeFile() {
        String name = "sounds-" + LocalDateTime.now().format(STAMP);
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
