/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.economy.minions.logic.MinionStateStore;
import sbs.modid.client.economy.minions.logic.MinionStoppages;

import java.util.List;

/**
 * Boxes each minion the island scan found stopped and writes the reason over it.
 *
 * <p>Drawn from the world pass, on the Private Island only. The positions come from
 * {@link MinionStateStore}, which is written by the 5 s stand scan - so a marker can be up to one
 * scan out of date, which for something that does not move is the right trade against scanning per
 * frame.
 *
 * <p>Read-only. It draws a box round something already standing in front of the player; it never
 * opens, empties or clicks a minion.
 */
public final class MinionStopHighlight {

    /** Half-width of the box round a minion's feet. A minion stand is small. */
    private static final double HALF = 0.5;
    private static final double HEIGHT = 1.6;

    private MinionStopHighlight() {
    }

    /** Called from the world-render pass once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.MinionCalcSettings cfg = ConfigManager.getInstance().get().minionCalc;
        if (!cfg.enabled || !cfg.stoppedWarning || !cfg.stoppedWorldMarks) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        // The scan only runs on the Private Island, so off it the store holds history. History is
        // fine on a settings page, where it is labelled with its age; it is not fine as a box in
        // the world, which reads as "this is here now".
        if (!SkyBlockLocation.matches("Private Island")) {
            return;
        }
        List<MinionStateStore.Stopped> stopped = MinionStateStore.getInstance().stopped();
        if (stopped.isEmpty()) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        for (MinionStateStore.Stopped entry : stopped) {
            int color = entry.reason.color();
            WorldRender.boxEdges(g, viewProjection, camPos,
                    entry.x - HALF, entry.y, entry.z - HALF,
                    entry.x + HALF, entry.y + HEIGHT, entry.z + HALF, color, 2);

            String text = MinionStoppages.label(entry.type, entry.tier)
                    + " - " + entry.reason.displayName();
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                    new Vec3(entry.x, entry.y + HEIGHT + 0.3, entry.z), g.guiWidth(), g.guiHeight());
            if (screen != null) {
                g.text(font, Component.literal(text),
                        screen[0] - font.width(text) / 2, screen[1], color);
            }
        }
    }
}
