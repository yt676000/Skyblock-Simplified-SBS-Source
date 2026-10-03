/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One sub-page of the profile viewer (Combat ▸ Dungeons, Inventory ▸ Wardrobe, ...).
 *
 * <p>A page owns only its own selection state and draws strictly inside {@link PvContext}'s
 * rectangle; the shell owns the window, the navigation and the scroll wheel. Adding a page is
 * therefore one class plus one line in {@link PvNav} – nothing else in the viewer changes.
 */
public interface PvPage {

    void render(GuiGraphicsExtractor g, PvContext ctx);

    /** Returns true when the click was consumed. */
    default boolean mouseClicked(PvContext ctx, double mouseX, double mouseY) {
        return false;
    }

    /** Mouse drag with the button held, in screen pixels. Returns true when consumed. */
    default boolean mouseDragged(PvContext ctx, double mouseX, double mouseY,
                                 double dragX, double dragY) {
        return false;
    }

    /** Called when the page is left, so stale selections do not reopen with it. */
    default void reset() {
    }
}
