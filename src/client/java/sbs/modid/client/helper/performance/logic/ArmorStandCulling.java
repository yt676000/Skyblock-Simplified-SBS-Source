/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.performance.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

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
 *       unreadable out there anyway), and stands a margin beyond THAT stop being ticked, so a
 *       stand gliding into view has resumed its movement interpolation before it is first seen.</li>
 * </ul>
 *
 * <p>Both predicates re-evaluate every frame/tick, so a stand that gains a name or equipment (or
 * walks into range) reappears instantly. Ticking is never skipped for a stand carrying passengers:
 * Hypixel rides visible mobs on invisible stands, and {@code tickNonPassenger} ticks the passenger
 * chain - freezing the mount would freeze the mob. Culling only affects vanilla rendering/ticking;
 * SBS trackers (Pelt, Frozen Corpse, Pest ...) read entity DATA and draw their own boxes, which is
 * untouched by either rule.
 */
public final class ArmorStandCulling {

    /**
     * Extra blocks past the render distance before ticking stops too. Inside the margin a stand is
     * invisible but still interpolating, so by the time it crosses into render range its position
     * is already smooth - no first-frame snap.
     */
    private static final int TICK_MARGIN = 16;

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
        return distance > 0 && stand.distanceToSqr(camX, camY, camZ) > (double) distance * distance;
    }

    /**
     * Whether this entity's client tick should be skipped entirely. Only stands that are not
     * rendered anyway qualify (chrome, or beyond render distance + {@link #TICK_MARGIN}), and
     * never one carrying passengers - its tick is what ticks the riders.
     */
    public static boolean skipTick(Entity entity) {
        if (!(entity instanceof ArmorStand stand)) {
            return false;
        }
        SBSConfig.PerformanceSettings settings = cfg();
        if (!settings.enabled || !settings.tickCulling || stand.isVehicle()) {
            return false;
        }
        if (settings.hideChromeStands && isChrome(stand)) {
            return true;
        }
        int distance = settings.standRenderDistance;
        if (distance <= 0) {
            return false;
        }
        var player = Minecraft.getInstance().player;
        int reach = distance + TICK_MARGIN;
        return player != null && stand.distanceToSqr(player) > (double) reach * reach;
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
