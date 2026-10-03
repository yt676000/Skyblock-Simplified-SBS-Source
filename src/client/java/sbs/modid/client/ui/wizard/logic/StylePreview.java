/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.theme.UiStyle;
import sbs.modid.client.ui.theme.style.StylePalette;

/**
 * Puts one UI style in force just long enough to draw a preview of it, then puts the real one back.
 *
 * <p><b>Why this is the only way.</b> A style is not a set of values that can be read and drawn from
 * - {@code StyleDefinition.applySurfaces()} rewrites the mutable constants on {@link SBSTheme}, and
 * {@code SciFiRender} reads those constants directly, hundreds of times a frame. There is no
 * "draw as if this style were active" call, and adding one would mean every style reimplementing its
 * geometry twice, with the copy in the preview free to drift from the copy that ships.
 *
 * <p>So the preview applies the style for real, draws a small panel, and applies the next one. It is
 * safe only because it happens inside a single render pass with nothing else drawing between the
 * tiles, and because {@link #restore()} runs unconditionally afterwards.
 *
 * <p><b>{@link #restore()} deliberately does not call {@code SBSTheme.refreshFromConfig()}</b>, even
 * though that is the obvious way to get back to the truth. That method also refreshes the Windows
 * title bar, which is a native call out of the game; running it once per frame for the lifetime of a
 * screen would be a real cost for no benefit. This re-derives the same colours and style without it.
 */
public final class StylePreview {

    private StylePreview() {
    }

    /**
     * Makes {@code style} the one every subsequent draw uses, until the next call or {@link #restore}.
     *
     * <p>A material style (oak, brass, arcane) previews in its own colours, because those are its
     * identity and previewing Steampunk in cyan would show something the player can never get by
     * picking it. A geometry style carries no palette by design - it is a transform over whatever
     * colours the player already chose - so it previews in theirs, which is also exactly what
     * selecting it would produce.
     */
    public static void apply(UiStyle style) {
        StylePalette palette = style.definition().palette();
        if (palette != null && !SBSTheme.isCustomized()) {
            SBSTheme.applyTheme(palette.accent(), palette.background(), palette.text());
        } else if (palette != null) {
            // The player has picked their own colours, so those win here exactly as they win in
            // SBSTheme.refreshFromConfig - the preview must not promise a look that selecting the
            // style would not actually give them.
            applyConfiguredColours();
        } else {
            applyConfiguredColours();
        }
        SBSTheme.applyStyle(style);
    }

    /** Puts the player's real style and colours back. Must run after any {@link #apply}. */
    public static void restore() {
        var theme = ConfigManager.getInstance().get().theme;
        UiStyle actual = UiStyle.parse(theme.style);
        StylePalette palette = actual.definition().palette();
        if (palette != null && !SBSTheme.isCustomized()) {
            SBSTheme.applyTheme(palette.accent(), palette.background(), palette.text());
        } else {
            applyConfiguredColours();
        }
        SBSTheme.applyStyle(actual);
    }

    private static void applyConfiguredColours() {
        var theme = ConfigManager.getInstance().get().theme;
        SBSTheme.applyTheme(
                parseHex(theme.accentHex, SBSTheme.DEFAULT_ACCENT),
                parseHex(theme.backgroundHex, SBSTheme.DEFAULT_BACKGROUND),
                parseHex(theme.textHex, SBSTheme.DEFAULT_TEXT));
    }

    private static int parseHex(String hex, int fallback) {
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(hex.trim().replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
