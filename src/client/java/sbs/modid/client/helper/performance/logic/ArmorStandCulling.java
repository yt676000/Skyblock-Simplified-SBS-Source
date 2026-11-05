/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.performance.logic;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import sbs.modid.client.combat.mobhighlight.model.SkyblockMobCatalog;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.helper.performance.logic.NametagDistance.LabelClass;

import java.util.Locale;

/**
 * The Performance module's armor-stand culling: which armor stands are worth rendering and
 * ticking at all.
 *
 * <p>Hypixel builds nearly everything out of armor stands - hologram text is one invisible stand
 * per LINE, mobs carry stands for their health bars, menus and islands are decorated with geared
 * stands, and plenty of stands are pure script anchors with nothing visible on them whatsoever.
 * Vanilla still pays the full price for each one every frame (render-state extraction, pose math,
 * the whole layer stack) and every tick (the complete {@code LivingEntity} tick, equipment-change
 * scans included), so a busy lobby burns a measurable slice of the frame on entities the player
 * never sees. Two rules cut that down:
 *
 * <ul>
 *   <li><b>Chrome stands</b> - invisible, no visible name, no equipment in any slot - draw nothing
 *       at all, ever. They are skipped outright.</li>
 *   <li><b>Distance</b> - stands beyond the configured range are not rendered (hologram text is
 *       unreadable out there anyway). They keep ticking: core/AGENTS.md rule 10 forbids thinning
 *       armor stand ticks.</li>
 * </ul>
 *
 * <p>Both predicates re-evaluate every frame, so a stand that gains a name or equipment (or
 * walks into range) reappears instantly. Culling only affects vanilla rendering;
 * SBS trackers (Pelt, Frozen Corpse, Pest ...) read entity DATA and draw their own boxes, which is
 * untouched by either rule.
 *
 * <p><b>Nametag distance by type</b> (opt-in, off by default) refines the distance rule for label
 * stands - invisible, name shown. Each label's {@link LabelClass} is derived once per name change
 * from the {@link EntityIndex} entry's cached {@code NameClass} and mob name, and kept on that entry:
 * common catalogued mobs get the short distance, NPC names, holograms and the exception list are
 * never distance-culled, everything else keeps the armor-stand distance. An
 * optional occlusion check casts one ray per label every {@link #OCCLUSION_INTERVAL} ticks and
 * reuses the answer in between; it stays off while another loaded mod already occlusion-culls
 * entities, so labels are never culled twice.
 */
public final class ArmorStandCulling {

    /** Ticks between two occlusion rays for the same label. */
    private static final int OCCLUSION_INTERVAL = 5;

    /** Mod id of an entity occlusion-culling mod; while it is loaded our occlusion check stays off. */
    private static final String OCCLUSION_CULLING_MOD = "entityculling";
    private static final boolean EXTERNAL_OCCLUSION =
            FabricLoader.getInstance().isModLoaded(OCCLUSION_CULLING_MOD);

    /** The exception setting as last parsed, and a version bumped on each re-parse. Client thread. */
    private static String exceptionsSource;
    private static String[] exceptions = new String[0];
    private static int exceptionsVersion;

    /** Per-label cache, stored on its {@link Tracked} entry. Client thread only. */
    private static final class LabelState {
        int revision = -1;
        int exceptionsVersion = -1;
        LabelClass cls = LabelClass.NORMAL;
        long checkedTick = Long.MIN_VALUE;
        boolean occluded;
    }

    private ArmorStandCulling() {
    }

    private static SBSConfig.PerformanceSettings cfg() {
        return ConfigManager.getInstance().get().performance;
    }

    /**
     * Whether this entity should be skipped by the render dispatcher. Called for EVERY entity
     * every frame (from the {@code shouldRender} hook), so the armor-stand check bails first.
     * {@code camX/Y/Z} is the camera position the dispatcher passed alongside the entity.
     */
    public static boolean skipRender(Entity entity, double camX, double camY, double camZ) {
        if (!(entity instanceof ArmorStand stand)) {
            return false;
        }
        SBSConfig.PerformanceSettings settings = cfg();
        if (!settings.enabled) {
            return false;
        }
        if (settings.hideChromeStands && isChrome(stand)) {
            return true;
        }
        int distance = settings.standRenderDistance;
        if (settings.nametagClasses && isLabel(stand)) {
            return skipLabel(stand, settings, distance, camX, camY, camZ);
        }
        return distance > 0 && stand.distanceToSqr(camX, camY, camZ) > (double) distance * distance;
    }

    /** A label stand: invisible, its name shown. Hypixel's mob tags, NPC names and holograms. */
    private static boolean isLabel(ArmorStand stand) {
        return stand.isInvisible() && stand.isCustomNameVisible();
    }

    private static boolean skipLabel(ArmorStand stand, SBSConfig.PerformanceSettings settings,
            int normal, double camX, double camY, double camZ) {
        double distanceSqr = stand.distanceToSqr(camX, camY, camZ);
        boolean occlusion = settings.nametagOcclusion && !EXTERNAL_OCCLUSION;
        int trash = settings.trashNametagDistance;
        int nearest = NametagDistance.distance(LabelClass.TRASH, trash, normal);
        if (!occlusion && (nearest <= 0 || distanceSqr <= (double) nearest * nearest)) {
            return false;
        }
        Tracked tracked = EntityIndex.get(stand);
        if (tracked == null) {
            return normal > 0 && distanceSqr > (double) normal * normal;
        }
        LabelState state = state(tracked, settings);
        if (NametagDistance.beyond(state.cls, distanceSqr, trash, normal)) {
            return true;
        }
        return occlusion && state.cls != LabelClass.ALWAYS && occluded(stand, state, camX, camY, camZ);
    }

    /** The entry's label state, re-derived only when its name or the exception list changed. */
    private static LabelState state(Tracked tracked, SBSConfig.PerformanceSettings settings) {
        String csv = settings.nametagAlwaysShow;
        if (csv != exceptionsSource) {
            exceptionsSource = csv;
            exceptions = NametagDistance.parseExceptions(csv);
            exceptionsVersion++;
        }
        LabelState state = tracked.labelState instanceof LabelState s ? s : null;
        if (state == null) {
            state = new LabelState();
            tracked.labelState = state;
        }
        int revision = tracked.revision();
        if (state.revision != revision || state.exceptionsVersion != exceptionsVersion) {
            state.cls = NametagDistance.classify(tracked.nameClass(), tracked.mobName(),
                    tracked.plain().toLowerCase(Locale.ROOT), exceptions, SkyblockMobCatalog::isKnown);
            state.revision = revision;
            state.exceptionsVersion = exceptionsVersion;
        }
        return state;
    }

    /**
     * Whether the label is behind blocks, as of its last ray. A ray is cast at most once per
     * {@link #OCCLUSION_INTERVAL} ticks per label, staggered by entity id so labels spread across
     * ticks; every frame in between reuses the answer.
     */
    private static boolean occluded(ArmorStand stand, LabelState state,
            double camX, double camY, double camZ) {
        long tick = stand.level().getGameTime();
        if (state.checkedTick == Long.MIN_VALUE) {
            state.checkedTick = tick - OCCLUSION_INTERVAL + Math.floorMod(stand.getId(), OCCLUSION_INTERVAL);
        }
        if (tick - state.checkedTick >= OCCLUSION_INTERVAL) {
            state.checkedTick = tick;
            Vec3 from = new Vec3(camX, camY, camZ);
            Vec3 to = new Vec3(stand.getX(), stand.getEyeY(), stand.getZ());
            state.occluded = stand.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE, CollisionContext.empty())).getType() != HitResult.Type.MISS;
        }
        return state.occluded;
    }

    /**
     * A "chrome" stand renders nothing whatsoever: invisible, its name hidden (or absent), and
     * every equipment slot empty. Hypixel scatters these as script anchors, hologram spacers and
     * seat entities. Re-checked live, so one that gains a visible part comes back the same frame.
     */
    public static boolean isChrome(ArmorStand stand) {
        if (!stand.isInvisible()) {
            return false;
        }
        if (stand.isCustomNameVisible() && stand.getCustomName() != null) {
            return false;
        }
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (!stand.getItemBySlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
