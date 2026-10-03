/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.combat.carry.logic.CarryCounter;
import sbs.modid.client.core.render.WorldRender;

import java.util.List;

/**
 * Draws the Slayer Carry Counter's boss highlight: a box (and optional name label) on every slayer
 * mini-boss the counter is already tracking – collected on the client tick by {@link CarryCounter},
 * projected and drawn here every frame so the box follows the boss smoothly.
 *
 * <p><b>Why this is "legal".</b> A slayer mini-boss is recognised only by its floating health
 * nametag, i.e. an entity the client already renders and the player can already see. The box merely
 * redraws that visible entity's outline – it reveals no hidden information (no unloaded entities, no
 * players behind walls, no positions the client does not already know), so it is a cosmetic
 * highlight rather than a wallhack. The rendering is identical to the shipped {@link
 * sbs.modid.client.combat.slayer.render.SlayerHighlight}.
 */
public final class CarryHighlight {

    private static final int[][] EDGES = {
            {0, 1}, {1, 3}, {3, 2}, {2, 0},
            {4, 5}, {5, 7}, {7, 6}, {6, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7},
    };

    private CarryHighlight() {
    }

    /** One boss highlight: an axis-aligned world box, its ARGB colour and an optional name label. */
    public record Box(AABB box, int argb, String label) {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        List<Box> boxes = CarryCounter.getInstance().highlightBoxes();
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

        boolean tracers = sbs.modid.client.core.config.ConfigManager.getInstance()
                .get().carryCounter.highlightTracer;
        for (Box highlight : boxes) {
            drawEdges(g, viewProjection, camPos, highlight.box(), highlight.argb());
            if (tracers) {
                sbs.modid.client.core.render.WorldRender.tracerToBox(g, viewProjection, camPos,
                        highlight.box(), highlight.argb(), 2);
            }
            if (highlight.label() != null) {
                drawLabel(g, font, viewProjection, camPos, highlight.box(), highlight.label(), highlight.argb());
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
        // Clipped to the near plane instead of dropped when a corner goes behind the camera, so a
        // box you are standing inside stays whole as you turn.
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
