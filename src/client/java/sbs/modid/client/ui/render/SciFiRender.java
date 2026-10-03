/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.theme.style.SurfaceMaterial;

/**
 * Drawing helpers for the SBS theme, built on the only primitives this version's
 * render system exposes via {@link GuiGraphicsExtractor}: {@code fill},
 * {@code fillGradient} and {@code outline}.
 *
 * <p>All helpers work in GUI-scaled ("logical") coordinates, so they adapt to the
 * player's GUI Scale automatically. Rounded corners are approximated per row using
 * the circle equation – no textures required.
 *
 * <p><b>This class is also where a {@link sbs.modid.client.ui.theme.UiStyle}'s material reaches the
 * mod.</b> Every panel, card, row, button and HUD element in all ~156 UI files draws through these
 * four methods, so a material hooked in here covers all of them at once - and a screen written next
 * year is covered the day it is written. That is deliberate: the previous style system expressed
 * itself only as colours and radii, and every look that needed more than that had to be applied
 * screen by screen, which is exactly how surfaces kept getting missed. Nothing can be left out of a
 * style now, because no caller gets a say in whether it participates.
 */
public final class SciFiRender {

    private SciFiRender() {
    }

    /**
     * Filled rectangle with rounded corners, wearing the active style's material.
     *
     * <p>With a HUD element's body dial shut and a frame colour asked for, this draws the {@link #ring}
     * instead. A filled rectangle in one of the theme's frame colours is never a shape in its own
     * right in this mod - it is the plate whatever comes next is painted one pixel inside, which is
     * exactly the invariant {@link SBSTheme#isBorderColor} is built on. So when no body is coming, a
     * rim is what the caller was drawing all along, and the ~5 overlays that build their own frame
     * out of this call get "background off" right without each having to know about it.
     */
    public static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (sbs.modid.client.ui.hud.edit.logic.HudOpacity.hidesBody() && SBSTheme.isBorderColor(color)) {
            ring(g, x, y, w, h, radius, color);
            return;
        }
        roundedRectRaw(g, x, y, w, h, radius, color);
        if (w > 0 && h > 0) {
            SBSTheme.material().paintBody(g, x, y, w, h, clampRadius(w, h, radius), color,
                    SurfaceMaterial.Weight.of(w, h));
        }
    }

    /**
     * The bare rounded rectangle, with <b>no</b> material pass.
     *
     * <p>For the material implementations themselves - a material that drew through the styled entry
     * point would call itself for every shape it painted. Also for the inner passes of the helpers
     * below, which apply the material once for the surface as a whole rather than once per layer.
     */
    public static void roundedRectRaw(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                      int radius, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = clampRadius(w, h, radius);
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5D;
            int dx = (int) Math.round(r - Math.sqrt((double) r * r - dy * dy));
            g.fill(x + dx, y + i, x + w - dx, y + i + 1, color);
            g.fill(x + dx, y + h - i - 1, x + w - dx, y + h - i, color);
        }
    }

    /** Rounded rectangle with a vertical gradient body (corners use the top color). */
    public static void roundedRectGradient(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                           int radius, int top, int bottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = clampRadius(w, h, radius);
        // Gradient for the central block.
        g.fillGradient(x, y + r, x + w, y + h - r, top, bottom);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5D;
            int dx = (int) Math.round(r - Math.sqrt((double) r * r - dy * dy));
            g.fill(x + dx, y + i, x + w - dx, y + i + 1, top);
            g.fill(x + dx, y + h - i - 1, x + w - dx, y + h - i, bottom);
        }
        // The material sees the top tone: it is the lit face of the surface, which is what grain,
        // sheen and bloom are all describing.
        SurfaceMaterial.Weight weight = SurfaceMaterial.Weight.of(w, h);
        SBSTheme.material().paintBody(g, x, y, w, h, r, top, weight);
        // ...and the frame pass runs here too, because this call IS the SBS window. A window is drawn
        // as a border plate (roundedRect) with a gradient body on top of it, never through
        // roundedRectWithBorder - so hanging frame ornament only off that one would put brackets and
        // rivets on every card while leaving the actual windows bare. Found exactly that way.
        SBSTheme.material().paintFrame(g, x, y, w, h, r, top, weight);
    }

    /**
     * The 1 px rim of {@link #roundedRectRaw} on its own – an outline, with nothing filled inside it.
     *
     * <p><b>Defined as the plate minus the body</b>, using the very inset the fill passes use
     * ({@code x+1, y+1, w-2, h-2, radius-1}), rather than as an outline derived in its own right. So
     * the pixels lit here are exactly the ones a card shows around its body today, and a surface that
     * loses its fill keeps the shape it had – down to which pixel of a 4 px corner is on. An outline
     * traced by any independent rule drifts from the filled form at the corners, and on these radii
     * the corner is most of what tells the styles apart.
     *
     * <p>Costs about what the plate it replaces did: the two flanks between the arcs are one tall
     * fill each, and only the rows an arc actually curves through are drawn one at a time.
     */
    public static void ring(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (w <= 2 || h <= 2) {
            roundedRectRaw(g, x, y, w, h, radius, color);   // no room for a hole; it is all rim
            return;
        }
        int outer = clampRadius(w, h, radius);
        int inner = clampRadius(w - 2, h - 2, radius - 1);
        int arc = Math.max(1, outer);   // at radius 0 the "arcs" are just the top and bottom caps
        for (int row = 0; row < h; row++) {
            if (row == arc && h - arc > arc) {
                g.fill(x, y + arc, x + 1, y + h - arc, color);
                g.fill(x + w - 1, y + arc, x + w, y + h - arc, color);
                row = h - arc - 1;
                continue;
            }
            int left = x + rowInset(row, h, outer);
            int right = x + w - rowInset(row, h, outer);
            int bodyRow = row - 1;   // the body starts one row down, so it lags the plate by one
            if (bodyRow < 0 || bodyRow >= h - 2) {
                g.fill(left, y + row, right, y + row + 1, color);   // a cap: no body behind this row
                continue;
            }
            int bodyLeft = x + 1 + rowInset(bodyRow, h - 2, inner);
            int bodyRight = x + w - 1 - rowInset(bodyRow, h - 2, inner);
            g.fill(left, y + row, Math.min(bodyLeft, right), y + row + 1, color);
            g.fill(Math.max(bodyRight, left), y + row, right, y + row + 1, color);
        }
    }

    /**
     * How far a rounded rectangle's filled span is inset on one row – the same arc
     * {@link #roundedRectRaw} paints, read back so {@link #ring} can subtract one shape from another.
     */
    private static int rowInset(int row, int h, int r) {
        int i = row < r ? row : (row >= h - r ? h - 1 - row : -1);
        if (i < 0) {
            return 0;
        }
        double dy = r - i - 0.5D;
        return (int) Math.round(r - Math.sqrt((double) r * r - dy * dy));
    }

    /** Rounded rectangle with a 1px border (border drawn underneath, fill on top). */
    public static void roundedRectWithBorder(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                             int radius, int fill, int border) {
        if (w <= 0 || h <= 0) {
            return;
        }
        SurfaceMaterial material = SBSTheme.material();
        SurfaceMaterial.Weight weight = SurfaceMaterial.Weight.of(w, h);
        int r = clampRadius(w, h, radius);

        // With no body to cover its middle, the plate is not a border - it is a filled rectangle in
        // the border colour, which is the opposite of what a transparent fill asked for. Draw the rim
        // the caller meant instead. Both ways in matter: the fill's own alpha reaches zero through
        // Surface Opacity, the body dial through the HUD editor, and neither is visible to the other.
        if ((fill >>> 24) == 0 || sbs.modid.client.ui.hud.edit.logic.HudOpacity.hidesBody()) {
            ring(g, x, y, w, h, radius, border);
            material.paintFrame(g, x, y, w, h, r, border, weight);
            return;
        }

        roundedRectRaw(g, x, y, w, h, radius, border);
        // Frame first: it belongs to the rim, so the body still paints over its inner edge exactly
        // the way the plain border does.
        material.paintFrame(g, x, y, w, h, r, border, weight);
        roundedRectRaw(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
        material.paintBody(g, x + 1, y + 1, w - 2, h - 2,
                clampRadius(w - 2, h - 2, radius - 1), fill, weight);
    }

    /**
     * Soft outer glow: a few expanding translucent rounded rings around the box. Marked as frame
     * geometry, so a HUD element faded "background only" keeps its glow intact.
     *
     * <p>A material may replace this entirely - an arcane bloom instead of a sci-fi rim, or nothing
     * at all for the styles where a glow would be wrong.
     */
    public static void glow(GuiGraphicsExtractor g, int x, int y, int w, int h,
                            int radius, int color, int layers) {
        if (SBSTheme.material().paintGlow(g, x, y, w, h, radius, color, layers)) {
            return;
        }
        sbs.modid.client.ui.hud.edit.logic.HudOpacity.beginOutline();
        for (int i = layers; i >= 1; i--) {
            roundedRectRaw(g, x - i, y - i, w + 2 * i, h + 2 * i, radius + i, color);
        }
        sbs.modid.client.ui.hud.edit.logic.HudOpacity.endOutline();
    }

    /** The radius actually used for a box this size - clamped so a corner can never exceed it. */
    private static int clampRadius(int w, int h, int radius) {
        return Math.max(0, Math.min(radius, Math.min(w, h) / 2));
    }
}
