/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.hitboxes;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.player.RealPlayers;

/**
 * Entity Hitboxes: the vanilla hitbox (what F3+B shows) for the entity types the player picked,
 * depth-tested like vanilla's - never through walls.
 *
 * <p><b>How it draws (verified on the 26.2 jar).</b> 26.2 draws F3+B through debug gizmos:
 * {@code EntityHitboxDebugRenderer.showHitboxes} emits {@code Gizmos.cuboid} for the box, a thin red
 * cuboid at eye height and {@code Gizmos.arrow} for the look direction, inside the gizmo scope that
 * {@code LevelExtractor.extract} opens around {@code DebugRenderer.emitGizmos} every frame. This
 * class emits the same gizmos from the TAIL of that call ({@code EntityHitboxesMixin}), with a colour
 * per category. A gizmo is depth-tested unless {@code setAlwaysOnTop()} is called, which is never done
 * here. While vanilla's F3+B is on it already boxes everything, so nothing is added; vanilla's own
 * F3+B state is only read, never changed.
 *
 * <p><b>Cost.</b> Off: one config check. On: per frame one map lookup per rendered entity; the
 * classification runs at most every {@value #REFRESH_MS} ms. Armour stands are read only when a
 * category needs them - the Armor stands category itself, or SkyBlock mobs (whose name sits on the
 * stand above them, read with the Mob Highlight helpers).
 */
public final class EntityHitboxes {

    private static final EntityHitboxes INSTANCE = new EntityHitboxes();

    static final long REFRESH_MS = 150L;

    /** Vanilla's eye-height line and look arrow colours. */
    private static final int EYE_COLOR = 0xFFFF0000;
    private static final int LOOK_COLOR = 0xFF0000FF;

    /** Entity id -> category ordinal, rebuilt on the throttle. */
    private final Int2IntOpenHashMap categories = new Int2IntOpenHashMap();
    /** Category ordinal -> ARGB box colour, rebuilt with the map. */
    private final int[] colors = new int[HitboxCategory.values().length];
    /** Category ordinal -> drawn at all, rebuilt with the map. */
    private final boolean[] shown = new boolean[HitboxCategory.values().length];
    private long refreshedAt;
    private ClientLevel refreshedLevel;

    private EntityHitboxes() {
        categories.defaultReturnValue(-1);
    }

    public static EntityHitboxes getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.EntityHitboxSettings cfg() {
        return ConfigManager.getInstance().get().entityHitboxes;
    }

    /** From the TAIL of {@code DebugRenderer.emitGizmos}, inside the frame's gizmo scope. */
    public void emit(Frustum frustum, double camX, double camY, double camZ, float partialTick) {
        SBSConfig.EntityHitboxSettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES)) {
            return;   // vanilla's F3+B is on and already boxes everything
        }
        long now = System.currentTimeMillis();
        if (now - refreshedAt >= REFRESH_MS || level != refreshedLevel) {
            refresh(level, cfg, minecraft);
            refreshedAt = now;
            refreshedLevel = level;
        }
        double maxSq = (double) cfg.maxDistance * cfg.maxDistance;
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        for (Entity entity : level.entitiesForRendering()) {
            int category = categories.get(entity.getId());
            if (category < 0 || !shown[category]) {
                continue;
            }
            if (entity == minecraft.getCameraEntity() && firstPerson) {
                continue;   // vanilla skips the camera entity in first person too
            }
            if (entity.isInvisible() && !cfg.includeInvisible) {
                continue;
            }
            if (entity.distanceToSqr(camX, camY, camZ) > maxSq) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            if (!frustum.isVisible(box)) {
                continue;
            }
            draw(entity, box, colors[category], cfg.eyeAndLook, partialTick);
        }
    }

    /** The box (interpolated like vanilla), and optionally the eye-height line and look arrow. */
    private static void draw(Entity entity, AABB box, int color, boolean eyeAndLook, float partialTick) {
        Vec3 offset = entity.getPosition(partialTick).subtract(entity.position());
        AABB moved = box.move(offset);
        Gizmos.cuboid(moved, GizmoStyle.stroke(color));
        if (!eyeAndLook || !(entity instanceof LivingEntity)) {
            return;
        }
        double eyeY = moved.minY + entity.getEyeHeight();
        Gizmos.cuboid(new AABB(moved.minX, eyeY - 0.01, moved.minZ, moved.maxX, eyeY + 0.01, moved.maxZ),
                GizmoStyle.stroke(EYE_COLOR));
        Vec3 eye = entity.getPosition(partialTick).add(0, entity.getEyeHeight(), 0);
        Gizmos.arrow(eye, eye.add(entity.getViewVector(partialTick).scale(2.0)), LOOK_COLOR);
    }

    // ------------------------------------------------------------------ classification (throttled)

    private void refresh(ClientLevel level, SBSConfig.EntityHitboxSettings cfg, Minecraft minecraft) {
        for (HitboxCategory category : HitboxCategory.values()) {
            shown[category.ordinal()] = on(cfg, category);
            colors[category.ordinal()] = 0xFF000000 | rgb(cfg, category);
        }
        categories.clear();
        boolean wantStands = cfg.armorStands;
        boolean wantSkyblock = cfg.skyblockMobs;
        // SkyBlock mobs whose name sits on the nametag stand above them - only walked when wanted.
        it.unimi.dsi.fastutil.ints.IntOpenHashSet namedByStand = null;
        if (wantSkyblock) {
            namedByStand = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof ArmorStand stand && stand.hasCustomName()
                        && MobHighlightTracker.mobNameInNametag(stand.getCustomName().getString()) != null) {
                    LivingEntity mob = MobHighlightTracker.mobBelow(level, stand);
                    if (mob != null) {
                        namedByStand.add(mob.getId());
                    }
                }
            }
        }
        for (Entity entity : level.entitiesForRendering()) {
            boolean stand = entity instanceof ArmorStand;
            if (stand && !wantStands) {
                continue;   // thousands of hologram stands: not even classified unless asked for
            }
            boolean playerShaped = entity instanceof Avatar;
            boolean living = entity instanceof LivingEntity;
            boolean skyblockNamed = wantSkyblock && living && !stand && !playerShaped
                    && (namedByStand.contains(entity.getId())
                    || (entity.hasCustomName()
                    && MobHighlightTracker.mobNameInNametag(entity.getCustomName().getString()) != null));
            HitboxCategory category = HitboxCategory.classify(entity == minecraft.player, playerShaped,
                    playerShaped && RealPlayers.isRealPlayer(entity), stand, entity instanceof ItemEntity,
                    entity instanceof Projectile, living, skyblockNamed, entity instanceof Enemy);
            categories.put(entity.getId(), category.ordinal());
        }
    }

    /** Whether a category is drawn. NPCs ride on Other, and only while they are not hidden. */
    static boolean on(SBSConfig.EntityHitboxSettings cfg, HitboxCategory category) {
        return switch (category) {
            case PLAYER -> cfg.players;
            case SELF -> cfg.self;
            case SKYBLOCK_MOB -> cfg.skyblockMobs;
            case PASSIVE -> cfg.passive;
            case HOSTILE -> cfg.hostile;
            case ARMOR_STAND -> cfg.armorStands;
            case ITEM -> cfg.items;
            case PROJECTILE -> cfg.projectiles;
            case NPC -> !cfg.hideNpcs && cfg.other;
            case OTHER -> cfg.other;
        };
    }

    /** The category's colour, RRGGBB: the setting, or the default when it is empty or invalid. */
    static int rgb(SBSConfig.EntityHitboxSettings cfg, HitboxCategory category) {
        String hex = switch (category) {
            case PLAYER -> cfg.playersHex;
            case SELF -> cfg.selfHex;
            case SKYBLOCK_MOB -> cfg.skyblockMobsHex;
            case PASSIVE -> cfg.passiveHex;
            case HOSTILE -> cfg.hostileHex;
            case ARMOR_STAND -> cfg.armorStandsHex;
            case ITEM -> cfg.itemsHex;
            case PROJECTILE -> cfg.projectilesHex;
            case NPC, OTHER -> cfg.otherHex;
        };
        return parseRgb(hex, category.defaultRgb());
    }

    static int parseRgb(String hex, int fallback) {
        if (hex == null) {
            return fallback;
        }
        String s = hex.trim().replace("#", "");
        return s.matches("[0-9A-Fa-f]{6}") ? Integer.parseInt(s, 16) : fallback;
    }
}
