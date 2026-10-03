/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.devlog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ScalableParticleOptionsBase;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.diana.devlog.DevLogEvent.Pos;
import sbs.modid.client.combat.diana.devlog.DevLogEvents.DataEntry;
import sbs.modid.client.combat.diana.devlog.DevLogEvents.EquipmentEntry;
import sbs.modid.client.combat.diana.devlog.DevLogEvents.Stamp;
import sbs.modid.client.combat.diana.logic.DianaEvent;
import sbs.modid.client.combat.diana.logic.DianaGuard;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.dev.ParticleProbe;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.perf.Perf;
import sbs.modid.client.core.sound.NotePitch;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.texture.logic.SkyblockItemModels;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Diana Log Mode: a capture of everything the server sends and everything the player does around the
 * Mythological Ritual, one event per line, in the order it happened.
 *
 * <h2>Why it exists</h2>
 *
 * <p>The Diana toolkit was built from a description of another client and has never been checked
 * against this one; it misreads the event and a tester's game crashed in it. Rather than a third round
 * of guessing, this records the raw signals - sounds, particles, entities, blocks, chat, the player's
 * clicks and position - so the detection can be rebuilt from what Hypixel actually sends. See
 * {@code docs/features/diana-log-mode.md} for the event table.
 *
 * <h2>Cost while off</h2>
 *
 * <p>Every hook reads {@link #enabled} first and returns, before it allocates anything. That is the
 * only work this class adds to the game while no capture is running. The one object that still
 * exists per hooked call is the {@code CallbackInfo} Mixin creates for every {@code @Inject} - the
 * same for each injection in the mod; it never escapes the handler, so the JIT can usually remove it.
 *
 * <h2>Threads</h2>
 *
 * <p>Every producer runs on the client thread and turns what it was handed into strings and numbers
 * there - {@code Component}s into text, ids into registry keys - so no packet, component, entity or
 * stack ever reaches the writer thread ({@link DevLogWriter}). The one exception is the packet
 * counter, which increments on the netty thread and is read here ({@link DevLogPackets}).
 *
 * <h2>What it never does</h2>
 *
 * <p>It observes. Nothing is sent, delayed, modified or cancelled, no interaction is altered, and the
 * hooks that feed it return nothing to vanilla. A producer that throws is caught here, counted, logged
 * once per event type and recorded as an {@code error} event; the game carries on.
 *
 * <p>Not behind developer mode, deliberately, like the probes: the capture has to be taken by whoever
 * is in the Hub while the ritual runs. Never persisted: a restart always starts with it off.
 */
public final class DianaDevLog {

    /** Read first by every hook. Written only here, under the class lock. */
    public static volatile boolean enabled;

    /** Sound, particle, entity and block events further than this from the player are not written. */
    public static final int RADIUS = 64;
    private static final double RADIUS_SQ = (double) RADIUS * RADIUS;

    /**
     * At JVM shutdown, a capture larger than this is closed but not converted to an array - the
     * conversion of a bigger file would not finish inside {@link #SHUTDOWN_WAIT_MS}.
     */
    private static final long SHUTDOWN_EXPORT_LIMIT = 32L << 20;

    /**
     * How long JVM shutdown waits for the writer to drain, close and export. Well under the 15 s after
     * which Minecraft's post-main watchdog writes a "Client shutdown from post-main" crash report for a
     * JVM still running its shutdown hooks. The {@code .jsonl} is closed before the export starts, so
     * running out of time costs at most the {@code .json}, which {@code export} rebuilds later.
     */
    private static final long SHUTDOWN_WAIT_MS = 5_000L;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Tab lines worth recording in the {@code state} event, lower case. */
    private static final String[] TAB_WORDS = {
            "event", "diana", "mayor", "minister", "mytholog", "ritual", "griffin", "burrow", "perk", "election"
    };

    private static final Pattern TEXTURE_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    private static volatile DevLogWriter writer;

    /** Client ticks since the capture started. Written by the client thread only. */
    private static volatile long tick;

    private static long startedAt;
    private static String lastState;
    private static boolean shutdownHookAdded;

    /** Producer failures per event type. Client thread only. */
    private static final Map<String, Integer> FAILURES = new HashMap<>();

    /** The last finished capture, for {@code status}. */
    private static volatile DevLogWriter.Outcome lastOutcome;

    /**
     * Captures that were stopped and are still draining or exporting. {@code export} must not touch
     * their files (it would read a half-written {@code .jsonl} and write the same {@code .part}), and
     * JVM shutdown waits for them like it waits for a running capture.
     */
    private static final Set<DevLogWriter> FINISHING = ConcurrentHashMap.newKeySet();

    private DianaDevLog() {
    }

    // ------------------------------------------------------------------
    // Switching
    // ------------------------------------------------------------------

    /** On if off, off if on. */
    public static synchronized void toggle() {
        if (enabled) {
            stop("command");
        } else {
            start();
        }
    }

    /** Opens a new capture file and starts writing. One chat line with the path. */
    public static synchronized void start() {
        if (enabled) {
            say("§7The Diana log is already on: §f" + writer.file());
            return;
        }
        Path file;
        try {
            Path dir = SBSFiles.dianaLogDir();
            Files.createDirectories(dir);
            file = freeFile(dir, "diana-" + LocalDateTime.now().format(STAMP), ".jsonl");
        } catch (IOException | RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Diana] could not create the capture file", e);
            say("§cThe Diana log could not create its file - see the log.");
            return;
        }
        tick = 0;
        startedAt = System.currentTimeMillis();
        lastState = null;
        FAILURES.clear();
        DevLogPackets.reset();
        DevLogWriter started = new DevLogWriter(file, DianaDevLog::stamp);
        started.start();
        writer = started;
        addShutdownHook();
        try {
            started.offer(sessionStart());
        } catch (RuntimeException | LinkageError e) {
            started.offer(DevLogEvents.error(stamp(), "devlog", DevLogEvents.SESSION_START, null, e, null, null));
        }
        enabled = true;
        say("§aDiana log on §7- §f" + file);
    }

    /** Stops the capture. The writer drains, closes, exports, and reports in chat when done. */
    public static synchronized void stop(String reason) {
        if (!enabled) {
            say("§7The Diana log is off.");
            return;
        }
        enabled = false;
        DevLogWriter stopping = writer;
        writer = null;
        if (stopping == null) {
            return;
        }
        say("§7Diana log stopping - writing §f" + (stopping.written() + stopping.queued()) + "§7 event(s)...");
        FINISHING.add(stopping);
        stopping.stop(reason, -1, outcome -> {
            FINISHING.remove(stopping);
            lastOutcome = outcome;
            Minecraft.getInstance().execute(() -> reportFinished(outcome));
        });
    }

    /** {@code mark <text>}: a labelled point in the capture, at the player's position. */
    public static void mark(String text) {
        if (!enabled) {
            say("§7The Diana log is off - §f/sbs devlog diana§7 starts it.");
            return;
        }
        String label = text == null || text.isBlank() ? "(no text)" : DevLogText.cap(text.trim());
        submit(DevLogEvents.mark(stamp(), playerPos(), label));
        say("§7Marked: §f" + label);
    }

    /** Lines for {@code status}. */
    public static List<String> statusLines() {
        List<String> out = new ArrayList<>();
        DevLogWriter current = writer;
        if (enabled && current != null) {
            out.add("§aDiana log on §7for §f" + (System.currentTimeMillis() - startedAt) / 1000 + "s");
            out.add("§7  file: §f" + current.file());
            out.add("§7  written: §f" + current.written() + "§7 · dropped: §f" + current.dropped()
                    + "§7 · queued: §f" + current.queued());
            int failures = FAILURES.values().stream().mapToInt(Integer::intValue).sum();
            if (failures > 0) {
                out.add("§e  capture hook failures: " + failures + " " + FAILURES);
            }
        } else {
            out.add("§7Diana log off. §f/sbs devlog diana§7 starts a capture.");
            DevLogWriter.Outcome last = lastOutcome;
            if (last != null) {
                out.add("§7  last capture: §f" + last.jsonl() + "§7 (" + last.written() + " written, "
                        + last.dropped() + " dropped)");
            }
        }
        out.add("§7  tracker guard: §f" + guardSummary());
        return out;
    }

    /** The capture file while on, else {@code null}. */
    public static Path file() {
        DevLogWriter current = writer;
        return enabled && current != null ? current.file() : null;
    }

    /**
     * {@code export}: converts the newest capture that has no {@code .json} beside it - the one a crash
     * or a forced exit left behind. Runs on the shared IO thread; reports in chat.
     */
    public static void exportNewest() {
        Path dir = SBSFiles.dianaLogDir();
        Path current = file();
        Path newest;
        try (Stream<Path> files = Files.list(dir)) {
            newest = files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(p -> !p.equals(current))
                    .filter(p -> FINISHING.stream().noneMatch(w -> w.file().equals(p)))
                    .filter(p -> !Files.exists(DevLogExport.arrayPathFor(p)))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException | RuntimeException e) {
            say("§7No captures to convert in §f" + dir);
            return;
        }
        if (newest == null) {
            say("§7Every capture in §f" + dir + "§7 already has its .json.");
            return;
        }
        Path source = newest;
        say("§7Converting §f" + source.getFileName() + "§7...");
        SbsExecutors.io().execute(() -> {
            String line;
            try {
                Path target = DevLogExport.arrayPathFor(source);
                DevLogExport.Result result = DevLogExport.export(source, target);
                line = "§aWrote §f" + result.events() + "§a event(s) to §f" + target
                        + (result.skipped() > 0 ? "§e (" + result.skipped() + " unreadable line(s) skipped"
                        + (result.lastLineTruncated() ? ", the last one cut short" : "") + ")" : "");
            } catch (IOException | RuntimeException e) {
                SkyblockSimplifiedSBS.LOGGER.error("[SBS][Diana] converting {} failed", source, e);
                line = "§cConverting the capture failed - see the log.";
            }
            String report = line;
            Minecraft.getInstance().execute(() -> say(report));
        });
    }

    // ------------------------------------------------------------------
    // For the guard
    // ------------------------------------------------------------------

    /** Queues an event built elsewhere on the client thread (the guard's error report). */
    public static void submit(DevLogEvent event) {
        DevLogWriter current = writer;
        if (enabled && current != null && event != null) {
            current.offer(event);
        }
    }

    /** The current wall clock and capture tick. */
    public static Stamp stamp() {
        return new Stamp(System.currentTimeMillis(), tick);
    }

    // ------------------------------------------------------------------
    // Tick: player samples, state, packet counts
    // ------------------------------------------------------------------

    /** Once per client tick, from the tick hub. Returns at once while off. */
    public static void onClientTick() {
        if (!enabled) {
            return;
        }
        DevLogWriter current = writer;
        if (current == null) {
            return;
        }
        if (current.failure() != null) {
            writerFailed(current);
            return;
        }
        long now = ++tick;
        try (Perf.Section perf = Perf.tick("tick.DianaDevLog")) {
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = minecraft.player;
            if (player != null && now % 2 == 0) {
                samplePlayer(player);
            }
            if (now % 20 == 0) {
                flushPacketCounts();
                checkState(minecraft, player);
            }
        }
    }

    private static void samplePlayer(LocalPlayer player) {
        try {
            submit(DevLogEvents.player(stamp(), new Pos(player.getX(), player.getY(), player.getZ()),
                    player.getYRot(), player.getXRot(), player.onGround(),
                    SkyblockItemModels.skyblockId(player.getMainHandItem())));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.PLAYER, e);
        }
    }

    private static void flushPacketCounts() {
        try {
            Map<String, Long> counts = DevLogPackets.drainCounts();
            Map<String, Long> bundled = DevLogPackets.drainBundled();
            if (!counts.isEmpty() || !bundled.isEmpty()) {
                submit(DevLogEvents.packetCounts(stamp(), counts, bundled));
            }
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.PACKET_COUNTS, e);
        }
    }

    /** Location, scoreboard and the event-related tab lines, written only when one of them changed. */
    private static void checkState(Minecraft minecraft, LocalPlayer player) {
        try {
            JsonObject area = area();
            ClientLevel level = minecraft.level;
            String world = level == null ? "none"
                    : level.dimension() + "@" + Integer.toHexString(System.identityHashCode(level));
            String event = DianaEvent.state().name();
            List<String> scoreboard = SkyBlockLocation.sidebarLines();
            List<String> tab = new ArrayList<>();
            for (String line : TabWidgets.lines()) {
                if (line != null && mentionsEvent(line)) {
                    tab.add(line);
                }
            }
            String key = area + "|" + world + "|" + event + "|" + scoreboard + "|" + tab;
            if (key.equals(lastState)) {
                return;
            }
            lastState = key;
            submit(DevLogEvents.state(stamp(), player == null ? null : playerPos(), area, world, event,
                    scoreboard, tab));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.STATE, e);
        }
    }

    private static boolean mentionsEvent(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String word : TAB_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Server packets - called by DianaDevLogMixin once enabled has been checked
    // ------------------------------------------------------------------

    public static void onSound(ClientboundSoundPacket packet) {
        try {
            double x = packet.getX();
            double y = packet.getY();
            double z = packet.getZ();
            double distance = distance(x, y, z);
            if (outOfRange(distance)) {
                return;
            }
            Holder<SoundEvent> sound = packet.getSound();
            submit(DevLogEvents.sound(stamp(), new Pos(x, y, z), soundId(sound), registered(sound),
                    packet.getSource().getName(), packet.getVolume(), packet.getPitch(),
                    NotePitch.noteOf(packet.getPitch()), packet.getSeed(), distance));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.SOUND, e);
        }
    }

    public static void onSoundEntity(ClientboundSoundEntityPacket packet) {
        try {
            Entity entity = entity(packet.getId());
            Pos pos = entity == null ? null : new Pos(entity.getX(), entity.getY(), entity.getZ());
            double distance = pos == null ? Double.NaN : distance(pos.x(), pos.y(), pos.z());
            if (outOfRange(distance)) {
                return;
            }
            Holder<SoundEvent> sound = packet.getSound();
            submit(DevLogEvents.soundEntity(stamp(), pos, soundId(sound), registered(sound), packet.getId(),
                    packet.getSource().getName(), packet.getVolume(), packet.getPitch(),
                    NotePitch.noteOf(packet.getPitch()), packet.getSeed(), distance));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.SOUND_ENTITY, e);
        }
    }

    public static void onParticle(ClientboundLevelParticlesPacket packet) {
        try {
            double x = packet.getX();
            double y = packet.getY();
            double z = packet.getZ();
            double distance = distance(x, y, z);
            if (outOfRange(distance)) {
                return;
            }
            ParticleOptions options = packet.getParticle();
            submit(DevLogEvents.particle(stamp(), new Pos(x, y, z), particleType(options),
                    optionsDetail(options), options == null ? null : ParticleProbe.colour(options),
                    packet.getXDist(), packet.getYDist(), packet.getZDist(), packet.getMaxSpeed(),
                    packet.getCount(), packet.isOverrideLimiter(), packet.alwaysShow(), distance));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.PARTICLE, e);
        }
    }

    public static void onAddEntity(ClientboundAddEntityPacket packet) {
        try {
            double x = packet.getX();
            double y = packet.getY();
            double z = packet.getZ();
            double distance = distance(x, y, z);
            if (outOfRange(distance)) {
                return;
            }
            submit(DevLogEvents.entityAdd(stamp(), new Pos(x, y, z), packet.getId(),
                    String.valueOf(packet.getUUID()),
                    String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(packet.getType())),
                    packet.getYRot(), packet.getXRot(), packet.getYHeadRot(), packet.getData(), distance));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.ENTITY_ADD, e);
        }
    }

    /**
     * Metadata. Called before the values are applied, so the entity is still where it was; an entity
     * the client does not know yet is written without a position rather than dropped.
     */
    public static void onEntityData(ClientboundSetEntityDataPacket packet) {
        try {
            Entity entity = entity(packet.id());
            Pos pos = entity == null ? null : new Pos(entity.getX(), entity.getY(), entity.getZ());
            if (pos != null && outOfRange(distance(pos.x(), pos.y(), pos.z()))) {
                return;
            }
            List<DataEntry> values = new ArrayList<>();
            String customName = null;
            String customNameLegacy = null;
            Boolean nameVisible = null;
            Boolean invisible = null;
            for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
                Object raw = value.value();
                String[] text = describe(raw);
                values.add(new DataEntry(value.id(), EntityDataSerializers.getSerializedId(value.serializer()),
                        raw == null ? "null" : raw.getClass().getSimpleName(), text[0], text[1]));
                // The base Entity's own slots, which every entity shares: 0 flags, 2 name, 3 name shown.
                switch (value.id()) {
                    case 0 -> {
                        if (raw instanceof Byte flags) {
                            invisible = (flags & 0x20) != 0;
                        }
                    }
                    case 2 -> {
                        if (raw instanceof Optional<?> name) {
                            Object inner = name.orElse(null);
                            if (inner instanceof Component component) {
                                customName = DevLogText.plain(component);
                                customNameLegacy = DevLogText.legacy(component);
                            } else {
                                customName = "";
                            }
                        }
                    }
                    case 3 -> {
                        if (raw instanceof Boolean shown) {
                            nameVisible = shown;
                        }
                    }
                    default -> {
                    }
                }
            }
            submit(DevLogEvents.entityData(stamp(), pos, packet.id(), values, customName, customNameLegacy,
                    nameVisible, invisible));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.ENTITY_DATA, e);
        }
    }

    public static void onEquipment(ClientboundSetEquipmentPacket packet) {
        try {
            Entity entity = entity(packet.getEntity());
            Pos pos = entity == null ? null : new Pos(entity.getX(), entity.getY(), entity.getZ());
            if (pos != null && outOfRange(distance(pos.x(), pos.y(), pos.z()))) {
                return;
            }
            List<EquipmentEntry> slots = new ArrayList<>();
            packet.getSlots().forEach(pair -> {
                EquipmentSlot slot = pair.getFirst();
                ItemStack stack = pair.getSecond();
                slots.add(new EquipmentEntry(slot == null ? null : slot.getName(),
                        SkyblockItemModels.skyblockId(stack), itemKey(stack),
                        stack == null || stack.getCustomName() == null ? null
                                : DevLogText.plain(stack.getCustomName()),
                        stack != null && stack.has(DataComponents.PROFILE),
                        SkyblockItemModels.skinId(stack), textureUrl(stack)));
            });
            submit(DevLogEvents.entityEquipment(stamp(), pos, packet.getEntity(), slots));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.ENTITY_EQUIPMENT, e);
        }
    }

    /** Called before removal, while the entities still exist and their positions can be read. */
    public static void onRemoveEntities(ClientboundRemoveEntitiesPacket packet) {
        try {
            List<Integer> near = new ArrayList<>();
            int far = 0;
            for (int id : packet.getEntityIds()) {
                Entity entity = entity(id);
                if (entity != null && !outOfRange(distance(entity.getX(), entity.getY(), entity.getZ()))) {
                    near.add(id);
                } else {
                    far++;
                }
            }
            if (near.isEmpty()) {
                return;
            }
            int[] ids = near.stream().mapToInt(Integer::intValue).toArray();
            submit(DevLogEvents.entityRemove(stamp(), ids, far));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.ENTITY_REMOVE, e);
        }
    }

    /** Called before the block is set, so the previous state is still in the level. */
    public static void onBlockUpdate(ClientboundBlockUpdatePacket packet) {
        try {
            block(packet.getPos(), String.valueOf(packet.getBlockState()), false);
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.BLOCK_UPDATE, e);
        }
    }

    /** Several blocks in one section. {@code runUpdates} reuses one mutable position, hence the copy. */
    public static void onSectionBlocks(ClientboundSectionBlocksUpdatePacket packet) {
        try {
            packet.runUpdates((pos, state) -> block(pos.immutable(), String.valueOf(state), true));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.BLOCK_UPDATE, e);
        }
    }

    private static void block(BlockPos pos, String state, boolean multi) {
        if (pos == null || outOfRange(distance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5))) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        String previous = level == null ? null : String.valueOf(level.getBlockState(pos));
        submit(DevLogEvents.blockUpdate(stamp(), new Pos(pos.getX(), pos.getY(), pos.getZ()), state,
                previous, multi));
    }

    /**
     * A line of server text. {@code packet} is the packet it came in, because whether Hypixel sends the
     * action bar as overlay system chat or as its own packet is one of the things being found out.
     */
    public static void onText(String type, Component text, boolean overlay, String packet) {
        try {
            submit(DevLogEvents.text(stamp(), type, DevLogText.plain(text), DevLogText.legacy(text),
                    overlay, packet));
        } catch (RuntimeException | LinkageError e) {
            failed(type, e);
        }
    }

    // ------------------------------------------------------------------
    // Player actions - called by DianaDevLogInputMixin once enabled has been checked
    // ------------------------------------------------------------------

    /** A right click that reached {@code useItem}: the crosshair on nothing, or a block that passed. */
    public static void onUseItem(Player player, InteractionHand hand) {
        try {
            ItemStack held = player.getItemInHand(hand);
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getViewVector(1.0F);
            submit(DevLogEvents.useItem(stamp(), new Pos(player.getX(), player.getY(), player.getZ()),
                    hand.name(), SkyblockItemModels.skyblockId(held), DevLogText.plain(held.getHoverName()),
                    player.getYRot(), player.getXRot(), pos(eye), pos(look), cameraPos()));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.USE_ITEM, e);
        }
    }

    /** A right click with the crosshair on a block. */
    public static void onUseItemOn(Player player, InteractionHand hand, BlockHitResult hit) {
        try {
            BlockPos pos = hit.getBlockPos();
            ClientLevel level = Minecraft.getInstance().level;
            submit(DevLogEvents.useBlock(stamp(), new Pos(pos.getX(), pos.getY(), pos.getZ()), hand.name(),
                    hit.getDirection().getName(), pos(hit.getLocation()),
                    level == null ? null : String.valueOf(level.getBlockState(pos)),
                    SkyblockItemModels.skyblockId(player.getItemInHand(hand)),
                    player.getYRot(), player.getXRot(), pos(player.getEyePosition()),
                    pos(player.getViewVector(1.0F)), cameraPos()));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.USE_BLOCK, e);
        }
    }

    /** A left click on a block - which is how a burrow is dug. */
    public static void onAttackBlock(BlockPos pos, Direction face) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            ClientLevel level = Minecraft.getInstance().level;
            submit(DevLogEvents.attackBlock(stamp(), new Pos(pos.getX(), pos.getY(), pos.getZ()),
                    face == null ? null : face.getName(),
                    level == null ? null : String.valueOf(level.getBlockState(pos)),
                    player == null ? null : SkyblockItemModels.skyblockId(player.getMainHandItem()),
                    player == null ? null : new Pos(player.getX(), player.getY(), player.getZ()),
                    player == null ? Float.NaN : player.getYRot(),
                    player == null ? Float.NaN : player.getXRot()));
        } catch (RuntimeException | LinkageError e) {
            failed(DevLogEvents.ATTACK_BLOCK, e);
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static DevLogEvent sessionStart() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        SBSConfig.DianaSettings diana = ConfigManager.getInstance().get().diana;
        JsonElement config = SBSFiles.GSON.toJsonTree(diana);
        return DevLogEvents.sessionStart(stamp(), player == null ? null : playerPos(), modVersion(),
                SharedConstants.getCurrentVersion().name(), area(), diana.enabled, config,
                DianaEvent.state().name(), DianaGuard.state(),
                player == null ? null : SkyblockItemModels.skyblockId(player.getMainHandItem()), RADIUS);
    }

    private static String guardSummary() {
        String down = DianaGuard.status();
        return down.isEmpty() ? "running" : down;
    }

    private static JsonObject area() {
        JsonObject area = new JsonObject();
        area.addProperty("describe", SkyBlockLocation.describe());
        area.addProperty("zone", SkyBlockLocation.zone());
        area.addProperty("island", SkyBlockLocation.island());
        return area;
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("skyblock-simplified-sbs")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static void reportFinished(DevLogWriter.Outcome outcome) {
        if (outcome.failure() != null) {
            say("§cThe Diana log stopped with an error: §f" + outcome.failure());
        }
        say("§aDiana log off §7- §f" + outcome.written() + "§7 line(s), §f" + outcome.dropped()
                + "§7 dropped");
        say("§7  §f" + outcome.jsonl());
        if (outcome.json() != null) {
            say("§7  §f" + outcome.json());
        } else if (outcome.exportSkipped() != null) {
            say("§e  " + outcome.exportSkipped());
        }
    }

    /** The writer thread died (disk full, file removed): stop, and say so once. */
    private static synchronized void writerFailed(DevLogWriter failedWriter) {
        if (writer != failedWriter) {
            return;
        }
        enabled = false;
        writer = null;
        say("§cThe Diana log stopped: it could not write §f" + failedWriter.file() + "§c ("
                + failedWriter.failure() + ")");
    }

    /**
     * A producer threw. Counted per type; the first of each type is logged with its stack and written
     * as an {@code error} event, the rest only counted - a hook that throws on one packet usually throws
     * on the next, and a log line per packet would be a second way of hurting the game.
     */
    private static void failed(String type, Throwable failure) {
        int count = FAILURES.merge(type, 1, Integer::sum);
        if (count > 1) {
            return;
        }
        try {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Diana] the capture's {} hook threw; further failures "
                    + "of it are only counted", type, failure);
            submit(DevLogEvents.error(stamp(), "devlog", type, null, failure, null, null));
        } catch (RuntimeException | LinkageError ignored) {
            // The count above is kept; there is nothing further to report this through.
        }
    }

    private static void addShutdownHook() {
        if (shutdownHookAdded) {
            return;
        }
        shutdownHookAdded = true;
        Thread hook = new Thread(DianaDevLog::onShutdown, "SBS-DianaLog-Shutdown");
        // A shutdown hook is the one thread that must not be a daemon (core/AGENTS.md).
        hook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /**
     * The game is closing: finish a running capture (exporting it unless it is large), and give any
     * capture stopped moments earlier the same time to finish - all inside one {@link #SHUTDOWN_WAIT_MS}
     * budget. Whatever is still exporting when the budget runs out leaves a {@code .part} file, which
     * {@code export} turns into the {@code .json} later.
     */
    private static void onShutdown() {
        List<DevLogWriter> waiting = new ArrayList<>(FINISHING);
        synchronized (DianaDevLog.class) {
            if (enabled && writer != null) {
                enabled = false;
                waiting.add(writer);
                writer.stop("shutdown", SHUTDOWN_EXPORT_LIMIT, null);
                writer = null;
            }
        }
        long deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS;
        for (DevLogWriter closing : waiting) {
            long left = Math.max(0L, deadline - System.currentTimeMillis());
            if (!closing.awaitFinished(left)) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Diana] the capture {} was still being written at exit",
                        closing.file());
            }
        }
    }

    private static Pos playerPos() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? null : new Pos(player.getX(), player.getY(), player.getZ());
    }

    private static Pos cameraPos() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gameRenderer == null ? null : pos(minecraft.gameRenderer.mainCamera().position());
    }

    private static Pos pos(Vec3 vec) {
        return vec == null ? null : new Pos(vec.x, vec.y, vec.z);
    }

    /** Distance from the player's feet, or NaN when there is no player. */
    private static double distance(double x, double y, double z) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return Double.NaN;
        }
        double dx = x - player.getX();
        double dy = y - player.getY();
        double dz = z - player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** True only for a known distance beyond the radius; an unknown one is kept. */
    private static boolean outOfRange(double distance) {
        return !Double.isNaN(distance) && distance * distance > RADIUS_SQ;
    }

    private static Entity entity(int id) {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntity(id);
    }

    private static String soundId(Holder<SoundEvent> sound) {
        try {
            return sound.value().location().toString();
        } catch (RuntimeException e) {
            return String.valueOf(sound);
        }
    }

    /** Whether the sound is a registry entry or sent inline - a custom sound arrives inline. */
    private static boolean registered(Holder<SoundEvent> sound) {
        return sound.unwrapKey().isPresent();
    }

    private static String particleType(ParticleOptions options) {
        return options == null ? null : String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()));
    }

    /** The options class, plus the fields a capture cannot do without: scale, block, or the record text. */
    private static String optionsDetail(ParticleOptions options) {
        if (options == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(options.getClass().getSimpleName());
        if (options instanceof ScalableParticleOptionsBase scalable) {
            out.append(" scale=").append(scalable.getScale());
        }
        if (options instanceof BlockParticleOption block) {
            out.append(" block=").append(block.getState());
        }
        String text = String.valueOf(options);
        if (!text.startsWith(options.getClass().getName() + "@")) {
            out.append(' ').append(text);
        }
        return DevLogText.cap(out.toString());
    }

    private static String itemKey(ItemStack stack) {
        return stack == null ? null : String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** A head's skin texture URL, or {@code null}: what identifies a mob head beyond "player_head". */
    private static String textureUrl(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        if (profile == null || profile.partialProfile() == null) {
            return null;
        }
        for (var property : profile.partialProfile().properties().get("textures")) {
            String value = property.value();
            if (value == null || value.isEmpty()) {
                continue;
            }
            try {
                String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                Matcher url = TEXTURE_URL.matcher(json);
                return url.find() ? url.group(1) : DevLogText.cap(value);
            } catch (IllegalArgumentException notBase64) {
                return DevLogText.cap(value);
            }
        }
        return null;
    }

    /** A metadata value as {plain text, legacy text or null}. */
    private static String[] describe(Object value) {
        if (value == null) {
            return new String[] {null, null};
        }
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? describe(optional.get()) : new String[] {"empty", null};
        }
        if (value instanceof Component component) {
            return new String[] {DevLogText.plain(component), DevLogText.legacy(component)};
        }
        if (value instanceof ItemStack stack) {
            String id = SkyblockItemModels.skyblockId(stack);
            String name = stack.getCustomName() == null ? "" : " '" + DevLogText.plain(stack.getCustomName()) + "'";
            return new String[] {(id == null ? itemKey(stack) : id) + " x" + stack.getCount() + name, null};
        }
        return new String[] {DevLogText.cap(String.valueOf(value)), null};
    }

    private static Path freeFile(Path dir, String base, String extension) {
        Path file = dir.resolve(base + extension);
        for (int i = 2; Files.exists(file) && i < 100; i++) {
            file = dir.resolve(base + "-" + i + extension);
        }
        return file;
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
