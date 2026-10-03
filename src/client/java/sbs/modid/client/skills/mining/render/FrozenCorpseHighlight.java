/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frozen Corpse highlight for the Glacite Mineshafts: once a corpse (Lapis / Umber / Tungsten / Vanguard)
 * has been <b>seen</b>, its position is remembered and a box is drawn there, so you can walk back to
 * it even after it leaves render distance or you have wandered off. Toggleable; only active in a
 * Mineshaft, and the memory is cleared on leaving (each Mineshaft is its own instance).
 *
 * <p><b>Detection is a best-guess.</b> Corpses are armor stands frozen into the ice wearing a
 * player-head skin; the exact corpse skins are not hard-coded, so this boxes every player-head armor
 * stand in the Mineshaft and logs each candidate ({@code [SBS][Corpse]}: head id, skull owner, hover
 * name, position) so the real corpse skins can be pinned and non-corpse heads filtered out.
 */
public final class FrozenCorpseHighlight {

    /** How far to look for corpse armor stands each frame. */
    private static final double SCAN_RADIUS = 48.0;
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** Remembered corpses in the current Mineshaft: packed block position -> precise feet position. */
    private static final Map<Long, Vec3> corpses = new ConcurrentHashMap<>();

    private static long lastLogAt;

    private FrozenCorpseHighlight() {
    }

    /** Called from the HUD world-render pass once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.FrozenCorpseSettings cfg = ConfigManager.getInstance().get().frozenCorpse;
        // The island gate applies to every mining feature, this one included. A Mineshaft is on the
        // whitelist, so with the default settings this changes nothing here - it is the uniform rule,
        // not a second opinion about where Mineshafts are.
        if (!cfg.enabled || !sbs.modid.client.skills.SkillIslands.miningAllowed()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        // Each Mineshaft is a fresh instance; outside one, forget the remembered corpses.
        if (!inMineshaft()) {
            corpses.clear();
            return;
        }

        collectCorpses(level, player);
        if (corpses.isEmpty()) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int color = cfg.color.argb();
        for (Vec3 pos : corpses.values()) {
            WorldRender.boxEdges(g, viewProjection, camPos,
                    pos.x - 0.4, pos.y, pos.z - 0.4,
                    pos.x + 0.4, pos.y + 2.0, pos.z + 0.4, color, 2);
            if (cfg.showTracers) {
                WorldRender.tracer(g, viewProjection, camPos, pos.add(0, 1.0, 0), color, 2);
            }
        }
    }

    /** Adds every player-head armor stand near the player to the remembered corpses (+ tuning log). */
    private static void collectCorpses(ClientLevel level, LocalPlayer player) {
        AABB area = player.getBoundingBox().inflate(SCAN_RADIUS);
        boolean log = System.currentTimeMillis() - lastLogAt > LOG_INTERVAL_MS;
        StringBuilder sample = log ? new StringBuilder() : null;
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, area, FrozenCorpseHighlight::isCorpse)) {
            long key = stand.blockPosition().asLong();
            if (corpses.putIfAbsent(key, stand.position()) == null && sample != null) {
                sample.append(describe(stand)).append(' ');
            }
        }
        if (sample != null && sample.length() > 0) {
            lastLogAt = System.currentTimeMillis();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Corpse] new candidate(s): {}", sample.toString().trim());
        }
    }

    /** Whether an armor stand looks like a frozen corpse: it wears a player head. */
    private static boolean isCorpse(ArmorStand stand) {
        if (!stand.isAlive()) {
            return false;
        }
        ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
        return !head.isEmpty()
                && BuiltInRegistries.ITEM.getKey(head.getItem()).getPath().equals("player_head");
    }

    private static String describe(ArmorStand stand) {
        ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
        String owner = "";
        var profile = head.get(DataComponents.PROFILE);
        if (profile != null && profile.name().isPresent()) {
            owner = profile.name().get();
        }
        BlockPos pos = stand.blockPosition();
        return String.format(Locale.US, "[owner=\"%s\" name=\"%s\" @%d,%d,%d]",
                owner, head.getHoverName().getString().replaceAll("§.", ""),
                pos.getX(), pos.getY(), pos.getZ());
    }

    /** Whether the player is in a Glacite Mineshaft (zone name contains "Mineshaft"). */
    private static boolean inMineshaft() {
        return sbs.modid.client.core.location.SkyBlockLocation.matches("Mineshaft");
    }
}
