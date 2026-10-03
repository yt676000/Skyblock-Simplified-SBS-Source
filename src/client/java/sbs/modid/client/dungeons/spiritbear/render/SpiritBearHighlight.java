/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.spiritbear.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.spiritbear.logic.SpiritBearTracker;

/**
 * The Spirit Bear box: thick, amber, traced and drawn through the arena, because the bear is the one
 * thing in Thorn's fight worth dropping everything for and it does not always land where you were
 * looking. Pure view over {@link SpiritBearTracker}'s tick cache.
 */
public final class SpiritBearHighlight {

    private static final int COLOR_BEAR = 0xFFFFC020;

    private SpiritBearHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().spiritBear;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.enabled || !cfg.highlightBear || minecraft.player == null || minecraft.level == null) {
            return;
        }
        LivingEntity bear = SpiritBearTracker.getInstance().bear();
        if (bear == null || !bear.isAlive()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        AABB box = bear.getBoundingBox();
        WorldRender.boxEdges(g, viewProjection, camPos,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, COLOR_BEAR, 3);
        WorldRender.tracerToBox(g, viewProjection, camPos, box, COLOR_BEAR, 3);

        Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.5, (box.minZ + box.maxZ) / 2);
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos, top, g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.centeredText(font, Component.literal("Spirit Bear"), screen[0], screen[1], COLOR_BEAR);
        }
    }
}
