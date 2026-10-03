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
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.User32;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.keybind.Keys;

import java.nio.IntBuffer;
import java.util.Locale;

/**
 * See-through Minecraft (Visuals): a key that makes the whole game window semi-transparent, so
 * whatever is behind it - a guide, a spreadsheet, a video - can be read without alt-tabbing away
 * from the game.
 *
 * <p><b>This is the real window, not a dark overlay.</b> Dimming the rendered frame would only ever
 * make the game darker; it cannot show what is underneath, which is the entire point. Windows can,
 * through {@code SetLayeredWindowAttributes}: give the window the {@code WS_EX_LAYERED} style and the
 * desktop compositor blends the whole thing - game, HUD, title bar - against the desktop for us, at
 * no cost to the frame rate.
 *
 * <h2>Reaching the API</h2>
 * The same route the {@linkplain WindowTitleBar title bar colours} take: the {@code HWND} behind
 * Minecraft's GLFW window from {@link GLFWNativeWin32#glfwGetWin32Window}, and LWJGL's own
 * {@link User32} bindings, which already ship with the game. No extra dependency and no bundled
 * native.
 *
 * <h2>Two ways to work it</h2>
 * {@link SBSConfig.VisualsSettings#opacityHold} picks between them. <b>Hold</b> is transparent only
 * while the key is down, which suits a glance at something; <b>press</b> toggles and stays, which
 * suits reading. Both are polled from the same client tick, so a key that is released while the
 * window is not focused is still noticed the moment it comes back.
 *
 * <h2>Getting the window back</h2>
 * Every path out restores full opacity: switching the feature off, unbinding the key, releasing it,
 * pressing again, and leaving the world. The slider also cannot be dragged below
 * {@link SBSConfig.VisualsSettings#MIN_WINDOW_OPACITY} - a window at zero is a window the player
 * cannot find to fix, and the setting that did it is behind it.
 *
 * <p><b>Windows only.</b> Nothing here runs on any other system, and the settings page says so
 * rather than offering a switch that does nothing. macOS and X11 can do this too, through entirely
 * different calls; neither is written yet.
 *
 * <p>Exclusive fullscreen has no layered window to blend, so the key does nothing there. Borderless
 * and windowed both work.
 */
public final class WindowOpacity {

    /** {@code LWA_ALPHA}: the alpha argument is the one to use (rather than a colour key). */
    private static final int LWA_ALPHA = 0x00000002;

    /** Fully opaque, as Windows counts it. */
    private static final int OPAQUE = 255;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Set once Windows refuses the call, so a failing setup is not asked again every tick. */
    private static boolean unsupported;

    /** Whether {@code WS_EX_LAYERED} has been put on the window by us. */
    private static boolean layered;

    /** The alpha currently on the window, so an unchanged value costs no call at all. */
    private static int appliedAlpha = OPAQUE;

    /** Whether the press-to-toggle mode currently has the window dimmed. */
    private static boolean toggledOn;

    /** The key state last tick, for the press edge - polling alone cannot tell a press from a hold. */
    private static boolean keyWasDown;

    private WindowOpacity() {
    }

    private static SBSConfig.VisualsSettings cfg() {
        return ConfigManager.getInstance().get().visuals;
    }

    /** Whether this system can do it at all - the settings page asks before offering the rows. */
    public static boolean supported() {
        return WINDOWS && !unsupported;
    }

    /** One line for the settings page: what this machine can and cannot do. */
    public static String statusLine() {
        if (!WINDOWS) {
            return "§8Windows only - other systems make windows see-through their own way";
        }
        if (unsupported) {
            return "§cWindows refused the call - the window stays solid on this setup";
        }
        if (cfg().opacityKey == 0) {
            return "§8Bind a key above, or nothing will happen";
        }
        return "§8Does nothing in exclusive fullscreen - there is no window to see through";
    }

    /** Whether the window is see-through right now, for the settings page. */
    public static boolean active() {
        return appliedAlpha < OPAQUE;
    }

    /**
     * Called once per client tick.
     *
     * <p>Polled rather than driven by a key event: the toggle has to survive a screen being open, and
     * a hold has to end even when the key came up while the game was not the focused window - which a
     * key-release event would never deliver.
     */
    public static void tick(Minecraft minecraft) {
        if (!WINDOWS || unsupported) {
            return;
        }
        SBSConfig.VisualsSettings cfg = cfg();
        if (!cfg.opacityEnabled || cfg.opacityKey == 0 || minecraft.getWindow() == null) {
            reset();
            return;
        }
        boolean down = Keys.isDown(cfg.opacityKey);
        boolean wanted;
        if (cfg.opacityHold) {
            wanted = down;
            toggledOn = false;   // so switching modes mid-hold cannot leave the toggle armed
        } else {
            if (down && !keyWasDown) {
                toggledOn = !toggledOn;
            }
            wanted = toggledOn;
        }
        keyWasDown = down;
        apply(wanted ? alpha(cfg.opacityPercent) : OPAQUE);
    }

    /** Puts the window back to solid and disarms the toggle - every way out of the feature. */
    public static void reset() {
        toggledOn = false;
        keyWasDown = false;
        apply(OPAQUE);
    }

    /**
     * Settings handler: applies a changed percentage immediately while the window is see-through, so
     * dragging the slider is its own preview rather than something to be tested afterwards.
     */
    public static void refresh() {
        if (active()) {
            apply(alpha(cfg().opacityPercent));
        }
    }

    /** A percentage as Windows counts alpha, clamped so the window can never be made invisible. */
    private static int alpha(int percent) {
        int clamped = Math.max(SBSConfig.VisualsSettings.MIN_WINDOW_OPACITY, Math.min(100, percent));
        return Math.max(1, Math.round(clamped * 255f / 100f));
    }

    /**
     * Writes an alpha onto the window, adding {@code WS_EX_LAYERED} the first time.
     *
     * <p>The style is added once and left on: taking it off and putting it back on every press makes
     * the compositor rebuild the window's backing surface, which flickers. A layered window at full
     * alpha is indistinguishable from an ordinary one.
     */
    private static void apply(int alpha) {
        // Guarded here as well as at every caller, and not only defensively: touching User32 at all
        // loads user32.dll, so a settings row clicked on Linux must not reach this method's body.
        if (!WINDOWS || unsupported || alpha == appliedAlpha) {
            return;
        }
        long hwnd = hwnd();
        if (hwnd == 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.callocInt(1);
            if (!layered) {
                long style = User32.GetWindowLongPtr(hwnd, User32.GWL_EXSTYLE);
                User32.SetWindowLongPtr(errcode, hwnd, User32.GWL_EXSTYLE,
                        style | User32.WS_EX_LAYERED);
                layered = true;
            }
            if (!User32.SetLayeredWindowAttributes(errcode, hwnd, 0, (byte) alpha, LWA_ALPHA)) {
                unsupported = true;
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS] window opacity refused by Windows (error {}) - leaving the window solid",
                        errcode.get(0));
                return;
            }
            appliedAlpha = alpha;
        } catch (Throwable t) {
            unsupported = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] window opacity unavailable - window left solid", t);
        }
    }

    /** The {@code HWND} behind Minecraft's GLFW window; {@code 0} before the window exists. */
    private static long hwnd() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Window window = minecraft == null ? null : minecraft.getWindow();
            if (window == null || window.handle() == 0L) {
                return 0L;
            }
            return GLFWNativeWin32.glfwGetWin32Window(window.handle());
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] could not reach the native window handle", t);
            return 0L;
        }
    }
}
