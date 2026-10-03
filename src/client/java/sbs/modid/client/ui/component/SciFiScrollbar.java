/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.function.IntConsumer;

/**
 * The mod's one scrollbar: a slim track with a draggable thumb, sized and placed from how much of the
 * content is on screen.
 *
 * <p><b>Why this is shared rather than copied.</b> It was copied, several times, and the copies do not
 * agree: some screens drew a 3px square-cornered bar you could only look at, some drew nothing at all,
 * and only the module sidebar could actually be grabbed. A player who learns that the bar on one SBS
 * panel is draggable has learned something false about the next one, which is worse than never having
 * offered it - see the "one widget per interaction type" rule in {@code ui/AGENTS.md}.
 *
 * <p>The look and the feel are the module sidebar's, because that is the one players already use:
 * a {@code 0x22FFFFFF} track, an accent thumb that brightens under the cursor, and a hit zone widened
 * by {@link #GRAB_PAD} on each side so a 4px target is not a 4px target.
 *
 * <p><b>The caller owns the scroll offset.</b> This class is handed the current value and reports the
 * new one through an {@link IntConsumer}; it never stores it. Scroll offsets are clamped, reset and
 * rebuilt by their owners for reasons this component cannot see (a filter changed, a list reloaded,
 * a room switched), and a second copy of that number would go stale in exactly those moments.
 *
 * <p><b>Usage.</b> Call {@link #set} each frame with the track box and the content size, then
 * {@link #render}. Route {@code mouseClicked} / {@code mouseDragged} / {@code mouseReleased} through
 * {@link #handleClick} / {@link #handleDrag} / {@link #release}, each of which reports whether it
 * consumed the event, <b>before</b> the host handles its own rows - a click on the bar must not also
 * land on whatever is painted underneath it.
 */
public final class SciFiScrollbar {

    /** Drawn width of the bar. */
    public static final int WIDTH = 4;

    /** How far either side of the bar still counts as grabbing it. */
    private static final int GRAB_PAD = 2;

    /** Shortest the thumb may get, however long the list is - below this it cannot be grabbed. */
    private static final int MIN_THUMB = 16;

    private int x;
    private int top;
    private int height;
    private int total;
    private int visible;

    private boolean dragging;

    /** {@code mouseY - thumbTop} captured when the thumb was grabbed, so it does not jump on grab. */
    private double grabOffset;

    /**
     * Sets the track box and what it is scrolling. Call every frame before rendering or hit-testing,
     * so the bar the player grabs is exactly the one that was drawn.
     *
     * @param x       left edge of the bar
     * @param top     top of the track
     * @param height  track height
     * @param total   how many items exist
     * @param visible how many fit on screen at once
     */
    public void set(int x, int top, int height, int total, int visible) {
        this.x = x;
        this.top = top;
        this.height = height;
        this.total = Math.max(0, total);
        this.visible = Math.max(1, visible);
    }

    /** Whether a bar is needed at all - false when everything already fits. */
    public boolean needed() {
        return maxScroll() > 0 && height > 0;
    }

    /** The largest valid scroll offset for the current content. */
    public int maxScroll() {
        return Math.max(0, total - visible);
    }

    /** Whether the thumb is currently being dragged. */
    public boolean isDragging() {
        return dragging;
    }

    private int thumbHeight() {
        return Math.max(MIN_THUMB, (int) ((long) height * visible / Math.max(1, total)));
    }

    /** How far the thumb can travel down the track. */
    private int travel() {
        return height - thumbHeight();
    }

    private int thumbTop(int scroll) {
        int max = maxScroll();
        return max <= 0 ? top : top + (int) ((long) clamp(scroll, max) * travel() / max);
    }

    /** Whether a point is inside the widened grab zone of the whole bar. */
    public boolean over(double mouseX, double mouseY) {
        return needed() && mouseX >= x - GRAB_PAD && mouseX <= x + WIDTH + GRAB_PAD
                && mouseY >= top && mouseY <= top + height;
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /** Draws the bar for the given scroll offset. Nothing is drawn when the content fits. */
    public void render(GuiGraphicsExtractor g, int scroll, int mouseX, int mouseY) {
        if (!needed()) {
            return;
        }
        int thumbH = thumbHeight();
        int thumbY = thumbTop(scroll);
        SciFiRender.roundedRect(g, x, top, WIDTH, height, WIDTH / 2, TRACK_COLOR);
        boolean hovered = dragging
                || (mouseX >= x - GRAB_PAD && mouseX <= x + WIDTH + GRAB_PAD
                        && mouseY >= thumbY && mouseY <= thumbY + thumbH);
        SciFiRender.roundedRect(g, x, thumbY, WIDTH, thumbH, WIDTH / 2,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
    }

    /**
     * Track colour, as a literal rather than a theme constant.
     *
     * <p>It is a translucent white wash over whatever the panel already is, so it reads the same on
     * every one of the eight UI styles without any of them having to name a colour for it. A theme
     * entry would be eight entries, seven of which nobody would ever look at again.
     */
    private static final int TRACK_COLOR = 0x22FFFFFF;

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Offers a click to the bar: grabbing the thumb starts a drag, clicking the track jumps there.
     *
     * <p>Clicking the empty track centres the thumb on the click rather than paging by one screen.
     * Both are defensible; this one is what the module sidebar does, and matching it matters more
     * than the choice itself.
     *
     * @param scroll the current offset
     * @param apply  receives the new offset when the click moves it
     * @return whether the click was consumed and the host should stop
     */
    public boolean handleClick(double mouseX, double mouseY, int scroll, IntConsumer apply) {
        if (!over(mouseX, mouseY)) {
            return false;
        }
        int thumbH = thumbHeight();
        int thumbY = thumbTop(scroll);
        if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
            dragging = true;
            grabOffset = mouseY - thumbY;
        } else {
            apply.accept(scrollFor(mouseY - thumbH / 2.0));
        }
        return true;
    }

    /**
     * Offers a drag to the bar.
     *
     * @return whether a drag is in progress and the host should stop
     */
    public boolean handleDrag(double mouseY, IntConsumer apply) {
        if (!dragging) {
            return false;
        }
        if (needed()) {
            apply.accept(scrollFor(mouseY - grabOffset));
        }
        return true;
    }

    /**
     * Ends a drag.
     *
     * @return whether a drag was in progress and the host should stop
     */
    public boolean release() {
        if (!dragging) {
            return false;
        }
        dragging = false;
        return true;
    }

    /** The scroll offset that would put the thumb's top at {@code thumbY}. */
    private int scrollFor(double thumbY) {
        int travel = travel();
        int max = maxScroll();
        if (travel <= 0 || max <= 0) {
            return 0;
        }
        double fraction = Math.max(0.0, Math.min(1.0, (thumbY - top) / travel));
        return clamp((int) Math.round(fraction * max), max);
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }
}
