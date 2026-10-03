/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.floordrop.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.helper.floordrop.logic.FloorDropTracker;

import java.util.List;

/**
 * The floor drops' world drawing: a box on each, what it is above it, and an optional tracer.
 *
 * <p>Pure view over {@link FloorDropTracker}'s tick cache - no entity iteration happens here, so the
 * per-frame cost is one loop over the handful of drops the last sweep found.
 *
 * <p><b>A collected drop loses its box on the pickup frame.</b> The sweep runs four times a second,
 * so without a per-frame check a box would hang over nothing for up to 250 ms after the player
 * walked over the drop - which reads as the highlighter being wrong about what is there, and this is
 * the one feature where the player is looking straight at the thing when it happens.
 *
 * <p><b>Never through walls, and there is no toggle for it.</b> {@link WorldRender} projects world
 * points onto the HUD and depth-tests nothing, so drawing through terrain is what a renderer does by
 * <i>default</i> here and hiding a drop behind a wall is the part that takes work: one
 * {@link ClipContext.Block#COLLIDER} ray from the camera, and the drop is skipped when it is
 * blocked. Two samples per drop, because a drop lies on the floor and can have its base behind a
 * ridge while its top is in plain sight - one sample would hide drops that are plainly visible, and
 * a highlight that flickers off in the open reads as broken rather than as occluded.
 */
public final class FloorDropHighlight {

    /** Fallback when the configured hex is unparseable, so a half-typed colour never blanks a box. */
    private static final int FALLBACK_RGB = 0xFFFFFF;

    private FloorDropHighlight() {
    }

    /** Called from the HUD world-render pass once per frame. Self-gating. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.FloorDropSettings cfg = ConfigManager.getInstance().get().floorDrops;
        if (!cfg.enabled) {
            return;
        }
        List<FloorDropTracker.Sighting> sightings = FloorDropTracker.getInstance().sightings();
        if (sightings.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());

        Integer custom = OverlayColor.parseHex(cfg.colorHex);
        int color = 0xFF000000 | (custom == null ? FALLBACK_RGB : custom);
        double limit = (double) cfg.renderDistance * cfg.renderDistance;
        Vec3 playerPos = minecraft.player.position();

        for (FloorDropTracker.Sighting sighting : sightings) {
            if (!sighting.present()) {
                continue;
            }
            AABB box = sighting.box();
            Vec3 centre = box.getCenter();
            if (camPos.distanceToSqr(centre) > limit) {
                continue;
            }
            if (!visible(minecraft, camPos, box)) {
                continue;
            }
            WorldRender.boxEdges(g, viewProjection, camPos,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, 3);
            label(g, minecraft, viewProjection, camPos, box, sighting, playerPos, cfg, color);
            if (cfg.showTracer) {
                WorldRender.tracerToBox(g, viewProjection, camPos, box, color, 2);
            }
        }
    }

    /**
     * Whether the camera has a clear line to any part of the drop.
     *
     * <p>The centre and the top of the box are both tried. One sample is not enough for something
     * lying on the floor: standing a few blocks back puts a lip of terrain between the camera and the
     * drop's middle while its top is perfectly visible, and hiding the box for that would read as
     * the highlighter losing drops rather than as terrain covering them.
     */
    private static boolean visible(Minecraft minecraft, Vec3 camPos, AABB box) {
        Vec3 centre = box.getCenter();
        return clear(minecraft, camPos, centre)
                || clear(minecraft, camPos, new Vec3(centre.x, box.maxY, centre.z));
    }

    private static boolean clear(Minecraft minecraft, Vec3 camPos, Vec3 target) {
        if (minecraft.level == null || minecraft.player == null) {
            return true;
        }
        HitResult hit = minecraft.level.clip(new ClipContext(camPos, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return true;
        }
        // A block hit at or beyond the drop is not between us and it; the tolerance keeps a ray that
        // grazes the floor the drop is lying on from reading as an obstruction.
        return hit.getLocation().distanceToSqr(camPos) >= target.distanceToSqr(camPos) - 1.0;
    }

    /**
     * What the drop is, over its box: the item's name, the stack size when there is more than one,
     * and how far away it is when that row is on.
     *
     * <p>The distance is measured from the <i>player</i> rather than from the camera, because that
     * is the number the player means - in third person the camera stands several blocks behind them.
     */
    private static void label(GuiGraphicsExtractor g, Minecraft minecraft, Matrix4f viewProjection,
                              Vec3 camPos, AABB box, FloorDropTracker.Sighting sighting,
                              Vec3 playerPos, SBSConfig.FloorDropSettings cfg, int color) {
        StringBuilder text = new StringBuilder(sighting.name());
        if (sighting.count() > 1) {
            text.append(" §7x").append(sighting.count());
        }
        if (cfg.showDistance) {
            text.append(" §7").append(Math.round(playerPos.distanceTo(box.getCenter()))).append('m');
        }
        Vec3 top = new Vec3(box.getCenter().x, box.maxY + 0.4, box.getCenter().z);
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos, top,
                g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.centeredText(minecraft.font, Component.literal(text.toString()),
                    screen[0], screen[1], color);
        }
    }
}
