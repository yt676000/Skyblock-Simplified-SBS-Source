/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventorybuttons.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.helper.inventorybuttons.logic.InventoryButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a single inventory button, in the SBS card look.
 *
 * <p>Shared by the live overlay and the editor so a button looks the same in both – placing it and
 * using it should not be two different visual experiences.
 */
public final class InventoryButtonRenderer {

    /** Colour of the "…" edit affordance shown in the editor. */
    private static final int DOTS_COLOR = 0xFFFFFFFF;

    /** Edge length of the square resize grip in the bottom-right corner (editor only). */
    public static final int GRIP = 4;

    /** Vanilla's item render size – the basis the icon is scaled from. */
    private static final int ICON_SIZE = 16;

    /** Breathing room kept between the icon and the button's border, per side. */
    private static final int ICON_PADDING = 1;

    private InventoryButtonRenderer() {
    }

    /**
     * Draws the button at absolute screen coordinates, at its own {@linkplain InventoryButton#width()
     * width} and {@linkplain InventoryButton#height() height}.
     *
     * @param hovered whether the pointer is over it
     * @param editing whether the editor's extras ("…" badge, resize grip) should show
     */
    public static void draw(GuiGraphicsExtractor g, InventoryButton button, int x, int y,
                            boolean hovered, boolean editing, boolean selected) {
        int w = button.width();
        int h = button.height();
        int border = selected ? SBSTheme.ACCENT
                : hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h,
                SBSTheme.CORNER_RADIUS, hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, border);
        drawIcon(g, button, x, y, w, h);

        if (editing) {
            // A small "…" badge in the corner: the handle for the per-button editor.
            g.text(Minecraft.getInstance().font, Component.literal("…"),
                    x + w - 5, y - 1, DOTS_COLOR);
            drawGrip(g, x, y, w, h, hovered || selected);
        }
    }

    /**
     * The icon, scaled to fill the button and centred in it.
     *
     * <p>{@code g.item} always draws 16x16, so a freely sized button needs the pose stack rather than
     * different coordinates – the same technique the GUI editor uses for HUD elements. Aspect ratio is
     * kept (the smaller axis wins) so a wide button gets a centred icon instead of a stretched one. At
     * the default 18x18 the scale works out to exactly 1, reproducing the original look.
     */
    private static void drawIcon(GuiGraphicsExtractor g, InventoryButton button,
                                 int x, int y, int w, int h) {
        float scale = Math.min((w - ICON_PADDING * 2) / (float) ICON_SIZE,
                (h - ICON_PADDING * 2) / (float) ICON_SIZE);
        float drawn = ICON_SIZE * scale;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x + (w - drawn) / 2f, y + (h - drawn) / 2f);
        pose.scale(scale);
        g.item(button.iconStack(), 0, 0);
        pose.popMatrix();
    }

    /** The corner ticks marking the drag-to-resize grip. */
    private static void drawGrip(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active) {
        int color = active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
        int gx = x + w - GRIP;
        int gy = y + h - GRIP;
        g.fill(gx, gy + GRIP - 1, gx + GRIP, gy + GRIP, color);
        g.fill(gx + GRIP - 1, gy, gx + GRIP, gy + GRIP, color);
    }

    /** Whether a panel-relative point is on a button's "…" badge (editor only). */
    public static boolean hitsDots(InventoryButton button, double relX, double relY) {
        int bx = button.x + button.width() - 6;
        int by = button.y - 1;
        return relX >= bx && relX < bx + 7 && relY >= by && relY < by + 8;
    }

    /** Whether a panel-relative point is on a button's resize grip (editor only). */
    public static boolean hitsGrip(InventoryButton button, double relX, double relY) {
        int gx = button.x + button.width() - GRIP;
        int gy = button.y + button.height() - GRIP;
        return relX >= gx && relX < gx + GRIP && relY >= gy && relY < gy + GRIP;
    }

    /** The hover tooltip: the button's name plus what it will run. */
    public static List<Component> tooltip(InventoryButton button, boolean editing) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§f" + button.name));
        if (button.runnable()) {
            lines.add(Component.literal("§8/" + button.cleanCommand()));
        } else {
            lines.add(Component.literal("§cNo command set"));
        }
        if (editing) {
            lines.add(Component.literal("§8drag to move  •  corner to resize  •  … to edit"));
        }
        return lines;
    }
}
