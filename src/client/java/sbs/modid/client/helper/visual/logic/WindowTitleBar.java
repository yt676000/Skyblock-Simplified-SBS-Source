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
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.WindowsLibrary;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.helper.visual.model.WindowButtonStyle;
import sbs.modid.client.ui.theme.SBSTheme;

import java.nio.IntBuffer;
import java.util.Locale;

/**
 * Custom Windows title bar (Visuals): repaints the strip Windows draws above the game – the one
 * carrying the window title and the minimise / maximise / close buttons – plus the thin frame
 * around the window, in colours of the player's choosing instead of the system white.
 *
 * <p>The title bar is drawn by the desktop compositor, not by the game, so no amount of rendering
 * inside Minecraft can touch it. What can is {@code DwmSetWindowAttribute} from {@code dwmapi.dll},
 * which since Windows 11 (build 22000) accepts a caption colour, a title-text colour and a border
 * colour per window. Those three attributes are exactly this feature.
 *
 * <h2>Reaching the API from Java</h2>
 * The window Minecraft owns is a {@code GLFWwindow*}; the compositor wants the {@code HWND} behind
 * it, which is what {@link GLFWNativeWin32#glfwGetWin32Window} returns. The call itself goes
 * through LWJGL's own foreign-call plumbing ({@link WindowsLibrary} to load the DLL, {@link JNI} to
 * invoke): every one of those pieces already ships with the game, so this stays a plain mod with no
 * extra dependency, no bundled native and none of the start-up flags a direct FFM binding would
 * need.
 *
 * <h2>What cannot be coloured</h2>
 * The minimise / maximise / close glyphs. Windows draws those itself and exposes no per-window
 * colour for them – {@code DWMWA_TEXT_COLOR} is the window <i>title</i> only. The single lever is
 * {@code DWMWA_USE_IMMERSIVE_DARK_MODE}, which picks light or dark glyphs, and that is the whole of
 * the "Window Buttons" setting ({@link WindowButtonStyle}); its {@code Auto} reads the caption
 * colour and picks the legible one, which is as close to a colour of their own as they get. Giving
 * them an arbitrary colour means giving up the system caption altogether and drawing our own over
 * an undecorated window, with the window dragging, snapping, double-click-maximise and per-monitor
 * DPI that Windows currently handles for free.
 *
 * <h2>Colour order</h2>
 * Windows takes a {@code COLORREF}, which is {@code 0x00BBGGRR} – red and blue the other way round
 * from the {@code 0xAARRGGBB} the rest of the mod uses. {@link #colorRef} is the only place that
 * conversion happens.
 *
 * <h2>Giving the colours back</h2>
 * Switching the feature off writes {@code DWMWA_COLOR_DEFAULT} to all three attributes, which hands
 * each one back to Windows rather than pinning it to a guessed white. The light/dark flag has no
 * such "default" value, so the value in force is read once <i>before</i> the first override and
 * written back on the way out.
 *
 * <p>Nothing here is visible while the game is in fullscreen or in
 * {@link BorderlessWindow borderless mode} – both leave the window without a caption to paint. The
 * attributes stay on the window regardless, so leaving either mode shows the colours again.
 */
public final class WindowTitleBar {

    /** {@code DWMWA_USE_IMMERSIVE_DARK_MODE}: light glyphs on the caption buttons. */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    /** {@code DWMWA_BORDER_COLOR}: the thin frame around the whole window. */
    private static final int DWMWA_BORDER_COLOR = 34;
    /** {@code DWMWA_CAPTION_COLOR}: the title bar itself. */
    private static final int DWMWA_CAPTION_COLOR = 35;
    /** {@code DWMWA_TEXT_COLOR}: the window title drawn on it. */
    private static final int DWMWA_TEXT_COLOR = 36;
    /** {@code DWMWA_COLOR_DEFAULT}: hands one colour back to Windows. */
    private static final int DWMWA_COLOR_DEFAULT = 0xFFFFFFFF;

    /** {@code S_OK} – anything else is a refusal, and on this API always a permanent one. */
    private static final int S_OK = 0;

    /** Returned by {@link #dwmGet} when the attribute could not be read. */
    private static final int UNREADABLE = Integer.MIN_VALUE;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static long setAttribute;
    private static long getAttribute;
    private static boolean nativeReady;
    private static boolean nativeFailed;

    /**
     * Set once Windows refuses a caption attribute – i.e. on Windows 10 and older, where the three
     * colours simply do not exist. Retrying every frame would be pointless, so the feature reports
     * itself unsupported from then on.
     */
    private static boolean unsupported;

    /** Whether our colours are currently on the window, and so whether there is anything to undo. */
    private static boolean applied;

    /** The light/dark flag as Windows had it before the first override; {@link #UNREADABLE} if unknown. */
    private static int originalDarkMode = UNREADABLE;

    private WindowTitleBar() {
    }

    /** Applies the persisted colours once at startup, after the window exists (no-op when off). */
    public static void init() {
        if (WINDOWS && ConfigManager.getInstance().get().visuals.titleBar) {
            Minecraft.getInstance().execute(WindowTitleBar::refresh);
        }
    }

    /** Settings-row toggle handler: flips the config and repaints (or restores) the caption. */
    public static void toggle() {
        var visuals = ConfigManager.getInstance().get().visuals;
        visuals.titleBar = !visuals.titleBar;
        ConfigManager.getInstance().save();
        refresh();
    }

    /**
     * Settings-row handler: puts the caption back to stock – SBS blue text and frame, and a caption
     * colour left empty, which is what makes it follow the theme rather than a fixed colour. The
     * buttons go back to {@code Auto} with them: a light/dark pick made for the colours being reset
     * away is no more wanted than the colours themselves.
     */
    public static void resetColors() {
        var visuals = ConfigManager.getInstance().get().visuals;
        visuals.titleBarColorHex = "";
        visuals.titleBarTextHex = SBSConfig.VisualsSettings.STOCK_TITLE_BAR_TEXT_COLOR;
        visuals.titleBarBorderHex = SBSConfig.VisualsSettings.STOCK_TITLE_BAR_BORDER_COLOR;
        visuals.titleBarButtons = WindowButtonStyle.AUTO;
        ConfigManager.getInstance().save();
        refresh();
    }

    /**
     * Puts the configured colours on the window, or takes ours back off when the feature is off.
     *
     * <p>Called from {@link SBSTheme#refreshFromConfig()} as well as from the rows here, so a title
     * bar left following the theme re-derives together with everything else the moment the player
     * picks a new accent, switches UI style, resets the profile or loads another one.
     */
    public static void refresh() {
        if (!WINDOWS) {
            return;
        }
        var visuals = ConfigManager.getInstance().get().visuals;
        if (!visuals.titleBar) {
            revert();
            return;
        }
        if (unsupported) {
            return; // Windows 10 and older: asking again on every theme refresh changes nothing
        }
        long hwnd = hwnd();
        if (hwnd == 0L || !nativeInit()) {
            return;
        }
        if (originalDarkMode == UNREADABLE) {
            originalDarkMode = dwmGet(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE);
        }
        int caption = color(visuals.titleBarColorHex, SBSTheme.PANEL_FILL_TOP);
        int text = color(visuals.titleBarTextHex, SBSTheme.TEXT);
        int border = color(visuals.titleBarBorderHex, SBSTheme.ACCENT);
        // The caption is the attribute worth judging support by: it is the one the feature is about,
        // and every Windows version that has it has the other two as well.
        if (dwmSet(hwnd, DWMWA_CAPTION_COLOR, colorRef(caption)) != S_OK) {
            if (!unsupported) {
                unsupported = true;
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS] title bar colors need Windows 11 (build 22000+) - leaving the caption alone");
            }
            return;
        }
        dwmSet(hwnd, DWMWA_TEXT_COLOR, colorRef(text));
        dwmSet(hwnd, DWMWA_BORDER_COLOR, colorRef(border));
        // Auto decides from the caption we just set, so the glyphs re-judge themselves on every
        // theme change rather than staying with a choice made against some earlier colour.
        dwmSet(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE,
                visuals.titleBarButtons.lightGlyphs(caption) ? 1 : 0);
        applied = true;
    }

    /** Hands all three colours – and the light/dark flag – back to Windows. */
    private static void revert() {
        if (!applied) {
            return;
        }
        applied = false;
        long hwnd = hwnd();
        if (hwnd == 0L || !nativeInit()) {
            return;
        }
        dwmSet(hwnd, DWMWA_CAPTION_COLOR, DWMWA_COLOR_DEFAULT);
        dwmSet(hwnd, DWMWA_TEXT_COLOR, DWMWA_COLOR_DEFAULT);
        dwmSet(hwnd, DWMWA_BORDER_COLOR, DWMWA_COLOR_DEFAULT);
        if (originalDarkMode != UNREADABLE) {
            dwmSet(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, originalDarkMode);
        }
    }

    /**
     * One line for the settings page: whether this machine can do any of it. Read when the page is
     * built, which is also when the player is looking – by then a first attempt has been made and
     * {@link #unsupported} means something.
     */
    public static String statusLine() {
        if (!WINDOWS) {
            return "§8Windows only - your system draws the window frame its own way";
        }
        if (unsupported || nativeFailed) {
            return "§8Windows 11 only - this Windows build keeps its own title bar colors";
        }
        return "§8Window buttons can only be white or black - Windows draws those glyphs itself";
    }

    /** The configured colour, or {@code fallback} (a theme colour) while the player has picked none. */
    private static int color(String hex, int fallback) {
        Integer picked = OverlayColor.parseHex(hex);
        return picked == null ? fallback : picked;
    }

    /** {@code 0xAARRGGBB} (mod-wide) to {@code 0x00BBGGRR} (what Windows takes). */
    private static int colorRef(int argb) {
        return ((argb & 0xFF) << 16) | (argb & 0xFF00) | ((argb >> 16) & 0xFF);
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

    /** Loads {@code dwmapi.dll} and caches the two entry points. Failure is remembered, not retried. */
    private static boolean nativeInit() {
        if (nativeReady) {
            return true;
        }
        if (nativeFailed) {
            return false;
        }
        try {
            SharedLibrary dwmapi = new WindowsLibrary("dwmapi");
            setAttribute = dwmapi.getFunctionAddress("DwmSetWindowAttribute");
            getAttribute = dwmapi.getFunctionAddress("DwmGetWindowAttribute");
            nativeReady = setAttribute != 0L && getAttribute != 0L;
            nativeFailed = !nativeReady;
        } catch (Throwable t) {
            nativeFailed = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] dwmapi unavailable - title bar colors disabled", t);
        }
        return nativeReady;
    }

    /**
     * {@code HRESULT DwmSetWindowAttribute(HWND, DWORD, LPCVOID, DWORD)} – the attribute value is a
     * single {@code int} for all four attributes used here, so it goes on the stack rather than
     * through an allocation.
     */
    private static int dwmSet(long hwnd, int attribute, int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.ints(value);
            return JNI.invokePPI(hwnd, attribute, MemoryUtil.memAddress(buffer),
                    Integer.BYTES, setAttribute);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] title bar attribute {} failed", attribute, t);
            return -1;
        }
    }

    /** The same call in reverse; {@link #UNREADABLE} when Windows will not report the attribute. */
    private static int dwmGet(long hwnd, int attribute) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.callocInt(1);
            int result = JNI.invokePPI(hwnd, attribute, MemoryUtil.memAddress(buffer),
                    Integer.BYTES, getAttribute);
            return result == S_OK ? buffer.get(0) : UNREADABLE;
        } catch (Throwable t) {
            return UNREADABLE;
        }
    }
}
