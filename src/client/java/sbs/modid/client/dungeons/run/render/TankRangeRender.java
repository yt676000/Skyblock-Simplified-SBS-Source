/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.run.logic.DungeonScoreboard;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;
import sbs.modid.client.core.render.WorldRender;

/**
 * "Show Tank Range": a translucent green ground bubble in the Tank's <b>Diversion</b> range – the
 * 30-block radius within which the Tank diverts 80% of a teammate's incoming damage – so you can
 * see at a glance whether you are standing in the covered zone.
 *
 * <p>The Tank is any loaded player (you included) whose Catacombs sidebar class is {@code T}
 * ({@link DungeonTeamClasses}). The disc lies on the tank's foot plane and is drawn through the HUD
 * projection like the rest of the dungeon highlight (so it shows through walls). It is filled by a fan of
 * horizontal world-space chords – the {@code fill} primitive is axis-aligned, so a proper polygon
 * fill is not available – plus a bold perimeter ring for a crisp boundary.
 */
public final class TankRangeRender {

    /** Tank Diversion radius (blocks) – wiki: "within 30 blocks around you". */
    private static final double RADIUS = 30.0;
    /** Beyond this the tank is not relevant to you – skip drawing (perf + clutter). */
    private static final double MAX_DRAW_DIST = 90.0;
    /** Chord slices across the disc for the fill, and segments around the perimeter ring. */
    private static final int FILL_SLICES = 60;
    private static final int RING_SEGMENTS = 64;

    private static final int FILL_COLOR = 0x2200FF66;   // ~13% green
    private static final int RING_COLOR = 0xAA00FF66;   // bold green boundary

    private TankRangeRender() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().dungeons.showTankRange) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || !DungeonScoreboard.isInCatacombs() || !DungeonTeamClasses.hasClasses()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());

        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (!player.isAlive() || DungeonTeamClasses.classOf(player.getGameProfile().name()) != 'T') {
                continue;
            }
            if (camPos.distanceTo(player.position()) > MAX_DRAW_DIST + RADIUS) {
                continue;
            }
            drawBubble(g, vp, camPos, player.getX(), player.getY(), player.getZ());
        }
    }

    /** Draws the filled disc + ring at {@code (cx, y, cz)} on the tank's foot plane. */
    private static void drawBubble(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos,
                                   double cx, double y, double cz) {
        // Fill: horizontal chords across the disc (each a thick, near-transparent ground line).
        for (int i = 1; i < FILL_SLICES; i++) {
            double dz = (i / (double) FILL_SLICES * 2.0 - 1.0) * RADIUS;
            double half = Math.sqrt(Math.max(0.0, RADIUS * RADIUS - dz * dz));
            int[] a = WorldRender.projectToScreen(vp, camPos, new Vec3(cx - half, y, cz + dz),
                    g.guiWidth(), g.guiHeight());
            int[] b = WorldRender.projectToScreen(vp, camPos, new Vec3(cx + half, y, cz + dz),
                    g.guiWidth(), g.guiHeight());
            if (a != null && b != null) {
                WorldRender.line(g, a[0], a[1], b[0], b[1], FILL_COLOR, 3);
            }
        }
        // Perimeter ring: a bold boundary so "inside vs outside" is unmistakable (closed loop:
        // i runs to RING_SEGMENTS inclusive, so the last point coincides with the first).
        int[] prev = null;
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = i / (double) RING_SEGMENTS * Math.PI * 2.0;
            Vec3 point = new Vec3(cx + Math.cos(angle) * RADIUS, y, cz + Math.sin(angle) * RADIUS);
            int[] screen = WorldRender.projectToScreen(vp, camPos, point, g.guiWidth(), g.guiHeight());
            if (prev != null && screen != null) {
                WorldRender.line(g, prev[0], prev[1], screen[0], screen[1], RING_COLOR, 2);
            }
            prev = screen;
        }
    }
}
