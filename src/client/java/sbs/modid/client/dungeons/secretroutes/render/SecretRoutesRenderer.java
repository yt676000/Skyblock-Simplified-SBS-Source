/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.secretroutes.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.secretroutes.logic.SecretRoutesManager;
import sbs.modid.client.dungeons.secretroutes.model.SecretWaypoint;
import sbs.modid.client.core.pathfinding.PathRenderer;
import sbs.modid.client.core.render.WorldRender;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the Secret Routes module in the world: the selected route's typed waypoint markers with their
 * order number and description, the recorded walk path / breaker blocks, and a crosshair aim indicator
 * for pearl / etherwarp points.
 *
 * <p>The movement line reuses the shared {@link PathRenderer#drawWorldLine} sci-fi renderer (no
 * duplicated path code). Everything else is hand-projected through {@link WorldRender}, matching
 * {@code DungeonHighlight} / {@code PathRenderer}: 26.2 has no world line-box renderer, so world points are
 * projected to the screen by hand in the HUD pass.
 *
 * <p><b>Depth check</b> is a raycast approximation, not real depth-testing: the HUD pass has no depth
 * buffer, so an occluded point can only be found by clipping camera→point and is merely dimmed.
 */
public final class SecretRoutesRenderer {

    private static final double BEAM_HEIGHT = 2.5;
    private static final int BEAM_SLICES = 5;
    private static final double LABEL_RANGE = 80.0;
    private static final long MARKER_PERIOD_MS = 1_800L;

    /** Colours per waypoint type / secret subtype. */
    private static final int COLOR_STANDING = 0x3FB4FF;   // blue
    private static final int COLOR_AOTV = 0xB44DFF;        // purple
    private static final int COLOR_PEARL = 0x4DE0E0;       // cyan
    private static final int COLOR_CHEST = 0xFFC94D;       // gold
    private static final int COLOR_LEVER = 0xFF8A3F;       // orange
    private static final int COLOR_BAT = 0xE0605F;         // red
    private static final int COLOR_ITEM = 0xF0E24B;        // yellow
    private static final int COLOR_ESSENCE = 0x9A6BFF;     // essence purple
    private static final int COLOR_SECRET = 0x57D977;      // green
    private static final int COLOR_BREAKER = 0xFF7A29;     // breaker orange

    private SecretRoutesRenderer() {
    }

    private static SBSConfig.SecretRoutesSettings cfg() {
        return ConfigManager.getInstance().get().secretRoutes;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.SecretRoutesSettings cfg = cfg();
        if (!cfg.enabled || (!cfg.showRoutes && !cfg.showPathfinding && !cfg.showBreakerBlocks)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        SecretRoutesManager manager = SecretRoutesManager.getInstance();
        List<SecretRoutesManager.RenderWaypoint> waypoints = manager.renderWaypoints();
        List<Vec3> trail = manager.renderTrail();
        List<SecretRoutesManager.RenderBreaker> breakers = manager.renderBreakers();
        if (waypoints.isEmpty() && trail.isEmpty() && breakers.isEmpty()) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        if (cfg.showPathfinding) {
            drawMovementLine(g, viewProjection, camPos, trail, waypoints, cfg);
        }
        if (cfg.showBreakerBlocks) {
            drawBreakers(g, viewProjection, camPos, font, breakers);
        }
        if (cfg.showRoutes) {
            drawWaypoints(g, viewProjection, camPos, font, waypoints, cfg, minecraft, camera);
            drawAimIndicator(g, player, manager, waypoints, cfg);
        }
    }

    // ------------------------------------------------------------------
    // Movement line: recorded trail, else the waypoints joined in order
    // ------------------------------------------------------------------

    private static void drawMovementLine(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                         List<Vec3> trail, List<SecretRoutesManager.RenderWaypoint> waypoints,
                                         SBSConfig.SecretRoutesSettings cfg) {
        int rgb = ConfigManager.getInstance().get().pathfinding.pathRgb();
        List<Vec3> points = new ArrayList<>();
        if (!trail.isEmpty()) {
            for (Vec3 point : trail) {
                points.add(point.add(0, 0.12, 0)); // lift onto the floor
            }
        } else {
            // No recorded walk: connect the placed waypoints in order so "Show Recorded Path" still
            // draws a usable line between them.
            for (SecretRoutesManager.RenderWaypoint waypoint : waypoints) {
                points.add(waypoint.world().add(0.5, 0.12, 0.5));
            }
        }
        PathRenderer.drawWorldLine(g, viewProjection, camPos, points, rgb, cfg.lineWidth);
    }

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    private static void drawWaypoints(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                      Font font, List<SecretRoutesManager.RenderWaypoint> waypoints,
                                      SBSConfig.SecretRoutesSettings cfg, Minecraft minecraft, Camera camera) {
        double breath = breath();
        for (SecretRoutesManager.RenderWaypoint rw : waypoints) {
            SecretWaypoint waypoint = rw.source();
            Vec3 base = rw.world();
            boolean dim = (cfg.dimPassed && rw.passed())
                    || (cfg.depthCheck && occluded(minecraft, camera, base.add(0.5, 0.5, 0.5)));
            int rgb = colorOf(waypoint);
            int alpha = dim ? 70 : (int) (150 + 80 * breath);

            box(g, viewProjection, camPos, base, 0.03 + 0.04 * breath, argb(rgb, alpha), dim ? 1 : 2);
            drawBeam(g, viewProjection, camPos, base, rgb, dim);

            double distance = camPos.distanceTo(base.add(0.5, 0.5, 0.5));
            if (distance <= LABEL_RANGE) {
                drawLabel(g, viewProjection, camPos, font, waypoint, base, rgb, dim, cfg);
            }
        }
    }

    /** Number tag (always) plus the description (when enabled), stacked above the marker. */
    private static void drawLabel(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos, Font font,
                                  SecretWaypoint waypoint, Vec3 base, int rgb, boolean dim,
                                  SBSConfig.SecretRoutesSettings cfg) {
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                base.add(0.5, BEAM_HEIGHT + 0.4, 0.5), g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        int color = argb(rgb, dim ? 120 : 255);
        String tag = "#" + (waypoint.index + 1) + " " + waypoint.typeLabel();
        g.centeredText(font, Component.literal(tag), screen[0], screen[1], color);
        if (cfg.showDescriptions && waypoint.description != null && !waypoint.description.isBlank()) {
            g.centeredText(font, Component.literal("§7" + waypoint.description),
                    screen[0], screen[1] + font.lineHeight + 1, argb(0xFFFFFF, dim ? 120 : 220));
        }
    }

    // ------------------------------------------------------------------
    // Breaker blocks
    // ------------------------------------------------------------------

    private static void drawBreakers(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                     Font font, List<SecretRoutesManager.RenderBreaker> breakers) {
        for (SecretRoutesManager.RenderBreaker breaker : breakers) {
            Vec3 base = breaker.world();
            box(g, viewProjection, camPos, base, 0.02, argb(COLOR_BREAKER, 180), 2);
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                    base.add(0.5, 1.1, 0.5), g.guiWidth(), g.guiHeight());
            if (screen != null) {
                g.centeredText(font, Component.literal(String.valueOf(breaker.order() + 1)),
                        screen[0], screen[1], argb(COLOR_BREAKER, 255));
            }
        }
    }

    // ------------------------------------------------------------------
    // Aim indicator (pearl / AOTV): crosshair overlay, green on target
    // ------------------------------------------------------------------

    private static void drawAimIndicator(GuiGraphicsExtractor g, LocalPlayer player,
                                         SecretRoutesManager manager,
                                         List<SecretRoutesManager.RenderWaypoint> waypoints,
                                         SBSConfig.SecretRoutesSettings cfg) {
        SecretWaypoint nearest = null;
        double best = Double.MAX_VALUE;
        for (SecretRoutesManager.RenderWaypoint rw : waypoints) {
            if (!rw.source().hasAim() || rw.passed()) {
                continue;
            }
            double distance = player.position().distanceToSqr(
                    rw.world().x + 0.5, rw.world().y + 0.5, rw.world().z + 0.5);
            if (distance < best) {
                best = distance;
                nearest = rw.source();
            }
        }
        if (nearest == null) {
            return;
        }
        float[] aim = manager.worldAim(nearest);
        if (aim == null) {
            return;
        }
        float yawErr = Mth.wrapDegrees(aim[0] - player.getYRot());
        float pitchErr = aim[1] - player.getXRot();
        boolean onTarget = Math.abs(yawErr) <= cfg.aimToleranceDeg && Math.abs(pitchErr) <= cfg.aimToleranceDeg;
        int rgb = onTarget ? 0x57D977 : 0xE0605F;

        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        // A reference cross at the centre, and an offset dot showing how far off aim is (4px/degree,
        // clamped) - walk the dot into the centre and it goes green.
        int px = cx + (int) Mth.clamp(-yawErr * 4.0, -40, 40);
        int py = cy + (int) Mth.clamp(pitchErr * 4.0, -40, 40);
        WorldRender.line(g, cx - 7, cy, cx + 7, cy, argb(rgb, 200), 1);
        WorldRender.line(g, cx, cy - 7, cx, cy + 7, argb(rgb, 200), 1);
        g.fill(px - 2, py - 2, px + 2, py + 2, argb(rgb, 255));
    }

    // ------------------------------------------------------------------
    // Shared drawing helpers (mirrors PathRenderer)
    // ------------------------------------------------------------------

    private static void box(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                            Vec3 base, double grow, int color, int width) {
        WorldRender.boxEdges(g, viewProjection, camPos,
                base.x - grow, base.y - grow, base.z - grow,
                base.x + 1 + grow, base.y + 1 + grow, base.z + 1 + grow,
                color, width);
    }

    private static void drawBeam(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                 Vec3 base, int rgb, boolean dim) {
        double x = base.x + 0.5;
        double z = base.z + 0.5;
        double baseY = base.y + 1.0;
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int topAlpha = dim ? 40 : 90;
        int[] previous = WorldRender.projectToScreen(viewProjection, camPos, new Vec3(x, baseY, z), gw, gh);
        for (int i = 1; i <= BEAM_SLICES; i++) {
            double y = baseY + BEAM_HEIGHT * i / BEAM_SLICES;
            int[] current = WorldRender.projectToScreen(viewProjection, camPos, new Vec3(x, y, z), gw, gh);
            if (previous != null && current != null) {
                int alpha = (int) (topAlpha * (1.0 - (double) i / BEAM_SLICES));
                WorldRender.line(g, previous[0], previous[1], current[0], current[1],
                        argb(rgb, alpha), dim ? 1 : 2);
            }
            previous = current;
        }
    }

    /** Raycast occlusion approximation for the depth-check option. */
    private static boolean occluded(Minecraft minecraft, Camera camera, Vec3 target) {
        if (minecraft.level == null || minecraft.player == null) {
            return false;
        }
        Vec3 from = camera.position();
        HitResult hit = minecraft.level.clip(new ClipContext(from, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getLocation().distanceToSqr(from) < target.distanceToSqr(from) - 1.0;
    }

    private static int colorOf(SecretWaypoint waypoint) {
        return switch (waypoint.type) {
            case STANDING -> COLOR_STANDING;
            case AOTV_WARP -> COLOR_AOTV;
            case PEARL -> COLOR_PEARL;
            case SECRET -> waypoint.subtype == null ? COLOR_SECRET : switch (waypoint.subtype) {
                case CHEST -> COLOR_CHEST;
                case LEVER -> COLOR_LEVER;
                case BAT -> COLOR_BAT;
                case ITEM -> COLOR_ITEM;
                case WITHER_ESSENCE -> COLOR_ESSENCE;
            };
        };
    }

    private static int argb(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    private static double breath() {
        double t = (System.currentTimeMillis() % MARKER_PERIOD_MS) / (double) MARKER_PERIOD_MS;
        return t < 0.5 ? t * 2 : (1 - t) * 2;
    }
}
