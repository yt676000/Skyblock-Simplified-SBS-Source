/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

import sbs.modid.client.ui.theme.style.StylePalette;
import sbs.modid.client.ui.theme.style.SurfaceMaterial;

/**
 * Central Sci-Fi theme for the Skyblock Simplified GUI.
 *
 * <p>Colors are packed ARGB ({@code 0xAARRGGBB}) and passed straight to the
 * {@code GuiGraphicsExtractor} drawing calls used by {@link sbs.modid.client.ui.render.SciFiRender}.
 * Palette: dark Sci-Fi blue surfaces with white / light-blue accents and a soft glow.
 *
 * <p>Everything visual is centralized here so the whole UI stays consistent and a
 * future theme switch only needs to change these values.
 */
public final class SBSTheme {

    private SBSTheme() {
    }

    /**
     * The tooltip style for {@code setTooltipForNextFrame(..., Identifier)}: resolves to the bundled
     * nine-slice sprites {@code tooltip/<name>_background} + {@code tooltip/<name>_frame}.
     *
     * <p><b>A method, not a constant, because it follows the style.</b> Tooltips are drawn by vanilla
     * straight from a texture, so they are the one surface {@code SciFiRender}'s material hook cannot
     * reach - without this they would stay sci-fi navy in an oak or brass client, the single
     * left-over rectangle from the old theme. Each style ships its own 8x8 pair instead.
     */
    public static net.minecraft.resources.Identifier tooltipStyle() {
        return tooltipStyle;
    }

    private static net.minecraft.resources.Identifier tooltipStyle = tooltipId("sbs");

    private static net.minecraft.resources.Identifier tooltipId(String sprite) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath("skyblock-simplified-sbs", sprite);
    }

    // ------------------------------------------------------------------
    // Themed surfaces & accents. NOT final: the Theme module recolours the whole family from three
    // base colours (accent / background / text) via applyTheme - every colour keeps its original
    // alpha and its brightness RELATION to the others, so gradients stay consistent automatically.
    // The literals below are the stock Sci-Fi-blue defaults (identity until a theme is applied).
    // ------------------------------------------------------------------

    /** Subtle extra darkening over the whole screen behind the panel. */
    public static int BG_TINT = 0x55040A14;

    /** Panel fill (top) and a darker bottom tone for a vertical gradient. */
    public static int PANEL_FILL_TOP = 0xF00B2138;
    public static int PANEL_FILL_BOTTOM = 0xF005101F;

    /** Soft white/blue panel outline + outer glow. */
    public static int PANEL_BORDER = 0x9099D9FF;
    public static int PANEL_GLOW = 0x223FB4FF;

    /** Search field background. */
    public static int SEARCH_FILL = 0xF0040C16;

    // Module cards.
    public static int CARD_BG = 0xE00C2138;
    public static int CARD_BG_HOVER = 0xFF143A5E;
    public static int CARD_BG_DISABLED = 0xC0091523;
    public static int CARD_BORDER = 0x553FB4FF;

    /** Opaque panel base painted behind the gradient (hotbar / container theme). Background family. */
    public static int PANEL_BASE = 0xFF0C1A2E;
    /** Slot-cell fill for the hotbar, container theme and item previews. Background family. */
    public static int SLOT_BG = 0xFF102640;

    public static int ACCENT = 0xFF3FB4FF;        // sci-fi blue
    public static int ACCENT_BRIGHT = 0xFFFFFFFF;  // white highlight
    public static int ACCENT_SOFT = 0x553FB4FF;

    /** Warning / rejection accent (e.g. a duplicate keybind). Semantic - never themed. */
    public static final int WARN = 0xFFE0605F;

    /** "ON" accent for {@link sbs.modid.client.ui.component.SciFiToggleButton}. Semantic. */
    public static final int TOGGLE_ON = 0xFF57D977;

    public static int TEXT = 0xFFFFFFFF;
    public static int TEXT_MUTED = 0xFF8FA9C8;

    // ------------------------------------------------------------------
    // Theme engine: three base colours drive every themed constant above.
    // ------------------------------------------------------------------

    /** The stock base colours the defaults were designed around. */
    public static final int DEFAULT_ACCENT = 0x3FB4FF;
    public static final int DEFAULT_BACKGROUND = 0x0C2138;
    public static final int DEFAULT_TEXT = 0xFFFFFF;

    /**
     * Recolours every themed constant from the three base colours. Each constant is transformed
     * relative to its family's DEFAULT base in HSB space: the hue shifts with the base, saturation
     * and brightness scale with it - so "one colour with gradients" stays exactly one pick, and the
     * light/dark steps (panel top vs bottom, card vs hover) re-derive automatically.
     */
    public static void applyTheme(int accentRgb, int backgroundRgb, int textRgb) {
        // Background family.
        BG_TINT = retint(0x55040A14, DEFAULT_BACKGROUND, backgroundRgb);
        PANEL_FILL_TOP = retint(0xF00B2138, DEFAULT_BACKGROUND, backgroundRgb);
        PANEL_FILL_BOTTOM = retint(0xF005101F, DEFAULT_BACKGROUND, backgroundRgb);
        SEARCH_FILL = retint(0xF0040C16, DEFAULT_BACKGROUND, backgroundRgb);
        CARD_BG = retint(0xE00C2138, DEFAULT_BACKGROUND, backgroundRgb);
        CARD_BG_HOVER = retint(0xFF143A5E, DEFAULT_BACKGROUND, backgroundRgb);
        CARD_BG_DISABLED = retint(0xC0091523, DEFAULT_BACKGROUND, backgroundRgb);
        PANEL_BASE = retint(0xFF0C1A2E, DEFAULT_BACKGROUND, backgroundRgb);
        SLOT_BG = retint(0xFF102640, DEFAULT_BACKGROUND, backgroundRgb);
        HUD_TRACK = retint(0xC00A1422, DEFAULT_BACKGROUND, backgroundRgb);
        // Accent family.
        PANEL_BORDER = retint(0x9099D9FF, DEFAULT_ACCENT, accentRgb);
        PANEL_GLOW = retint(0x223FB4FF, DEFAULT_ACCENT, accentRgb);
        CARD_BORDER = retint(0x553FB4FF, DEFAULT_ACCENT, accentRgb);
        ACCENT = retint(0xFF3FB4FF, DEFAULT_ACCENT, accentRgb);
        ACCENT_SOFT = retint(0x553FB4FF, DEFAULT_ACCENT, accentRgb);
        TEXT_MUTED = retint(0xFF8FA9C8, DEFAULT_ACCENT, accentRgb);
        // Text family.
        TEXT = retint(0xFFFFFFFF, DEFAULT_TEXT, textRgb);
        ACCENT_BRIGHT = retint(0xFFFFFFFF, DEFAULT_TEXT, textRgb);
        // HUD surfaces start out identical to the panel's cards; only a style pulls them apart.
        HUD_CARD_BG = CARD_BG;
        HUD_CARD_BORDER = CARD_BORDER;
    }

    /**
     * Re-reads the three base colours AND the style from the config and applies both.
     *
     * <p>The two can disagree about colour, and the rule is that <b>the player wins</b>. A material
     * style (oak, brass, arcane) carries base colours because its identity depends on them, but they
     * are only used while the three Theme pickers are still at stock. Once the player has chosen a
     * colour, theirs drives the derivation and the style contributes shape and material only - so
     * switching to Steampunk never quietly discards a deliberate pick.
     */
    public static void refreshFromConfig() {
        var theme = sbs.modid.client.core.config.ConfigManager.getInstance().get().theme;
        UiStyle selected = UiStyle.parse(theme.style);
        StylePalette palette = selected.definition().palette();
        if (palette != null && !isCustomized()) {
            applyTheme(palette.accent(), palette.background(), palette.text());
        } else {
            applyTheme(parse(theme.accentHex, DEFAULT_ACCENT),
                    parse(theme.backgroundHex, DEFAULT_BACKGROUND),
                    parse(theme.textHex, DEFAULT_TEXT));
        }
        applyStyle(selected);
        appliedScreenOpacity = Math.max(MIN_SCREEN_OPACITY, Math.min(100, theme.surfaceOpacity));
        applySurfaceOpacity(theme.surfaceOpacity);
        // The Windows title bar follows these same colours whenever the player has not overridden
        // it, and it lives outside the game where nothing else would ever repaint it - so it is
        // re-derived here, with every other surface, rather than only where the theme is edited.
        sbs.modid.client.helper.visual.logic.WindowTitleBar.refresh();
    }

    /** The style currently in force, for the few places that need to branch on shape rather than colour. */
    public static UiStyle style() {
        return UiStyle.parse(sbs.modid.client.core.config.ConfigManager.getInstance().get().theme.style);
    }

    /**
     * What every surface is currently made of.
     *
     * <p>Read by {@link sbs.modid.client.ui.render.SciFiRender} several hundred times per frame, so
     * it is a cached field rather than a config lookup - {@link #applyStyle} is the only writer.
     */
    public static SurfaceMaterial material() {
        return material;
    }

    private static SurfaceMaterial material = SurfaceMaterial.NONE;

    /**
     * Applies a {@link UiStyle} <b>on top of</b> whatever {@link #applyTheme} just derived.
     *
     * <p>Deliberately a second pass rather than a second set of literals: the style is expressed as a
     * transform of the themed colours (flatten this gradient, lift that card, fade that border), so
     * every style keeps working with a custom accent or background instead of pinning the mod back to
     * stock blue. Which is also why it must run after {@code applyTheme}, never before.
     *
     * <p>Switching back to {@link UiStyle#CLASSIC} restores the <i>geometry</i> only - the colours a
     * previous style rewrote are whatever {@code applyTheme} last derived. Always go through
     * {@link #refreshFromConfig()}, which runs both in the right order; calling this alone with
     * CLASSIC would leave the previous style's surfaces in place.
     */
    public static void applyStyle(UiStyle style) {
        style.definition().applySurfaces();
        material = style.definition().material();
        tooltipStyle = tooltipId(style.definition().tooltipSprite());
    }

    /**
     * The lowest the Surface Opacity slider goes: 0, an SBS HUD you see straight through, with only
     * its text and icons left over the game.
     */
    public static final int MIN_SURFACE_OPACITY = 0;

    /**
     * The lowest an <b>interactive screen's</b> surfaces fade to, whatever the slider says.
     *
     * <p>Not 0, and not for symmetry with the HUD: every control in the mod is drawn as a surface,
     * so a screen faded to nothing is a screen whose buttons cannot be found - and the slider that
     * did it is on one of those screens, so the player would have to fix it blind. 20% is a pane you
     * can still see the edge of.
     *
     * <p>The HUD has no such trap, which is why the same slider is allowed all the way down there.
     * Nothing on it is clicked, and the HUD editor draws its own boxes rather than the elements, so
     * an element faded to nothing is still listed, still outlined and still draggable.
     */
    public static final int MIN_SCREEN_OPACITY = 20;

    /**
     * Scales the alpha of every SBS <b>surface</b> by {@code percent}, on top of whatever
     * {@link #applyTheme} derived and {@link #applyStyle} then rewrote.
     *
     * <p>Deliberately a third pass over the same constants rather than a fourth base colour. Alpha
     * is the one property a style expresses a look through as much as hue - Glass at 0x4A, Bug at
     * 0xF2 - so a slider that <i>set</i> an opacity would flatten every style into the same sheet
     * of plastic. A multiplier keeps each style's relations intact: the panel stays behind the card,
     * the card stays behind its hover, and all three fade together. Which is also why it has to run
     * last; run before the style and the style would simply overwrite it.
     *
     * <p>Surfaces only. Text, the accent and the semantic colours (health red, mana blue, the
     * warning red) are left at full alpha on purpose - fading a HUD panel is a look, fading the
     * number on it is a bug. Per-element HUD fading is a separate dial and still composes on top;
     * see {@link sbs.modid.client.ui.hud.edit.logic.HudOpacity}.
     *
     * <p>The one surface it cannot reach is the tooltip, which vanilla draws straight from a texture
     * - each style ships its own instead, at the alpha that style was drawn with.
     *
     * <p><b>Two scales come out of the one slider.</b> The HUD follows it the whole way, down to
     * nothing; screens stop at {@link #MIN_SCREEN_OPACITY} - see there for why the two ends of the
     * same setting are allowed to differ. In the range both share (20-100) they are the same number,
     * so the split is invisible to anyone who never drags past it.
     */
    public static void applySurfaceOpacity(int percent) {
        applySurfaceOpacity(percent, percent);
    }

    /**
     * The screen opacity the palette is currently derived with - what {@link #useScreenOpacity}
     * compares against so a frame with nothing to change costs one int comparison.
     */
    private static int appliedScreenOpacity = 100;

    /**
     * Re-derives the palette so <b>screen</b> surfaces sit at {@code screenPercent} while the HUD keeps
     * the global value - the per-screen opacity entry point, called once per frame by the screen
     * render hook with the value for the screen being drawn. A no-op unless the value changed, so it
     * only re-derives when the player moves between screens with different settings.
     */
    public static void useScreenOpacity(int screenPercent) {
        int wanted = Math.max(MIN_SCREEN_OPACITY, Math.min(100, screenPercent));
        if (wanted == appliedScreenOpacity) {
            return;
        }
        var theme = sbs.modid.client.core.config.ConfigManager.getInstance().get().theme;
        UiStyle selected = UiStyle.parse(theme.style);
        StylePalette palette = selected.definition().palette();
        if (palette != null && !isCustomized()) {
            applyTheme(palette.accent(), palette.background(), palette.text());
        } else {
            applyTheme(parse(theme.accentHex, DEFAULT_ACCENT),
                    parse(theme.backgroundHex, DEFAULT_BACKGROUND),
                    parse(theme.textHex, DEFAULT_TEXT));
        }
        applyStyle(selected);
        applySurfaceOpacity(wanted, theme.surfaceOpacity);
        appliedScreenOpacity = wanted;
    }

    /** {@link #applySurfaceOpacity(int)} with the screen and HUD halves set separately. */
    private static void applySurfaceOpacity(int screenPercent, int hudPercent) {
        int clampedScreen = Math.max(MIN_SURFACE_OPACITY, Math.min(100, screenPercent));
        int clamped = Math.max(MIN_SURFACE_OPACITY, Math.min(100, hudPercent));
        if (clamped >= 100 && clampedScreen >= 100) {
            return;   // identity - and this keeps a stock config bit-for-bit what it always was
        }
        float scale = Math.max(MIN_SCREEN_OPACITY, clampedScreen) / 100F;
        float hudScale = clamped / 100F;
        // Background family: the panes themselves.
        BG_TINT = scaleAlpha(BG_TINT, scale);
        PANEL_FILL_TOP = scaleAlpha(PANEL_FILL_TOP, scale);
        PANEL_FILL_BOTTOM = scaleAlpha(PANEL_FILL_BOTTOM, scale);
        PANEL_BASE = scaleAlpha(PANEL_BASE, scale);
        SEARCH_FILL = scaleAlpha(SEARCH_FILL, scale);
        CARD_BG = scaleAlpha(CARD_BG, scale);
        CARD_BG_HOVER = scaleAlpha(CARD_BG_HOVER, scale);
        CARD_BG_DISABLED = scaleAlpha(CARD_BG_DISABLED, scale);
        SLOT_BG = scaleAlpha(SLOT_BG, scale);
        HUD_CARD_BG = scaleAlpha(HUD_CARD_BG, hudScale);
        HUD_TRACK = scaleAlpha(HUD_TRACK, hudScale);
        // Frames fade too, but only half as far. They are what is left of a shape once the fill has
        // gone, so fading them at the same rate is how a faded screen stops being a screen.
        float edge = 1F - (1F - scale) * 0.5F;
        // Except at the very bottom, where the HUD has to actually disappear: half of nothing is
        // still a ring drawn around nothing, which is not what 0% was asked for.
        float hudEdge = hudScale <= 0F ? 0F : 1F - (1F - hudScale) * 0.5F;
        PANEL_BORDER = scaleAlpha(PANEL_BORDER, edge);
        CARD_BORDER = scaleAlpha(CARD_BORDER, edge);
        HUD_CARD_BORDER = scaleAlpha(HUD_CARD_BORDER, hudEdge);
        PANEL_GLOW = scaleAlpha(PANEL_GLOW, scale);
        ACCENT_SOFT = scaleAlpha(ACCENT_SOFT, edge);
    }

    /**
     * {@code color} with its alpha multiplied. An already-invisible colour stays invisible (some
     * styles zero their glow outright), and a visible one never fades below 1 - rounding a faint
     * surface away would turn "very faint" into "gone" halfway down the slider.
     *
     * <p>A scale of exactly 0 is the one case that skips that floor, because there it is not
     * rounding: 0% was asked for, and a surface left at 1/255 is a surface still there.
     */
    private static int scaleAlpha(int color, float scale) {
        int a = color >>> 24;
        if (a == 0) {
            return color;
        }
        if (scale <= 0F) {
            return color & 0xFFFFFF;
        }
        return (Math.max(1, Math.round(a * scale)) << 24) | (color & 0xFFFFFF);
    }

    /**
     * Brightens {@code color} towards white by {@code amount}, at the given alpha - how a card
     * sits on its background. Derived from the themed colour so a custom background stays in charge
     * of the hue.
     */
    public static int lift(int color, float amount, int alphaValue) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = Math.round(r + (255 - r) * amount * 0.28F);
        g = Math.round(g + (255 - g) * amount * 0.30F);
        b = Math.round(b + (255 - b) * amount * 0.34F);
        return (alphaValue << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /** The same colour at a different alpha. */
    private static int alpha(int color, int alphaValue) {
        return (alphaValue << 24) | (color & 0xFFFFFF);
    }

    /**
     * Whether the user has changed any of the three theme base colours away from their stock values.
     * Lets surfaces with their own colour pickers (e.g. the Custom Scoreboard) defer to the global
     * theme once it has actually been customised, and keep their own colours while it is still stock.
     */
    public static boolean isCustomized() {
        var theme = sbs.modid.client.core.config.ConfigManager.getInstance().get().theme;
        return parse(theme.accentHex, DEFAULT_ACCENT) != DEFAULT_ACCENT
                || parse(theme.backgroundHex, DEFAULT_BACKGROUND) != DEFAULT_BACKGROUND
                || parse(theme.textHex, DEFAULT_TEXT) != DEFAULT_TEXT;
    }

    private static int parse(String hex, int fallback) {
        Integer parsed = sbs.modid.client.core.render.OverlayColor.parseHex(hex);
        return parsed == null ? fallback : parsed;
    }

    /**
     * Transforms {@code original} the same way the family base moved from {@code fromRgb} to
     * {@code toRgb}: hue is shifted by the delta, saturation and brightness are scaled by the
     * ratio (absolute when the source base was grey - a grey has no meaningful ratio). Alpha is
     * kept untouched.
     */
    private static int retint(int original, int fromRgb, int toRgb) {
        int alpha = original >>> 24;
        float[] o = rgbToHsb(original);
        float[] f = rgbToHsb(fromRgb);
        float[] t = rgbToHsb(toRgb);
        float h = (o[0] + (t[0] - f[0]) + 1.0f) % 1.0f;
        float s = f[1] < 0.02f ? t[1] : clamp01(o[1] * (t[1] / f[1]));
        float b = f[2] < 0.02f ? t[2] * o[2] : clamp01(o[2] * (t[2] / f[2]));
        return (alpha << 24) | hsbToRgb(h, s, b);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    /** RGB (0xRRGGBB, alpha ignored) -> {hue 0..1, sat 0..1, bri 0..1}. */
    public static float[] rgbToHsb(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue = 0f;
        if (delta > 0f) {
            if (max == r) {
                hue = ((g - b) / delta) / 6f;
            } else if (max == g) {
                hue = (2f + (b - r) / delta) / 6f;
            } else {
                hue = (4f + (r - g) / delta) / 6f;
            }
            hue = (hue + 1f) % 1f;
        }
        float sat = max <= 0f ? 0f : delta / max;
        return new float[] {hue, sat, max};
    }

    /** {hue 0..1, sat 0..1, bri 0..1} -> 0xRRGGBB. */
    public static int hsbToRgb(float hue, float sat, float bri) {
        float h = ((hue % 1f) + 1f) % 1f * 6f;
        int sector = (int) h % 6;
        float f = h - (int) h;
        float p = bri * (1f - sat);
        float q = bri * (1f - sat * f);
        float t = bri * (1f - sat * (1f - f));
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = bri; g = t; b = p; }
            case 1 -> { r = q; g = bri; b = p; }
            case 2 -> { r = p; g = bri; b = t; }
            case 3 -> { r = p; g = q; b = bri; }
            case 4 -> { r = t; g = p; b = bri; }
            default -> { r = bri; g = p; b = q; }
        }
        return (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    // ------------------------------------------------------------------
    // Bazaar order status highlights (semi-transparent fill + solid frame)
    // ------------------------------------------------------------------

    /** Best Offer – green. */
    public static final int BAZAAR_BEST_FILL = 0x5557D977;
    public static final int BAZAAR_BEST_FRAME = 0xFF57D977;

    /** Matched – orange. */
    public static final int BAZAAR_MATCHED_FILL = 0x55E0A14D;
    public static final int BAZAAR_MATCHED_FRAME = 0xFFE0A14D;

    /** Outdated – red. */
    public static final int BAZAAR_OUTDATED_FILL = 0x55E0605F;
    public static final int BAZAAR_OUTDATED_FRAME = 0xFFE0605F;

    // ------------------------------------------------------------------
    // HUD bars (rounded health / mana), drawn over the vanilla HUD
    // ------------------------------------------------------------------

    /** Empty track behind the rounded HUD bars (themed: background family). */
    public static int HUD_TRACK = 0xC00A1422;

    /** Normal health segment (red). */
    public static final int HUD_HEALTH = 0xFFFF5555;

    /** Overheal / absorption segment (yellow). */
    public static final int HUD_OVERHEAL = 0xFFFFD64D;

    /**
     * Mana bar / mana text. <b>Semantic - never themed:</b> mana is blue the same way health is red
     * and XP is green, so it stays fixed while the accent recolours the rest of the UI (re-theming
     * it looked like the mana bar was "partially changing" with the palette).
     */
    public static final int HUD_MANA = 0xFF3FB4FF;

    /** XP bar fill (vanilla experience green, slightly toned to fit the SBS palette). */
    public static final int HUD_XP = 0xFF80E62E;

    /**
     * Vitality bar (Hypixel's new "healing pool" stat). <b>Semantic - never themed</b> like health /
     * mana / XP: a rose-pink that reads as "life/healing" and stays distinct from health-red and
     * mana-blue while the accent recolours the rest of the UI.
     */
    public static final int HUD_VITALITY = 0xFFFF6EC7;

    // ------------------------------------------------------------------
    // Dimensions (GUI-scaled pixels)
    // ------------------------------------------------------------------

    public static final int SCREEN_MARGIN = 20;

    public static final int PANEL_MIN_WIDTH = 210;
    public static final int PANEL_MAX_WIDTH = 340;
    public static final int PANEL_MIN_HEIGHT = 150;
    public static final int PANEL_MAX_HEIGHT = 330;

    public static final int PANEL_PADDING = 12;

    /**
     * Corner radius of an outer <b>frame</b> - the window a screen lives in, a floating window, a HUD
     * card's own outline.
     *
     * <p>NOT final, for the same reason the colours above are not: {@link UiStyle} rewrites it, and a
     * {@code static final int} would be constant-folded by javac into all ~117 files that read it,
     * so the style would never reach them. Never restore the {@code final}.
     */
    /**
     * Card surface for HUD elements drawn over the world - the pet card, server stats, the scoreboard,
     * the tracker cards. Identical to {@link #CARD_BG} / {@link #CARD_BORDER} until a
     * {@link UiStyle} pulls them apart, because a HUD card and a card inside a panel are the same
     * thing visually right up to the point where a style starts talking about panels.
     *
     * <p>Not final, for the same reason as {@link #PANEL_CORNER}.
     */
    public static int HUD_CARD_BG = 0xE00C2138;
    public static int HUD_CARD_BORDER = 0x553FB4FF;
    /**
     * Corner radius for HUD cards. Also not final.
     *
     * <p>7 under CLASSIC because that is what the HUD cards already had: they were reading
     * {@link #PANEL_CORNER}, the <i>window</i> radius, which is exactly why the second style - whose
     * whole point is that windows go square - turned every card on the HUD into a rectangle.
     */
    public static int HUD_CORNER = 7;

    /**
     * Corner radius for <b>item slots</b> - the 16-20 px cells in containers, the hotbar and the
     * item previews. Also not final.
     *
     * <p>Slots need their own radius because the rounded-rectangle primitive clamps the radius to
     * half the shorter side: a card radius of 9 on an 18 px slot is not "generously rounded", it is
     * a circle, and a grid of circles is unreadable as an inventory. This stays at a third of the
     * cell so a slot is always a square with soft corners, whatever the style does elsewhere.
     */
    public static int SLOT_CORNER = 5;

    public static int PANEL_CORNER = 7;

    /**
     * Corner radius of everything <b>inside</b> a frame - cards, rows, buttons, slots, input fields.
     * Also not final; see {@link #PANEL_CORNER}.
     */
    public static int CORNER_RADIUS = 5;

    public static final int HEADER_HEIGHT = 26;
    public static final int SEARCH_HEIGHT = 18;
    public static final int ENTRY_HEIGHT = 22;
    public static final int ENTRY_SPACING = 5;

    public static final int GAP_AFTER_HEADER = 6;
    public static final int GAP_AFTER_SEARCH = 9;
    public static final int PAGINATION_HEIGHT = 18;
    public static final int GAP_BEFORE_PAGINATION = 8;

    /**
     * Whether {@code color} is one of the theme's frame colours.
     *
     * <p>An SBS card is a border-coloured plate with its body painted on top, one pixel in – the frame
     * you see is the plate's rim, not a shape of its own. By the time that reaches the render pipeline
     * both are just filled rectangles, so the HUD opacity's "background vs. outline" split asks this
     * instead: the frame colours are exact theme constants and nothing else uses them as a fill.
     *
     * <p>Compared against the live values, so a re-themed accent keeps working.
     *
     * <p>{@link #HUD_CARD_BORDER} is in the list even though it starts out equal to
     * {@link #CARD_BORDER}: eight of the nine styles pull the two apart, and the handful of overlays
     * that draw their own frame from it (the arrow counter, the tab list, the flip popups) were
     * having that frame counted as body and faded away with it on every style but Classic.
     *
     * <p>A colour this cannot recognise - a frame the player pinned to a hex of their own - is a
     * frame that falls back to the body dial. Where that matters the drawing site says so outright
     * with {@link sbs.modid.client.ui.hud.edit.logic.HudOpacity#beginOutline}, which needs no
     * predicate; this is for the sites that cannot, having no idea a HUD element is above them.
     */
    public static boolean isBorderColor(int color) {
        return color == PANEL_BORDER || color == CARD_BORDER || color == HUD_CARD_BORDER
                || color == PANEL_GLOW;
    }
}
