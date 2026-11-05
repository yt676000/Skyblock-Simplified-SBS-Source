/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.gyro.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.ItemAnalysis;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tick-side state of the Gyro Helper: whether the wand is held, where Gravity Storm would land,
 * which ring vertices you can see, how many mobs stand inside, and the ability's cooldown. The
 * renderer only reads the fields this publishes - nothing here runs per frame.
 *
 * <p><b>Cadence.</b> Aim raycast every tick (one clip); ring visibility every
 * {@link #VISIBILITY_TICKS} ticks (one clip per vertex, ~50); mob count every {@link #COUNT_TICKS}.
 * Nothing at all runs unless the wand is in the main hand.
 *
 * <p><b>Why the ring needs a visibility pass.</b> The mod has no depth-tested world renderer -
 * {@code WorldRender} draws projected lines on the HUD, which nothing in the world can cover. The
 * ring is a prediction of your own ability, not an entity reveal, but it still must not draw
 * through walls, so each vertex is kept only when a ray from the eye reaches it unobstructed
 * (the same answer {@code EffectiveBlockScanner} gives for faces).
 *
 * <p><b>The probe.</b> Each detected cast logs the predicted centre and, ~1 s later, what the server
 * showed near it - particle packets, and the entities that appeared - as
 * {@code [SBS][Gyro] predicted ... -> observed ...}. Those lines decide the targeting rule in
 * {@link GyroAim#centre}.
 */
public final class GyroState {

    private static final GyroState INSTANCE = new GyroState();

    public static final long COOLDOWN_MS = 30_000L;
    private static final int VISIBILITY_TICKS = 2;
    private static final int COUNT_TICKS = 5;
    /** A vertex ray that stops this close to the vertex still counts as reaching it (the floor itself). */
    private static final double VISIBLE_SLACK_SQ = 0.35 * 0.35;
    /** Ring vertices are lifted this far so the floor they sit on does not occlude them. */
    public static final double LIFT = 0.05;

    private static final long PROBE_WINDOW_MS = 1_000L;
    /** Particles further than this from the predicted centre are not the vortex. */
    private static final double PROBE_REACH = 32.0;
    private static final int PROBE_MAX_SAMPLES = 400;
    /** A cooldown message this soon after a wand click belongs to that click. */
    private static final long CHAT_LINK_MS = 1_500L;

    /** Read by {@code ParticleProbeMixin}: a static field read per packet while no cast is open. */
    public static volatile boolean LISTENING;

    // ---------------------------------------------------------------- published (main thread)
    private boolean holding;
    private boolean hasCentre;
    private final double[] centre = new double[3];
    private GyroAim.Ring ring = GyroAim.Ring.of(GyroAim.DEFAULT_RADIUS);
    private boolean[] visible = new boolean[ring.size()];
    private int mobsInside;
    private long cooldownEndsAt;

    // ---------------------------------------------------------------- internal
    private String loreKey;
    private int loreRange = GyroAim.DEFAULT_RANGE;
    private int loreRadius = GyroAim.DEFAULT_RADIUS;
    private int tickCount;
    private long lastClickAt;
    private boolean announced;

    // probe
    private long castAt;
    private boolean castHit;
    private final double[] castCentre = new double[3];
    private int probeSamples;
    private double sumX;
    private double sumY;
    private double sumZ;
    private final List<String> probeTypes = new ArrayList<>();

    private GyroState() {
    }

    public static GyroState getInstance() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- reads for the renderer

    public boolean holding() {
        return holding;
    }

    public boolean hasCentre() {
        return hasCentre;
    }

    public double centreX() {
        return centre[0];
    }

    public double centreY() {
        return centre[1];
    }

    public double centreZ() {
        return centre[2];
    }

    public GyroAim.Ring ring() {
        return ring;
    }

    public boolean[] visible() {
        return visible;
    }

    public int mobsInside() {
        return mobsInside;
    }

    public boolean onCooldown() {
        return System.currentTimeMillis() < cooldownEndsAt;
    }

    // ---------------------------------------------------------------- tick

    public void onClientTick() {
        SBSConfig.GyroHelperSettings cfg = ConfigManager.getInstance().get().gyroHelper;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        finishProbeIfDue(level);
        if (!cfg.enabled || player == null || level == null
                || (cfg.dungeonsOnly && !SkyBlockLocation.inDungeon())) {
            clear();
            return;
        }
        ItemStack held = player.getMainHandItem();
        ItemAnalysis analysis = ItemAnalysis.of(held);
        if (!GyroAim.isWand(analysis.id())) {
            clear();
            return;
        }
        holding = true;
        readLore(held, analysis);
        int range = cfg.rangeOverride > 0 ? cfg.rangeOverride : loreRange;
        int radius = cfg.radiusOverride > 0 ? cfg.radiusOverride : loreRadius;
        if (ring.radius() != radius) {
            ring = GyroAim.Ring.of(radius);
            visible = new boolean[ring.size()];
        }

        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(range));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean blockHit = hit.getType() == HitResult.Type.BLOCK;
        Vec3 at = hit.getLocation();
        hasCentre = GyroAim.centre(blockHit, blockHit && hit.getDirection() == Direction.UP,
                at.x, at.y, at.z, hit.getBlockPos().getY(), centre);

        tickCount++;
        if (hasCentre && tickCount % VISIBILITY_TICKS == 0) {
            updateVisibility(level, player, eye);
        }
        if (cfg.showMobCount && hasCentre && tickCount % COUNT_TICKS == 0) {
            mobsInside = countMobs(level, radius);
        }
        if (!announced) {
            announced = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Gyro] wand held: range={} radius={} (lore range={}, radius={})",
                    range, radius, loreRange, loreRadius);
        }
    }

    private void clear() {
        holding = false;
        hasCentre = false;
        announced = false;
    }

    /** Lore is read once per item instance (uuid), never per tick. */
    private void readLore(ItemStack held, ItemAnalysis analysis) {
        String key = analysis.uuid() != null ? analysis.uuid() : analysis.id();
        if (key.equals(loreKey)) {
            return;
        }
        loreKey = key;
        ItemLore lore = held.get(DataComponents.LORE);
        List<String> lines = new ArrayList<>();
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(line.getString());
            }
        }
        String joined = GyroAim.joinLore(lines);
        loreRange = GyroAim.range(joined, GyroAim.DEFAULT_RANGE);
        loreRadius = GyroAim.radius(joined, GyroAim.DEFAULT_RADIUS);
        SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Gyro] lore: {}", joined);
    }

    private void updateVisibility(ClientLevel level, LocalPlayer player, Vec3 eye) {
        double y = centre[1] + LIFT;
        for (int i = 0; i < ring.size(); i++) {
            double x = centre[0] + ring.dx()[i];
            double z = centre[2] + ring.dz()[i];
            Vec3 target = new Vec3(x, y, z);
            BlockHitResult ray = level.clip(new ClipContext(
                    eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
            visible[i] = ray.getType() != HitResult.Type.BLOCK
                    || ray.getLocation().distanceToSqr(target) <= VISIBLE_SLACK_SQ;
        }
    }

    private int countMobs(ClientLevel level, int radius) {
        AABB box = new AABB(centre[0] - radius, centre[1] - radius, centre[2] - radius,
                centre[0] + radius, centre[1] + radius, centre[2] + radius);
        double r2 = (double) radius * radius;
        int n = 0;
        for (Entity e : level.getEntities((Entity) null, box)) {
            if (e instanceof LivingEntity living && !(e instanceof Player) && living.isAlive()
                    && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
                double dx = e.getX() - centre[0];
                double dz = e.getZ() - centre[2];
                if (dx * dx + dz * dz <= r2) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- cast detection

    /** From the left-click hook: a Gravity Storm attempt when the wand is in hand. */
    public void onLeftClick(ItemStack held) {
        if (!holding || held == null || !GyroAim.isWand(ItemAnalysis.of(held).id())) {
            return;
        }
        long now = System.currentTimeMillis();
        lastClickAt = now;
        if (now < cooldownEndsAt) {
            return;                                   // the ability does not fire while cooling down
        }
        cooldownEndsAt = now + COOLDOWN_MS;
        castAt = now;
        castHit = hasCentre;
        if (hasCentre) {
            System.arraycopy(centre, 0, castCentre, 0, 3);
        } else {
            // No block in range: probe around the ray's end so the "mid-air or ground" question is answered.
            LocalPlayer player = Minecraft.getInstance().player;
            int range = loreRange;
            Vec3 end = player.getEyePosition().add(player.getViewVector(1.0f).scale(range));
            castCentre[0] = end.x;
            castCentre[1] = end.y;
            castCentre[2] = end.z;
        }
        probeSamples = 0;
        sumX = sumY = sumZ = 0;
        probeTypes.clear();
        LISTENING = true;
    }

    /** "This ability is on cooldown for Ns" shortly after a wand click corrects the clock. */
    public void onChat(String message) {
        long now = System.currentTimeMillis();
        if (holding && message.contains("Gravity Storm")) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Gyro] chat: {}", message);
        }
        if (now - lastClickAt > CHAT_LINK_MS) {
            return;
        }
        int at = message.indexOf("cooldown for ");
        if (at < 0) {
            return;
        }
        int seconds = 0;
        for (int i = at + "cooldown for ".length(); i < message.length() && Character.isDigit(message.charAt(i)); i++) {
            seconds = seconds * 10 + (message.charAt(i) - '0');
        }
        if (seconds > 0) {
            cooldownEndsAt = now + seconds * 1000L;
            if (now - castAt < CHAT_LINK_MS) {
                LISTENING = false;                    // that click did not cast - nothing to probe
                castAt = 0;
            }
        }
    }

    /** From {@code ParticleProbeMixin}, main thread, only while {@link #LISTENING}. */
    public void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (System.currentTimeMillis() - castAt > PROBE_WINDOW_MS || probeSamples >= PROBE_MAX_SAMPLES) {
            return;
        }
        double dx = packet.getX() - castCentre[0];
        double dy = packet.getY() - castCentre[1];
        double dz = packet.getZ() - castCentre[2];
        if (dx * dx + dy * dy + dz * dz > PROBE_REACH * PROBE_REACH) {
            return;
        }
        probeSamples++;
        sumX += packet.getX();
        sumY += packet.getY();
        sumZ += packet.getZ();
        if (probeTypes.size() < 8) {
            String type = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType()));
            if (!probeTypes.contains(type)) {
                probeTypes.add(type);
            }
        }
    }

    private void finishProbeIfDue(ClientLevel level) {
        if (!LISTENING || System.currentTimeMillis() - castAt < PROBE_WINDOW_MS) {
            return;
        }
        LISTENING = false;
        String predicted = String.format(Locale.ROOT, "%.1f,%.1f,%.1f (hit=%s)",
                castCentre[0], castCentre[1], castCentre[2], castHit ? "block" : "air");
        String particles = "none";
        if (probeSamples > 0) {
            double ox = sumX / probeSamples;
            double oy = sumY / probeSamples;
            double oz = sumZ / probeSamples;
            particles = String.format(Locale.ROOT, "%.1f,%.1f,%.1f (dxz=%.2f dy=%.2f, %d packets, %s)",
                    ox, oy, oz, Math.hypot(ox - castCentre[0], oz - castCentre[2]), oy - castCentre[1],
                    probeSamples, probeTypes);
        }
        StringBuilder entities = new StringBuilder();
        if (level != null) {
            AABB box = new AABB(castCentre[0] - 12, castCentre[1] - 12, castCentre[2] - 12,
                    castCentre[0] + 12, castCentre[1] + 12, castCentre[2] + 12);
            int listed = 0;
            for (Entity e : level.getEntities((Entity) null, box)) {
                if (e instanceof Player || e.tickCount > 40 || listed >= 6) {
                    continue;                         // only what appeared since the cast
                }
                listed++;
                entities.append(String.format(Locale.ROOT, " %s@%.1f,%.1f,%.1f",
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), e.getX(), e.getY(), e.getZ()));
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Gyro] predicted {} -> observed particles {}; new entities:{}",
                predicted, particles, entities.length() == 0 ? " none" : entities);
    }

    /** World change: nothing from the old world is valid. */
    public void onWorldChange() {
        clear();
        LISTENING = false;
        castAt = 0;
        loreKey = null;
    }
}
