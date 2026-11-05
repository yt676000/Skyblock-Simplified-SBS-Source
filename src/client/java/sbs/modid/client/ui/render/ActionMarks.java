/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.theme.MarkPalette;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The shared "mark language" for anything the mod points at in a GUI: a slot to click, the next
 * one, a count of clicks, a column to watch. Terminals are the first user; other helpers that mark
 * cells or screen boxes draw through here so every hint in the mod looks the same.
 *
 * <p>Shapes are fixed per {@link MarkKind} (see there); colours come from {@link MarkPalette},
 * derived from the theme once per {@link SBSTheme#revision()} - nothing here allocates per frame
 * except the {@code Component} wrapping a badge string, which the text API requires.
 *
 * <p>Render thread only.
 */
public final class ActionMarks {

    /** Melody's "now" pulse period. */
    public static final int PULSE_MS = 500;

    private static MarkPalette palette;
    private static int paletteRevision = Integer.MIN_VALUE;

    private ActionMarks() {
    }

    /** The current palette, re-derived only when the theme changed. */
    public static MarkPalette palette() {
        int revision = SBSTheme.revision();
        if (palette == null || revision != paletteRevision) {
            palette = MarkPalette.derive(SBSTheme.ACCENT, SBSTheme.PANEL_BASE, SBSTheme.TEXT);
            paletteRevision = revision;
        }
        return palette;
    }

    /** The edge colour of a kind - for text or a badge outline that belongs to that mark. */
    public static int edgeColor(MarkKind kind) {
        MarkPalette p = palette();
        return switch (kind) {
            case DO, NOW -> p.doEdge;
            case ALT -> p.altEdge;
            case NEXT, WAIT -> p.nextRing;
        };
    }

    /**
     * Marks a square or rectangular cell. Filled kinds are drawn translucent <b>over</b> the cell's
     * content (a mark under a full-bleed colour cell would be invisible), hollow kinds as a ring
     * just inside it.
     */
    public static void cell(GuiGraphicsExtractor g, int x, int y, int w, int h, MarkKind kind) {
        MarkPalette p = palette();
        int radius = Math.max(2, Math.min(w, h) / 6);
        int ring = Math.max(1, Math.min(w, h) / 14);
        switch (kind) {
            case DO -> filled(g, x, y, w, h, radius, ring, p.doGlow, p.doFill, p.doEdge);
            case NOW -> {
                float k = MarkPalette.pulse(System.currentTimeMillis(), PULSE_MS);
                filled(g, x, y, w, h, radius, ring, MarkPalette.scaleAlpha(p.doGlow, k * 2f),
                        MarkPalette.scaleAlpha(p.doFill, 0.5f + 0.5f * k), p.doEdge);
                SciFiRender.glow(g, x, y, w, h, radius, MarkPalette.scaleAlpha(p.doGlow, k), 3);
            }
            case ALT -> {
                filled(g, x, y, w, h, radius, ring, p.altGlow, p.altFill, p.altEdge);
                // Corner notch: a second shape cue besides the amber, top-left like its badge.
                int notch = Math.max(3, Math.min(w, h) / 4);
                for (int i = 0; i < notch; i++) {
                    g.fill(x + ring, y + ring + i, x + ring + notch - i, y + ring + i + 1, p.altEdge);
                }
            }
            case NEXT -> SciFiRender.ring(g, x, y, w, h, radius, p.nextRing);
            case WAIT -> {
                SciFiRender.ring(g, x, y, w, h, radius, p.nextRing);
                int dot = Math.max(2, Math.min(w, h) / 6);
                SciFiRender.roundedRectRaw(g, x + (w - dot) / 2, y + (h - dot) / 2, dot, dot, dot / 2,
                        p.nextRing);
            }
        }
    }

    /** {@link #cell(GuiGraphicsExtractor, int, int, int, int, MarkKind)} for a square cell. */
    public static void cell(GuiGraphicsExtractor g, int x, int y, int size, MarkKind kind) {
        cell(g, x, y, size, size, kind);
    }

    private static void filled(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int ring,
                               int glow, int fill, int edge) {
        SciFiRender.glow(g, x, y, w, h, radius, glow, 2);
        SciFiRender.roundedRectRaw(g, x, y, w, h, radius, fill);
        for (int i = 0; i < ring; i++) {
            SciFiRender.ring(g, x + i, y + i, w - 2 * i, h - 2 * i, Math.max(0, radius - i), edge);
        }
    }

    /**
     * A count badge in the cell's corner - bottom-right for most kinds, top-left for {@link MarkKind#ALT}.
     * The badge is a dark pill with the mark's edge colour, so the number reads on any cell colour.
     */
    public static void badge(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String text,
                             MarkKind kind) {
        if (text == null || text.isEmpty()) {
            return;
        }
        MarkPalette p = palette();
        int tw = font.width(text);
        int bw = tw + 4;
        int bh = font.lineHeight + 2;
        int bx = kind.badgeTopLeft() ? x - 1 : x + w - bw + 1;
        int by = kind.badgeTopLeft() ? y - 1 : y + h - bh + 1;
        int edge = edgeColor(kind);
        SciFiRender.roundedRectRaw(g, bx, by, bw, bh, 3, edge);
        SciFiRender.roundedRectRaw(g, bx + 1, by + 1, bw - 2, bh - 2, 2, p.badgeFill);
        g.text(font, Component.literal(text), bx + 2, by + 2, edge, false);
    }

    /** Dims a cell that is already done and puts a tick on it - the board keeps its shape. */
    public static void done(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h) {
        MarkPalette p = palette();
        int radius = Math.max(2, Math.min(w, h) / 6);
        SciFiRender.roundedRectRaw(g, x, y, w, h, radius, p.doneVeil);
        g.centeredText(font, Component.literal("✔"), x + w / 2, y + (h - font.lineHeight) / 2 + 1, p.doneCheck);
    }

    /** A band behind a column or row the player should watch (drawn under the cells). */
    public static void band(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        MarkPalette p = palette();
        SciFiRender.roundedRectRaw(g, x, y, w, h, Math.max(2, Math.min(w, h) / 8), p.band);
        SciFiRender.ring(g, x, y, w, h, Math.max(2, Math.min(w, h) / 8), p.nextRing);
    }
}
