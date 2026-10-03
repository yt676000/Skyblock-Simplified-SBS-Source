/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.resources.Identifier;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.render.SciFiRender;

import java.util.Map;

/**
 * Minecraft Overlay module: the SBS look for the recipe book beside the inventory - backdrop,
 * category tabs, recipe tiles, page arrows, filter toggle and search field.
 *
 * <p><b>Sprites are swapped, not painted over.</b> While a recipe book is drawing (the scope opened
 * by {@code RecipeBookScopeMixin} around the screen's one call into it) each vanilla recipe-book
 * sprite that reaches {@code GuiGraphicsExtractor.blit} / {@code blitSprite} is replaced by the SBS
 * shape of the same size at the same place; everything else - item icons, text, any other mod's own
 * textures - is drawn exactly as before. Outside the scope nothing is matched at all, so a vanilla
 * sprite used anywhere else keeps its look.
 *
 * <p><b>Why at the draw call and not in {@code RecipeBookComponent}.</b> A mod can replace the book
 * with its own subclass whose {@code extractRenderState} never reaches the vanilla method - which is
 * why the earlier backdrop-only mixin (a wrap inside that method) left such a book fully grey. Both
 * still come in through the screen's call and still draw vanilla's sprites, so this covers both
 * without touching another mod's classes.
 *
 * <p><b>Gate.</b> The vanilla book follows {@code minecraftOverlay.enabled}. A book another mod put in
 * its place is themed only when {@code minecraftOverlay.themeOtherMods} is on as well: we can vouch
 * for what vanilla's sprites mean, not for how another mod lays them out, and that toggle is the
 * existing opt-in for exactly this. Off, every sprite is drawn with its own arguments - vanilla to
 * the pixel. The swap draws only; it never touches a widget, so every click, tab and the search
 * field work as before.
 */
public final class RecipeBookSkin {

    private enum Part {
        BACKDROP, TAB, TAB_SELECTED, SLOT, SLOT_MANY, SLOT_UNCRAFTABLE, SLOT_MANY_UNCRAFTABLE,
        PAGE_FORWARD, PAGE_FORWARD_HOVER, PAGE_BACKWARD, PAGE_BACKWARD_HOVER,
        FILTER_ON, FILTER_ON_HOVER, FILTER_OFF, FILTER_OFF_HOVER, SEARCH, SEARCH_FOCUSED
    }

    /**
     * Every sprite swapped, by id (javap-checked against 26.2). {@code recipe_book/button} - the
     * green book button - is deliberately absent: the screen draws it outside the scope, and
     * {@code RecipeBookButtonMixin} owns it.
     */
    private static final Map<Identifier, Part> PARTS = Map.ofEntries(
            Map.entry(Identifier.withDefaultNamespace("textures/gui/recipe_book.png"), Part.BACKDROP),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/tab"), Part.TAB),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/tab_selected"), Part.TAB_SELECTED),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/slot_craftable"), Part.SLOT),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/slot_many_craftable"), Part.SLOT_MANY),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/slot_uncraftable"), Part.SLOT_UNCRAFTABLE),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/slot_many_uncraftable"),
                    Part.SLOT_MANY_UNCRAFTABLE),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/page_forward"), Part.PAGE_FORWARD),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/page_forward_highlighted"),
                    Part.PAGE_FORWARD_HOVER),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/page_backward"), Part.PAGE_BACKWARD),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/page_backward_highlighted"),
                    Part.PAGE_BACKWARD_HOVER),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/filter_enabled"), Part.FILTER_ON),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/filter_enabled_highlighted"),
                    Part.FILTER_ON_HOVER),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/filter_disabled"), Part.FILTER_OFF),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/filter_disabled_highlighted"),
                    Part.FILTER_OFF_HOVER),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/furnace_filter_enabled"), Part.FILTER_ON),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/furnace_filter_enabled_highlighted"),
                    Part.FILTER_ON_HOVER),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/furnace_filter_disabled"), Part.FILTER_OFF),
            Map.entry(Identifier.withDefaultNamespace("recipe_book/furnace_filter_disabled_highlighted"),
                    Part.FILTER_OFF_HOVER),
            Map.entry(Identifier.withDefaultNamespace("widget/text_field"), Part.SEARCH),
            Map.entry(Identifier.withDefaultNamespace("widget/text_field_highlighted"), Part.SEARCH_FOCUSED));

    private static final int UNCRAFTABLE_EDGE = 0xAAFF5555;

    /** True only while a themable recipe book is drawing, on the render thread. */
    private static boolean active;
    /** The class of the book last reported, so the log line comes once per book, not per frame. */
    private static Class<?> reported;

    private RecipeBookSkin() {
    }

    /** Opens the scope for one book's draw. Returns whether it is themed, for the matching {@link #end}. */
    public static boolean begin(RecipeBookComponent<?> book) {
        SBSConfig.MinecraftOverlaySettings cfg = ConfigManager.getInstance().get().minecraftOverlay;
        boolean vanilla = book.getClass().getName().startsWith("net.minecraft.");
        boolean themed = cfg.enabled && (vanilla || cfg.themeOtherMods);
        if (book.getClass() != reported) {
            reported = book.getClass();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Theme] recipe book is {} - {}",
                    vanilla ? "vanilla" : "replaced by another mod",
                    themed ? "themed" : vanilla ? "not themed (Minecraft Overlay off)"
                            : "not themed (theme other mods is off)");
        }
        active = themed;
        return themed;
    }

    public static void end() {
        active = false;
    }

    /**
     * Draws the SBS shape in place of {@code sprite} when it is a recipe-book sprite and a themed
     * book is drawing. Returns whether it did - the caller then skips the vanilla draw.
     */
    public static boolean draw(GuiGraphicsExtractor g, Identifier sprite, int x, int y, int w, int h) {
        if (!active || sprite == null) {
            return false;
        }
        Part part = PARTS.get(sprite);
        if (part == null) {
            return false;
        }
        switch (part) {
            case BACKDROP -> {
                // The same three coats as the container panel, so book and menu read as one window.
                SciFiRender.roundedRect(g, x - 2, y - 2, w + 4, h + 4, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
                SciFiRender.roundedRect(g, x - 1, y - 1, w + 2, h + 2, SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_BASE);
                SciFiRender.roundedRectGradient(g, x - 1, y - 1, w + 2, h + 2, SBSTheme.PANEL_CORNER - 1,
                        SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            }
            case TAB -> SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                    SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
            case TAB_SELECTED -> {
                SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                        SBSTheme.CARD_BG_HOVER, SBSTheme.ACCENT_BRIGHT);
                g.fill(x + 1, y + 2, x + 3, y + h - 2, SBSTheme.ACCENT);   // a bar, not only a colour
            }
            case SLOT, SLOT_MANY -> slot(g, x, y, w, h, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER,
                    part == Part.SLOT_MANY);
            case SLOT_UNCRAFTABLE, SLOT_MANY_UNCRAFTABLE -> slot(g, x, y, w, h, SBSTheme.CARD_BG_DISABLED,
                    UNCRAFTABLE_EDGE, part == Part.SLOT_MANY_UNCRAFTABLE);
            case PAGE_FORWARD, PAGE_FORWARD_HOVER -> arrow(g, x, y, w, h, true,
                    part == Part.PAGE_FORWARD_HOVER);
            case PAGE_BACKWARD, PAGE_BACKWARD_HOVER -> arrow(g, x, y, w, h, false,
                    part == Part.PAGE_BACKWARD_HOVER);
            case FILTER_ON, FILTER_ON_HOVER, FILTER_OFF, FILTER_OFF_HOVER -> filter(g, x, y, w, h,
                    part == Part.FILTER_ON || part == Part.FILTER_ON_HOVER,
                    part == Part.FILTER_ON_HOVER || part == Part.FILTER_OFF_HOVER);
            case SEARCH, SEARCH_FOCUSED -> SciFiRender.roundedRectWithBorder(g, x, y, w, h,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    part == Part.SEARCH_FOCUSED ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        }
        return true;
    }

    /** A recipe tile. "Many" (several recipes behind it) shows as a second card edge behind it. */
    private static void slot(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int edge, boolean many) {
        if (many) {
            SciFiRender.roundedRectWithBorder(g, x + 2, y + 2, w - 2, h - 2, SBSTheme.CORNER_RADIUS - 1,
                    fill, edge);
            SciFiRender.roundedRectWithBorder(g, x, y, w - 2, h - 2, SBSTheme.CORNER_RADIUS - 1, fill, edge);
        } else {
            SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS - 1, fill, edge);
        }
    }

    /**
     * A page arrow: a chevron ({@code >} or {@code <}), one stroke per row, each row offset by its
     * distance from the centre line so the two arms meet in a point on the paging side.
     */
    private static void arrow(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean forward, boolean hover) {
        int color = hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT;
        int stroke = Math.max(2, Math.min(3, w / 4));
        int reach = Math.max(1, w - stroke);
        int half = Math.max(1, h / 2);
        for (int row = 0; row < h; row++) {
            int distance = Math.abs(row - half);
            int offset = Math.min(reach, distance * reach / half);
            int sx = forward ? x + reach - offset : x + offset;
            g.fill(sx, y + row, sx + stroke, y + row + 1, color);
        }
    }

    /** The filter toggle: a card with three narrowing bars, lit when the filter is on. */
    private static void filter(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean on, boolean hover) {
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                on ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
        int bar = on ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED;
        int cx = x + w / 2;
        int top = y + (h - 7) / 2;
        for (int i = 0; i < 3; i++) {
            int half = 5 - i * 2;
            g.fill(cx - half, top + i * 3, cx + half, top + i * 3 + 1, bar);
        }
        if (!on) {
            g.fill(cx - 6, top + 7, cx + 6, top + 8, SBSTheme.TEXT_MUTED);   // struck through: off
        }
    }
}
