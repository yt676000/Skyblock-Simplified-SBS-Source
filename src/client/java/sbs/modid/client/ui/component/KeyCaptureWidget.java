/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.component;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

/**
 * A control that is waiting for the player to press the input it should bind.
 *
 * <p>Exists for one reason: <b>the wheel is bindable, and the wheel is also how every list on the
 * screen scrolls.</b> A key press reaches a listening widget on its own (the screen routes keys to
 * whatever holds focus), but a wheel notch is claimed by the settings list, the action list or an
 * open dropdown long before any widget sees it. So a listening capture claims the wheel wherever
 * the cursor is – the same rule an open dropdown already follows for clicks – and the host asks
 * {@link #claimScroll} first in its {@code mouseScrolled}.
 *
 * <p>Only one control can be listening at a time (clicking a second one unfocuses the first), so
 * "whichever is listening" is never ambiguous.
 */
public interface KeyCaptureWidget {

    /** Whether this control is waiting for the next input to bind. */
    boolean isListening();

    /**
     * Binds a wheel direction, ending the capture.
     *
     * @param scrollY the wheel delta; positive is up
     * @return {@code true} when the wheel was bound, {@code false} when this control refuses the
     *         wheel (a hold-style binding has nothing to hold). Either way the capture ends and the
     *         wheel is consumed – the list underneath must not scroll out from under the player.
     */
    boolean captureScroll(double scrollY);

    /**
     * The text a listening field shows: the full hint when it fits in {@code maxWidth}, else a
     * shorter one, else the bare "..." – narrow fields must not overflow their card.
     */
    static Component listeningHint(Font font, int maxWidth) {
        for (String hint : new String[] {"Press a key · Esc: unbind", "Esc: unbind"}) {
            if (font.width(hint) <= maxWidth) {
                return Component.literal(hint);
            }
        }
        return Component.literal("...");
    }

    /**
     * Cancels a listening capture when the click lands outside it. A click on empty space reaches
     * no widget and moves no focus, so without this the field would keep waiting; hosts call it
     * first in their {@code mouseClicked}. The click itself is not consumed.
     */
    static void cancelOnOutsideClick(Iterable<? extends GuiEventListener> widgets, double x, double y) {
        for (GuiEventListener widget : widgets) {
            if (widget instanceof KeyCaptureWidget capture && capture.isListening()
                    && !widget.isMouseOver(x, y)) {
                widget.setFocused(false);
            }
        }
    }

    /**
     * Offers the wheel to whichever of {@code widgets} is listening.
     *
     * @return {@code true} when a capture took it and the host should stop
     */
    static boolean claimScroll(Iterable<? extends GuiEventListener> widgets, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        for (GuiEventListener widget : widgets) {
            if (widget instanceof KeyCaptureWidget capture && capture.isListening()) {
                capture.captureScroll(scrollY);
                return true;
            }
        }
        return false;
    }
}
