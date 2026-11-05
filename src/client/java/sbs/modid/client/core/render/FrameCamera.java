/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The camera as the HUD sees it this frame, captured once instead of once per renderer.
 *
 * <p>{@code HudMixin} fills it at the HEAD of {@code Hud.extractRenderState}, which every HUD hook
 * nests under. In 26.2 the camera is extracted <i>before</i> the GUI ({@code GameRenderer.extract}
 * runs {@code extractCamera} and then {@code gui.extractRenderState}) and nothing moves it while the
 * HUD is extracted, so what is captured here is exactly what each renderer used to read for itself.
 *
 * <p>The view-projection matrix lives in one reused {@link Matrix4f} and is handed out as a read-only
 * {@link Matrix4fc}: every world-to-screen renderer shares it, so one that changed it would break the
 * rest, and the type makes that a compile error instead.
 *
 * <p>Render thread only, and only meaningful inside the HUD extract. Anything drawn outside it must
 * read the camera itself.
 */
public final class FrameCamera {

    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static Vec3 position = Vec3.ZERO;
    private static int guiWidth;
    private static int guiHeight;

    private FrameCamera() {
    }

    /** Called once per frame from the HUD's HEAD hook. */
    public static void capture(GuiGraphicsExtractor g) {
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        camera.getViewRotationProjectionMatrix(VIEW_PROJECTION);
        position = camera.position();
        guiWidth = g.guiWidth();
        guiHeight = g.guiHeight();
    }

    /** This frame's view-rotation-projection matrix. Shared - read it, never cast it back to change it. */
    public static Matrix4fc viewProjection() {
        return VIEW_PROJECTION;
    }

    /** This frame's camera position. */
    public static Vec3 position() {
        return position;
    }

    public static int guiWidth() {
        return guiWidth;
    }

    public static int guiHeight() {
        return guiHeight;
    }
}
