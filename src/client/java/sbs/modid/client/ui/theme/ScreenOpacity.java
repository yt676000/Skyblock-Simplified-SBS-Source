/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-screen opacity: each screen may carry its own value in {@code gui.screenOpacity}, which
 * replaces the global Surface Opacity for that screen only. The HUD is untouched - it keeps the
 * global value and its own per-element dial.
 *
 * <p>Per frame, the screen render hook asks {@link #enter} for the screen about to draw; the key is
 * cached per screen instance, and {@link SBSTheme#useScreenOpacity} only re-derives the palette when
 * the value actually differs from what is applied, so steady frames cost a reference and an int
 * compare.
 *
 * <p>Control: <b>Ctrl + mouse wheel</b> over any keyed screen steps its value by
 * {@link #STEP}; <b>Ctrl + middle click</b> puts it back to the global value. A small line at the top
 * of the screen says what changed for two seconds. Like the global slider, it never goes below
 * {@link SBSTheme#MIN_SCREEN_OPACITY}.
 */
public final class ScreenOpacity {

    public static final int STEP = 5;
    private static final long NOTICE_MS = 2_000L;

    private static Screen cachedScreen;
    private static String cachedKey;
    private static String notice;
    private static long noticeAt;

    private ScreenOpacity() {
    }

    private static SBSConfig cfg() {
        return ConfigManager.getInstance().get();
    }

    /** The saved per-screen values; created on first use so an old config has an empty map. */
    public static Map<String, Integer> overrides() {
        SBSConfig.GuiSettings gui = cfg().gui;
        if (gui.screenOpacity == null) {
            gui.screenOpacity = new LinkedHashMap<>();
        }
        return gui.screenOpacity;
    }

    /** The key for {@code screen}, or {@code null} when it has no stable identity (global value). */
    public static String keyOf(Screen screen) {
        if (screen == null) {
            return null;
        }
        if (screen == cachedScreen) {
            return cachedKey;
        }
        String key = computeKey(screen);
        cachedScreen = screen;
        cachedKey = key;
        return key;
    }

    private static String computeKey(Screen screen) {
        if (screen instanceof KeyedScreen keyed) {
            return ScreenKeys.sbsKey(keyed.screenId());
        }
        // Hypixel menus are chest containers; their title is their identity.
        if (screen instanceof ContainerScreen) {
            return ScreenKeys.menuKey(screen.getTitle() == null ? "" : screen.getTitle().getString());
        }
        // Vanilla screens by type, from a fixed list: class names are remapped at runtime, so they
        // cannot be a key.
        if (screen instanceof CreativeModeInventoryScreen) {
            return ScreenKeys.vanillaKey("creative_inventory");
        }
        if (screen instanceof InventoryScreen) {
            return ScreenKeys.vanillaKey("inventory");
        }
        if (screen instanceof PauseScreen) {
            return ScreenKeys.vanillaKey("pause_menu");
        }
        String pkg = screen.getClass().getPackageName();
        if (pkg.startsWith("net.minecraft") || pkg.startsWith("sbs.modid")) {
            return null;
        }
        // Someone else's screen: only with the "theme other mods" opt-in, keyed on their own class
        // name (not remapped - it is theirs).
        return cfg().minecraftOverlay.themeOtherMods ? ScreenKeys.modKey(screen.getClass().getSimpleName()) : null;
    }

    /** The value {@code screen} is drawn at. */
    public static int percentFor(Screen screen) {
        return ScreenKeys.resolve(overrides(), keyOf(screen), cfg().theme.surfaceOpacity);
    }

    /** Called at the start of every screen frame. */
    public static void enter(Screen screen) {
        SBSTheme.useScreenOpacity(percentFor(screen));
    }

    /** No screen open: back to the global value, so nothing drawn outside a screen inherits one. */
    public static void leave() {
        SBSTheme.useScreenOpacity(cfg().theme.surfaceOpacity);
    }

    /** Ctrl + wheel: step this screen's own value. Returns whether the event was used. */
    public static boolean scroll(Screen screen, double amount) {
        String key = keyOf(screen);
        if (key == null || amount == 0) {
            return false;
        }
        int current = percentFor(screen);
        int next = Math.max(SBSTheme.MIN_SCREEN_OPACITY,
                Math.min(100, current + (amount > 0 ? STEP : -STEP)));
        overrides().put(key, next);
        ConfigManager.getInstance().save();
        show(ScreenKeys.displayName(key) + ": " + next + "% (own value)  -  Ctrl+middle click resets");
        return true;
    }

    /** Ctrl + middle click: drop this screen's own value. */
    public static boolean reset(Screen screen) {
        String key = keyOf(screen);
        if (key == null) {
            return false;
        }
        if (overrides().remove(key) != null) {
            ConfigManager.getInstance().save();
        }
        show(ScreenKeys.displayName(key) + ": back to the global " + cfg().theme.surfaceOpacity + "%");
        return true;
    }

    private static void show(String text) {
        notice = text;
        noticeAt = System.currentTimeMillis();
    }

    /** The two-second notice after a change, drawn over the top of the screen. */
    public static void drawNotice(GuiGraphicsExtractor g) {
        if (notice == null || System.currentTimeMillis() - noticeAt > NOTICE_MS) {
            return;
        }
        var font = Minecraft.getInstance().font;
        String text = sbs.modid.client.ui.render.RowText.fit(font, notice, g.guiWidth() - 12);
        int width = font.width(text) + 8;
        int x = Math.max(2, (g.guiWidth() - width) / 2);
        g.fill(x, 2, x + width, 4 + font.lineHeight + 2, 0xE0101018);
        g.text(font, Component.literal(text), x + 4, 4, 0xFFFFFFFF);
    }
}
