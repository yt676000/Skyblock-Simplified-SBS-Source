/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.floorseven.logic.DragonTracker;
import sbs.modid.client.dungeons.floorseven.logic.FloorSevenPhaseTimer;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.helper.timers.ServerWorldTime;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A <b>capture-only</b> probe for the M7 dragon phase, written to settle the four questions the
 * three dragon features are blocked on. It answers them in one run rather than four, because they
 * can only be asked from inside a live Master Mode floor 7 and nobody gets four of those to spare.
 *
 * <p><b>1. What does our phase detection actually say in phase 5?</b> Both of the mod's "are we
 * there" tests are recorded once a second beside the player's Y: {@code DungeonStateManager}'s floor
 * and {@link sbs.modid.client.dungeons.events.DungeonEvents.Phase}, and the chat-driven
 * {@link FloorSevenPhaseTimer}. The raw sidebar lines go in whenever they change, because
 * {@code floorType()=='M'} rests entirely on the sidebar being worded {@code The Catacombs (M7)} and
 * that has never been checked - if it is worded otherwise, every M7 feature in the mod is dark and
 * this file is where that shows up. The Y column is the other half: the request's proposal is that
 * the five phases occupy separated height bands, and a run of this file is the band table.
 *
 * <p><b>2. Do particle packets reach us at all?</b> Every one is recorded, unfiltered, while the
 * capture is armed - and each is scored against the spawn-effect criteria (flame, a fixed count, a
 * fixed Y, fixed spreads, zero speed, whole-number X and Z) so the file shows near-misses rather
 * than only exact hits. A criterion nothing ever satisfies is a criterion written from the wrong
 * assumption, and that is invisible in a filter that just returns false.
 *
 * <p><b>3. Are ender dragons recognised as such?</b> Every spawn packet is recorded with its entity
 * type, and dragons are called out by name.
 *
 * <p><b>4. Which metadata index carries the health?</b> Every index of every dragon metadata packet
 * is dumped with its serializer and value. The index is 9 on this version - counted down the
 * {@code defineId} chain in the 26.2 sources - but that is a statement about the client. What
 * Hypixel chooses to put in it is not, and the full dump is what tells the difference between "index
 * 9 carries it" and "index 9 carries a clamped number and the real one is elsewhere".
 *
 * <p><b>Why the packets and not the entities.</b> For the health specifically there is no choice:
 * {@code EnderDragon.aiStep} runs {@code setHealth(getHealth())} client-side every tick and
 * {@code setHealth} clamps to the vanilla maximum of 200, so a reading taken off the entity is 200
 * whatever the server sent. See {@code EntityDataProbeMixin}.
 *
 * <p><b>What it does not do.</b> Nothing is cancelled, drawn, highlighted or sent. No command leaves
 * the client. While disarmed it costs one static boolean read per packet.
 *
 * <p><b>Deliberately not gated behind {@link DevMode}</b>, for the reason {@link ParticleProbe} is
 * not: the capture that matters has to be taken during a live M7 by whoever is running one, and that
 * is not necessarily a developer. It stays harmless because it only ever reads.
 */
public final class M7DragonProbe {

    private static final M7DragonProbe INSTANCE = new M7DragonProbe();

    /**
     * Read once per particle and per entity packet by the mixins, so the disarmed cost is a single
     * static field read on paths that run thousands of times a minute. Static rather than an
     * instance field for the same reason {@link ParticleProbe#ARMED} is.
     */
    public static volatile boolean ARMED;

    /** Raw records kept. Past this the tallies keep counting but the detail stops. */
    private static final int MAX_RECORDS = 40_000;

    /** A forgotten arm stops itself rather than growing a list for the rest of the session. */
    private static final long MAX_DURATION_MS = 30 * 60 * 1000L;

    /** How often the gate/Y line is written. A run is minutes long; 20 samples a second is noise. */
    private static final long STATE_SAMPLE_MS = 1_000L;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * The particle type the request names for the spawn effect. Held as a string rather than a
     * registry object so that a version where the id moved produces "never matched" in the tally
     * instead of a class-load failure.
     */
    private static final String FLAME = "minecraft:flame";

    /** How close to a whole number an X/Z has to be to count as one. Floats arrive as floats. */
    private static final double WHOLE_EPSILON = 1.0E-4;

    /** One line per record, in arrival order. */
    private final List<String> records = new ArrayList<>();

    /** Particle type id -> how many packets carried it. One map hit per packet. */
    private final Map<String, int[]> particleTally = new LinkedHashMap<>();

    /** Entity type id -> how many spawned. */
    private final Map<String, int[]> spawnTally = new LinkedHashMap<>();

    /**
     * Every distinct (index, serializer) pair seen on a dragon, with the last value. This is the
     * answer to question 4 in one table, so it survives the detail being truncated.
     */
    private final Map<String, String> dragonIndices = new LinkedHashMap<>();

    /** Entity ids the spawn packet said were ender dragons, so their metadata can be picked out. */
    private final Set<Integer> dragonIds = new HashSet<>();

    /** Lowest and highest Y seen while each phase-timer phase was current: the band table. */
    private final Map<String, double[]> yBands = new LinkedHashMap<>();

    /** Flame packets that met every criterion but one, counted per criterion that failed. */
    private final Map<String, int[]> nearMisses = new LinkedHashMap<>();

    /** Last sidebar seen, so only changes are written. */
    private String lastSidebar = "";

    private long armedAt;
    private long lastStateSample;
    private long particlePackets;
    private long dataPackets;
    private long spawnPackets;
    private boolean truncated;

    /**
     * Boss lines, recorded verbatim while armed.
     *
     * <p>Only {@code [BOSS]} lines, not all of chat: {@link ChatProbe} already captures everything
     * and a second copy of the dungeon's whole chat would bury the five rows this file is for.
     * Phase 5's speaker is a literal in {@link FloorSevenPhaseTimer} ({@code "Wither King"}) that has
     * never been checked against a live run, and the timer cannot reach {@code DRAGONS} unless all
     * four earlier lines matched first - so the whole chain has to be seen, not only the last one.
     */
    private static final String BOSS_LINE = "\\[BOSS\\]";

    private M7DragonProbe() {
        // Registration is process-lifetime (the registry offers no unregister), and the handler is a
        // no-op while disarmed.
        ChatPatternRegistry.getInstance().register(java.util.regex.Pattern.compile(BOSS_LINE),
                (matcher, component) -> {
                    if (ARMED) {
                        record("chat   " + PlainText.strip(component.getString()));
                    }
                }, "m7 dragon probe: boss lines");
    }

    public static M7DragonProbe getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Command
    // ------------------------------------------------------------------

    /** {@code /sbs m7probe [arm|off|status]} - the bare form reports what it is doing. */
    public void handleCommand(String argument) {
        switch (argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT)) {
            case "arm", "on", "watch" -> arm();
            case "off", "stop", "disarm" -> disarm();
            default -> status();
        }
    }

    private void arm() {
        records.clear();
        particleTally.clear();
        spawnTally.clear();
        dragonIndices.clear();
        dragonIds.clear();
        yBands.clear();
        nearMisses.clear();
        lastSidebar = "";
        particlePackets = 0;
        dataPackets = 0;
        spawnPackets = 0;
        truncated = false;
        armedAt = System.currentTimeMillis();
        lastStateSample = 0;
        ARMED = true;
        say("§aM7 dragon probe armed §7- particles, entity spawns, dragon metadata, the phase gate");
        say("§7and your Y are recorded until §f/sbs m7probe off§7.");
        say("§7Arm it before the boss room and leave it on through all five phases.");
    }

    private void disarm() {
        if (!ARMED && records.isEmpty()) {
            say("§7The M7 dragon probe was not armed.");
            return;
        }
        ARMED = false;
        if (records.isEmpty()) {
            say("§7Disarmed. Nothing arrived, so nothing was written.");
            return;
        }
        write();
    }

    private void status() {
        if (!ARMED) {
            say("§7M7 dragon probe is off. §f/sbs m7probe arm§7 starts a capture.");
            return;
        }
        say("§aArmed §7for §f" + ((System.currentTimeMillis() - armedAt) / 1000) + "s§7: §f"
                + particlePackets + "§7 particle, §f" + spawnPackets + "§7 spawn, §f" + dataPackets
                + "§7 dragon metadata packet(s)" + (truncated ? " §e(detail truncated)" : "") + ".");
        say("§7Gate right now: §f" + gateLine());
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    /**
     * One particle packet, unfiltered. Called from the TAIL of the packet handler, which is the
     * main-thread invocation - the netty thread re-dispatches before the body runs - so nothing here
     * needs to be thread-safe.
     */
    public void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (!ARMED || packet == null || expired()) {
            return;
        }
        particlePackets++;
        ParticleOptions options = packet.getParticle();
        String type = typeId(options);
        particleTally.computeIfAbsent(type, key -> new int[1])[0]++;

        // Only flame is scored - scoring every type would bury the interesting rows. The tally above
        // still counts everything, so a spawn effect that turns out not to be flame is still visible.
        String verdict = FLAME.equals(type) ? score(packet) : null;
        record(String.format(Locale.ROOT,
                "particle type=%s n=%d pos=%.4f,%.4f,%.4f off=%.4f,%.4f,%.4f spd=%.4f%s%s",
                type, packet.getCount(), packet.getX(), packet.getY(), packet.getZ(),
                packet.getXDist(), packet.getYDist(), packet.getZDist(), packet.getMaxSpeed(),
                verdict == null ? "" : "  flame[" + verdict + "]",
                packet.isOverrideLimiter() ? " limiter=override" : ""));
    }

    /**
     * Scores a flame packet against the spawn-effect criteria and names the ones it fails. A filter
     * that has to be strict is a filter whose criteria must each be checked against reality first:
     * the near-miss tally at the bottom of the file is what says which of them is written wrong.
     */
    private String score(ClientboundLevelParticlesPacket packet) {
        List<String> failed = new ArrayList<>(3);
        if (packet.getMaxSpeed() != 0.0) {
            failed.add("speed");
        }
        if (!whole(packet.getX()) || !whole(packet.getZ())) {
            failed.add("wholeXZ");
        }
        if (packet.getCount() == 0) {
            failed.add("count0");   // the directional form: not what a fixed-count effect looks like
        }
        for (String criterion : failed) {
            nearMisses.computeIfAbsent(criterion, key -> new int[1])[0]++;
        }
        return failed.isEmpty() ? "all criteria met" : "fails " + String.join("+", failed);
    }

    private static boolean whole(double value) {
        return Math.abs(value - Math.rint(value)) < WHOLE_EPSILON;
    }

    /** One entity spawn packet. Dragons are called out; everything else is tallied by type. */
    public void onAddEntity(ClientboundAddEntityPacket packet) {
        if (!ARMED || packet == null || expired()) {
            return;
        }
        spawnPackets++;
        EntityType<?> type = packet.getType();
        String id = type == null ? "null" : EntityType.getKey(type).toString();
        spawnTally.computeIfAbsent(id, key -> new int[1])[0]++;
        boolean dragon = type == EntityTypes.ENDER_DRAGON;
        if (dragon) {
            dragonIds.add(packet.getId());
        }
        record(String.format(Locale.ROOT, "spawn  %s id=%d uuid=%s pos=%.4f,%.4f,%.4f%s",
                id, packet.getId(), packet.getUUID(), packet.getX(), packet.getY(), packet.getZ(),
                dragon ? "   <-- ENDER DRAGON" : ""));
    }

    /**
     * One metadata packet. Only a dragon's is recorded - every armour stand in the dungeon sends
     * these, and a capture that kept all of them would bury the five rows that matter.
     *
     * <p>Two ways to know it is a dragon, because either can be the one available: the spawn packet
     * seen earlier in this capture, or the entity the client has already built. The second covers a
     * dragon that came up before the probe was armed.
     */
    public void onEntityData(ClientboundSetEntityDataPacket packet) {
        if (!ARMED || packet == null || expired()) {
            return;
        }
        if (!dragonIds.contains(packet.id()) && !isDragonEntity(packet.id())) {
            return;
        }
        dragonIds.add(packet.id());
        dataPackets++;
        StringBuilder line = new StringBuilder(96);
        line.append("meta   dragon id=").append(packet.id()).append(" uuid=").append(uuidOf(packet.id()));
        for (SynchedEntityData.DataValue<?> item : packet.packedItems()) {
            String serializer = serializerName(item);
            String value = String.valueOf(item.value());
            line.append("\n         [").append(item.id()).append("] ").append(serializer)
                    .append(" = ").append(value);
            dragonIndices.put(String.format(Locale.ROOT, "%3d  %s", item.id(), serializer), value);
        }
        record(line.toString());
    }

    private static boolean isDragonEntity(int entityId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        return minecraft.level.getEntity(entityId) instanceof EnderDragon;
    }

    private static String uuidOf(int entityId) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(entityId);
        return entity == null ? "?" : entity.getUUID().toString();
    }

    /**
     * The serializer's registered id plus the value's class. The id is what the wire carries; the
     * class is what makes the row readable. A float at index 9 is the health hypothesis.
     */
    private static String serializerName(SynchedEntityData.DataValue<?> item) {
        int id = EntityDataSerializers.getSerializedId(item.serializer());
        Object value = item.value();
        return "ser=" + id + "/" + (value == null ? "null" : value.getClass().getSimpleName());
    }

    /**
     * Called every client tick. Samples the gate and the player's Y on a throttle, and keeps the
     * per-phase Y band. Cheap enough to be unconditional: it returns on the {@link #ARMED} read.
     */
    public void onClientTick() {
        if (!ARMED || expired()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        FloorSevenPhaseTimer.Phase phase = FloorSevenPhaseTimer.getInstance().current();
        String key = phase == null ? "(no phase)" : phase.label();
        double[] band = yBands.computeIfAbsent(key,
                ignored -> new double[]{Double.MAX_VALUE, -Double.MAX_VALUE});
        band[0] = Math.min(band[0], player.getY());
        band[1] = Math.max(band[1], player.getY());

        long now = System.currentTimeMillis();
        if (now - lastStateSample < STATE_SAMPLE_MS) {
            return;
        }
        lastStateSample = now;
        record(String.format(Locale.ROOT, "state  %s  pos=%.2f,%.2f,%.2f",
                gateLine(), player.getX(), player.getY(), player.getZ()));

        String sidebar = String.join(" | ", SkyBlockLocation.sidebarLines());
        if (!sidebar.equals(lastSidebar)) {
            lastSidebar = sidebar;
            record("sidebar " + sidebar);
        }
    }

    /**
     * Every gate the three dragon features pass through, on one line. {@code inBossRoom} is the one
     * they actually turn on, and it is a boss-<i>room</i> test rather than a phase-5 test - it is
     * true from Maxor onwards, which is exactly the confusion this line exists to make visible.
     */
    private static String gateLine() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        FloorSevenPhaseTimer.Phase timerPhase = FloorSevenPhaseTimer.getInstance().current();
        return String.format(Locale.ROOT,
                "inDungeon=%b floor=%s type=%s num=%d statePhase=%s timerPhase=%s "
                        + "onM7=%b inBossRoom=%b inDragonPhase=%b dragonsTracked=%d serverTick=%d",
                state.inDungeon(), state.floorLabel(),
                state.floorType() == 0 ? "?" : String.valueOf(state.floorType()), state.floorNumber(),
                state.phase(), timerPhase == null ? "-" : timerPhase.name(),
                DragonTracker.onMasterSeven(), DragonTracker.inBossRoom(),
                DragonTracker.inDragonPhase(), DragonTracker.getInstance().dragons().size(),
                ServerWorldTime.gameTime());
    }

    private boolean expired() {
        if (System.currentTimeMillis() - armedAt <= MAX_DURATION_MS) {
            return false;
        }
        ARMED = false;
        say("§7M7 dragon probe stopped itself after " + (MAX_DURATION_MS / 60_000) + " minutes.");
        write();
        return true;
    }

    private void record(String line) {
        if (records.size() >= MAX_RECORDS) {
            truncated = true;
            return;
        }
        records.add(String.format(Locale.ROOT, "%7.2f  %s",
                (System.currentTimeMillis() - armedAt) / 1000.0, line));
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
            say("§aWrote §f" + records.size() + "§a record(s) to");
            say("§7" + file);
        } catch (IOException | RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Probe] Writing the M7 dragon capture failed", e);
            say("§cWriting the capture failed - see the log.");
        }
    }

    /**
     * The capture. The four summary tables come before the raw records deliberately: they are the
     * part that stays complete when the detail is truncated, and each is the direct answer to one of
     * the four questions the probe was armed to settle.
     */
    private String render() {
        StringBuilder out = new StringBuilder(1 << 16);
        out.append("SkyBlock Simplified - M7 dragon phase capture\n");
        out.append("written ").append(LocalDateTime.now()).append('\n');
        out.append("armed for ").append((System.currentTimeMillis() - armedAt) / 1000).append("s: ")
                .append(particlePackets).append(" particle, ").append(spawnPackets)
                .append(" spawn, ").append(dataPackets).append(" dragon metadata packet(s)\n");
        if (truncated) {
            out.append("DETAIL TRUNCATED at ").append(MAX_RECORDS)
                    .append(" records - the tables below still cover the whole capture\n");
        }
        out.append("location: ").append(SkyBlockLocation.describe()).append('\n');
        out.append("gate at disarm: ").append(gateLine()).append('\n');

        out.append("\n=== 1. phase gate - player Y band per phase =========================\n");
        out.append("The proposal is that the five phases sit in separated height bands and that\n")
                .append("phase 5 is the lowest. If that holds, these rows do not overlap.\n");
        if (yBands.isEmpty()) {
            out.append("(no samples)\n");
        } else {
            yBands.forEach((phase, band) -> out.append(String.format(Locale.ROOT,
                    "  %-12s Y %8.2f .. %8.2f%n", phase, band[0], band[1])));
        }

        out.append("\n=== 2. particle types seen =========================================\n");
        appendTally(out, particleTally);
        out.append("\nflame packets that failed a spawn-effect criterion, per criterion:\n");
        if (nearMisses.isEmpty()) {
            out.append("  (none - either every flame packet matched, or none arrived at all;\n")
                    .append("   check the count above before concluding the filter is right)\n");
        } else {
            appendTally(out, nearMisses);
        }

        out.append("\n=== 3. entity types spawned =======================================\n");
        appendTally(out, spawnTally);

        out.append("\n=== 4. every metadata index seen on a dragon =======================\n");
        out.append("Index 9 is LivingEntity's health on 1.26.2, counted down the defineId chain.\n")
                .append("What matters here is whether the value in it is a SkyBlock number or 200.\n");
        if (dragonIndices.isEmpty()) {
            out.append("(no dragon metadata arrived)\n");
        } else {
            dragonIndices.forEach((index, value) ->
                    out.append("  [").append(index).append("] last = ").append(value).append('\n'));
        }

        out.append("\n=== records ========================================================\n");
        records.forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    private static void appendTally(StringBuilder out, Map<String, int[]> tally) {
        if (tally.isEmpty()) {
            out.append("  (none)\n");
            return;
        }
        tally.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> out.append(String.format(Locale.ROOT, "  %8d  %s%n",
                        e.getValue()[0], e.getKey())));
    }

    private static Path freeFile() {
        String name = "m7-dragons-" + LocalDateTime.now().format(STAMP);
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
