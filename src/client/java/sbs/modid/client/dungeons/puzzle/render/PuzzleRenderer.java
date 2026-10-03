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
import org.joml.Matrix4f;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.puzzle.logic.PuzzleCoordinator;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;

import java.util.List;

/**
 * Draws whatever the active puzzle solver decided on - and nothing else.
 *
 * <p>The one render path for every solver, so "show nothing when not certain" is enforced in exactly
 * one place: an empty highlight list draws an empty screen, and no solver can bypass that by drawing
 * for itself. Same hand-projected approach as {@code SecretRoutesRenderer} and {@code PuzzleSolver},
 * because this project has no world-render mixin - points are projected from the camera matrix in
 * the HUD pass.
 *
 * <p>Reads a cached list produced on the tick thread; it never scans, solves or allocates a solution
 * here. A five-player run draws this at frame rate and must not pay for it.
 */
public final class PuzzleRenderer {

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
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        for (PuzzleHighlight highlight : highlights) {
            AABB box = highlight.box();
            WorldRender.boxEdges(g, viewProjection, camPos,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    highlight.color(), highlight.strong() ? 3 : 1);
            if (highlight.label() == null) {
                continue;
            }
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                    new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.4, (box.minZ + box.maxZ) / 2),
                    g.guiWidth(), g.guiHeight());
            if (screen != null) {
                g.centeredText(font, Component.literal(highlight.label()), screen[0], screen[1],
                        0xFFFFFFFF);
            }
        }
    }
}
