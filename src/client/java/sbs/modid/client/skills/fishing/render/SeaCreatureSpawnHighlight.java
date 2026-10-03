/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.skills.fishing.logic.SeaCreatureAnnouncer;

/**
 * Boxes the sea creature the {@link SeaCreatureAnnouncer} just announced, in its rarity's colour, so
 * "a Lord Jawbus spawned" and "that one, over there" are the same piece of information.
 *
 * <p>Same shape as every other SBS world overlay: the announcer picks the entity on the client tick
 * and publishes it here, and this only draws. Nothing is chosen in the render pass.
 *
 * <p>Deliberately not line-of-sight gated. A creature that surfaces behind the lip of a lava pool is
 * exactly the one worth pointing at, and unlike a combat highlight this one expires on its own after
 * a few seconds – it cannot become a permanent wallhack over the pond.
 */
public final class SeaCreatureSpawnHighlight {

    /** Published by the client thread, read by the render thread. */
    private static volatile LivingEntity target;
    private static volatile int color;

    private SeaCreatureSpawnHighlight() {
    }

    /** Replaces what is drawn; {@code null} clears it. */
    public static void setTarget(LivingEntity mob, int argb) {
        target = mob;
        color = argb;
    }

    /** Called from the world-overlay pass once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        LivingEntity mob = target;
        if (mob == null || !mob.isAlive()) {
            return;
        }
        // Both gates, not just the highlight's own: switching the module off mid-session stops the
        // tick that would otherwise have cleared the target, and a box nothing can clear is forever.
        var cfg = ConfigManager.getInstance().get().seaCreatureAnnouncer;
        if (!cfg.enabled || !cfg.highlight) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        AABB box = mob.getBoundingBox().inflate(0.1);
        WorldRender.boxEdges(g, viewProjection, camPos,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, 2);
    }
}
