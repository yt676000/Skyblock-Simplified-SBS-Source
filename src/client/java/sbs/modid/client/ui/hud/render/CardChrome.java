/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.render;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * What colour a HUD card's frame is, and which setting got to decide.
 *
 * <h2>The priority order</h2>
 *
 * Several settings can change how SBS is coloured, and before this they simply fought: some cards
 * followed the theme, some carried a colour of their own, and there was no way to tell from the
 * screen which had happened or how to undo it. They are now ranked, and the ranking is stated on the
 * settings page in this order:
 *
 * <ol>
 *   <li><b>Theme colours</b> - the three base colours. Everything derives from these, and with no
 *       override set they are the only thing deciding anything. This is what "reset to priority 1"
 *       goes back to.</li>
 *   <li><b>UI Style</b> - a look brings its own shape, material and default colours, which move the
 *       theme's own values. It sits under the theme because the player's picked colours still win
 *       wherever they have picked any.</li>
 *   <li><b>Card frame overrides</b> - the three fields here. Each is blank by default, and blank
 *       means "let the theme decide"; typing a colour into one pins that part of the frame.</li>
 *   <li><b>Per-feature colours</b> - the bar colours, the scoreboard's own colours, a highlight
 *       colour on a world feature. Narrowest scope, so they win last, and each stays the property of
 *       the feature that owns it.</li>
 * </ol>
 *
 * <p><b>Blank means inherit.</b> That is the whole mechanism: an override is stored as an empty
 * string until the player sets one, so "reset to the theme" is clearing text rather than restoring a
 * remembered default, and a theme change immediately reaches everything nobody has pinned.
 */
public final class CardChrome {

    private CardChrome() {
    }

    private static SBSConfig.ThemeSettings cfg() {
        return ConfigManager.getInstance().get().theme;
    }

    /** The card's border, or the theme's when nothing is pinned. */
    public static int border() {
        return resolve(cfg().hudCardBorderHex, SBSTheme.PANEL_BORDER);
    }

    /** The glow behind the card, or the theme's. */
    public static int glow() {
        return resolve(cfg().hudCardGlowHex, SBSTheme.PANEL_GLOW);
    }

    /** The top of the card's background gradient, or the theme's. */
    public static int fillTop() {
        return resolve(cfg().hudCardFillHex, SBSTheme.PANEL_FILL_TOP);
    }

    /**
     * The bottom of the gradient.
     *
     * <p>Derived from the top rather than being a fourth field: the gradient is a card looking lit
     * from above, and two independently chosen ends stop being that as soon as they disagree. A
     * pinned fill therefore keeps the theme's own top-to-bottom ratio instead of asking the player
     * to pick two colours that only work as a pair.
     */
    public static int fillBottom() {
        if (isBlank(cfg().hudCardFillHex)) {
            return SBSTheme.PANEL_FILL_BOTTOM;
        }
        return scaled(fillTop(), themeGradientRatio(), SBSTheme.PANEL_FILL_BOTTOM >>> 24);
    }

    /**
     * How much darker the theme draws the bottom of a card than the top, as a plain channel ratio.
     * Reading it off the theme's own pair is what gives a pinned fill the same depth as an unpinned
     * one, rather than a hardcoded "10% darker" that would be wrong for every style but one.
     */
    private static float themeGradientRatio() {
        float top = brightest(SBSTheme.PANEL_FILL_TOP);
        if (top <= 0.5f) {
            return 1f;   // an all-but-black theme fill has no room for a gradient
        }
        return Math.clamp(brightest(SBSTheme.PANEL_FILL_BOTTOM) / top, 0.2f, 1f);
    }

    /** The brightest channel of {@code argb}, 0-255. */
    private static float brightest(int argb) {
        return Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF));
    }

    /** {@code argb}'s channels multiplied by {@code ratio}, at the given alpha. */
    private static int scaled(int argb, float ratio, int alpha) {
        int r = Math.round(((argb >> 16) & 0xFF) * ratio);
        int g = Math.round(((argb >> 8) & 0xFF) * ratio);
        int b = Math.round((argb & 0xFF) * ratio);
        return (alpha << 24) | (Math.clamp(r, 0, 255) << 16)
                | (Math.clamp(g, 0, 255) << 8) | Math.clamp(b, 0, 255);
    }

    /**
     * A stored {@code RRGGBB} override, or {@code fallback} when it is blank or unreadable.
     *
     * <p>The override keeps the theme value's alpha. A card frame's transparency is what makes it a
     * HUD element rather than a solid box over the game, and asking for it in a hex field is asking
     * the player to make the game unplayable by typing eight characters instead of six.
     */
    private static int resolve(String hex, int fallback) {
        if (isBlank(hex)) {
            return fallback;
        }
        try {
            int rgb = Integer.parseInt(hex.trim().replace("#", ""), 16) & 0xFFFFFF;
            return (fallback & 0xFF000000) | rgb;
        } catch (NumberFormatException notAColour) {
            // Half-typed text is the normal state of a field being edited, not an error worth
            // logging - the card simply keeps the theme's colour until the six characters are there.
            return fallback;
        }
    }

    private static boolean isBlank(String hex) {
        return hex == null || hex.isBlank();
    }

    /** Whether anything at all is currently overriding the theme's frame. */
    public static boolean anyOverride() {
        SBSConfig.ThemeSettings theme = cfg();
        for (String hex : List.of(theme.hudCardBorderHex, theme.hudCardGlowHex, theme.hudCardFillHex)) {
            if (!isBlank(hex)) {
                return true;
            }
        }
        return !theme.hudCardGlow;
    }

    /**
     * Hands the card frame back to the theme: clears every override and puts the glow back on.
     * "Reset to priority 1" in the settings, and the only thing that writes these three fields
     * without the player having typed in one.
     */
    public static void resetToTheme() {
        SBSConfig.ThemeSettings theme = cfg();
        theme.hudCardBorderHex = "";
        theme.hudCardGlowHex = "";
        theme.hudCardFillHex = "";
        theme.hudCardGlow = true;
        ConfigManager.getInstance().save();
    }
}
