/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Z-order of the floating overlay window layers on container screens: the windowed Recipe Viewer,
 * the price-browser windows (one layer – the manager orders its own windows internally) and the
 * Item Value table. Clicking into a layer {@linkplain #raise raises} it above the others, exactly
 * like clicking a desktop window; rendering iterates {@link #renderOrder()} (bottom → top) and
 * input routing iterates {@link #clickOrder()} (top → bottom). Client thread only.
 */
public final class FloatingWindows {

    public enum Layer {
        /** The Recipe Viewer in windowed mode. */
        RECIPE,
        /** All price-browser windows (internally z-ordered by the manager). */
        BROWSERS,
        /** The Item Value table window. */
        VALUE,
        /** The Similar Auctions window. */
        AUCTIONS,
        /** The Best Flips window (Bazaar module). */
        FLIPS,

        /** The Forge Flips ranking beside Hypixel's forge menu. */
        FORGE,

        /** The AH Flips window (AH Flip Alerts module). */
        AH_FLIPS,

        /** The in-game Calculator window. */
        CALCULATOR,

        /** The Garden plot grid (Garden only). */
        GARDEN_PLOTS,

        /** The cost-to-max overview beside an essence shop. */
        ESSENCE_SHOP,

        /** The shopping list beside the Attribute Menu (Hunting). */
        MISSING_SHARDS
    }

    private static final List<Layer> order = new ArrayList<>(
            List.of(Layer.RECIPE, Layer.BROWSERS, Layer.VALUE, Layer.AUCTIONS, Layer.FLIPS,
                    Layer.FORGE, Layer.AH_FLIPS, Layer.CALCULATOR, Layer.GARDEN_PLOTS,
                    Layer.ESSENCE_SHOP, Layer.MISSING_SHARDS));

    /**
     * True while the container GUI itself was clicked last: the windows then render <b>behind</b>
     * the container content until one of them is clicked (or opened) again – exactly like raising
     * the "inventory window" on a desktop.
     */
    private static boolean containerRaised;

    /** Layers that have already reported a render failure, so the log is not written 60 times a second. */
    private static final java.util.Set<Layer> reportedFailures =
            java.util.EnumSet.noneOf(Layer.class);

    /**
     * Renders one layer, containing anything it throws.
     *
     * <p>These layers run inside the screen render mixin, so before this an exception in <b>any</b>
     * window crashed the client outright – and took every other window down with it, including the
     * ones that were working. That is a wildly disproportionate failure for an optional overlay: the
     * cost of a broken window should be a missing window, not a lost session. A real instance of it
     * (a null field read on the first frame the Bazaar opened) is what prompted this.
     *
     * <p>The failure is logged once per layer per session, with the stack trace, so it is still loud
     * enough to fix while a per-frame path stays quiet.
     */
    public static void render(Layer layer, Runnable draw) {
        try {
            draw.run();
        } catch (Throwable t) {
            if (reportedFailures.add(layer)) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.error(
                        "[SBS] The {} window failed to render and has been skipped. Further failures "
                                + "of this window are not logged.", layer, t);
            }
        }
    }

    private FloatingWindows() {
    }

    public static boolean isContainerRaised() {
        return containerRaised;
    }

    /** Brings the container above every floating window (a click landed on the container). */
    public static void raiseContainer() {
        containerRaised = true;
    }

    /** The layers bottom → top, for rendering. */
    public static List<Layer> renderOrder() {
        return List.copyOf(order);
    }

    /** The layers top → bottom, for input routing (topmost gets first pick). */
    public static List<Layer> clickOrder() {
        List<Layer> reversed = new ArrayList<>(order);
        Collections.reverse(reversed);
        return reversed;
    }

    /** Moves the layer above the others (called on click into it or when it opens a window). */
    public static void raise(Layer layer) {
        containerRaised = false; // interacting with a window puts the windows back in front
        if (order.remove(layer)) {
            order.add(layer);
        }
    }
}
