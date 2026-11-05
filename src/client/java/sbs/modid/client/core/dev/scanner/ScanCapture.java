/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.client.core.dev.DevLogText;
import sbs.modid.client.core.dev.LayoutRecorder;
import sbs.modid.client.core.dev.LayoutSignature;
import sbs.modid.client.core.dev.MenuReads;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.NameClass;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.mixin.BossHealthOverlayAccessor;
import sbs.modid.client.core.perf.Perf;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The world half of a full capture: entities, damage, sounds, titles and boss bars, the player's
 * inventory, particles, blocks and world changes. Owned by one scanner session; every event goes out
 * through {@link Out}, which stamps time, tick, island, zone and position, applies the channel budget
 * and the dedup window, and writes off-thread.
 *
 * <p><b>Diff, never dump.</b> Each channel keeps the last state it wrote - per entity its name and
 * what it wears, per inventory slot its item, per boss bar its name and progress - and writes only
 * what changed. An entity gets a short session id ({@link ScanIds}) and one full {@code spawn}
 * record; everything after names it by that id.
 *
 * <p><b>No second world scan.</b> Entities come from {@link EntityIndex}'s buckets, rejected by
 * distance first (core AGENTS rule 8). Nametag stands are tied to their mob the way the rest of the
 * mod does it: the vehicle when the stand rides one, else the nearest mob straight below it among the
 * entities already in view.
 *
 * <p>Client thread only. Hooks reach this only while the session runs and the channel is on.
 */
final class ScanCapture {

    /** Where events go; implemented by the session. */
    interface Out {
        /** A new event with {@code t}, {@code tick}, {@code ch} and {@code type} set. */
        JsonObject event(ScanChannel channel, String type);

        /** Budget, dedup ({@code key}, null for none), location stamp, queue. */
        void emit(ScanChannel channel, JsonObject event, String key);

        ScanSummary summary();

        String wall();
    }

    /** A hit this recent turns the target's removal into a kill. */
    static final long KILL_WINDOW_MS = 5000L;
    /** An entity is "left" (not removed) once it is this much further out than the radius. */
    private static final double LEAVE_FACTOR = 1.25;
    /** A position jump of more than this in one tick is a teleport. */
    private static final double TELEPORT_BLOCKS = 8.0;
    private static final int POLL_EVERY_TICKS = 5;
    private static final EquipmentSlot[] WORN = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    private static final String[] WORN_KEYS = {"head", "chest", "legs", "feet", "hand", "offhand"};
    private static final EntityKind[] KINDS = EntityKind.values();
    private static final Pattern SKIN_URL = Pattern.compile("/texture/([0-9a-fA-F]+)");

    /** One entity as last written. */
    private static final class Seen {
        final Entity entity;
        /** Set when the entity is first written; null while it is held back as noise. */
        String sid;
        final long firstMs;
        Component nameRef;
        String plain = "";
        NameClass cls = NameClass.NONE;
        final ItemStack[] wornRef = new ItemStack[WORN.length];
        final String[] worn = new String[WORN.length];
        /** Non-null while the entity is held back as noise; re-checked when its name or gear changes. */
        String noise;
        boolean written;
        boolean splash;
        int generation;

        Seen(Entity entity, long firstMs) {
            this.entity = entity;
            this.firstMs = firstMs;
        }
    }

    private static final class Agg {
        int packets;
        int count;
        double x;
        double y;
        double z;
    }

    private static final class Boss {
        final String bid;
        String name;
        float progress;

        Boss(String bid) {
            this.bid = bid;
        }
    }

    private final Out out;
    private final Set<ScanChannel> channels;
    private final boolean keepPlayers;
    private final double r2;
    private final double leave2;
    private final ScanIds ids = new ScanIds();

    private final Int2ObjectOpenHashMap<Seen> seen = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap lastHit = new Int2LongOpenHashMap();
    private final Set<String> holograms = new HashSet<>();
    private int generation;

    private final Map<UUID, Boss> bosses = new HashMap<>();
    private int bossIds;

    private ItemStack[] invRef = new ItemStack[0];
    private int[] invCount = new int[0];
    private ScanSlot[] inv = new ScanSlot[0];
    private boolean invSnapshotDone;

    private final Map<String, Agg> particles = new HashMap<>();
    private long particleWindowAt;

    // Ids as strings, computed once per id - Identifier.toString() concatenates on every call.
    private final IdentityHashMap<EntityType<?>, String> entityTypes = new IdentityHashMap<>();
    private final IdentityHashMap<ParticleType<?>, String> particleTypes = new IdentityHashMap<>();
    private final IdentityHashMap<BlockState, String> blockStates = new IdentityHashMap<>();
    private final HashMap<Identifier, String> soundIds = new HashMap<>();

    private ClientLevel level;
    private String island;
    private String zone;
    private double lastX = Double.NaN;
    private double lastY;
    private double lastZ;
    private boolean wasDead;
    private int ticks;
    private Set<String> players = Set.of();
    private String selfName = "";

    ScanCapture(Out out, Set<ScanChannel> channels, int radius, boolean keepPlayers) {
        this.out = out;
        this.channels = channels;
        this.keepPlayers = keepPlayers;
        this.r2 = (double) radius * radius;
        this.leave2 = r2 * LEAVE_FACTOR * LEAVE_FACTOR;
    }

    private boolean on(ScanChannel channel) {
        return channels.contains(channel);
    }

    // ------------------------------------------------------------------ tick

    /** Once per client tick, after the session's own tick. Each channel is its own Perf section. */
    void tick(Minecraft minecraft, long nowMs) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        ticks++;
        players = LayoutRecorder.knownPlayers();
        selfName = player.getGameProfile().name();
        if (on(ScanChannel.WORLD) || minecraft.level != level) {
            try (Perf.Section perf = Perf.tick("dev.scan.world")) {
                tickWorld(minecraft, player);
            }
        }
        if (on(ScanChannel.ENTITY) || on(ScanChannel.DAMAGE)) {
            try (Perf.Section perf = Perf.tick("dev.scan.entity")) {
                tickEntities(player, nowMs);
            }
        }
        if (on(ScanChannel.TITLE) && ticks % POLL_EVERY_TICKS == 0) {
            try (Perf.Section perf = Perf.tick("dev.scan.title")) {
                tickBosses(minecraft);
            }
        }
        if (on(ScanChannel.INVENTORY) && ticks % POLL_EVERY_TICKS == 0) {
            try (Perf.Section perf = Perf.tick("dev.scan.inventory")) {
                tickInventory(player);
            }
        }
        if (on(ScanChannel.PARTICLES) && nowMs - particleWindowAt >= 1000L) {
            try (Perf.Section perf = Perf.tick("dev.scan.particles")) {
                flushParticles(nowMs);
            }
        }
    }

    /** The session ends: what is still aggregated goes out. */
    void finish(long nowMs) {
        if (on(ScanChannel.PARTICLES)) {
            flushParticles(nowMs);
        }
    }

    // ------------------------------------------------------------------ world

    private void tickWorld(Minecraft minecraft, LocalPlayer player) {
        boolean world = on(ScanChannel.WORLD);
        if (minecraft.level != level) {
            boolean first = level == null;
            level = minecraft.level;
            // Numeric entity ids mean nothing in a new level; the session ids keep counting.
            seen.clear();
            lastHit.clear();
            ids.clear();
            bosses.clear();
            lastX = Double.NaN;
            if (world) {
                JsonObject event = out.event(ScanChannel.WORLD, first ? "world_start" : "world_change");
                event.addProperty("dimension", String.valueOf(level.dimension()));
                out.emit(ScanChannel.WORLD, event, null);
            }
        }
        if (!world) {
            return;
        }
        String nowIsland = SkyBlockLocation.island();
        String nowZone = SkyBlockLocation.zone();
        if (!java.util.Objects.equals(nowIsland, island) || !java.util.Objects.equals(nowZone, zone)) {
            JsonObject event = out.event(ScanChannel.WORLD, "location");
            event.addProperty("fromIsland", island);
            event.addProperty("fromZone", zone);
            island = nowIsland;
            zone = nowZone;
            out.emit(ScanChannel.WORLD, event, null);
        }
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        if (!Double.isNaN(lastX)) {
            double dx = x - lastX;
            double dy = y - lastY;
            double dz = z - lastZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > TELEPORT_BLOCKS) {
                JsonObject event = out.event(ScanChannel.WORLD, "teleport");
                event.add("from", pos(lastX, lastY, lastZ));
                event.addProperty("dist", round(distance));
                out.emit(ScanChannel.WORLD, event, null);
            }
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        boolean dead = player.isDeadOrDying();
        if (dead && !wasDead) {
            out.emit(ScanChannel.WORLD, out.event(ScanChannel.WORLD, "death"), null);
        }
        wasDead = dead;
    }

    /** {@code ClientPacketListener.handleRespawn}. */
    void onRespawn() {
        if (on(ScanChannel.WORLD)) {
            out.emit(ScanChannel.WORLD, out.event(ScanChannel.WORLD, "respawn"), null);
        }
        lastX = Double.NaN;   // the respawn jump is not a teleport
    }

    // ------------------------------------------------------------------ entities

    private void tickEntities(LocalPlayer player, long nowMs) {
        int gen = ++generation;
        for (EntityKind kind : KINDS) {
            List<Tracked> bucket = EntityIndex.bucket(kind);
            for (int i = 0; i < bucket.size(); i++) {
                Tracked tracked = bucket.get(i);
                Entity entity = tracked.entity;
                if (entity == player) {
                    continue;
                }
                double d2 = entity.distanceToSqr(player);
                Seen s = seen.get(entity.getId());
                if (s != null && s.entity != entity) {
                    gone(s, nowMs, "replaced");
                    s = null;
                }
                if (s == null) {
                    if (d2 > r2) {
                        continue;
                    }
                    s = new Seen(entity, nowMs);
                    seen.put(entity.getId(), s);
                    s.generation = gen;
                    firstSight(s, tracked, kind, player);
                    continue;
                }
                if (d2 > leave2) {
                    if (s.written) {
                        JsonObject event = out.event(ScanChannel.ENTITY, "leave");
                        event.addProperty("e", s.sid);
                        out.emit(ScanChannel.ENTITY, event, null);
                    }
                    seen.remove(entity.getId());
                    continue;
                }
                s.generation = gen;
                changes(s, tracked, kind, player);
            }
        }
        // Everything not met this tick is gone from the level.
        for (ObjectIterator<Seen> it = seen.values().iterator(); it.hasNext(); ) {
            Seen s = it.next();
            if (s.generation != gen) {
                it.remove();
                gone(s, nowMs, null);
            }
        }
        // Old hits can no longer make a kill.
        if (!lastHit.isEmpty()) {
            lastHit.int2LongEntrySet().removeIf(e -> nowMs - e.getLongValue() > KILL_WINDOW_MS);
        }
    }

    private void firstSight(Seen s, Tracked tracked, EntityKind kind, LocalPlayer player) {
        readName(s, tracked);
        readWorn(s);
        if (s.cls == NameClass.DAMAGE_TAG) {
            splash(s, player);
            return;
        }
        String noise = noise(s, kind, true);
        if (noise != null) {
            s.noise = noise;
            out.summary().suppressed(noise);
            return;
        }
        if (on(ScanChannel.ENTITY)) {
            spawn(s, kind, player);
        }
    }

    private void changes(Seen s, Tracked tracked, EntityKind kind, LocalPlayer player) {
        if (s.splash) {
            return;
        }
        String oldPlain = s.plain;
        boolean renamed = false;
        if (tracked.name() != s.nameRef) {
            readName(s, tracked);
            renamed = !oldPlain.equals(s.plain);
        }
        int wornChanged = wornChanges(s);
        if (!renamed && wornChanged == 0) {
            return;
        }
        if (s.cls == NameClass.DAMAGE_TAG && !s.written) {
            splash(s, player);
            return;
        }
        if (s.noise != null) {
            // Still noise? Re-checked only now, because its name or gear just changed.
            readWornAll(s);
            if (noise(s, kind, false) != null) {
                return;
            }
            s.noise = null;
            if (on(ScanChannel.ENTITY)) {
                spawn(s, kind, player);
            }
            return;
        }
        if (!s.written || !on(ScanChannel.ENTITY)) {
            return;
        }
        if (renamed) {
            JsonObject event = out.event(ScanChannel.ENTITY, "name");
            event.addProperty("e", s.sid);
            event.addProperty("old", oldPlain);
            event.addProperty("new", s.plain);
            out.emit(ScanChannel.ENTITY, event, null);
            out.summary().name(summaryName(s));
            if (s.entity instanceof ArmorStand) {
                String mob = mobOf(s);
                if (mob != null) {
                    event.addProperty("mob", mob);
                }
            }
        }
        for (int i = 0; i < WORN.length; i++) {
            if ((wornChanged & (1 << i)) == 0) {
                continue;
            }
            String before = s.worn[i];
            s.worn[i] = describe(s.wornRef[i]);
            if (java.util.Objects.equals(before, s.worn[i])) {
                continue;
            }
            JsonObject event = out.event(ScanChannel.ENTITY, "equip");
            event.addProperty("e", s.sid);
            event.addProperty("slot", WORN_KEYS[i]);
            event.addProperty("old", before);
            event.addProperty("new", s.worn[i]);
            out.emit(ScanChannel.ENTITY, event, null);
        }
    }

    private void spawn(Seen s, EntityKind kind, LocalPlayer player) {
        Entity entity = s.entity;
        String sid = sid(s);
        s.written = true;
        readWornAll(s);
        JsonObject event = out.event(ScanChannel.ENTITY, "spawn");
        event.addProperty("e", sid);
        event.addProperty("nid", entity.getId());
        event.addProperty("etype", entityType(entity));
        event.add("at", pos(entity.getX(), entity.getY(), entity.getZ()));
        event.addProperty("dist", round(Math.sqrt(entity.distanceToSqr(player))));
        if (entity.tickCount > 2) {
            event.addProperty("enter", true);   // was already there: walked into range, not spawned
        }
        if (!s.plain.isEmpty()) {
            event.addProperty("name", s.plain);
            Component name = entity.getCustomName();
            if (name != null) {
                event.addProperty("raw", LayoutSignature.redactPlayersKeepCodes(DevLogText.legacy(name), players));
            }
            event.addProperty("cls", s.cls.name());
        }
        if (entity instanceof Player) {
            boolean real = entity.getUUID().version() == 4;
            event.addProperty("player", real ? "real" : "npc");
            if (!real) {
                event.addProperty("name", entity.getName().getString());
            }
        }
        if (entity instanceof ArmorStand stand) {
            JsonArray flags = new JsonArray();
            if (stand.isInvisible()) {
                flags.add("invisible");
            }
            if (stand.isMarker()) {
                flags.add("marker");
            }
            if (stand.isSmall()) {
                flags.add("small");
            }
            if (stand.showArms()) {
                flags.add("arms");
            }
            event.add("stand", flags);
        }
        if (entity instanceof ItemEntity item) {
            event.addProperty("item", describe(item.getItem()));
            event.addProperty("count", item.getItem().getCount());
            event.addProperty("itemName", MenuReads.plain(item.getItem().getHoverName()));
        }
        JsonObject worn = new JsonObject();
        for (int i = 0; i < WORN.length; i++) {
            if (s.worn[i] != null) {
                worn.addProperty(WORN_KEYS[i], s.worn[i]);
            }
        }
        if (!worn.isEmpty()) {
            event.add("eq", worn);
        }
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            event.addProperty("vehicle", vehicle == player ? "self" : ids.assign(vehicle.getId()));
        }
        if (!entity.getPassengers().isEmpty()) {
            JsonArray riders = new JsonArray();
            for (Entity rider : entity.getPassengers()) {
                riders.add(ids.assign(rider.getId()));
            }
            event.add("riders", riders);
        }
        if (entity instanceof ArmorStand) {
            String mob = mobOf(s);
            if (mob != null) {
                event.addProperty("mob", mob);
            }
        }
        out.emit(ScanChannel.ENTITY, event, null);
        out.summary().name(summaryName(s));
        if (isHologram(s)) {
            holograms.add(hologramKey(s));
        }
    }

    /** The entity left the level: a remove event, a kill when the player hit it moments ago. */
    private void gone(Seen s, long nowMs, String why) {
        int nid = s.entity.getId();
        if (s.written && on(ScanChannel.ENTITY)) {
            JsonObject event = out.event(ScanChannel.ENTITY, "remove");
            event.addProperty("e", s.sid);
            Entity.RemovalReason reason = s.entity.getRemovalReason();
            event.addProperty("reason", why != null ? why : reason == null ? "gone" : reason.name().toLowerCase(java.util.Locale.ROOT));
            event.addProperty("life", nowMs - s.firstMs);
            if (s.entity instanceof LivingEntity living && living.isDeadOrDying()) {
                event.addProperty("dead", true);
            }
            long hit = lastHit.get(nid);
            if (hit != 0 && nowMs - hit <= KILL_WINDOW_MS) {
                event.addProperty("killed", true);
            }
            out.emit(ScanChannel.ENTITY, event, null);
        }
        long hit = lastHit.remove(nid);
        // The session id may come from the hit alone (damage on, entity off): ask the id map.
        String sid = s.sid != null ? s.sid : ids.get(nid);
        if (hit != 0 && nowMs - hit <= KILL_WINDOW_MS && sid != null && !s.splash) {
            out.summary().kill(out.wall(), sid + "  " + summaryName(s));
            if (on(ScanChannel.DAMAGE)) {
                JsonObject event = out.event(ScanChannel.DAMAGE, "kill");
                event.addProperty("e", sid);
                event.addProperty("name", summaryName(s));
                event.addProperty("sinceHit", nowMs - hit);
                out.emit(ScanChannel.DAMAGE, event, null);
            }
        }
        ids.forget(nid);
    }

    /** A damage splash stand: a damage event, never an entity of its own. */
    private void splash(Seen s, LocalPlayer player) {
        s.splash = true;
        if (!on(ScanChannel.DAMAGE)) {
            out.summary().suppressed(ScanNames.NOISE_DAMAGE_SPLASH);
            return;
        }
        Entity entity = s.entity;
        JsonObject event = out.event(ScanChannel.DAMAGE, "splash");
        event.addProperty("value", s.plain);
        Component name = entity.getCustomName();
        if (name != null) {
            event.addProperty("raw", DevLogText.legacy(name));
        }
        event.add("at", pos(entity.getX(), entity.getY(), entity.getZ()));
        // The splash belongs to the closest entity the player hit recently, if one is within 5 blocks.
        String near = null;
        double best = 25.0;
        for (Int2LongMap.Entry hit : lastHit.int2LongEntrySet()) {
            Seen target = seen.get(hit.getIntKey());
            if (target == null || target.sid == null) {
                continue;
            }
            double d2 = target.entity.distanceToSqr(entity);
            if (d2 < best) {
                best = d2;
                near = target.sid;
            }
        }
        if (near != null) {
            event.addProperty("near", near);
        }
        out.emit(ScanChannel.DAMAGE, event, null);
    }

    /** {@code MultiPlayerGameMode.attack}: the player hit {@code target}. */
    void onAttack(Entity target) {
        long now = System.currentTimeMillis();
        lastHit.put(target.getId(), now);
        if (!on(ScanChannel.DAMAGE)) {
            return;
        }
        Seen s = seen.get(target.getId());
        JsonObject event = out.event(ScanChannel.DAMAGE, "hit");
        event.addProperty("target", s != null && s.entity == target ? sid(s) : ids.assign(target.getId()));
        event.addProperty("etype", entityType(target));
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            event.addProperty("dist", round(Math.sqrt(target.distanceToSqr(player))));
        }
        out.emit(ScanChannel.DAMAGE, event, null);
    }

    /** Which noise rule {@code s} falls under, or null. {@code first}: the hologram re-sighting check. */
    private String noise(Seen s, EntityKind kind, boolean first) {
        Entity entity = s.entity;
        if (kind == EntityKind.PLAYER && !keepPlayers && entity.getUUID().version() == 4) {
            return ScanNames.NOISE_OTHER_PLAYER;
        }
        if (s.cls == NameClass.NPC_LABEL || ScanNames.noiseName(s.plain) != null) {
            return ScanNames.NOISE_CLICK_LABEL;
        }
        if (s.cls == NameClass.PET) {
            String owner = NameClass.petOwner(s.plain);
            if (owner != null && !owner.equalsIgnoreCase(selfName)) {
                return ScanNames.NOISE_OTHER_PET;
            }
        }
        if (entity instanceof ArmorStand stand) {
            Entity vehicle = stand.getVehicle();
            if (vehicle instanceof Player && vehicle != Minecraft.getInstance().player) {
                return ScanNames.NOISE_PLAYER_COSMETIC;
            }
            if (stand.isInvisible() && s.plain.isEmpty() && wornNothing(s)) {
                return ScanNames.NOISE_BARE_STAND;
            }
            if (first && isHologram(s) && holograms.contains(hologramKey(s))) {
                return ScanNames.NOISE_STATIC_HOLOGRAM;
            }
        }
        return null;
    }

    private static boolean isHologram(Seen s) {
        return s.entity instanceof ArmorStand stand && stand.isInvisible() && s.cls == NameClass.PLAIN
                && stand.getVehicle() == null;
    }

    private static String hologramKey(Seen s) {
        BlockPos at = s.entity.blockPosition();
        return s.plain + "@" + at.getX() + "," + at.getY() + "," + at.getZ();
    }

    /** The mob a nametag stand belongs to: its vehicle, else the nearest mob just below it. */
    private String mobOf(Seen stand) {
        if (stand.cls == NameClass.NONE || stand.cls == NameClass.DAMAGE_TAG) {
            return null;
        }
        Entity vehicle = stand.entity.getVehicle();
        if (vehicle instanceof LivingEntity && !(vehicle instanceof ArmorStand)) {
            return ids.assign(vehicle.getId());
        }
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (Seen s : seen.values()) {
            if (s.sid == null || !(s.entity instanceof LivingEntity) || s.entity instanceof ArmorStand) {
                continue;
            }
            double dx = s.entity.getX() - stand.entity.getX();
            double dz = s.entity.getZ() - stand.entity.getZ();
            double dy = stand.entity.getY() - s.entity.getY();
            if (Math.abs(dx) > 1.2 || Math.abs(dz) > 1.2 || dy < -0.5 || dy > 4.0) {
                continue;
            }
            double d = dx * dx + dz * dz + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = s.sid;
            }
        }
        return best;
    }

    private void readName(Seen s, Tracked tracked) {
        Component name = tracked.name();
        s.nameRef = name;
        s.plain = name == null ? "" : LayoutSignature.redactPlayers(tracked.plain(), players);
        s.cls = tracked.nameClass();
    }

    /** Bit {@code i} set when worn slot {@code i}'s stack is another object than last time. */
    private static int wornChanges(Seen s) {
        if (!(s.entity instanceof LivingEntity living)) {
            return 0;   // a ground item's stack is recorded at spawn and on pickup, not diffed
        }
        int mask = 0;
        for (int i = 0; i < WORN.length; i++) {
            ItemStack now = living.getItemBySlot(WORN[i]);
            if (now != s.wornRef[i]) {
                s.wornRef[i] = now;
                mask |= 1 << i;
            }
        }
        return mask;
    }

    private static void readWorn(Seen s) {
        if (s.entity instanceof LivingEntity living) {
            for (int i = 0; i < WORN.length; i++) {
                s.wornRef[i] = living.getItemBySlot(WORN[i]);
            }
        }
    }

    private void readWornAll(Seen s) {
        for (int i = 0; i < WORN.length; i++) {
            s.worn[i] = describe(s.wornRef[i]);
        }
    }

    private static boolean wornNothing(Seen s) {
        for (ItemStack stack : s.wornRef) {
            if (stack != null && !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The session id of {@code s}, minted now on first use (an event is about to name it). */
    private String sid(Seen s) {
        if (s.sid == null) {
            s.sid = ids.assign(s.entity.getId());
        }
        return s.sid;
    }

    /** What the summary and kill lines call {@code s}: the mob name, the name, or the type. */
    private String summaryName(Seen s) {
        if (!s.plain.isEmpty()) {
            String mob = sbs.modid.client.core.entity.NametagText.mobName(s.plain);
            return mob == null || mob.isEmpty() ? s.plain : mob;
        }
        if (s.entity instanceof Player && s.entity.getUUID().version() != 4) {
            return s.entity.getName().getString();
        }
        return entityType(s.entity);
    }

    // ------------------------------------------------------------------ title, boss bars

    /** {@code ClientPacketListener.setTitleText} / {@code setSubtitleText}. */
    void onMouse(String what, double x, double y, double keptX, double keptY, String detail) {
        JsonObject event = out.event(ScanChannel.MOUSE, what);
        event.addProperty("x", Math.round(x));
        event.addProperty("y", Math.round(y));
        event.addProperty("keptX", Math.round(keptX));
        event.addProperty("keptY", Math.round(keptY));
        event.addProperty("detail", detail);
        out.emit(ScanChannel.MOUSE, event, null);
    }

    void onTitle(Component text, boolean subtitle) {
        if (text == null) {
            return;
        }
        String raw = DevLogText.legacy(text);
        JsonObject event = out.event(ScanChannel.TITLE, subtitle ? "subtitle" : "title");
        event.addProperty("raw", raw);
        event.addProperty("plain", text.getString());
        out.emit(ScanChannel.TITLE, event, (subtitle ? "sub|" : "title|") + raw);
    }

    private void tickBosses(Minecraft minecraft) {
        if (minecraft.gui == null) {
            return;
        }
        Map<UUID, LerpingBossEvent> events =
                ((BossHealthOverlayAccessor) minecraft.gui.hud.getBossOverlay()).sbs$events();
        for (Iterator<Map.Entry<UUID, Boss>> it = bosses.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Boss> entry = it.next();
            if (!events.containsKey(entry.getKey())) {
                it.remove();
                JsonObject event = out.event(ScanChannel.TITLE, "boss_remove");
                event.addProperty("bar", entry.getValue().bid);
                out.emit(ScanChannel.TITLE, event, null);
            }
        }
        for (Map.Entry<UUID, LerpingBossEvent> entry : events.entrySet()) {
            LerpingBossEvent bar = entry.getValue();
            Boss known = bosses.get(entry.getKey());
            String name = DevLogText.legacy(bar.getName());
            float progress = Math.round(bar.getProgress() * 1000f) / 1000f;
            if (known == null) {
                known = new Boss("b" + ++bossIds);
                bosses.put(entry.getKey(), known);
                known.name = name;
                known.progress = progress;
                JsonObject event = out.event(ScanChannel.TITLE, "boss");
                event.addProperty("bar", known.bid);
                event.addProperty("raw", name);
                event.addProperty("plain", bar.getName().getString());
                event.addProperty("progress", progress);
                event.addProperty("color", bar.getColor().name().toLowerCase(java.util.Locale.ROOT));
                out.emit(ScanChannel.TITLE, event, null);
                continue;
            }
            boolean renamed = !name.equals(known.name);
            // Progress lerps on the client; a change under half a percent is the animation, not news.
            boolean moved = Math.abs(progress - known.progress) >= 0.005f;
            if (!renamed && !moved) {
                continue;
            }
            JsonObject event = out.event(ScanChannel.TITLE, "boss_change");
            event.addProperty("bar", known.bid);
            if (renamed) {
                event.addProperty("plain", bar.getName().getString());
                event.addProperty("raw", name);
                known.name = name;
            }
            if (moved) {
                event.addProperty("progress", progress);
                known.progress = progress;
            }
            out.emit(ScanChannel.TITLE, event, null);
        }
    }

    // ------------------------------------------------------------------ inventory

    private void tickInventory(LocalPlayer player) {
        var inventory = player.getInventory();
        int size = inventory.getContainerSize();
        if (invRef.length != size) {
            invRef = new ItemStack[size];
            invCount = new int[size];
            inv = new ScanSlot[size];
        }
        JsonArray snapshot = invSnapshotDone ? null : new JsonArray();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inventory.getItem(i);
            int count = stack.getCount();
            if (stack == invRef[i] && count == invCount[i] && invSnapshotDone) {
                continue;
            }
            invRef[i] = stack;
            invCount[i] = count;
            ScanSlot after = ScanReads.read(stack);
            ScanSlot before = inv[i];
            inv[i] = after;
            if (snapshot != null) {
                if (after != null) {
                    JsonObject slot = after.toJson();
                    slot.addProperty("slot", i);
                    snapshot.add(slot);
                }
                continue;
            }
            if (ScanSlot.same(before, after)) {
                continue;
            }
            JsonObject event = out.event(ScanChannel.INVENTORY, "slot");
            event.addProperty("slot", i);
            event.add("old", ScanSlot.oldFields(before, after));
            event.add("new", ScanSlot.newFields(before, after));
            out.emit(ScanChannel.INVENTORY, event, null);
        }
        if (snapshot != null) {
            invSnapshotDone = true;
            JsonObject event = out.event(ScanChannel.INVENTORY, "snapshot");
            event.add("items", snapshot);
            out.emit(ScanChannel.INVENTORY, event, null);
        }
    }

    /** {@code ClientPacketListener.handleTakeItemEntity}, before vanilla removes the ground item. */
    void onTakeItem(ClientboundTakeItemEntityPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        boolean self = packet.getPlayerId() == player.getId();
        Entity item = minecraft.level.getEntity(packet.getItemId());
        if (!self && (item == null || item.distanceToSqr(player) > r2)) {
            return;
        }
        JsonObject event = out.event(ScanChannel.INVENTORY, "pickup");
        Seen s = seen.get(packet.getItemId());
        event.addProperty("e", s != null && s.sid != null ? s.sid : ids.assign(packet.getItemId()));
        event.addProperty("self", self);
        event.addProperty("amount", packet.getAmount());
        String label = "?";
        if (item instanceof ItemEntity ground) {
            event.addProperty("item", describe(ground.getItem()));
            label = MenuReads.plain(ground.getItem().getHoverName());
            event.addProperty("itemName", label);
        } else if (item != null) {
            event.addProperty("etype", entityType(item));   // experience orbs and arrows are picked up too
            label = entityType(item);
        }
        if (!self) {
            event.addProperty("by", packet.getPlayerId());
        }
        out.emit(ScanChannel.INVENTORY, event, null);
        if (self) {
            out.summary().pickup(out.wall(), packet.getAmount() + "x " + label);
        }
    }

    // ------------------------------------------------------------------ sound

    /** {@code SoundEngine.play}, through {@code SoundListeners}. */
    void onSound(SoundInstance instance) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Identifier id = instance.getIdentifier();
        if (id == null) {
            return;
        }
        String key = soundIds.get(id);
        if (key == null) {
            key = id.toString();   // once per sound id, never per sound
            soundIds.put(id, key);
        }
        if (instance.getSource() == SoundSource.MUSIC || ScanNames.noiseSound(id.getPath())) {
            out.summary().suppressed(ScanNames.NOISE_SOUND);
            return;
        }
        boolean relative = instance.isRelative()
                || instance.getAttenuation() == SoundInstance.Attenuation.NONE;
        double x = instance.getX();
        double y = instance.getY();
        double z = instance.getZ();
        if (!relative && player.distanceToSqr(x, y, z) > r2) {
            return;
        }
        float pitch = Math.round(instance.getPitch() * 1000f) / 1000f;
        JsonObject event = out.event(ScanChannel.SOUND, "sound");
        event.addProperty("id", key);
        if (relative) {
            event.addProperty("relative", true);
        } else {
            event.add("at", pos(x, y, z));
        }
        event.addProperty("vol", Math.round(instance.getVolume() * 1000f) / 1000f);
        event.addProperty("pitch", pitch);
        event.addProperty("src", instance.getSource().getName());
        out.emit(ScanChannel.SOUND, event,
                key + "@" + Math.round(x) + "," + Math.round(y) + "," + Math.round(z) + "|" + pitch);
    }

    // ------------------------------------------------------------------ particles

    /** {@code ClientPacketListener.handleParticleEvent}: counted, written once a second per type. */
    void onParticles(ClientboundLevelParticlesPacket packet) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.distanceToSqr(packet.getX(), packet.getY(), packet.getZ()) > r2) {
            return;
        }
        ParticleType<?> type = packet.getParticle().getType();
        String key = particleTypes.get(type);
        if (key == null) {
            key = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(type));
            particleTypes.put(type, key);
        }
        Agg agg = particles.get(key);
        if (agg == null) {
            agg = new Agg();
            particles.put(key, agg);
        }
        agg.packets++;
        agg.count += Math.max(1, packet.getCount());
        agg.x += packet.getX();
        agg.y += packet.getY();
        agg.z += packet.getZ();
    }

    private void flushParticles(long nowMs) {
        particleWindowAt = nowMs;
        if (particles.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Agg> entry : particles.entrySet()) {
            Agg agg = entry.getValue();
            JsonObject event = out.event(ScanChannel.PARTICLES, "particles");
            event.addProperty("id", entry.getKey());
            event.addProperty("packets", agg.packets);
            event.addProperty("count", agg.count);
            event.add("center", pos(agg.x / agg.packets, agg.y / agg.packets, agg.z / agg.packets));
            out.emit(ScanChannel.PARTICLES, event, null);
        }
        particles.clear();
    }

    // ------------------------------------------------------------------ blocks

    /** {@code ClientLevel.setServerVerifiedBlockState}, HEAD: {@code old} is still in the level. */
    void onBlock(BlockPos at, BlockState old, BlockState now) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || old == now
                || player.distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5) > r2) {
            return;
        }
        JsonObject event = out.event(ScanChannel.BLOCKS, "block");
        JsonArray where = new JsonArray();
        where.add(at.getX());
        where.add(at.getY());
        where.add(at.getZ());
        event.add("at", where);
        event.addProperty("old", state(old));
        event.addProperty("new", state(now));
        out.emit(ScanChannel.BLOCKS, event,
                "b" + at.getX() + "," + at.getY() + "," + at.getZ() + ">" + state(now));
    }

    /** {@code lever[face=wall,facing=north,powered=true]}; block states are shared, so cached by identity. */
    private String state(BlockState state) {
        String text = blockStates.get(state);
        if (text == null) {
            text = String.valueOf(state);
            // "Block{minecraft:lever}[face=wall,...]" -> "lever[face=wall,...]"
            if (text.startsWith("Block{")) {
                int close = text.indexOf('}');
                String id = close > 6 ? text.substring(6, close) : text;
                text = (id.startsWith("minecraft:") ? id.substring(10) : id) + (close > 0 ? text.substring(close + 1) : "");
            }
            blockStates.put(state, text);
        }
        return text;
    }

    // ------------------------------------------------------------------ helpers

    private String entityType(Entity entity) {
        EntityType<?> type = entity.getType();
        String key = entityTypes.get(type);
        if (key == null) {
            key = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
            entityTypes.put(type, key);
        }
        return key;
    }

    /** {@code player_head|SB_ID#texturehash}, or null for nothing. */
    static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        String sbId = SkyblockItem.id(stack);
        if (sbId != null && !sbId.isEmpty()) {
            out.append('|').append(sbId);
        }
        String texture = textureHash(stack);
        if (texture != null) {
            out.append('#').append(texture);
        }
        return out.toString();
    }

    /** A player head's skin texture hash (the last segment of its URL), or null. */
    static String textureHash(ItemStack stack) {
        var profile = stack.get(DataComponents.PROFILE);
        if (profile == null) {
            return null;
        }
        for (var property : profile.partialProfile().properties().get("textures")) {
            try {
                String json = new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
                Matcher matcher = SKIN_URL.matcher(json);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            } catch (IllegalArgumentException ignored) {
                // not base64: no hash to give
            }
        }
        return null;
    }

    static JsonArray pos(double x, double y, double z) {
        JsonArray out = new JsonArray();
        out.add(round(x));
        out.add(round(y));
        out.add(round(z));
        return out;
    }

    /** Rounded to 0.1 - a block is ten steps, which is finer than anything a capture is read for. */
    static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
