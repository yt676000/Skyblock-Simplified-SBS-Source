/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stable keys for "which screen is this", and the per-screen opacity lookup built on them.
 *
 * <p>Three families, each keyed on something that survives a refactor:
 * <ul>
 *   <li>{@code sbs:<id>} - an SBS screen, by a constant it declares ({@link KeyedScreen}), never its
 *       class name.</li>
 *   <li>{@code menu:<title>} - a Hypixel menu, by its title with colour codes and paging stripped and
 *       lower-cased, so {@code (1/3) Pets}, {@code Pets (2/3)} and {@code Pets} are one screen.
 *       Paging comes both as a prefix and a suffix ({@code docs/skyblock-ui/menus.md}).</li>
 *   <li>{@code vanilla:<type>} - a vanilla screen, by a fixed type name.</li>
 * </ul>
 * Other mods' screens get {@code mod:<class>} - the best they offer - and only behind the "theme
 * other mods" opt-in.
 *
 * <p>Pure: strings in, strings out.
 */
public final class ScreenKeys {

    private static final Pattern PREFIX_PAGE = Pattern.compile("^\\(\\s*\\d+\\s*/\\s*\\d+\\s*\\)\\s*");
    private static final Pattern SUFFIX_PAGE = Pattern.compile("\\s*\\(\\s*\\d+\\s*/\\s*\\d+\\s*\\)$");

    /** Friendly names for the SBS screens that declare a key. */
    private static final Map<String, String> SBS_NAMES = Map.of(
            "sbs:config", "SBS Settings",
            "sbs:hud_editor", "HUD Editor",
            "sbs:scoreboard_layout", "Scoreboard Layout",
            "sbs:skyblock_map", "SkyBlock Map",
            "sbs:minion_calc", "Minion Calculator");

    private ScreenKeys() {
    }

    /** {@code "(1/3) §6Pets"} -> {@code "menu:pets"}; blank titles give {@code null}. */
    public static String menuKey(String title) {
        if (title == null) {
            return null;
        }
        String plain = title.replaceAll("§.", "").trim();
        plain = PREFIX_PAGE.matcher(plain).replaceFirst("");
        plain = SUFFIX_PAGE.matcher(plain).replaceFirst("");
        plain = plain.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return plain.isEmpty() ? null : "menu:" + plain;
    }

    public static String sbsKey(String id) {
        return "sbs:" + id;
    }

    public static String vanillaKey(String type) {
        return "vanilla:" + type;
    }

    public static String modKey(String className) {
        return "mod:" + className;
    }

    /**
     * The opacity {@code key} is drawn at: its own value if it has one - which <b>replaces</b> the
     * global one rather than multiplying it - otherwise {@code global}. Always at least
     * {@link SBSTheme#MIN_SCREEN_OPACITY}: a screen faded to nothing hides its own controls.
     */
    public static int resolve(Map<String, Integer> overrides, String key, int global) {
        Integer own = key == null || overrides == null ? null : overrides.get(key);
        int value = own != null ? own : global;
        return Math.max(SBSTheme.MIN_SCREEN_OPACITY, Math.min(100, value));
    }

    /** What the settings page calls a key: "Pets", "SBS Settings", "Inventory". */
    public static String displayName(String key) {
        if (key == null) {
            return "";
        }
        String known = SBS_NAMES.get(key);
        if (known != null) {
            return known;
        }
        int colon = key.indexOf(':');
        String rest = colon < 0 ? key : key.substring(colon + 1);
        if (key.startsWith("mod:")) {
            return rest + " (other mod)";
        }
        StringBuilder out = new StringBuilder();
        for (String word : rest.replace('_', ' ').split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
