/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.yearofthepig.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.YearOfThePigSettings;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker;

import java.util.List;
import java.util.Locale;

/**
 * The in-world half of the Year of the Pig helpers, drawn every frame from the state
 * {@link ShinyOrbTracker} collects on the tick.
 *
 * <p>What it draws, and why each part earns its place:
 * <ul>
 *   <li><b>Pig highlights</b> – a box on every Shiny Pig around you. The pigs wander among the
 *       Village's ordinary mobs and the whole run is bottlenecked on finding the next one.</li>
 *   <li><b>A line to your pig</b> – once an orb is out, the launch throws the pig well away and
 *       out of sight. The line is what lets you re-acquire it without hunting. One is drawn per
 *       live orb, so running two pigs at once shows one line each.</li>
 *   <li><b>A line from the pig to its orb</b> – the pig has to be knocked <i>towards</i> the orb,
 *       so what actually matters is the geometry between the two, not where either one is alone.
 *       Standing so the two lines meet lines the knockback up for you.</li>
 *   <li><b>The orb marker</b> – a box and a countdown where the orb waits, since it is invisible
 *       from any distance and the 90 seconds are spent walking back to it.</li>
 * </ul>
 *
 * <p>Everything is drawn by projecting world points to the screen through {@link WorldRender}, the
 * same way the Slayer and Dungeon boxes work – 1.26.2's world renderer no longer exposes a line-box
 * call, so the HUD pipeline does the drawing.
 */
public final class ShinyPigHighlight {

    /** Vertical offset onto the pig's back, so a line lands on the animal and not in the dirt. */
    private static final double PIG_ANCHOR_Y = 0.55;

    /** The orb hovers a little above where the pig was launched from. */
    private static final double ORB_ANCHOR_Y = 0.5;

    /** Under this many seconds left the orb marker and countdown turn red. */
    private static final int URGENT_SECONDS = 15;

    private static final int URGENT_COLOR = 0xFFFF4040;

    private ShinyPigHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        YearOfThePigSettings cfg = ConfigManager.getInstance().get().yearOfThePig;
        if (!cfg.enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        ShinyOrbTracker tracker = ShinyOrbTracker.getInstance();
        List<ShinyOrbTracker.ActiveOrb> orbs = tracker.activeOrbs();
        List<ShinyOrbTracker.ChargedOrb> charged = tracker.chargedOrbs();
        // Nothing to draw and nothing to look for: stay off the screen entirely outside the event.
        if (orbs.isEmpty() && charged.isEmpty() && !tracker.hasOrbs()) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        if (cfg.highlightPigs) {
            drawPigBoxes(g, viewProjection, camPos, tracker.nearbyPigs(), cfg);
        }

        // Every live orb draws its own pair of lines and its own countdown: with two pigs out the
        // whole point is telling which pig belongs to which orb.
        for (ShinyOrbTracker.ActiveOrb orb : orbs) {
            boolean urgent = orb.remainingMs() <= URGENT_SECONDS * 1000L;
            int orbColor = urgent ? URGENT_COLOR : cfg.orbColor.argb();
            Vec3 orbAnchor = orb.position().add(0, ORB_ANCHOR_Y, 0);

            LivingEntity pig = tracker.pigOf(orb);
            if (pig != null) {
                Vec3 pigAnchor = pig.position().add(0, PIG_ANCHOR_Y, 0);
                if (cfg.lineToPig) {
                    drawLineFromCrosshair(g, viewProjection, camPos, pigAnchor,
                            cfg.pigColor.argb(), cfg.lineThickness);
                }
                if (cfg.lineToOrb) {
                    drawLine(g, viewProjection, camPos, pigAnchor, orbAnchor, orbColor,
                            cfg.lineThickness);
                }
            }
            drawOrbMarker(g, font, viewProjection, camPos, orbAnchor, orbColor,
                    String.format(Locale.US, "Orb  %.0fs", orb.remainingMs() / 1000.0));
        }
        for (ShinyOrbTracker.ChargedOrb orb : charged) {
            // Charged and waiting to be clicked - the walk back is the only thing left to do.
            Vec3 anchor = orb.position().add(0, ORB_ANCHOR_Y, 0);
            drawOrbMarker(g, font, viewProjection, camPos, anchor, cfg.orbColor.argb(),
                    "Charged - click it!");
            if (cfg.lineToOrb) {
                drawLineFromCrosshair(g, viewProjection, camPos, anchor,
                        cfg.orbColor.argb(), cfg.lineThickness);
            }
        }
    }

    /** A box around every shiny pig in range, so the next target is visible at a glance. */
    private static void drawPigBoxes(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos,
                                     List<LivingEntity> pigs, YearOfThePigSettings cfg) {
        for (LivingEntity pig : pigs) {
            if (!pig.isAlive()) {
                continue;
            }
            AABB box = pig.getBoundingBox().inflate(0.08);
            WorldRender.boxEdges(g, vp, camPos, box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ, cfg.pigColor.argb(), cfg.lineThickness);
        }
    }

    /** The orb's box plus its label, drawn a little above the ground it hovers over. */
    private static void drawOrbMarker(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                                      Vec3 anchor, int color, String label) {
        WorldRender.boxEdges(g, vp, camPos,
                anchor.x - 0.35, anchor.y - 0.35, anchor.z - 0.35,
                anchor.x + 0.35, anchor.y + 0.35, anchor.z + 0.35, color, 2);
        int[] screen = WorldRender.projectToScreen(vp, camPos, anchor.add(0, 0.6, 0),
                g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.text(font, Component.literal(label), screen[0] - font.width(label) / 2, screen[1], color);
        }
    }

    /**
     * A line from the middle of the screen out to a world point.
     *
     * <p>The player's end of this line cannot be projected like any other world point: the eyes sit
     * <i>on</i> the camera, so the vector to project is very nearly zero and the clip-space {@code w}
     * lands on the wrong side of {@link WorldRender}'s near-plane cull – the line then either
     * disappeared or shot off from a meaningless corner. The centre of the screen is where the eyes
     * project to by definition, so it is used directly and the line always starts at the crosshair.
     */
    private static void drawLineFromCrosshair(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos,
                                              Vec3 to, int color, int thickness) {
        int[] target = WorldRender.projectToScreen(vp, camPos, to, g.guiWidth(), g.guiHeight());
        if (target != null) {
            WorldRender.line(g, g.guiWidth() / 2, g.guiHeight() / 2, target[0], target[1],
                    color, Math.max(1, thickness));
        }
    }

    /** One projected world-space line; skipped when either end is behind the camera. */
    private static void drawLine(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos,
                                 Vec3 from, Vec3 to, int color, int thickness) {
        int[] a = WorldRender.projectToScreen(vp, camPos, from, g.guiWidth(), g.guiHeight());
        int[] b = WorldRender.projectToScreen(vp, camPos, to, g.guiWidth(), g.guiHeight());
        if (a != null && b != null) {
            WorldRender.line(g, a[0], a[1], b[0], b[1], color, Math.max(1, thickness));
        }
    }
}
