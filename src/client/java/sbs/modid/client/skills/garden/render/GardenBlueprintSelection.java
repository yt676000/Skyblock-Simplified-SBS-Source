/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.build.logic.SelectionManager;
import sbs.modid.client.core.build.render.GhostStyle;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;

/**
 * Garden Blueprint's custom-area selection box, drawn while picking corners - before anything is
 * copied - in this page's selection colour.
 *
 * <p>The corners are the shared build selection ({@link SelectionManager}); the ghost itself is drawn
 * by the shared hologram renderer. Only this box stays Garden-specific, and only while "Custom Area
 * Selection" is on, as before.
 */
public final class GardenBlueprintSelection {

    private GardenBlueprintSelection() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.GardenBlueprintSettings cfg = ConfigManager.getInstance().get().gardenBlueprint;
        if (!cfg.enabled || !cfg.customArea) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        SelectionManager selection = SelectionManager.getInstance();
        BlockPos a = selection.corner1();
        BlockPos b = selection.corner2();
        if (a == null && b == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int color = 0xFF000000 | GhostStyle.rgb(cfg.selectionColorHex, 0xFFE24B);
        int thickness = Math.max(1, Math.min(4, cfg.lineWidth));
        if (a != null && b != null) {
            WorldRender.boxEdges(g, viewProjection, camPos,
                    Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1,
                    Math.max(a.getZ(), b.getZ()) + 1, color, thickness);
        } else {
            BlockPos only = a != null ? a : b;
            WorldRender.boxEdges(g, viewProjection, camPos,
                    only.getX(), only.getY(), only.getZ(), only.getX() + 1, only.getY() + 1, only.getZ() + 1,
                    color, thickness);
        }
    }
}
