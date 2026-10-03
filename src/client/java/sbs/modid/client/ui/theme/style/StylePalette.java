/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme.style;

/**
 * The three base colours a style wants the theme engine to derive everything from.
 *
 * <p>Only styles with a <b>material identity</b> carry one - weathered wood is not brown because of
 * a border radius, it is brown because it is wood. Styles that are pure geometry (Classic,
 * Futuristic) return {@code null} instead and inherit whatever the player picked, which is what
 * keeps "a style and a custom colour compose" true for them.
 *
 * <p>A palette is a <b>default, not an override</b>: it is only consulted while the player has left
 * the three Theme colours at stock. The moment they pick their own, their pick wins and the style
 * contributes shape and material only - so choosing Steampunk never silently eats a colour the
 * player deliberately chose.
 */
public record StylePalette(int accent, int background, int text) {
}
