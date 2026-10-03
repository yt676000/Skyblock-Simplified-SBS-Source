/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import sbs.modid.client.core.util.PlainText;

import java.util.Locale;

/**
 * The open container screen's title, worked out once instead of once per feature per frame.
 *
 * <p><b>What this replaces.</b> Nearly every overlay and decorator answers "is this my menu?" from
 * the title, and each of them used to derive it for itself:
 * {@code screen.getTitle().getString()}, then a colour strip, then {@code toLowerCase}, then a
 * {@code contains}. On a Bazaar page that ran six times a frame before anything was drawn, and one
 * of them - the Accessory Bag button - ran a regex {@code replaceAll} to do the stripping. None of
 * those calls could see that the five beside it had just computed the same string.
 *
 * <p><b>Why caching on the screen is correct.</b> A container's title is fixed when the screen is
 * constructed, and Hypixel changes a menu's title by re-opening the window - which makes a new
 * screen. So the cache is keyed on the screen instance plus the menu's {@code containerId}: either
 * changing means a different menu and a fresh read. Nothing that varies within a screen's life -
 * slot contents, the state id - is cached here, precisely because it varies.
 *
 * <p><b>Render thread only.</b> One entry, no synchronisation, no expiry. Every caller is inside a
 * frame on the render thread; the cost of making it thread-safe would be paid on the hot path to
 * protect a case that does not exist.
 */
public final class MenuFrame {

    /** The last screen asked about. One entry is enough: only one container is open at a time. */
    private static MenuFrame current;

    private final AbstractContainerScreen<?> screen;
    private final int containerId;

    private String title;
    private String normalised;

    private MenuFrame(AbstractContainerScreen<?> screen) {
        this.screen = screen;
        this.containerId = screen.getMenu().containerId;
    }

    /** The frame for {@code screen}, reusing the cached one while the same menu is open. */
    public static MenuFrame of(AbstractContainerScreen<?> screen) {
        MenuFrame cached = current;
        if (cached != null && cached.screen == screen
                && cached.containerId == screen.getMenu().containerId) {
            return cached;
        }
        MenuFrame frame = new MenuFrame(screen);
        current = frame;
        return frame;
    }

    /** The screen this describes. */
    public AbstractContainerScreen<?> screen() {
        return screen;
    }

    /** The raw title, colour codes and all, exactly as {@code Component#getString} returns it. */
    public String title() {
        String value = title;
        if (value == null) {
            value = screen.getTitle() == null ? "" : screen.getTitle().getString();
            title = value;
        }
        return value;
    }

    /**
     * The title stripped of colour codes, trimmed and lowercased - the form every "is this my menu?"
     * test wants. Ask for this one, not {@link #title()}, unless the codes themselves matter.
     */
    public String normalised() {
        String value = normalised;
        if (value == null) {
            value = PlainText.strip(title()).trim().toLowerCase(Locale.ROOT);
            normalised = value;
        }
        return value;
    }

    /**
     * Whether the normalised title contains {@code needle}, which must already be lowercase - this
     * is the hot path, and lowercasing a constant on every frame is the cost this class exists to
     * remove.
     */
    public boolean titleContains(String needle) {
        return normalised().contains(needle);
    }
}
