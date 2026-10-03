/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.ghost.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.combat.ghost.logic.GhostTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;

import java.util.List;

/**
 * Draws what {@link GhostTracker} chose: a full box and a tracer on the ghost worth going for next,
 * and - if wanted - a faint box on every other ghost in range, so the pick reads as "this one out of
 * those" rather than as a lone box with no context.
 *
 * <p>Same shape as every other SBS world overlay: the target list is collected on the client tick,
 * this only draws it, and each box is taken from the entity's live bounding box so it tracks the
 * ghost smoothly between ticks. Nothing here is line-of-sight gated - a ghost behind a pillar is
 * exactly the one a tracer is for; the walk there was already priced into the choice.
 */
public final class GhostHighlight {

    /** Alpha the non-chosen ghosts' boxes are drawn at, so the pick stays unmistakable. */
    private static final int OTHERS_ALPHA = 0x55;

    /** Published by the client thread, read by the render thread. */
    private static volatile List<GhostTracker.Ghost> targets = List.of();
    private static volatile GhostTracker.Ghost best;

    private GhostHighlight() {
    }

    /** Replaces what is drawn (collected once per scan on the client tick, never in render). */
    public static void setTargets(List<GhostTracker.Ghost> newTargets, GhostTracker.Ghost newBest) {
        targets = newTargets == null ? List.of() : List.copyOf(newTargets);
        best = newBest;
    }

    /** Called from the HUD world-render pass once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        List<GhostTracker.Ghost> current = targets;
        GhostTracker.Ghost chosen = best;
        if (current.isEmpty()) {
            return;
        }
        SBSConfig.GhostHunterSettings cfg = ConfigManager.getInstance().get().ghostHunter;
        if (!cfg.enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int color = cfg.color.argb();
        int dim = (color & 0x00FFFFFF) | (OTHERS_ALPHA << 24);

        if (cfg.showOthers) {
            for (GhostTracker.Ghost ghost : current) {
                if (ghost == chosen || !ghost.mob().isAlive()) {
                    continue;
                }
                box(g, viewProjection, camPos, ghost.mob(), dim, 1);
            }
        }
        if (chosen == null || !chosen.mob().isAlive()) {
            return;
        }
        AABB box = chosen.mob().getBoundingBox();
        box(g, viewProjection, camPos, chosen.mob(), color, 2);
        if (cfg.showTracer) {
            WorldRender.tracerToBox(g, viewProjection, camPos, box, color, 2);
        }
        if (cfg.showLabel) {
            label(g, minecraft.font, viewProjection, camPos, box, chosen, color);
        }
    }

    /** The twelve edges of a ghost's hitbox, slightly inflated so the box clears the model. */
    private static void box(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                            LivingEntity mob, int color, int thickness) {
        AABB box = mob.getBoundingBox().inflate(0.1);
        WorldRender.boxEdges(g, viewProjection, camPos,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, thickness);
    }

    /**
     * The chosen ghost's caption above its box: how far away it is, and - when it applies - why it
     * still won despite being awkward. A ghost someone else is nearer to is marked, since that is the
     * one case where you may want to override the pick yourself.
     */
    private static void label(GuiGraphicsExtractor g, Font font, Matrix4f viewProjection, Vec3 camPos,
                              AABB box, GhostTracker.Ghost ghost, int color) {
        Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.4, (box.minZ + box.maxZ) / 2);
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos, top, g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        String text = "» Ghost §7" + Math.round(ghost.distance()) + "m"
                + (ghost.contested() ? " §c(taken)" : "");
        g.centeredText(font, Component.literal(text), screen[0], screen[1] - font.lineHeight - 1, color);
    }
}
