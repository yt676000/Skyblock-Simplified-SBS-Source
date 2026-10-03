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
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorseven.logic.FloorSevenPhaseTimer;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

/**
 * The fixed "3x3" waypoint in the F7 / M7 Necron arena – the spot the healer breaks during the
 * Necron phase. One hard-coded world position, because the boss room is the same build every run.
 *
 * <p><b>Only while you can see it.</b> The box is drawn only when the camera has an unobstructed
 * line to the spot: a through-wall marker here would sit on top of Necron himself from half the
 * arena and turn into noise exactly when the phase gets busy. The raycast is the
 * {@link ClipContext.Block#COLLIDER} check the Secret Routes renderer uses, with one difference that
 * matters here – the marked block is itself solid until the healer breaks it, so a ray stopping on
 * <i>that</i> block counts as seeing it, not as being blocked by it.
 *
 * <p>Live only on floor 7, in the boss room, from Necron onwards – the phase comes from
 * {@link FloorSevenPhaseTimer}, which tracks the fight regardless of whether its HUD card is on.
 */
public final class NecronHealerWaypoint {

    /** The spot itself, as given: the block the healer mines. */
    private static final Vec3 SPOT = new Vec3(54.5, 63.7, 114.5);

    /** Label drawn above the box. */
    private static final String LABEL = "3x3";

    /** Black, as configured for this waypoint. */
    private static final int COLOR = 0xFF000000;

    /** Faint white halo behind the black edges – pure black on the arena's dark stone is invisible. */
    private static final int HALO = 0x60FFFFFF;

    private NecronHealerWaypoint() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().dungeons.necronHealerWaypoint) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !inNecronPhase()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (occluded(minecraft, camera.position())) {
            return; // no visual contact - nothing is drawn
        }
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());

        double x = Math.floor(SPOT.x);
        double y = Math.floor(SPOT.y);
        double z = Math.floor(SPOT.z);
        WorldRender.boxEdges(g, viewProjection, camPos, x, y, z, x + 1, y + 1, z + 1, HALO, 3);
        WorldRender.boxEdges(g, viewProjection, camPos, x, y, z, x + 1, y + 1, z + 1, COLOR, 2);

        Font font = minecraft.font;
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                new Vec3(SPOT.x, y + 1.4, SPOT.z), g.guiWidth(), g.guiHeight());
        if (screen != null) {
            String text = LABEL + " §7" + Math.round(camPos.distanceTo(SPOT)) + "m";
            g.centeredText(font, Component.literal(text), screen[0], screen[1], 0xFFFFFFFF);
        }
    }

    /** Floor 7 (F7 or M7), inside the boss room, Necron phase or later. */
    private static boolean inNecronPhase() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return state.inDungeon() && state.floorNumber() == 7
                && state.phase() == sbs.modid.client.dungeons.events.DungeonEvents.Phase.BOSS
                && FloorSevenPhaseTimer.getInstance().necronOrLater();
    }

    /** True when a solid block sits between the camera and the spot – i.e. no visual contact. */
    private static boolean occluded(Minecraft minecraft, Vec3 from) {
        var hit = minecraft.level.clip(new ClipContext(from, SPOT,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        // Stopping on the marked block itself is exactly what "I can see it" looks like while it is
        // still standing; only a block in front of it hides the waypoint.
        return !hit.getBlockPos().equals(net.minecraft.core.BlockPos.containing(SPOT));
    }
}
