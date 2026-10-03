/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.loadouts;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;

/**
 * The equipped loadout as a HUD widget: <b>the SBS Loadouts card for that slot, one to one.</b>
 *
 * <p>There is no second layout here to drift out of step with the grid - the widget measures a card
 * rectangle and hands it to {@link LoadoutsOverlay#renderCard}, which runs the grid's own drawing
 * code inside it. Pet chip with its nametag, HOTF / HOTM / Power Stone / Tuning down the left,
 * Necklace / Cloak / Belt / Gloves down the right, your player model in the middle, the four armor
 * pieces along the bottom, the green glow when it is the one you are wearing. Only the two things
 * you would click are left off: the rename pencil and the page chip.
 *
 * <p>Which loadout that is comes from {@link LoadoutsOverlay#equipped()}: the helmet on your head
 * matched against each cached loadout's own helmet, so it is right after a restart and after
 * equipping outside the overlay. Until one matches, the card is built from the gear on your body,
 * so the widget is never blank while you are wearing something.
 *
 * <p>Movable, scalable and hideable like every SBS card ({@link HudElement#EQUIPPED_LOADOUT}).
 */
public final class LoadoutHud {

    /**
     * Card width on the HUD. The height follows the grid's own proportion ({@code width + 60}), so
     * the widget is the same card shape you see in the menu; the GUI editor's scale resizes it.
     */
    private static final int CARD_W = 120;

    private LoadoutHud() {
    }

    /**
     * Draws the widget, or nothing at all: it is self-gating on its mode, the editor's hide toggle,
     * and there being a loadout (or gear) to show.
     */
    public static void render(GuiGraphicsExtractor g) {
        LoadoutWidgetMode mode = ConfigManager.getInstance().get().skyblockMenu.loadoutWidget;
        if (mode == null || !mode.shows() || HudLayout.isHidden(HudElement.EQUIPPED_LOADOUT)) {
            return;
        }
        LoadoutsOverlay overlay = LoadoutsOverlay.getInstance();
        LoadoutsOverlay.Equipped loadout = overlay.equipped();
        if (loadout == null) {
            return;
        }
        HudElement.Bounds b = HudElement.EQUIPPED_LOADOUT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        int w = CARD_W;
        int h = CARD_W + 60;

        HudLayout.measure(HudElement.EQUIPPED_LOADOUT, x, y, w, h);
        // Where the card really ends up: the same offset + scale the GUI editor applies, resolved
        // for the rectangle just measured. The card's own drawing rides the pose stack, but the
        // player model cannot (its render state takes screen coordinates and ignores the matrix),
        // so it has to be told where the card went.
        HudElement.Bounds shown = HudLayout.displayBounds(HudElement.EQUIPPED_LOADOUT,
                g.guiWidth(), g.guiHeight());
        float[] screen = {shown.x(), shown.y(), shown.w() / (float) w};

        HudLayout.begin(g, HudElement.EQUIPPED_LOADOUT);
        overlay.renderCard(g, loadout, x, y, w, h, screen);
        HudLayout.end(g);
    }
}
