/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorsix.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorsix.logic.TerracottaTracker;

import java.util.Locale;

/**
 * The terracotta respawn markers: every single dead terracotta block outlined, with its countdown
 * standing on top of it.
 *
 * <p>The colour <b>is</b> the timer. Red while there is time to be elsewhere, amber as it gets close,
 * green when it is about to land - so the arena can be read at a glance, without picking a number out
 * of a fight. The number is there too, for the block you are actually walking to; it sits just above
 * the block's top face, where the mob will come out, rather than a mob's height further up where it
 * would read as belonging to nothing.
 */
public final class TerracottaHighlight {

    private static final int COLOR_FAR = 0xFFFF5555;   // red - not yet
    private static final int COLOR_SOON = 0xFFFFC24A;  // amber - get there
    private static final int COLOR_NOW = 0xFF7CFF6A;   // green - it is coming back now

    /** Under this many milliseconds left, the spot is "get there"; under a second, it is "now". */
    private static final long SOON_MS = 3_000L;
    private static final long NOW_MS = 1_000L;

    /** How far over the block's top face the countdown floats - clear of it, still touching it. */
    private static final double LABEL_HEIGHT = 1.3;

    private TerracottaHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ConfigManager.getInstance().get().dungeons.terracottaTimer
                || minecraft.player == null || minecraft.level == null) {
            return;
        }
        var spots = TerracottaTracker.getInstance().respawns();
        if (spots.isEmpty()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        for (TerracottaTracker.Respawn spot : spots) {
            long left = spot.remainingMs();
            int color = left <= NOW_MS ? COLOR_NOW : left <= SOON_MS ? COLOR_SOON : COLOR_FAR;
            BlockPos block = spot.block();
            WorldRender.boxEdges(g, viewProjection, camPos,
                    block.getX(), block.getY(), block.getZ(),
                    block.getX() + 1.0, block.getY() + 1.0, block.getZ() + 1.0, color, 2);
            Vec3 above = Vec3.upFromBottomCenterOf(block, LABEL_HEIGHT);
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos, above,
                    g.guiWidth(), g.guiHeight());
            if (screen != null) {
                g.centeredText(font, Component.literal(label(left)), screen[0], screen[1], color);
            }
        }
    }

    /** {@code 4.2s} on the way down, {@code now} once the clock has run out. */
    private static String label(long remainingMs) {
        return remainingMs <= 0 ? "now" : String.format(Locale.US, "%.1fs", remainingMs / 1000.0);
    }
}
