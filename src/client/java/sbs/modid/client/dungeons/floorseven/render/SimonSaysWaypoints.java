/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import sbs.modid.client.core.render.FrameCamera;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorseven.logic.SimonSaysTracker;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the Simon Says order on the button faces themselves, in the shared mark language:
 * <ul>
 *   <li><b>#1 - do this:</b> a filled, corner-cut glow on the face with the number on it;</li>
 *   <li><b>#2 - next:</b> a hollow ring around the face with the number;</li>
 *   <li><b>#3 onward - count:</b> a small number badge in the face's upper corner, softer colour.</li>
 * </ul>
 * The three differ by shape, not only colour, so the order reads without telling hues apart.
 *
 * <p>Everything comes from {@link SimonSaysTracker}. Geometry is rebuilt only when its step list
 * changes, colours only when the theme revision changes; a frame allocates nothing. A step whose
 * face is not in line of sight (the tracker's ray test) is not drawn - the HUD layer has no depth
 * buffer, so that ray is the depth test.
 */
public final class SimonSaysWaypoints {
    /** Render-thread-only scratch for world-to-screen projection. */
    private static final int[] SCREEN = new int[2];

    /** Distance of the drawn plane from the block's back face: a button is 2/16 thick, plus a hair. */
    private static final double FACE_DEPTH = 2.0 / 16.0 + 0.01;
    private static final double GLOW_HALF = 0.20;
    private static final double GLOW_CUT = 0.06;
    private static final double HALO_HALF = 0.30;
    private static final double RING_HALF = 0.26;
    private static final double BADGE_OFFSET = 0.34;

    private static List<SimonSaysTracker.Step> geometryFor = List.of();
    /** Per step: centre, u, v (unit tangents), all on the face plane. */
    private static Vec3[] centres = new Vec3[0];
    private static Vec3[][] glow = new Vec3[0][];
    private static Vec3[][] halo = new Vec3[0][];
    private static Vec3[][] ring = new Vec3[0][];
    private static Vec3[] badge = new Vec3[0];

    private static final List<Component> LABELS = new ArrayList<>();

    private static int colorRevision = -1;
    private static int strong;
    private static int strongFill;
    private static int strongHalo;
    private static int strongText;
    private static int soft;

    private SimonSaysWaypoints() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SimonSaysTracker tracker = SimonSaysTracker.getInstance();
        if (!tracker.enabled()) {
            return;
        }
        List<SimonSaysTracker.Step> steps = tracker.steps();
        if (steps.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (steps != geometryFor) {
            buildGeometry(steps);
        }
        refreshColors();
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4fc viewProjection = FrameCamera.viewProjection();
        Font font = minecraft.font;
        int next = tracker.next();

        // Back to front in order, so #1 is painted last and sits on top of anything it overlaps.
        for (int step = steps.size() - 1; step >= 0; step--) {
            if (!tracker.visible(step)) {
                continue;
            }
            switch (SimonSaysMark.of(step, next)) {
                case DO_THIS -> {
                    quad(g, viewProjection, camPos, halo[step], strongHalo);
                    // Corner-cut: three bands make an octagon, which reads as rounded at this size.
                    Vec3[] o = glow[step];
                    WorldRender.fillQuad(g, viewProjection, camPos, o[0], o[1], o[2], o[3], strongFill, 1);
                    WorldRender.fillQuad(g, viewProjection, camPos, o[4], o[5], o[6], o[7], strongFill, 1);
                    WorldRender.fillQuad(g, viewProjection, camPos, o[8], o[9], o[10], o[11], strongFill, 1);
                    label(g, viewProjection, camPos, font, centres[step], step, strongText);
                }
                case NEXT -> {
                    Vec3[] r = ring[step];
                    for (int i = 0; i < 4; i++) {
                        WorldRender.line3d(g, viewProjection, camPos, r[i], r[(i + 1) % 4], strong, 2);
                    }
                    label(g, viewProjection, camPos, font, centres[step], step, strong);
                }
                case COUNT -> label(g, viewProjection, camPos, font, badge[step], step, soft);
                case DONE -> {
                }
            }
        }
    }

    private static void quad(GuiGraphicsExtractor g, Matrix4fc vp, Vec3 cam, Vec3[] c, int color) {
        WorldRender.fillQuad(g, vp, cam, c[0], c[1], c[2], c[3], color, 1);
    }

    private static void label(GuiGraphicsExtractor g, Matrix4fc vp, Vec3 cam, Font font,
                              Vec3 at, int step, int color) {
        int[] screen = SCREEN;
        if (WorldRender.project(vp, cam.x, cam.y, cam.z, at.x, at.y, at.z, g.guiWidth(), g.guiHeight(), screen)) {
            g.centeredText(font, LABELS.get(step), screen[0], screen[1] - font.lineHeight / 2, color);
        }
    }

    /** Theme colours, recomputed only when the theme changes. */
    private static void refreshColors() {
        int revision = SBSTheme.revision();
        if (revision == colorRevision) {
            return;
        }
        colorRevision = revision;
        int rgb = SBSTheme.ACCENT & 0xFFFFFF;
        strong = 0xFF000000 | rgb;
        strongFill = 0xB0000000 | rgb;
        strongHalo = 0x40000000 | rgb;
        strongText = SBSTheme.TEXT;
        soft = SBSTheme.TEXT_MUTED;
    }

    /** Face-plane geometry for every step; runs when the order grows, never per frame. */
    private static void buildGeometry(List<SimonSaysTracker.Step> steps) {
        int n = steps.size();
        centres = new Vec3[n];
        glow = new Vec3[n][];
        halo = new Vec3[n][];
        ring = new Vec3[n][];
        badge = new Vec3[n];
        for (int i = 0; i < n; i++) {
            SimonSaysTracker.Step step = steps.get(i);
            Direction normal = step.normal();
            Vec3 nrm = new Vec3(normal.getStepX(), normal.getStepY(), normal.getStepZ());
            Vec3 u;
            Vec3 v;
            if (normal.getAxis() == Direction.Axis.Y) {
                u = new Vec3(1, 0, 0);
                v = new Vec3(0, 0, 1);
            } else if (normal.getAxis() == Direction.Axis.X) {
                u = new Vec3(0, 0, 1);
                v = new Vec3(0, 1, 0);
            } else {
                u = new Vec3(1, 0, 0);
                v = new Vec3(0, 1, 0);
            }
            Vec3 c = new Vec3(step.pos().getX() + 0.5, step.pos().getY() + 0.5, step.pos().getZ() + 0.5)
                    .add(nrm.scale(-0.5 + FACE_DEPTH));
            centres[i] = c;
            halo[i] = rect(c, u, v, HALO_HALF, HALO_HALF);
            ring[i] = rect(c, u, v, RING_HALF, RING_HALF);
            Vec3[] mid = rect(c, u, v, GLOW_HALF, GLOW_HALF - GLOW_CUT);
            Vec3[] top = rect(c.add(v.scale(GLOW_HALF - GLOW_CUT / 2)), u, v, GLOW_HALF - GLOW_CUT, GLOW_CUT / 2);
            Vec3[] bottom = rect(c.add(v.scale(-(GLOW_HALF - GLOW_CUT / 2))), u, v, GLOW_HALF - GLOW_CUT,
                    GLOW_CUT / 2);
            glow[i] = new Vec3[] {mid[0], mid[1], mid[2], mid[3], top[0], top[1], top[2], top[3],
                bottom[0], bottom[1], bottom[2], bottom[3]};
            badge[i] = c.add(u.scale(BADGE_OFFSET)).add(v.scale(BADGE_OFFSET));
        }
        while (LABELS.size() < n) {
            LABELS.add(Component.literal(String.valueOf(LABELS.size() + 1)));
        }
        geometryFor = steps;
    }

    private static Vec3[] rect(Vec3 c, Vec3 u, Vec3 v, double hu, double hv) {
        return new Vec3[] {
            c.add(u.scale(-hu)).add(v.scale(-hv)),
            c.add(u.scale(hu)).add(v.scale(-hv)),
            c.add(u.scale(hu)).add(v.scale(hv)),
            c.add(u.scale(-hu)).add(v.scale(hv)),
        };
    }
}
