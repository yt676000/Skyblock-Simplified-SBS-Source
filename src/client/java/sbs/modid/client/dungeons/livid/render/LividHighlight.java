/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.livid.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import sbs.modid.client.core.render.FrameCamera;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.livid.logic.LividTracker;

/**
 * Draws the real Livid: a box around it in its own nametag colour plus a tracer from the crosshair,
 * so it is found through the wall of clones. Purely a view over {@link LividTracker}'s tick cache -
 * no entity is looked up here.
 *
 * <p>A pick that is only the "odd health out" guess is drawn dashed-thin (1px) with a {@code ?} on
 * the label, a confirmed one solid. The difference is deliberate: the box must never look equally
 * certain in both cases.
 */
public final class LividHighlight {
    /** Render-thread-only scratch for world-to-screen projection. */
    private static final int[] SCREEN = new int[2];

    private LividHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.DungeonsSettings cfg = ConfigManager.getInstance().get().dungeons;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.lividTracker || minecraft.player == null || minecraft.level == null) {
            return;
        }
        LividTracker.Identification id = LividTracker.getInstance().identification();
        if (id == null || !id.candidate().entity().isAlive()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4fc viewProjection = FrameCamera.viewProjection();

        AABB box = id.candidate().entity().getBoundingBox();
        int color = id.candidate().color();
        int thickness = id.confirmed() ? 2 : 1;

        if (cfg.lividBox) {
            WorldRender.boxEdges(g, viewProjection, camPos,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, thickness);
        }
        if (cfg.lividTracer) {
            WorldRender.tracerToBox(g, viewProjection, camPos, box, color, thickness);
        }
        drawLabel(g, minecraft.font, viewProjection, camPos, box,
                id.candidate().label() + (id.confirmed() ? "" : " ?"), color);
    }

    private static void drawLabel(GuiGraphicsExtractor g, Font font, Matrix4fc vp, Vec3 camPos,
                                  AABB box, String label, int color) {
        int[] screen = SCREEN;
        if (WorldRender.project(vp, camPos.x, camPos.y, camPos.z,
                (box.minX + box.maxX) / 2, box.maxY + 0.6, (box.minZ + box.maxZ) / 2,
                g.guiWidth(), g.guiHeight(), screen)) {
            g.text(font, Component.literal(label), screen[0] - font.width(label) / 2, screen[1], color);
        }
    }
}
