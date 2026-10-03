/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.tooltip;

import net.minecraft.client.Minecraft;
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
 *   <li>{@code ScrollableTooltipsMixin} → {@link #apply(Font, List)} on the tooltip render path
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
    public List<ClientTooltipComponent> apply(Font font, List<ClientTooltipComponent> components) {
        if (!isEnabled() || components == null || components.size() <= 1) {
            notOverflowing();
            return components;
        }

        int budget = availableHeight();
        if (measure(font, components) <= budget) {
            notOverflowing();
            return components;
        }

        // --- Overflowing: build a page that fits, keeping room for the two indicator lines. ---
        int contentBudget = Math.max(font.lineHeight, budget - 2 * INDICATOR_HEIGHT);
        int pageSize = pageSizeFor(font, components, contentBudget);

        int total = components.size();
        this.maxScroll = Math.max(0, total - pageSize);
        this.scrollOffset = clamp(this.scrollOffset, 0, this.maxScroll);
        markOverflowing();

        int from = this.scrollOffset;
        int to = Math.min(total, from + pageSize);

        List<ClientTooltipComponent> page = new ArrayList<>(pageSize + 2);
        if (from > 0) {
            page.add(indicator("§8§o▲ " + from + " more"));
        }
        for (int i = from; i < to; i++) {
            page.add(components.get(i));
        }
        int below = total - to;
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
        this.scrollOffset = clamp(this.scrollOffset + delta, 0, this.maxScroll);
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Vertical space (px) a tooltip may occupy before it clips against a screen edge. */
    private static int availableHeight() {
        int guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return guiHeight - VERTICAL_CHROME - 2 * SCREEN_MARGIN;
    }

    /** Total rendered height of the given lines (matching vanilla's tooltip height maths). */
    private static int measure(Font font, List<ClientTooltipComponent> components) {
        int height = components.size() > 1 ? EXTRA_SPACE_AFTER_FIRST_LINE : 0;
        for (ClientTooltipComponent c : components) {
            height += c.getHeight(font);
        }
        return height;
    }

    /** How many leading lines fit into {@code contentBudget} (at least one). */
    private static int pageSizeFor(Font font, List<ClientTooltipComponent> components, int contentBudget) {
        int used = 0;
        int fit = 0;
        for (ClientTooltipComponent c : components) {
            int h = c.getHeight(font);
            if (used + h > contentBudget) {
                break;
            }
            used += h;
            fit++;
        }
        return Math.max(1, fit);
    }

    private static ClientTooltipComponent indicator(String legacyText) {
        return ClientTooltipComponent.create(Component.literal(legacyText).getVisualOrderText());
    }

    private void markOverflowing() {
        this.lastOverflowNanos = System.nanoTime();
    }

    private void notOverflowing() {
        this.maxScroll = 0;
        this.scrollOffset = 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
