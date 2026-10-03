/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.recipe.ui.RecipeOverlay;
import sbs.modid.client.skills.garden.logic.PestTracker;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.window.EdgeSnap;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.ui.window.WindowMemory;
import sbs.modid.client.ui.window.WindowResizer;

import java.util.Set;

/**
 * The Garden plot grid as a floating window over container screens - the same shell the Recipe
 * Viewer and Calculator use: draggable by its header, Ctrl+scroll or edge-drag to resize, never
 * closed but minimized to a small button beside the search bar, z-ordered through
 * {@code FloatingWindows}.
 *
 * <p><b>Why a window and not just the standalone screen.</b> The grid is something you consult while
 * doing something else - checking which plot the pests are on while the composter menu is open, or
 * warping out of the desk. A screen you must close first to see your inventory is the wrong shape
 * for that; a window that sits beside the inventory is the right one. The standalone screen stays
 * for the in-world hotkey, and both draw the identical grid through {@link GardenPlotsGrid}.
 *
 * <p>Clicking a plot closes the open container before sending {@code /plottp}: Hypixel will not warp
 * you while a menu is up, and a click that silently does nothing is worse than one that costs a
 * screen close.
 */
public final class GardenPlotsOverlay {

    private static final GardenPlotsOverlay INSTANCE = new GardenPlotsOverlay();

    private static final int WIN_HEADER = 16;
    private static final int WIN_PAD = 6;
    private static final int WIN_MARGIN = 2;
    private static final int WIN_MIN_W = 130;
    private static final int WIN_MAX_W = 420;
    private static final int WIN_MIN_H = 130;
    private static final int WIN_MAX_H = 460;
    private static final int BTN = 14;

    private int winX = Integer.MIN_VALUE;
    private int winY = Integer.MIN_VALUE;
    private int winW = 170;
    private int winH = 210;
    private boolean minimized = true;
    private boolean draggingWindow;
    private double grabDX;
    private double grabDY;

    private final WindowResizer windowResizer = new WindowResizer();

    /** Where the player left the window: position, size and whether it was collapsed. */
    private final WindowMemory memory = new WindowMemory(FloatingWindows.Layer.GARDEN_PLOTS);

    private GardenPlotsOverlay() {
    }

    public static GardenPlotsOverlay getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.GardenPlotsSettings cfg() {
        return ConfigManager.getInstance().get().gardenPlots;
    }

    /**
     * The window only exists on the Garden. Everywhere else it would be a dead panel taking up the
     * inventory's edge, so it is not drawn and claims no clicks.
     */
    public boolean active() {
        return cfg().enabled && cfg().showInInventory && PestTracker.getInstance().onGarden();
    }

    /** Opens the window if it was minimized - what the hotkey does while a container is open. */
    public void restore() {
        // Adopt the remembered state first: it carries a "minimized" of its own, and applied after
        // this it would collapse the window the hotkey was pressed to open.
        restoreRemembered();
        minimized = false;
        rememberWindow();
    }

    public boolean isMinimized() {
        return minimized;
    }

    // ------------------------------------------------------------------ geometry

    private int[] windowRect(Screen screen) {
        // Every path into the window's geometry comes through here, so this is where the remembered
        // spot is adopted - before the "never placed" default below can claim it.
        restoreRemembered();
        winW = clamp(winW, WIN_MIN_W, Math.max(WIN_MIN_W, screen.width - WIN_MARGIN * 2));
        winH = clamp(winH, WIN_MIN_H, Math.max(WIN_MIN_H, screen.height - WIN_MARGIN * 2));
        if (winX == Integer.MIN_VALUE) {
            // Default spot: left edge, clear of the Recipe Viewer's default place on the right.
            winX = 8;
            winY = 18;
        }
        winX = clamp(winX, WIN_MARGIN, screen.width - winW - WIN_MARGIN);
        winY = clamp(winY, WIN_MARGIN, screen.height - winH - WIN_MARGIN);
        return new int[]{winX, winY, winW, winH};
    }

    private boolean inWindow(Screen screen, double mx, double my) {
        int[] r = windowRect(screen);
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private int minimizeX(Screen screen) {
        int[] r = windowRect(screen);
        return r[0] + r[2] - 14;
    }

    private boolean inMinimizeBox(Screen screen, double mx, double my) {
        int[] r = windowRect(screen);
        return mx >= minimizeX(screen) && mx < minimizeX(screen) + 12
                && my >= r[1] + 2 && my < r[1] + WIN_HEADER;
    }

    /**
     * The reopen button sits two slots left of the search bar: the Recipe Viewer takes the right
     * side and the Calculator the first left slot, and three minimized windows must not stack.
     */
    private int[] reopenRect(Screen screen) {
        RecipeOverlay recipe = RecipeOverlay.getInstance();
        return new int[]{recipe.barX(screen) - (BTN + 4) * 2, recipe.barY(screen), BTN, BTN};
    }

    private boolean inReopenButton(Screen screen, double mx, double my) {
        int[] b = reopenRect(screen);
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    /** The grid's drawing area inside the window: {@code {x, y, w, h}}. */
    private int[] contentRect(Screen screen) {
        int[] r = windowRect(screen);
        int x = r[0] + WIN_PAD;
        int y = r[1] + WIN_HEADER + 3;
        return new int[]{x, y, r[2] - WIN_PAD * 2, r[1] + r[3] - WIN_PAD - y};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,
                com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,
                        com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL);
    }

    /** Whether the cursor sits over the window, so the container suppresses its own tooltip. */
    public boolean coversForTooltip(AbstractContainerScreen<?> screen, double mx, double my) {
        if (!active()) {
            return false;
        }
        return minimized ? inReopenButton(screen, mx, my) : inWindow(screen, mx, my);
    }

    // ------------------------------------------------------------------ rendering

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!active()) {
            return;
        }
        // Before the collapsed branch: a window remembered as open is drawn as open on its first
        // frame, and while collapsed nothing else here reaches the geometry that would restore it.
        restoreRemembered();
        if (minimized) {
            drawReopenButton(screen, g, mouseX, mouseY);
            return;
        }
        Font font = Minecraft.getInstance().font;
        int[] r = windowRect(screen);
        windowResizer.renderGrips(g, r[0], r[1], r[2], r[3], mouseX, mouseY);
        SciFiRender.glow(g, r[0], r[1], r[2], r[3], SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, r[0], r[1], r[2], r[3], SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, r[0] + 1, r[1] + 1, r[2] - 2, r[3] - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int textY = r[1] + (WIN_HEADER - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal("Garden Plots"), r[0] + WIN_PAD, textY, SBSTheme.ACCENT);
        boolean minimizeHover = inMinimizeBox(screen, mouseX, mouseY);
        g.text(font, Component.literal("-"), minimizeX(screen) + 4, textY,
                minimizeHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
        g.fill(r[0] + WIN_PAD, r[1] + WIN_HEADER, r[0] + r[2] - WIN_PAD, r[1] + WIN_HEADER + 1,
                SBSTheme.ACCENT_SOFT);

        int[] c = contentRect(screen);
        GardenPlotsGrid.draw(g, font, c[0], c[1], c[2], c[3], mouseX, mouseY, true);
    }

    /** Small grid-glyph button beside the search bar that restores the minimized window. */
    private void drawReopenButton(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                                  int mouseX, int mouseY) {
        int[] b = reopenRect(screen);
        boolean hover = inReopenButton(screen, mouseX, mouseY);
        Set<Integer> infested = PestTracker.getInstance().infestedPlots();
        // A pest waiting on a plot is the one thing worth pulling the eye to a closed window.
        int border = !infested.isEmpty() ? 0xFFFF6060
                : hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, b[0], b[1], b[2], b[3], SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, border);
        Font font = Minecraft.getInstance().font;
        String glyph = "▦";   // ▦ - a plot grid
        g.text(font, Component.literal(glyph), b[0] + (b[2] - font.width(glyph)) / 2,
                b[1] + (b[3] - font.lineHeight) / 2 + 1,
                !infested.isEmpty() ? 0xFFFF6060 : SBSTheme.TEXT);
    }

    // ------------------------------------------------------------------ input

    /** Window input, dispatched through the {@code FloatingWindows} z-order. */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!active()) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (minimized) {
            if (inReopenButton(screen, mx, my)) {
                minimized = false;
                rememberWindow();
                return true;
            }
            return false;
        }
        if (!inWindow(screen, mx, my)) {
            return false;   // outside: the container stays fully usable
        }
        if (event.button() != 0) {
            return true;    // right-clicks inside the window never reach a slot
        }
        if (inMinimizeBox(screen, mx, my)) {
            minimized = true;
            rememberWindow();
            return true;
        }
        int[] r = windowRect(screen);
        if (windowResizer.begin(mx, my, r[0], r[1], r[2], r[3])) {
            return true;
        }
        if (my < r[1] + WIN_HEADER) {
            draggingWindow = true;
            grabDX = mx - r[0];
            grabDY = my - r[1];
            return true;
        }
        int[] c = contentRect(screen);
        GardenPlotsGrid.click(c[0], c[1], c[2], c[3], mx, my);
        return true;
    }

    /** Ctrl+scroll resizes; anything else inside the window is swallowed so the container ignores it. */
    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY,
                                double scrollY) {
        if (!active() || scrollY == 0 || minimized || !inWindow(screen, mouseX, mouseY)) {
            return false;
        }
        if (isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            winW = clamp(winW + step * 20, WIN_MIN_W, WIN_MAX_W);
            winH = clamp(winH + step * 20, WIN_MIN_H, WIN_MAX_H);
            rememberWindow();
        }
        return true;
    }

    public boolean handleDrag(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!active()) {
            return false;
        }
        if (windowResizer.isActive()) {
            int[] rect = windowResizer.drag(event.x(), event.y(), WIN_MIN_W, WIN_MAX_W,
                    WIN_MIN_H, WIN_MAX_H);
            winX = rect[0];
            winY = rect[1];
            winW = rect[2];
            winH = rect[3];
            windowRect(screen);
            return true;
        }
        if (!draggingWindow) {
            return false;
        }
        winX = (int) (event.x() - grabDX);
        winY = (int) (event.y() - grabDY);
        int[] snapped = EdgeSnap.toBorders(winX, winY, winW, winH, screen.width, screen.height, WIN_MARGIN);
        winX = snapped[0];
        winY = snapped[1];
        windowRect(screen);
        return true;
    }

    public boolean handleRelease() {
        boolean wasResizing = windowResizer.end();
        if (!draggingWindow && !wasResizing) {
            return false;
        }
        draggingWindow = false;
        rememberWindow();
        return true;
    }

    /** Takes the window back to where the player last left it (first call only). */
    private void restoreRemembered() {
        memory.restore(state -> {
            winX = state.x;
            winY = state.y;
            winW = state.width(winW);
            winH = state.height(winH);
            minimized = state.minimized;
        });
    }

    /** Persists position, size and collapsed state so the window comes back where it was left. */
    private void rememberWindow() {
        memory.remember(winX, winY, winW, winH, minimized);
    }
}
