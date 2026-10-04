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
 * fill here is the fallback for every cell that pass did not draw this frame.
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

        // Farthest first, so nearer translucent cubes layer on top (a painter's order that reads as
        // solid instead of showing the far side through).
        List<GhostCollector.Ghost> ghosts = frame.ghosts();
        for (int i = ghosts.size() - 1; i >= 0; i--) {
            GhostCollector.Ghost ghost = ghosts.get(i);
            int rgb = style.colorFor(ghost.status());
            if (keepsFill(style.fill(), GhostModels.drewModel(ghost.x(), ghost.y(), ghost.z()))) {
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

    /**
     * Whether a cell gets the flat fill. It stands in for a real ghost block, so it is dropped only
     * where the level pass actually drew one this frame ({@link GhostModels#drewModel}) - never on
     * what kind of block it is. A liquid, head or chest the model pass skipped or could not draw,
     * models switched off, or a hook that is not firing all keep the fill, so no cell is ever just
     * an empty outline.
     */
    static boolean keepsFill(boolean fillEnabled, boolean modelDrawn) {
        return fillEnabled && !modelDrawn;
    }
}
