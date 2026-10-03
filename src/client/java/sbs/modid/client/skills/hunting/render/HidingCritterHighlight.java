/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.render;

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
import sbs.modid.client.skills.hunting.logic.HidingCritterTracker;

import java.util.List;

/**
 * The hiding critters' world drawing: a box on each, its name above it, and an optional tracer.
 *
 * <p>Pure view over {@link HidingCritterTracker}'s tick cache - no entity iteration happens here, so
 * the per-frame cost is one loop over the handful of critters the last sweep found.
 *
 * <p><b>Through-walls is a real switch, not a promise the renderer cannot keep.</b> {@link WorldRender}
 * projects world points onto the HUD and depth-tests nothing, so drawing through terrain is the
 * <i>default</i> behaviour and hiding something behind a wall is the part that takes work: one
 * {@link ClipContext.Block#COLLIDER} ray from the camera, the same check the trap and pathfinding
 * renderers use. Two samples per critter, because a critter on the ground can have its feet behind a
 * ridge while its head is in plain sight, and dropping it for that is the wrong answer.
 *
 * <p><b>A dead tag is dropped the same frame.</b> The sweep runs every ten ticks, so between two of
 * them a critter can be killed or unloaded; re-checking liveness here is what keeps a box from
 * lingering half a second over nothing.
 *
 * <p><b>Sparkling critters are a second colour and a marked label, never a colour alone.</b>
 * {@code ui/AGENTS.md} forbids conveying state by hue - a meaningful share of players cannot
 * separate two of them - so a sparkling critter's label carries a leading {@code ✦} as well.
 */
public final class HidingCritterHighlight {

    /** Fallback when the configured hex is unparseable, so a half-typed colour never blanks the box. */
    private static final int FALLBACK_RGB = 0xFF2FD5;

    /** The same, for the sparkling half: cyan, the shipped default. */
    private static final int FALLBACK_SPARKLING_RGB = 0x4DE8FF;

    private HidingCritterHighlight() {
    }

    /** Called from the HUD world-render pass once per frame. Self-gating. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.CritterFinderSettings cfg = ConfigManager.getInstance().get().critterFinder;
        if (!cfg.enabled && !cfg.sparkling.enabled) {
            return;
        }
        List<HidingCritterTracker.Sighting> sightings = HidingCritterTracker.getInstance().sightings();
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

        int color = colorOf(cfg.colorHex, FALLBACK_RGB);
        int sparklingColor = colorOf(cfg.sparkling.colorHex, FALLBACK_SPARKLING_RGB);
        double limit = (double) cfg.renderDistance * cfg.renderDistance;

        for (HidingCritterTracker.Sighting sighting : sightings) {
            if (!sighting.alive()) {
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
            int paint = sighting.sparkling() ? sparklingColor : color;
            WorldRender.boxEdges(g, viewProjection, camPos,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, paint, 3);
            label(g, minecraft, viewProjection, camPos, box, sighting, paint);
            if (cfg.showTracer) {
                WorldRender.tracerToBox(g, viewProjection, camPos, box, paint, 2);
            }
        }
    }

    /** A configured hex as an opaque colour, falling back so a half-typed value never blanks a box. */
    private static int colorOf(String hex, int fallbackRgb) {
        Integer custom = OverlayColor.parseHex(hex);
        return 0xFF000000 | (custom == null ? fallbackRgb : custom);
    }

    /**
     * Whether the camera has a clear line to any part of the critter.
     *
     * <p><b>Every box passes through here, and there is no toggle around it.</b> This is the rule
     * {@code MobHighlightTracker} applies with {@code hasLineOfSight}: a mob is drawn only while the
     * player can actually see it. It matters more here than anywhere else in the mod, because the
     * thing being boxed is a critter that is <i>hiding</i> - a box that carried through the terrain
     * would not be showing you what you can see, it would be finding it for you, and the search is
     * the content. The row that used to switch this off is gone rather than defaulted off.
     *
     * <p>The centre and the top of the box are both tried. One sample is not enough for something
     * that hugs the floor: standing a few blocks back puts a lip of terrain between the camera and
     * the critter's middle while its head is perfectly visible, and hiding the box for that would
     * read as the highlight being broken rather than as occluded. Nothing is passed as an "own
     * block" exception - a critter is not a solid block, so nothing here can obstruct itself.
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
        // A block hit at or beyond the critter is not between us and it; the tolerance keeps a ray
        // that grazes the floor the critter stands on from reading as an obstruction.
        return hit.getLocation().distanceToSqr(camPos) >= target.distanceToSqr(camPos) - 1.0;
    }

    /**
     * The critter's name over the box - and "(hidden)" when no body could be resolved under the
     * nametag, so the player can tell a boxed creature from a boxed tag floating over nothing.
     *
     * <p>A sparkling critter is prefixed with {@code ✦}. The marker may be a symbol the name
     * extractor removed, or a word the label already carries; either way the prefix is what makes
     * the two kinds distinguishable without relying on the colour.
     */
    private static void label(GuiGraphicsExtractor g, Minecraft minecraft, Matrix4f viewProjection,
                              Vec3 camPos, AABB box, HidingCritterTracker.Sighting sighting, int color) {
        String text = sighting.mob() == null ? sighting.name() + " (hidden)" : sighting.name();
        if (sighting.sparkling()) {
            text = "✦ " + text;
        }
        Vec3 top = new Vec3(box.getCenter().x, box.maxY + 0.5, box.getCenter().z);
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos, top,
                g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.centeredText(minecraft.font, Component.literal(text), screen[0], screen[1], color);
        }
    }
}
