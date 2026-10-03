/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.combat.slayer.logic.SlayerTracker;
import sbs.modid.client.core.render.WorldRender;

import java.util.List;

/**
 * Draws the Slayer module's world boxes (boss phase colour, beacon, nukekubi heads, minibosses,
 * vampire stands) – collected on the tick by {@link SlayerTracker}, projected and drawn here every
 * frame so the boxes follow the entities smoothly.
 */
public final class SlayerHighlight {

    private static final int[][] EDGES = {
            {0, 1}, {1, 3}, {3, 2}, {2, 0},
            {4, 5}, {5, 7}, {7, 6}, {6, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7},
    };

    private SlayerHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        List<SlayerTracker.HighlightBox> boxes = SlayerTracker.getInstance().highlightBoxes();
        if (boxes.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        boolean tracers = sbs.modid.client.core.config.ConfigManager.getInstance().get().slayer.showTracers;
        for (SlayerTracker.HighlightBox highlightBox : boxes) {
            if (highlightBox.gone()) {
                continue;   // its entity died between the scan and this frame
            }
            // Live box: the scan's snapshot re-placed on wherever its entity is right now, so the
            // box sits on the mob instead of trailing a quarter of a second behind it.
            AABB box = highlightBox.currentBox();
            drawEdges(g, viewProjection, camPos, box, highlightBox.argb());
            if (tracers || highlightBox.tracer()) {
                WorldRender.tracerToBox(g, viewProjection, camPos, box, highlightBox.argb(), 2);
            }
            if (highlightBox.label() != null) {
                drawLabel(g, font, viewProjection, camPos, box, highlightBox.label(), highlightBox.argb());
            }
        }
    }

    private static void drawEdges(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, AABB box, int color) {
        Vec3[] corners = {
                new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.minZ),
                new Vec3(box.minX, box.minY, box.maxZ), new Vec3(box.maxX, box.minY, box.maxZ),
                new Vec3(box.minX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ),
                new Vec3(box.minX, box.maxY, box.maxZ), new Vec3(box.maxX, box.maxY, box.maxZ),
        };
        // Each edge is clipped to the near plane rather than dropped when a corner goes behind the
        // camera - otherwise a box you are standing inside comes apart as you turn.
        for (int[] edge : EDGES) {
            WorldRender.line3d(g, vp, camPos, corners[edge[0]], corners[edge[1]], color, 2);
        }
    }

    private static void drawLabel(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                                  AABB box, String label, int color) {
        Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.4, (box.minZ + box.maxZ) / 2);
        int[] screen = WorldRender.projectToScreen(vp, camPos, top, g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.text(font, Component.literal(label), screen[0] - font.width(label) / 2, screen[1], color);
        }
    }
}
