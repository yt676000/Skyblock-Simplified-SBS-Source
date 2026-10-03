/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.render.WorldRender;

import java.util.List;

/**
 * Outlines the active hologram's cells, each in its status colour: still to place, wrong (or a
 * collision), correct, or - in an edit preview - removed.
 *
 * <p>Drawn in HUD space through {@link WorldRender} (26.2 has no line-box world API). The block
 * <i>inside</i> the box is drawn separately and in real world space by {@link GhostModels}; the flat
 * fill here is the fallback for cells that get no model, or when the model hook is not firing.
 */
public final class HologramRenderer {

    private HologramRenderer() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        GhostCollector.Frame frame = GhostCollector.collect();
        if (frame.isEmpty()) {
            return;
        }
        GhostStyle style = frame.style();
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int thickness = Math.max(1, Math.min(4, style.lineWidth()));
        boolean hookAlive = GhostModels.hookAlive();

        // Farthest first, so nearer translucent cubes layer on top (a painter's order that reads as
        // solid instead of showing the far side through).
        List<GhostCollector.Ghost> ghosts = frame.ghosts();
        for (int i = ghosts.size() - 1; i >= 0; i--) {
            GhostCollector.Ghost ghost = ghosts.get(i);
            int rgb = style.colorFor(ghost.status());
            // The flat fill stands in for a real ghost block; where the model is drawn instead it
            // would only muddy it, so it is dropped there - unless the model hook is not firing, in
            // which case the model never appears and the fill stays as the fallback.
            // A block the model pass cannot draw (chest, sign, banner, ...) keeps the fill, so it is
            // never just an empty outline.
            boolean modelShown = GhostModels.showsModel(ghost.status(), style) && hookAlive
                    && GhostModels.drawable(ghost.wanted());
            if (style.fill() && !modelShown) {
                WorldRender.fillBox(g, viewProjection, camPos,
                        ghost.x(), ghost.y(), ghost.z(),
                        ghost.x() + 1, ghost.y() + 1, ghost.z() + 1,
                        (style.fillAlpha() << 24) | rgb);
            }
            WorldRender.boxEdges(g, viewProjection, camPos,
                    ghost.x(), ghost.y(), ghost.z(),
                    ghost.x() + 1, ghost.y() + 1, ghost.z() + 1,
                    (style.edgeAlpha() << 24) | rgb, thickness);
        }
    }
}
