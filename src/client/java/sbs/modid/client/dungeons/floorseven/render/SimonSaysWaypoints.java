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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorseven.logic.SimonSaysTracker;

import java.util.List;

/**
 * Draws the Simon Says sequence on the device itself: a box on each button that lit up, numbered in
 * the order it did.
 *
 * <p>The first step is drawn bright and the rest fade back, so the sequence reads as an order at a
 * glance rather than as a wall of identical boxes. Everything comes from
 * {@link SimonSaysTracker}, which watched the lights; this class only paints what it remembered.
 */
public final class SimonSaysWaypoints {

    /** The next press. */
    private static final int FIRST_COLOR = 0xFF55FF55;
    /** Everything after it, in order. */
    private static final int LATER_COLOR = 0xFF3FB4FF;

    /** Buttons are thin plates on a wall - the box is inset so it hugs one rather than a whole block. */
    private static final double INSET = 0.15;

    private SimonSaysWaypoints() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SimonSaysTracker tracker = SimonSaysTracker.getInstance();
        if (!tracker.enabled()) {
            return;
        }
        List<BlockPos> sequence = List.copyOf(tracker.sequence());
        if (sequence.isEmpty()) {
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

        for (int step = 0; step < sequence.size(); step++) {
            BlockPos pos = sequence.get(step);
            int color = step == 0 ? FIRST_COLOR : LATER_COLOR;
            WorldRender.boxEdges(g, viewProjection, camPos,
                    pos.getX() + INSET, pos.getY() + INSET, pos.getZ() + INSET,
                    pos.getX() + 1 - INSET, pos.getY() + 1 - INSET, pos.getZ() + 1 - INSET,
                    color, step == 0 ? 3 : 2);
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5),
                    g.guiWidth(), g.guiHeight());
            if (screen != null) {
                g.centeredText(font, Component.literal(String.valueOf(step + 1)),
                        screen[0], screen[1], color);
            }
        }
    }
}
