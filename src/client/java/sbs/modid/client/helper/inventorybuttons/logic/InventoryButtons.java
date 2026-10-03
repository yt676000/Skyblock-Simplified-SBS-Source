/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventorybuttons.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The Inventory Buttons module: the button list, the edge-snapping layout rule, and running a
 * button's command.
 *
 * <p>Kept separate from both the editor and the live overlay so the two cannot disagree about where
 * a button sits – the editor snaps with the same call the overlay draws with, which is what makes
 * "what you place is what you get" true rather than approximately true.
 *
 * <p>Layout is in {@link InventoryButton container-relative} coordinates throughout; only the
 * renderer adds the GUI origin.
 */
public final class InventoryButtons {

    /** Default edge length of a button, matching a slot so it sits naturally against the inventory. */
    public static final int SIZE = 18;

    /**
     * Size limits for free resizing. The minimum keeps a button big enough to still grab its "…" badge
     * and resize grip; the maximum only exists so a mis-drag cannot produce a button covering the
     * whole screen with no way back to it.
     */
    public static final int MIN_SIZE = 10;
    public static final int MAX_SIZE = 128;

    /** Gap kept between a button snapped outside the panel and the panel itself. */
    public static final int GAP = 2;

    /** How close (in pixels) a drag must get to a snap line before it takes. */
    private static final int SNAP_DISTANCE = 6;

    private InventoryButtons() {
    }

    private static SBSConfig.InventoryButtonsSettings cfg() {
        return ConfigManager.getInstance().get().inventoryButtons;
    }

    /** Whether the module is on. */
    public static boolean enabled() {
        return cfg().enabled;
    }

    /** All configured buttons (never null). */
    public static List<InventoryButton> all() {
        SBSConfig.InventoryButtonsSettings cfg = cfg();
        if (cfg.buttons == null) {
            cfg.buttons = new ArrayList<>();
        }
        return cfg.buttons;
    }

    /** Adds a button parked just outside the panel's right edge and persists it. */
    public static InventoryButton add(int panelWidth) {
        InventoryButton button = new InventoryButton("Button " + (all().size() + 1),
                panelWidth + GAP, 0);
        all().add(button);
        save();
        return button;
    }

    public static void remove(InventoryButton button) {
        all().remove(button);
        save();
    }

    public static void save() {
        ConfigManager.getInstance().save();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /**
     * Snaps a coordinate to the panel's edges when it lands close to one.
     *
     * <p>The candidate lines are the four ways a button relates to an edge: parked just outside it,
     * or flush with it from the inside. Snapping each axis independently is what lets a button hug
     * the right edge while still sliding freely up and down.
     *
     * @param value  the dragged coordinate, relative to the panel's top-left
     * @param extent the panel's width (for x) or height (for y)
     * @param size   the button's own extent on that axis – buttons are sized individually, so the
     *               "flush from the inside" and "parked outside" lines differ per button
     */
    public static int snap(int value, int extent, int size) {
        int[] targets = {-size - GAP, 0, extent - size, extent + GAP};
        int best = value;
        int bestDistance = SNAP_DISTANCE + 1;
        for (int target : targets) {
            int distance = Math.abs(value - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = target;
            }
        }
        return best;
    }

    /** Whether {@code (relX, relY)} – panel-relative – is inside the given button. */
    public static boolean hits(InventoryButton button, double relX, double relY) {
        return relX >= button.x && relX < button.x + button.width()
                && relY >= button.y && relY < button.y + button.height();
    }

    /** The topmost button under a panel-relative point, or {@code null}. Later buttons win. */
    public static InventoryButton at(double relX, double relY) {
        List<InventoryButton> buttons = all();
        for (int i = buttons.size() - 1; i >= 0; i--) {
            if (hits(buttons.get(i), relX, relY)) {
                return buttons.get(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Action
    // ------------------------------------------------------------------

    /**
     * Runs a button's command. Closes the inventory first: most commands open another menu, and
     * sending one while a container is still open leaves the client and server disagreeing about
     * which screen is up.
     */
    public static void run(InventoryButton button) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !button.runnable()) {
            return;
        }
        Minecraft.getInstance().setScreenAndShow(null);
        player.connection.sendCommand(button.cleanCommand());
    }
}
