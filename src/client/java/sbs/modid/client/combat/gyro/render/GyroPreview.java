/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.gyro.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import sbs.modid.client.combat.gyro.logic.GyroAim;
import sbs.modid.client.combat.gyro.logic.GyroState;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.FrameCamera;
import sbs.modid.client.core.render.WorldRender;

/**
 * Gyro Helper drawing: a horizontal ring where Gravity Storm would pull, a small cross at its
 * centre, and optionally "N in range". Only reads {@link GyroState}; every number it draws was
 * prepared on the tick. Display only - nothing is aimed or cast for the player.
 */
public final class GyroPreview {

    private static final Component OUT_OF_RANGE = Component.literal("§7out of range");
    private static final String[] COUNT_TEXT = new String[64];
    private static final Component[] COUNT_LABEL = new Component[64];
    private static final double MARKER = 0.5;

    // render thread only
    private static final Vector4f A = new Vector4f();
    private static final Vector4f B = new Vector4f();
    private static final Vector4f FIRST = new Vector4f();
    private static final int[] SEG = new int[4];
    private static final int[] POINT = new int[2];

    private GyroPreview() {
    }

    /** Called from the HUD world-render pass once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        GyroState state = GyroState.getInstance();
        if (!state.holding()) {
            return;
        }
        SBSConfig.GyroHelperSettings cfg = ConfigManager.getInstance().get().gyroHelper;
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth();
        int h = g.guiHeight();
        if (!state.hasCentre()) {
            if (cfg.showOutOfRange) {
                g.text(mc.font, OUT_OF_RANGE, w / 2 + 6, h / 2 + 6, 0xFFAAAAAA);
            }
            return;
        }
        int color = state.onCooldown() ? cfg.cooldownColor.withOpacity(70) : cfg.readyColor.argb();
        Matrix4fc vp = FrameCamera.viewProjection();
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        double cx = state.centreX();
        double cy = state.centreY() + GyroState.LIFT;
        double cz = state.centreZ();

        GyroAim.Ring ring = state.ring();
        boolean[] visible = state.visible();
        int n = Math.min(ring.size(), visible.length);
        WorldRender.toClip(vp, cam.x, cam.y, cam.z, cx + ring.dx()[0], cy, cz + ring.dz()[0], FIRST);
        A.set(FIRST);
        for (int i = 0; i < n; i++) {
            int j = i + 1 == n ? 0 : i + 1;
            if (j == 0) {
                B.set(FIRST);
            } else {
                WorldRender.toClip(vp, cam.x, cam.y, cam.z, cx + ring.dx()[j], cy, cz + ring.dz()[j], B);
            }
            if (visible[i] && visible[j]
                    && WorldRender.projectSegment(A, B, w, h, SEG) != WorldRender.SEGMENT_CULLED) {
                WorldRender.line(g, SEG[0], SEG[1], SEG[2], SEG[3], color, 2);
            }
            A.set(B);
        }

        // Centre cross - two short world lines.
        cross(g, vp, cam, cx - MARKER, cy, cz, cx + MARKER, cy, cz, w, h, color);
        cross(g, vp, cam, cx, cy, cz - MARKER, cx, cy, cz + MARKER, w, h, color);

        if (cfg.showMobCount && WorldRender.project(vp, cam.x, cam.y, cam.z, cx, cy + 0.6, cz, w, h, POINT)) {
            g.text(mc.font, countLabel(state.mobsInside()), POINT[0] + 4, POINT[1] - 4, color);
        }
    }

    private static void cross(GuiGraphicsExtractor g, Matrix4fc vp, Vec3 cam,
                              double x0, double y0, double z0, double x1, double y1, double z1,
                              int w, int h, int color) {
        WorldRender.toClip(vp, cam.x, cam.y, cam.z, x0, y0, z0, A);
        WorldRender.toClip(vp, cam.x, cam.y, cam.z, x1, y1, z1, B);
        if (WorldRender.projectSegment(A, B, w, h, SEG) != WorldRender.SEGMENT_CULLED) {
            WorldRender.line(g, SEG[0], SEG[1], SEG[2], SEG[3], color, 2);
        }
    }

    /** "N in range", built once per count and cached - no string building per frame. */
    private static Component countLabel(int count) {
        int i = Math.max(0, Math.min(COUNT_LABEL.length - 1, count));
        Component label = COUNT_LABEL[i];
        if (label == null) {
            COUNT_TEXT[i] = (i == COUNT_LABEL.length - 1 ? i + "+" : String.valueOf(i)) + " in range";
            label = Component.literal(COUNT_TEXT[i]);
            COUNT_LABEL[i] = label;
        }
        return label;
    }
}
