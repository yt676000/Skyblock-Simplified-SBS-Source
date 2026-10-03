/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.titlescreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Places {@link SbsBrandArt} around the title screen's menu: the mark fills the space above the
 * buttons, the wordmark the space below them.
 *
 * <p>The two gaps are measured from the buttons that are actually on the screen rather than from
 * vanilla's layout constants, so the branding stays flush against the menu no matter what put the
 * buttons where they are - a different window size, the demo menu, or another mod adding rows. The
 * measurement is clamped to a sane band anyway: a mod that parks a full-height widget on the screen
 * would otherwise collapse both gaps to nothing.
 *
 * <p>Sizes are fractions of the screen with the aspect ratio taken from the artwork, which is what
 * keeps the proportions of the banner intact from a tiny window up to a 4K one.
 */
public final class TitleBranding {

    /** Widest the mark may get, as a fraction of the screen. */
    private static final float MARK_MAX_WIDTH = 0.55F;
    /** Widest the wordmark may get, as a fraction of the screen. */
    private static final float WORDMARK_MAX_WIDTH = 0.46F;
    /** Breathing room between the branding and the menu, as a fraction of the screen height. */
    private static final float GAP = 0.035F;
    /** Distance from the wordmark to the bottom edge - clear of the version line vanilla draws there. */
    private static final float BOTTOM_MARGIN = 0.045F;

    /**
     * Below these the art is no longer legible and is skipped instead of drawn as a smudge. The
     * wordmark's floor is the higher of the two because it carries actual lettering: at 18 units its
     * cap height is around 8 px, which is where "SIMPLIFIED" stops being readable. A short screen -
     * a small window at a large GUI scale - genuinely has no room under vanilla's button block, and
     * then only the mark is drawn.
     */
    private static final int MIN_MARK_HEIGHT = 12;
    private static final int MIN_WORDMARK_HEIGHT = 18;

    /** How far below its intended size the wordmark may be squeezed before it is dropped instead. */
    private static final float WORDMARK_MIN_SCALE = 0.6F;

    /**
     * How far off centre a widget may sit and still count as part of the menu, as a fraction of the
     * screen width. This is what keeps the corner chrome - vanilla's copyright button, and the side
     * columns other mods add - from being measured as the menu and squeezing the branding out.
     */
    private static final float MENU_CENTRE_BAND = 0.25F;

    private TitleBranding() {
    }

    /** Whether the SBS title screen replaces the vanilla logo. */
    public static boolean enabled() {
        return ConfigManager.getInstance().get().minecraftOverlay.titleScreenBranding;
    }

    /** Frees both baked textures - called when the toggle goes off, so nothing lingers on the GPU. */
    public static void discard() {
        SbsBrandArt.MARK.discard();
        SbsBrandArt.WORDMARK.discard();
    }

    /**
     * Paints the title screen's backdrop flat black in place of the rotating panorama, which is what
     * the brand banner is designed against - white letterforms over a moving, mostly-brown scene
     * read as noise.
     */
    public static void renderBackground(GuiGraphicsExtractor g, int width, int height) {
        g.fill(0, 0, width, height, 0xFF000000);
    }

    /**
     * Draws the branding.
     *
     * @param fade the title screen's fade-in factor, applied as the tint's alpha so the banner
     *             comes up with the rest of the screen instead of popping in
     */
    public static void render(GuiGraphicsExtractor g, Screen screen, int width, int height, float fade) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int tint = (Math.round(Math.clamp(fade, 0F, 1F) * 255) << 24) | 0x00FFFFFF;
        int stamp = SbsBrandArt.themeStamp();
        int gap = Math.max(4, Math.round(height * GAP));

        int[] menu = measureMenu(screen, width, height);
        int menuTop = Math.clamp(menu[0], Math.round(height * 0.18F), Math.round(height * 0.55F));
        // Math.clamp requires min <= max, which only a screen barely tall enough to hold the menu
        // could break; the outer max() keeps that case from throwing on the render thread.
        int menuBottom = Math.clamp(menu[1], menuTop + 10,
                Math.max(menuTop + 10, Math.round(height * 0.92F)));

        // Mark: centred in the band above the menu, as large as that band and the width cap allow.
        int markHeight = Math.min(menuTop - 2 * gap,
                Math.round(width * MARK_MAX_WIDTH / SbsBrandArt.MARK.aspect()));
        if (markHeight >= MIN_MARK_HEIGHT) {
            int markWidth = Math.round(markHeight * SbsBrandArt.MARK.aspect());
            SbsBrandArt.MARK.draw(g, (width - markWidth) / 2, (menuTop - markHeight) / 2,
                    markWidth, markHeight, tint, stamp);
        }

        // Wordmark: pinned to the bottom margin, shrinking if the menu reaches too far down. Past a
        // point shrinking stops being the right answer - a banner a fifth of the screen wide under a
        // mark half the screen wide just looks broken - so below WORDMARK_MIN_SCALE of its intended
        // size it is left off entirely and the mark carries the screen alone.
        int bottomMargin = Math.max(8, Math.round(height * BOTTOM_MARGIN));
        int wordIntended = Math.round(width * WORDMARK_MAX_WIDTH / SbsBrandArt.WORDMARK.aspect());
        int wordHeight = Math.min(height - bottomMargin - (menuBottom + gap), wordIntended);
        if (wordHeight >= MIN_WORDMARK_HEIGHT && wordHeight >= wordIntended * WORDMARK_MIN_SCALE) {
            int wordWidth = Math.round(wordHeight * SbsBrandArt.WORDMARK.aspect());
            SbsBrandArt.WORDMARK.draw(g, (width - wordWidth) / 2, height - bottomMargin - wordHeight,
                    wordWidth, wordHeight, tint, stamp);
        }
    }

    /**
     * Vertical extent of the centred button block as {@code {top, bottom}}.
     *
     * <p>Only widgets whose horizontal midpoint falls inside {@link #MENU_CENTRE_BAND} of the screen
     * centre are measured. That is the whole trick: vanilla's copyright button sits in the bottom
     * right corner and would otherwise report the menu as reaching the bottom edge, leaving no room
     * for the wordmark at all - and side columns other mods add would do the same on the flanks.
     * Falls back to vanilla's own layout constants when nothing qualifies.
     */
    private static int[] measureMenu(Screen screen, int width, int height) {
        int centre = width / 2;
        int band = Math.round(width * MENU_CENTRE_BAND);
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            int widgetCentre = widget.getX() + widget.getWidth() / 2;
            if (Math.abs(widgetCentre - centre) > band) {
                continue;
            }
            top = Math.min(top, widget.getY());
            bottom = Math.max(bottom, widget.getY() + widget.getHeight());
        }
        if (top == Integer.MAX_VALUE) {
            int firstRow = height / 4 + 48;
            return new int[] {firstRow, firstRow + 5 * 24};
        }
        return new int[] {top, bottom};
    }
}
