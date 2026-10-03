/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.render.WorldRender;

/**
 * The look of the route: not a flat line but a layered energy conduit with a pulse running along it.
 *
 * <p>Three things do the work, and each is cheap:
 * <ul>
 *   <li><b>Layering</b> – every segment is drawn three times: a wide, faint outer bloom, a mid body
 *       in the chosen colour, and a thin near-white core. That reads as "glowing" rather than
 *       "painted", which is most of the difference between a debug line and a sci-fi one.</li>
 *   <li><b>A travelling pulse</b> – a bright wave sweeps from the player toward the target, so the
 *       path shows its <i>direction</i> at a glance instead of just its shape.</li>
 *   <li><b>Chevrons</b> – periodic arrowheads along the route, pointing the way.</li>
 * </ul>
 *
 * <p>The time phase is reduced modulo the period in <b>long</b> arithmetic before becoming a float:
 * dividing raw epoch millis as a float lets the mantissa swallow the frame delta and freezes the
 * animation – a bug this codebase has already been bitten by once.
 */
final class SciFiPathStyle {

    /** One full sweep of the pulse along the whole path. */
    private static final long PULSE_PERIOD_MS = 1_400L;

    /** How much of the path the pulse lights up, as a fraction of its length. */
    private static final double PULSE_WIDTH = 0.16;

    /** Alpha of each layer at rest. */
    private static final int BLOOM_ALPHA = 38;
    private static final int BODY_ALPHA = 120;
    private static final int CORE_ALPHA = 210;

    /** Extra pixels each layer adds over the configured core width. */
    private static final int BLOOM_EXTRA = 5;
    private static final int BODY_EXTRA = 2;

    /** Draw an arrowhead every N segments. */
    private static final int CHEVRON_EVERY = 6;
    private static final int CHEVRON_SIZE = 5;

    /** Dashing of a teleport hop: target dash pitch in pixels, how much of it is drawn, and a cap. */
    private static final double HOP_DASH_PIXELS = 14.0;
    private static final double HOP_DASH_FILL = 0.55;
    private static final int HOP_MAX_DASHES = 40;

    private SciFiPathStyle() {
    }

    /** The pulse position along the path in {@code [0,1)}, sweeping toward the target. */
    static double pulsePhase() {
        return (System.currentTimeMillis() % PULSE_PERIOD_MS) / (double) PULSE_PERIOD_MS;
    }

    /**
     * Draws one projected segment of the route.
     *
     * @param t     how far along the whole path this segment sits, in {@code [0,1]}
     * @param phase the current {@link #pulsePhase()}
     * @param rgb   the configured path colour
     * @param width the configured core line width
     */
    static void segment(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
                        double t, double phase, int rgb, int width) {
        double intensity = pulseIntensity(t, phase);

        // Bloom and body stay constant; the core is what the pulse lights up, so the wave reads as
        // energy moving *through* the conduit rather than the whole line flashing.
        WorldRender.line(g, x0, y0, x1, y1, argb(rgb, BLOOM_ALPHA), width + BLOOM_EXTRA);
        WorldRender.line(g, x0, y0, x1, y1, argb(rgb, BODY_ALPHA), width + BODY_EXTRA);

        int coreAlpha = (int) (CORE_ALPHA + (255 - CORE_ALPHA) * intensity);
        int coreRgb = towardWhite(rgb, 0.35 + 0.65 * intensity);
        int coreWidth = width + (intensity > 0.5 ? 1 : 0);
        WorldRender.line(g, x0, y0, x1, y1, argb(coreRgb, coreAlpha), coreWidth);
    }

    /**
     * Draws one segment the route <b>teleports</b> across, as a dashed run of the same conduit.
     *
     * <p>Worth the separate look rather than reusing {@link #segment}: a teleport hop is a straight
     * line of up to sixty blocks that happily passes through a mountain, and drawn solid it reads as
     * a broken path rather than a jump. Dashes say "you do not walk this" at a glance, and the
     * brighter, whiter core says the ability is what covers it.
     */
    /**
     * The colour of the part of a route the player must sneak through (a 1.5-block gap): amber,
     * apart from every route preset, so "crouch here" reads without a legend.
     */
    static final int SNEAK_RGB = 0xFFB040;

    static void hop(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
                    double t, double phase, int rgb, int width) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0) {
            return;
        }
        double intensity = pulseIntensity(t, phase);
        int coreRgb = towardWhite(rgb, 0.5 + 0.5 * intensity);
        int coreAlpha = (int) (CORE_ALPHA + (255 - CORE_ALPHA) * intensity);

        // Dash count from the on-screen length, so a hop seen end-on does not collapse into one
        // blob and one seen side-on is not a hundred specks.
        int dashes = Math.max(2, Math.min(HOP_MAX_DASHES, (int) (length / HOP_DASH_PIXELS)));
        for (int i = 0; i < dashes; i++) {
            double from = (double) i / dashes;
            double to = from + HOP_DASH_FILL / dashes;
            int ax = (int) Math.round(x0 + dx * from);
            int ay = (int) Math.round(y0 + dy * from);
            int bx = (int) Math.round(x0 + dx * to);
            int by = (int) Math.round(y0 + dy * to);
            WorldRender.line(g, ax, ay, bx, by, argb(rgb, BLOOM_ALPHA), width + BLOOM_EXTRA);
            WorldRender.line(g, ax, ay, bx, by, argb(coreRgb, coreAlpha), width + 1);
        }
    }

    /** Draws an arrowhead at {@code (x1, y1)} pointing along the segment. */
    static void chevron(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int rgb, int width) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0) {
            return;
        }
        // Unit direction, and the two barbs swept back from it at ~135 degrees.
        double ux = dx / length;
        double uy = dy / length;
        int color = argb(towardWhite(rgb, 0.5), 220);
        for (int sign = -1; sign <= 1; sign += 2) {
            // Rotate the reversed direction by +/-40 degrees to get a barb.
            double angle = Math.atan2(-uy, -ux) + sign * Math.toRadians(40);
            int bx = x1 + (int) Math.round(Math.cos(angle) * CHEVRON_SIZE);
            int by = y1 + (int) Math.round(Math.sin(angle) * CHEVRON_SIZE);
            WorldRender.line(g, x1, y1, bx, by, color, Math.max(1, width - 1));
        }
    }

    /** Whether this segment index should carry a chevron. */
    static boolean isChevronAt(int index) {
        return index % CHEVRON_EVERY == 0;
    }

    /**
     * Draws one node of the cube trail: a faint outer shell around a bright core, swelling as the
     * pulse passes through it.
     *
     * <p>Cubes sidestep the line style's weak point entirely – there are no corner joins to break,
     * because nothing is joined. Each is an independent marker, so a right-angle turn looks the same
     * as a straight run.
     *
     * @param centre the node's world centre
     * @param size   edge length of the core cube in blocks
     * @param t      how far along the whole path this node sits, in {@code [0,1]}
     * @param phase  the current {@link #pulsePhase()}
     */
    static void cube(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos, Vec3 centre,
                     double size, double t, double phase, int rgb) {
        double intensity = pulseIntensity(t, phase);
        double half = size * (1.0 + 0.45 * intensity) / 2.0;

        // Outer shell: wide and faint, so the cube glows instead of looking like a wireframe.
        edges(g, viewProjection, camPos, centre, half + 0.035, argb(rgb, 45), 3);
        // Core: tightens to white as the wave hits it.
        int coreAlpha = (int) (165 + 90 * intensity);
        edges(g, viewProjection, camPos, centre, half,
                argb(towardWhite(rgb, 0.3 + 0.7 * intensity), coreAlpha), intensity > 0.5 ? 2 : 1);
    }

    private static void edges(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                              Vec3 centre, double half, int color, int width) {
        WorldRender.boxEdges(g, viewProjection, camPos,
                centre.x - half, centre.y - half, centre.z - half,
                centre.x + half, centre.y + half, centre.z + half,
                color, width);
    }

    /** How lit this point is by the travelling pulse, in {@code [0,1]}. */
    private static double pulseIntensity(double t, double phase) {
        double distance = Math.abs(t - phase);
        distance = Math.min(distance, 1.0 - distance); // the pulse wraps around
        if (distance > PULSE_WIDTH) {
            return 0;
        }
        // Smooth falloff, so the wave has soft shoulders instead of hard edges.
        double linear = 1.0 - distance / PULSE_WIDTH;
        return linear * linear;
    }

    /** Blends a colour toward white by {@code t}, which is what makes the core read as hot. */
    private static int towardWhite(int rgb, double t) {
        double f = Math.max(0, Math.min(1, t));
        int r = (int) (((rgb >> 16) & 0xFF) + (255 - ((rgb >> 16) & 0xFF)) * f);
        int g = (int) (((rgb >> 8) & 0xFF) + (255 - ((rgb >> 8) & 0xFF)) * f);
        int b = (int) ((rgb & 0xFF) + (255 - (rgb & 0xFF)) * f);
        return (r << 16) | (g << 8) | b;
    }

    static int argb(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }
}
