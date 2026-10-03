/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.mobhighlight.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.List;

/**
 * Draws the highlight box around every entity {@link MobHighlightTracker} selected this tick.
 *
 * <p>1.26.2 has no classic world-space {@code renderLineBox}, so – exactly like the dungeon
 * {@code DungeonHighlight} – each box corner is projected to the screen by hand (vanilla's
 * {@code projectPointToScreen} maths) and the twelve edges are drawn through the proven
 * {@link GuiGraphicsExtractor} HUD pipeline. The manual projection lets us read clip-space {@code w}
 * and cull corners behind the camera.
 *
 * <p>The target list is refreshed on the client tick and only read here, but each entity's bounding
 * box is sampled live per frame, so the boxes track moving mobs smoothly between ticks.
 */
public final class MobHighlightRenderer {

    private static final MobHighlightRenderer INSTANCE = new MobHighlightRenderer();

    /** One entity to box this frame, with the SkyBlock mob name to show above it. */
    public record Target(Entity entity, String label) {
    }

    /** Entities to box this frame (swapped wholesale by the tracker each client tick). */
    private volatile List<Target> targets = List.of();

    private MobHighlightRenderer() {
    }

    public static MobHighlightRenderer getInstance() {
        return INSTANCE;
    }

    /** Replaces the highlighted-target list (collected once per client tick, never in render). */
    public void setTargets(List<Target> newTargets) {
        this.targets = newTargets == null ? List.of() : List.copyOf(newTargets);
    }

    // ---- rendering ---------------------------------------------------------------------------------

    /** Draws a box around every current target. Called from the HUD render hook. */
    public void render(GuiGraphicsExtractor g) {
        List<Target> current = targets;
        if (current.isEmpty()) {
            return;
        }
        SBSConfig.MobHighlightSettings settings = ConfigManager.getInstance().get().mobHighlight;
        if (!settings.enabled) {
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
        int color = settings.color.argb();

        for (Target target : current) {
            Entity entity = target.entity();
            if (!entity.isAlive()) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            drawBoxEdges(g, viewProjection, camPos,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color);
            if (settings.showTracers) {
                sbs.modid.client.core.render.WorldRender.tracerToBox(g, viewProjection, camPos, box, color, 2);
            }
            if (settings.showLabels) {
                drawLabel(g, font, viewProjection, camPos, box, target.label(), color);
            }
        }
    }

    /** Projects and draws the 12 edges of a world-space box. */
    private static void drawBoxEdges(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos,
                                     double x0, double y0, double z0, double x1, double y1, double z1, int color) {
        // 8 corners: index bits = x,y,z.
        Vec3[] c = {
                new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x0, y0, z1), new Vec3(x1, y0, z1),
                new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z1), new Vec3(x1, y1, z1),
        };
        int[][] edges = {
                {0, 1}, {1, 3}, {3, 2}, {2, 0}, // bottom
                {4, 5}, {5, 7}, {7, 6}, {6, 4}, // top
                {0, 4}, {1, 5}, {2, 6}, {3, 7}, // verticals
        };
        // Clipped to the near plane instead of dropped when a corner goes behind the camera, so a
        // box you are standing inside stays whole as you turn.
        for (int[] e : edges) {
            sbs.modid.client.core.render.WorldRender.line3d(g, vp, camPos, c[e[0]], c[e[1]], color, 2);
        }
    }

    /** Draws the mob's SkyBlock name centred above the top face of its box. */
    private static void drawLabel(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                                  AABB box, String label, int color) {
        Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.4, (box.minZ + box.maxZ) / 2);
        int[] screen = projectToScreen(vp, camPos, top, g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        double distance = camPos.distanceTo(top);
        String text = label + " §7" + (int) Math.round(distance) + "m";
        g.centeredText(font, Component.literal(text), screen[0], screen[1] - font.lineHeight - 1, color);
    }

    /** Projects a world point to GUI-scaled screen pixels, or {@code null} when behind the camera. */
    private static int[] projectToScreen(Matrix4f vp, Vec3 camPos, Vec3 world, int guiWidth, int guiHeight) {
        Vec3 rel = world.subtract(camPos);
        Vector4f clip = vp.transform(new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0f));
        if (clip.w <= 1.0e-4f) {
            return null;
        }
        int sx = Math.round((clip.x / clip.w * 0.5f + 0.5f) * guiWidth);
        int sy = Math.round((0.5f - clip.y / clip.w * 0.5f) * guiHeight);
        return new int[] {sx, sy};
    }

    /**
     * Draws a solid line between two screen points: one filled 2px rectangle rotated to the segment's
     * angle – a single {@code fill} per edge regardless of length, mirroring the dungeon highlight so the
     * boxes stay effectively free while looking like proper SBS lines instead of dots.
     */
    private static void line2D(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0f) {
            g.fill(x0 - 1, y0 - 1, x0 + 1, y0 + 1, color);
            return;
        }
        org.joml.Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x0, y0);
        pose.rotate((float) Math.atan2(dy, dx));
        g.fill(0, -1, Math.round(length), 1, color);
        pose.popMatrix();
    }
}
