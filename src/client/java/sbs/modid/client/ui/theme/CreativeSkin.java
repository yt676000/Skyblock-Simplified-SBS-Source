/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.render.SciFiRender;

import java.util.HashMap;
import java.util.Map;

/**
 * Minecraft Overlay module: the creative inventory in the SBS look, by swapping its <b>draw calls</b>
 * rather than painting over it.
 *
 * <p><b>Why not the container reskin.</b> {@code SbsContainerThemeMixin} paints one panel over the
 * whole GUI rectangle, which is only safe on plain grids (the allowlist there - an anvil once lost its
 * name field and cost under it). The creative screen draws working parts in its own background pass:
 * the tab buttons, the scroll bar knob, the search field, the Destroy Item slot and the player preview
 * on the inventory tab. Painted over, they would vanish. So, as with the recipe book
 * ({@link RecipeBookSkin}), each vanilla sprite is replaced by its SBS counterpart at the call that
 * draws it, and everything vanilla draws itself - items, the search text, the player, the tab icons,
 * all interaction - is untouched.
 *
 * <p><b>The seam</b> (javap, 26.2, {@code CreativeModeInventoryScreen.extractBackground}): unselected
 * tabs ({@code blitSprite} with {@code container/creative_inventory/tab_top|bottom_unselected_1..7}),
 * then the tab's background texture ({@code blit} of {@code CreativeModeTab.getBackgroundTexture()},
 * {@code textures/gui/container/creative_inventory/tab_*.png}, which also carries the slot squares, the
 * scroll track, the search bar and the Destroy Item X), the search box, the scroller
 * ({@code scroller} / {@code scroller_disabled}), the selected tab ({@code ..._selected_N}), and on the
 * inventory tab the player. The scope is armed only for that method, so no other screen's sprites are
 * ever matched.
 *
 * <p><b>What the background texture carried is redrawn from the live screen</b>, never hard-coded:
 * slot cells at the menu's own slot positions (the item tabs and the inventory tab lay out differently,
 * and the inventory tab parks its crafting slots far off screen), the Destroy Item slot by reference,
 * the search field at the search box's bounds. The two fixed geometries - the scroll track and the
 * player box - are vanilla's own constants from the same method ({@code insideScrollbar}; the player
 * drawn at 73,6 - 105,49).
 */
public final class CreativeSkin {

    private enum Part { TAB, TAB_SELECTED, SCROLLER, SCROLLER_DISABLED }

    private static final String BACKGROUND_PREFIX = "textures/gui/container/creative_inventory/tab_";
    private static final Map<Identifier, Part> PARTS = new HashMap<>();

    static {
        PARTS.put(Identifier.withDefaultNamespace("container/creative_inventory/scroller"), Part.SCROLLER);
        PARTS.put(Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled"),
                Part.SCROLLER_DISABLED);
        for (int i = 1; i <= 7; i++) {
            PARTS.put(Identifier.withDefaultNamespace("container/creative_inventory/tab_top_unselected_" + i), Part.TAB);
            PARTS.put(Identifier.withDefaultNamespace("container/creative_inventory/tab_bottom_unselected_" + i), Part.TAB);
            PARTS.put(Identifier.withDefaultNamespace("container/creative_inventory/tab_top_selected_" + i),
                    Part.TAB_SELECTED);
            PARTS.put(Identifier.withDefaultNamespace("container/creative_inventory/tab_bottom_selected_" + i),
                    Part.TAB_SELECTED);
        }
    }

    /** Vanilla's scroll track, relative to the GUI origin ({@code insideScrollbar}, 26.2). */
    private static final int TRACK_X = 175;
    private static final int TRACK_Y = 18;
    private static final int TRACK_W = 12;
    private static final int TRACK_H = 112;
    /** Where vanilla draws the player on the inventory tab (73,6 - 105,49). */
    private static final int PLAYER_X1 = 73;
    private static final int PLAYER_Y1 = 6;
    private static final int PLAYER_X2 = 105;
    private static final int PLAYER_Y2 = 49;

    private static final int DESTROY_EDGE = 0xFFFF5555;

    /** True only while a themed creative background is drawing, on the render thread. */
    private static boolean active;
    private static AbstractContainerMenu menu;
    private static int imageWidth;
    private static int imageHeight;
    private static EditBox searchBox;
    private static Slot destroySlot;
    private static CreativeModeTab.Type tabType;

    private CreativeSkin() {
    }

    /** Whether the creative screen is themed right now (the Minecraft Overlay switch). */
    public static boolean themed() {
        return ConfigManager.getInstance().get().minecraftOverlay.enabled;
    }

    /** Opens the scope for one background pass; a no-op when the theme is off. */
    public static void begin(AbstractContainerMenu openMenu, int width, int height, EditBox search, Slot destroy,
                             CreativeModeTab selected) {
        active = themed();
        menu = openMenu;
        imageWidth = width;
        imageHeight = height;
        searchBox = search;
        destroySlot = destroy;
        tabType = selected == null ? CreativeModeTab.Type.CATEGORY : selected.getType();
    }

    public static void end() {
        active = false;
        menu = null;
        searchBox = null;
        destroySlot = null;
    }

    /**
     * Draws the SBS piece in place of {@code sprite} when it is a creative sprite and the scope is open.
     * Returns whether it did - the caller then skips the vanilla draw.
     */
    public static boolean draw(GuiGraphicsExtractor g, Identifier sprite, int x, int y, int w, int h) {
        if (!active || sprite == null) {
            return false;
        }
        if (sprite.getNamespace().equals("minecraft") && sprite.getPath().startsWith(BACKGROUND_PREFIX)) {
            background(g, x, y);
            return true;
        }
        Part part = PARTS.get(sprite);
        if (part == null) {
            return false;
        }
        switch (part) {
            case TAB -> SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                    SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
            case TAB_SELECTED -> {
                SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                        SBSTheme.CARD_BG_HOVER, SBSTheme.ACCENT_BRIGHT);
                g.fill(x + 3, y + 2, x + w - 3, y + 4, SBSTheme.ACCENT);   // a bar, not only a colour
            }
            case SCROLLER -> SciFiRender.roundedRectWithBorder(g, x, y, w, h, 3, SBSTheme.ACCENT,
                    SBSTheme.ACCENT_BRIGHT);
            case SCROLLER_DISABLED -> SciFiRender.roundedRectWithBorder(g, x, y, w, h, 3,
                    SBSTheme.CARD_BG_DISABLED, SBSTheme.CARD_BORDER);
        }
        return true;
    }

    /**
     * The panel in place of the tab texture, plus everything that texture carried: slot cells, the
     * scroll track, the search bar, the player box and the Destroy Item X. {@code (x, y)} is the GUI
     * origin - the texture is blitted at {@code leftPos, topPos}.
     */
    private static void background(GuiGraphicsExtractor g, int x, int y) {
        int w = imageWidth;
        int h = imageHeight;
        SciFiRender.roundedRect(g, x - 2, y - 2, w + 4, h + 4, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRect(g, x - 1, y - 1, w + 2, h + 2, SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_BASE);
        SciFiRender.roundedRectGradient(g, x - 1, y - 1, w + 2, h + 2, SBSTheme.PANEL_CORNER - 1,
                SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        if (tabType != CreativeModeTab.Type.INVENTORY) {
            SciFiRender.roundedRectWithBorder(g, x + TRACK_X - 1, y + TRACK_Y - 1, TRACK_W + 2, TRACK_H + 2,
                    3, SBSTheme.SEARCH_FILL, SBSTheme.CARD_BORDER);
        } else {
            SciFiRender.roundedRectWithBorder(g, x + PLAYER_X1 - 1, y + PLAYER_Y1 - 1,
                    PLAYER_X2 - PLAYER_X1 + 2, PLAYER_Y2 - PLAYER_Y1 + 2, SBSTheme.CORNER_RADIUS,
                    SBSTheme.SEARCH_FILL, SBSTheme.CARD_BORDER);
        }
        if (tabType == CreativeModeTab.Type.SEARCH && searchBox != null) {
            SciFiRender.roundedRectWithBorder(g, searchBox.getX() - 2, searchBox.getY() - 2,
                    searchBox.getWidth() + 4, searchBox.getHeight() + 3, SBSTheme.CORNER_RADIUS,
                    SBSTheme.SEARCH_FILL, searchBox.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        }
        if (menu == null) {
            return;
        }
        for (Slot slot : menu.slots) {
            // Only slots on the panel: the inventory tab parks its crafting slots far off screen.
            if (!slot.isActive() || slot.x < 0 || slot.y < 0 || slot.x > w || slot.y > h) {
                continue;
            }
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            if (slot == destroySlot) {
                SciFiRender.roundedRectWithBorder(g, sx, sy, 18, 18, SBSTheme.SLOT_CORNER,
                        SBSTheme.SLOT_BG, DESTROY_EDGE);
                cross(g, sx + 4, sy + 4, 10, DESTROY_EDGE);
            } else {
                SciFiRender.roundedRectWithBorder(g, sx, sy, 18, 18, SBSTheme.SLOT_CORNER,
                        SBSTheme.SLOT_BG, SBSTheme.CARD_BORDER);
            }
        }
    }

    /** The Destroy Item X: two diagonals, two pixels thick - a shape, not only a colour. */
    private static void cross(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        for (int i = 0; i < size; i++) {
            g.fill(x + i, y + i, x + i + 2, y + i + 1, color);
            g.fill(x + size - 2 - i, y + i, x + size - i, y + i + 1, color);
        }
    }
}
