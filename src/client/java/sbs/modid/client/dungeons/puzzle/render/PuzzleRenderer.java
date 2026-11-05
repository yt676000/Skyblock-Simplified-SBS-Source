/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import sbs.modid.client.core.render.FrameCamera;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.puzzle.logic.PuzzleCoordinator;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;

import java.util.List;

/**
 * Draws whatever the active puzzle solver decided on - and nothing else.
 *
 * <p>The one render path for every solver, so "show nothing when not certain" is enforced in exactly
 * one place: an empty highlight list draws an empty screen, and no solver can bypass that by drawing
 * for itself. Same hand-projected approach as {@code SecretRoutesRenderer},
 * because this project has no world-render mixin - points are projected from the camera matrix in
 * the HUD pass.
 *
 * <p>Reads a cached list produced on the tick thread; it never scans, solves or allocates a solution
 * here. A five-player run draws this at frame rate and must not pay for it.
 */
public final class PuzzleRenderer {
    /** Render-thread-only scratch for world-to-screen projection. */
    private static final int[] SCREEN = new int[2];

    private PuzzleRenderer() {
    }

    public static void render(GuiGraphicsExtractor g) {
        List<PuzzleHighlight> highlights = PuzzleCoordinator.getInstance().highlights();
        if (highlights.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4fc viewProjection = FrameCamera.viewProjection();
        Font font = minecraft.font;

        for (PuzzleHighlight highlight : highlights) {
            AABB box = highlight.box();
            Vec3 to = highlight.lineTo();
            if (to != null) {
                WorldRender.line3d(g, viewProjection, camPos, box.getCenter(), to,
                        highlight.color(), highlight.strong() ? 3 : 1);
            } else {
                WorldRender.boxEdges(g, viewProjection, camPos,
                        box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                        highlight.color(), highlight.strong() ? 3 : 1);
            }
            if (highlight.label() == null) {
                continue;
            }
            Vec3 at = to != null ? box.getCenter().add(to).scale(0.5)
                    : new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.4, (box.minZ + box.maxZ) / 2);
            int[] screen = SCREEN;
            if (WorldRender.project(viewProjection, camPos.x, camPos.y, camPos.z, at.x, at.y, at.z,
                    g.guiWidth(), g.guiHeight(), screen)) {
                g.centeredText(font, Component.literal(highlight.label()), screen[0], screen[1],
                        0xFFFFFFFF);
            }
        }
    }
}
