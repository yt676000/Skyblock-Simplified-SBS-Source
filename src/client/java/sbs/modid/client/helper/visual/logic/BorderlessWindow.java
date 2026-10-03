/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.logic;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.PointerBuffer;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Borderless window mode (Visuals): strips the window decorations and stretches the window over the
 * monitor it is on – alt-tab friendly "fullscreen" without the exclusive-fullscreen mode switch.
 *
 * <p>Pure GLFW: {@code GLFW_DECORATED} off + position/size set to the monitor bounds; turning it off
 * restores the decorations and the exact windowed rectangle from before. Skipped entirely while the
 * game is in real fullscreen (F11) – the two modes would fight over the window. Applied on toggle
 * and once at startup (deferred to the client thread, after the window exists).
 *
 * <h2>Why the window is one pixel too tall on Windows</h2>
 * An undecorated window sized <i>exactly</i> to a monitor is what Windows looks for when it decides
 * a program is running fullscreen, and it then manages it like one: pressing the Windows key, or
 * anything else that takes the foreground, minimises the game instead of just leaving it behind the
 * Start menu. Making the window a single pixel taller than the monitor fails that test, so Windows
 * treats it as the ordinary window it is – and the extra row of pixels sits below the bottom edge of
 * the screen, where nothing is visible anyway. {@code GLFW_AUTO_ICONIFY} is switched off for the
 * same reason on the GLFW side.
 *
 * <p>Because "the window changed size on its own" can also come from the desktop environment (a
 * resolution or DPI change, a monitor waking up), {@link #onClientTick} re-asserts the rectangle
 * whenever it drifts. It deliberately does <b>not</b> un-minimise the window: a minimised game is
 * something the player may well have asked for.
 */
public final class BorderlessWindow {

    /** Windows is the only platform with the fullscreen-detection behaviour described above. */
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    private static boolean applied;
    private static int restoreX;
    private static int restoreY;
    private static int restoreW;
    private static int restoreH;

    /** The rectangle borderless mode last asked for – what {@link #onClientTick} holds the window to. */
    private static int targetX;
    private static int targetY;
    private static int targetW;
    private static int targetH;

    private BorderlessWindow() {
    }

    /** Applies the persisted toggle once at startup (no-op when off). */
    public static void init() {
        if (ConfigManager.getInstance().get().visuals.borderlessWindow) {
            Minecraft.getInstance().execute(() -> apply(true));
        }
    }

    /** Settings-row toggle handler: flips the config and applies it immediately. */
    public static void toggle() {
        var visuals = ConfigManager.getInstance().get().visuals;
        visuals.borderlessWindow = !visuals.borderlessWindow;
        ConfigManager.getInstance().save();
        apply(visuals.borderlessWindow);
    }

    /** Puts the window into (or out of) borderless mode. Client thread only. */
    public static void apply(boolean on) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Window window = minecraft.getWindow();
            if (window == null || window.isFullscreen()) {
                return; // real fullscreen owns the window - the toggle applies after leaving it
            }
            long handle = window.handle();
            if (on && !applied) {
                restoreX = window.getX();
                restoreY = window.getY();
                restoreW = window.getWidth();
                restoreH = window.getHeight();
                long monitor = monitorUnder(window);
                GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
                if (mode == null) {
                    return;
                }
                int[] mx = new int[1];
                int[] my = new int[1];
                GLFW.glfwGetMonitorPos(monitor, mx, my);
                targetX = mx[0];
                targetY = my[0];
                targetW = mode.width();
                // The overhanging pixel that keeps Windows from managing this as a fullscreen app.
                targetH = mode.height() + (WINDOWS ? 1 : 0);
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                // Only meaningful for real fullscreen windows, but harmless here and free insurance
                // against GLFW itself iconifying the game the moment it loses focus.
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_FALSE);
                GLFW.glfwSetWindowPos(handle, targetX, targetY);
                GLFW.glfwSetWindowSize(handle, targetW, targetH);
                applied = true;
            } else if (!on && applied) {
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_TRUE);
                GLFW.glfwSetWindowPos(handle, restoreX, restoreY);
                GLFW.glfwSetWindowSize(handle, Math.max(320, restoreW), Math.max(240, restoreH));
                applied = false;
                // The decorations are back, so there is a title bar to paint again - and the frame
                // Windows just rebuilt is not guaranteed to have kept our caption colours.
                WindowTitleBar.refresh();
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] borderless window toggle failed", t);
        }
    }

    /**
     * Holds the window to the borderless rectangle: if something outside the game resized or moved
     * it, put it back. A <b>minimised</b> window is left alone – its reported geometry is meaningless
     * while iconified, and forcing it back up would override the player closing it to the taskbar.
     */
    public static void onClientTick() {
        if (!applied || !ConfigManager.getInstance().get().visuals.borderlessWindow) {
            return;
        }
        try {
            Window window = Minecraft.getInstance().getWindow();
            if (window == null || window.isFullscreen()) {
                return;
            }
            long handle = window.handle();
            if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
                return;
            }
            if (window.getX() == targetX && window.getY() == targetY
                    && window.getWidth() == targetW && window.getHeight() == targetH) {
                return;
            }
            GLFW.glfwSetWindowPos(handle, targetX, targetY);
            GLFW.glfwSetWindowSize(handle, targetW, targetH);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] borderless window re-apply failed", t);
        }
    }

    /** The monitor whose area contains the window centre; primary monitor when none matches. */
    private static long monitorUnder(Window window) {
        long primary = GLFW.glfwGetPrimaryMonitor();
        int cx = window.getX() + window.getWidth() / 2;
        int cy = window.getY() + window.getHeight() / 2;
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null) {
            return primary;
        }
        for (int i = 0; i < monitors.limit(); i++) {
            long monitor = monitors.get(i);
            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
            if (mode == null) {
                continue;
            }
            int[] mx = new int[1];
            int[] my = new int[1];
            GLFW.glfwGetMonitorPos(monitor, mx, my);
            if (cx >= mx[0] && cx < mx[0] + mode.width() && cy >= my[0] && cy < my[0] + mode.height()) {
                return monitor;
            }
        }
        return primary;
    }
}
