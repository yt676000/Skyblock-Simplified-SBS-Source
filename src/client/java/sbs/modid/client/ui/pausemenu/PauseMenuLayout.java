/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.pausemenu;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the saved per-button layout to a freshly built pause menu.
 *
 * <p><b>Why this works at all.</b> {@code PauseScreen.createPauseMenu} puts <i>everything</i> – the
 * "Game" heading included – into one {@code GridLayout}, arranges it, centres it, and then hands
 * every element to {@code addRenderableWidget}. So the entire menu is nothing but screen children,
 * and repositioning a child repositions the menu: no pose-stack translation, no render hook, no
 * fighting the layout code.
 *
 * <p><b>Anchored, not accumulated.</b> Each widget's freshly-laid-out geometry is captured once per
 * screen open ({@link #capture}) and every {@link PauseButtonStyle} is applied as {@code base +
 * offset}. Nudging the widgets directly would accumulate rounding drift over a long drag and slowly
 * shear the layout – and storing absolute positions instead would pin the menu to whatever window
 * size it was edited in, since vanilla re-centres the grid for every screen size.
 *
 * <p>The SBS button is captured like everything else, so it is editable in {@link PauseMenuEditor}
 * alongside the vanilla buttons. Its "base" is the default bottom-left corner it puts itself at
 * before the capture runs.
 */
public final class PauseMenuLayout {

    /** One widget with the geometry the layout gave it, before any SBS offset or resize. */
    public record Anchored(AbstractWidget widget, String id, int baseX, int baseY,
                           int baseWidth, int baseHeight) {
    }

    private final List<Anchored> anchored = new ArrayList<>();

    /** The captured widgets, in screen order – what the pause-menu editor edits. */
    public List<Anchored> captured() {
        return anchored;
    }

    /**
     * Records the freshly built layout and binds each widget to its {@link PauseButtonStyle} key.
     * Call at the end of {@code init()}, <b>after</b> every widget (the SBS button included) has been
     * added and put at its own default position.
     */
    public void capture(List<? extends GuiEventListener> children) {
        anchored.clear();
        PauseMenuStyles.clearBindings();
        for (GuiEventListener child : children) {
            if (!(child instanceof AbstractWidget widget)) {
                continue;
            }
            String id = PauseMenuStyles.idOf(widget);
            PauseMenuStyles.bind(widget, id);
            anchored.add(new Anchored(widget, id, widget.getX(), widget.getY(),
                    widget.getWidth(), widget.getHeight()));
        }
    }

    /**
     * Sizes and positions every captured widget from its {@link PauseButtonStyle}, clamped so nothing
     * can end up off screen. Resizing happens before positioning because the clamp depends on the
     * live size.
     */
    public void apply(int screenWidth, int screenHeight) {
        for (Anchored entry : anchored) {
            PauseButtonStyle style = PauseMenuStyles.get(entry.id());
            AbstractWidget widget = entry.widget();
            widget.setSize(style.width(entry.baseWidth()), style.height(entry.baseHeight()));
            if (widget instanceof PauseMenuButton sbsButton) {
                // SBS draws this one itself, so its corner radius and its label (which shortens when
                // the button gets too narrow) have to be pushed rather than read off the style by the
                // widget renderer.
                sbsButton.applyStyle();
            }
            widget.setX(Math.clamp(entry.baseX() + style.dx, 0,
                    Math.max(0, screenWidth - widget.getWidth())));
            widget.setY(Math.clamp(entry.baseY() + style.dy, 0,
                    Math.max(0, screenHeight - widget.getHeight())));
        }
    }
}
