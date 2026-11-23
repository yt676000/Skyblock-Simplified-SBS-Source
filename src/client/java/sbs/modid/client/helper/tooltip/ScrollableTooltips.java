/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Core logic of the <b>Scrollable Tooltips</b> module.
 *
 * <p>Hypixel Skyblock item lore (enchantments, stats, reforge/rebuild costs, ...) is often far
 * taller than the screen once the GUI scale is turned up, so vanilla clips it against the top or
 * bottom edge. When this module is <i>enabled</i> and a tooltip would not fit, the visible lines
 * are reduced to a page that fits the screen and the mouse wheel scrolls that page through the
 * full lore (with {@code ▲ / ▼ more} indicators). When it is <i>disabled</i>, {@link #apply} is a
 * no-op and Minecraft renders the tooltip exactly as it normally would.
 *
 * <p>This mirrors the design of the other modules: a single logic holder that exposes a live
 * {@link #isEnabled()} status backed by {@link ConfigManager}, driven from a thin Mixin. All the
 * state (scroll offset, overflow bookkeeping) lives here so the Mixins stay one-liners.
 *
 * <p>Called from two Mixins:
 * <ul>
 *   <li>{@code ScrollableTooltipsMixin} → {@link #apply(Font, List, int)} on the tooltip render path
 *       ({@code GuiGraphicsExtractor#tooltip}) to swap in the trimmed, scrolled line list.</li>
 *   <li>{@code TooltipScrollInputMixin} → {@link #onMouseScroll(double)} on
 *       {@code AbstractContainerScreen#mouseScrolled} to move the page with the wheel.</li>
 * </ul>
 */
public final class ScrollableTooltips {

    /** Stable module id (used for the config key / menu card, like the other modules). */
    public static final String ID = "scrollable_tooltips";

    /** Border + top/bottom padding of the tooltip frame, plus a small safety gap to the edge. */
    private static final int VERTICAL_CHROME = 12;
    /** Extra breathing room kept above and below so the frame never touches the screen edge. */
    private static final int SCREEN_MARGIN = 8;
    /** Height (px) reserved for one {@code ▲ / ▼ more} indicator line. */
    private static final int INDICATOR_HEIGHT = 10;
    /** Lines moved per wheel notch. */
    private static final int LINES_PER_NOTCH = 1;
    /** Vanilla adds this much extra space after the (bold) first line of an item tooltip. */
    private static final int EXTRA_SPACE_AFTER_FIRST_LINE = 2;
    /** How long (ns) after the last overflowing tooltip we still treat the wheel as "ours". */
    private static final long ACTIVE_WINDOW_NANOS = 100_000_000L; // 100 ms (a few frames)

    private static final ScrollableTooltips INSTANCE = new ScrollableTooltips();

    /** Number of leading lore lines currently scrolled off the top. */
    private int scrollOffset = 0;
    /** Upper bound for {@link #scrollOffset}, refreshed every frame an overflowing tooltip draws. */
    private int maxScroll = 0;
    /** {@link System#nanoTime()} of the last frame that drew an overflowing tooltip. */
    private long lastOverflowNanos = 0L;

    private ScrollableTooltips() {
    }

    public static ScrollableTooltips getInstance() {
        return INSTANCE;
    }

    /** The live On/Off toggle, read straight from the config (same pattern as the other modules). */
    public boolean isEnabled() {
        return ConfigManager.getInstance().get().scrollableTooltips.enabled;
    }

    /**
     * Entry point from the tooltip render Mixin. Returns the list of tooltip lines that should
     * actually be drawn this frame.
     *
     * <p>Returns the input list unchanged (zero allocation) whenever the module is off, the
     * tooltip is trivial, or it already fits – so vanilla behaviour is untouched in every normal
     * case. Only a genuinely screen-overflowing multi-line tooltip is replaced by a scrolled page.
     */
    public List<ClientTooltipComponent> apply(Font font, List<ClientTooltipComponent> components, int guiHeight) {
        if (!isEnabled() || components == null || components.size() <= 1) {
            notOverflowing();
            return components;
        }

        // Budget from the height the tooltip is actually laid out against (the extractor's own
        // guiHeight), not a separately read window height.
        int budget = guiHeight - VERTICAL_CHROME - 2 * SCREEN_MARGIN;
        if (measure(font, components) <= budget) {
            notOverflowing();
            return components;
        }

        int total = components.size();
        int[] heights = new int[total];
        for (int i = 0; i < total; i++) {
            heights[i] = components.get(i).getHeight(font);
        }
        // Vanilla draws 2px of extra space after the first drawn line; keep it out of the budget.
        TooltipPager.Page p = TooltipPager.page(heights, budget - EXTRA_SPACE_AFTER_FIRST_LINE,
                INDICATOR_HEIGHT, this.scrollOffset);
        this.maxScroll = p.maxOffset();
        this.scrollOffset = p.from();
        markOverflowing();

        List<ClientTooltipComponent> page = new ArrayList<>(p.to() - p.from() + 2);
        if (p.moreAbove()) {
            page.add(indicator("§8§o▲ " + p.from() + " more"));
        }
        for (int i = p.from(); i < p.to(); i++) {
            page.add(components.get(i));
        }
        int below = total - p.to();
        if (below > 0) {
            page.add(indicator("§8§o▼ " + below + " more"));
        }
        return page;
    }

    /**
     * Entry point from the scroll-input Mixin. Moves the page while an overflowing tooltip is
     * showing and reports whether the wheel event was consumed (so the container behind it does
     * not scroll at the same time).
     */
    public boolean onMouseScroll(double scrollY) {
        if (!isEnabled() || scrollY == 0) {
            return false;
        }
        // Only react while a long tooltip is actually on screen under the cursor.
        if (System.nanoTime() - lastOverflowNanos > ACTIVE_WINDOW_NANOS) {
            return false;
        }
        int delta = scrollY > 0 ? -LINES_PER_NOTCH : LINES_PER_NOTCH; // wheel up -> earlier lines
        this.scrollOffset = Math.max(0, Math.min(this.maxScroll, this.scrollOffset + delta));
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Total rendered height of the given lines (matching vanilla's tooltip height maths). */
    private static int measure(Font font, List<ClientTooltipComponent> components) {
        int height = components.size() > 1 ? EXTRA_SPACE_AFTER_FIRST_LINE : 0;
        for (ClientTooltipComponent c : components) {
            height += c.getHeight(font);
        }
        return height;
    }

    private static ClientTooltipComponent indicator(String legacyText) {
        return ClientTooltipComponent.create(Component.literal(legacyText).getVisualOrderText());
    }

    private void markOverflowing() {
        this.lastOverflowNanos = System.nanoTime();
    }

    /**
     * A tooltip that fits was drawn. The offset is only forgotten once no long tooltip has been
     * shown for the active window: resetting on every short tooltip let any other tooltip drawn
     * in between (or a single-line frame) throw the scrolled lore back to its first page.
     */
    private void notOverflowing() {
        if (System.nanoTime() - lastOverflowNanos > ACTIVE_WINDOW_NANOS) {
            this.maxScroll = 0;
            this.scrollOffset = 0;
        }
    }
}
