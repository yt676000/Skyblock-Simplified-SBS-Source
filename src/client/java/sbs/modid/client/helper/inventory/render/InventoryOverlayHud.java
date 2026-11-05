/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.helper.inventory.logic.SlotLock;
import sbs.modid.client.helper.inventory.ui.InventoryOverlay;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.theme.style.SurfaceMaterial;
import sbs.modid.client.ui.hud.edit.logic.HudOpacity;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

/**
 * The HUD half of the Inventory Overlay: the 27 main-inventory slots drawn above the hotbar while
 * playing, so the whole inventory is readable without opening anything.
 *
 * <p>Free movement and resizing come from the existing GUI editor rather than a bespoke drag
 * implementation: the element is registered as {@link HudElement#INVENTORY_OVERLAY} and this render
 * is wrapped in {@link HudLayout#begin}/{@link HudLayout#end}, which applies the saved offset and
 * scale. That also means it is hideable from the editor like every other HUD element.
 *
 * <p>Transparency applies to the panel and cells. The items themselves stay fully opaque – a faded
 * item is unreadable, which would defeat the point of the overlay.
 *
 * <p>This is HUD paint only: it never captures the mouse, so switching it on cannot interrupt play.
 * Clicking items is the transparent-screen mode's job (see {@link InventoryOverlay}).
 */
public final class InventoryOverlayHud {

    /** Grid geometry – must match {@code HudElement.INVENTORY_OVERLAY}'s default bounds (162x54). */
    private static final int COLS = 9;
    private static final int ROWS = 3;
    private static final int CELL = 18;
    /** Cell corner radius; {@link #drawCell} hard-codes the radius-2 arc. */
    private static final int CELL_RADIUS = 2;

    /** Main inventory occupies player-inventory indices 9..35 (0..8 being the hotbar). */
    private static final int FIRST_SLOT = 9;

    private InventoryOverlayHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!InventoryOverlay.hudActive() || minecraft.player == null
                || HudLayout.isHidden(HudElement.INVENTORY_OVERLAY)) {
            return;
        }
        // An open container (incl. the player inventory) already shows these slots; drawing the
        // overlay under it only duplicates them. Every other screen keeps it.
        if (minecraft.gui.screen() instanceof AbstractContainerScreen<?>) {
            return;
        }
        HudElement.Bounds bounds = HudElement.INVENTORY_OVERLAY
                .defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) bounds.x();
        int y = (int) bounds.y();

        HudLayout.begin(g, HudElement.INVENTORY_OVERLAY);
        drawGrid(g, minecraft.player.getInventory(), x, y);
        HudLayout.end(g);
    }

    /** The panel, one cell per slot, the items, and a padlock on any locked slot. */
    private static void drawGrid(GuiGraphicsExtractor g, Inventory inventory, int x, int y) {
        int w = COLS * CELL;
        int h = ROWS * CELL;
        SciFiRender.roundedRectWithBorder(g, x - 2, y - 2, w + 4, h + 4, SBSTheme.HUD_CORNER,
                InventoryOverlay.withOpacity(SBSTheme.PANEL_FILL_TOP),
                InventoryOverlay.withOpacity(SBSTheme.CARD_BORDER));

        // Hoisted: one config read and one font lookup per frame instead of one per cell.
        int cellColor = InventoryOverlay.withOpacity(SBSTheme.CARD_BG);
        boolean plainCells = !(HudOpacity.hidesBody() && SBSTheme.isBorderColor(cellColor));
        SurfaceMaterial material = SBSTheme.material();
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = FIRST_SLOT + row * COLS + col;
                int cx = x + col * CELL;
                int cy = y + row * CELL;
                if (plainCells) {
                    drawCell(g, material, cx, cy, cellColor);
                } else {
                    SciFiRender.roundedRect(g, cx, cy, CELL - 1, CELL - 1, CELL_RADIUS, cellColor);
                }

                ItemStack stack = inventory.getItem(index);
                if (!stack.isEmpty()) {
                    g.item(stack, cx + 1, cy + 1);
                    g.itemDecorations(font, stack, cx + 1, cy + 1);
                }
                if (SlotLock.isLocked(index)) {
                    SlotLockIcon.draw(g, cx + 1, cy + 1);
                }
            }
        }
    }

    /**
     * Pixel-identical to {@code SciFiRender.roundedRect(g, x, y, 17, 17, 2, color)} in 3 fills
     * instead of 5. At radius 2 the corner insets are {1, 0}: only the outermost row on each side is
     * inset, so the second rows merge into the body. The fills do not overlap, so translucent cells
     * blend exactly as before. Every fill costs a vanilla render-state + matrix allocation in 26.2,
     * which makes this 54 fewer allocations per frame.
     */
    private static void drawCell(GuiGraphicsExtractor g, SurfaceMaterial material, int x, int y,
                                 int color) {
        int s = CELL - 1;
        g.fill(x + 1, y, x + s - 1, y + 1, color);
        g.fill(x, y + 1, x + s, y + s - 1, color);
        g.fill(x + 1, y + s - 1, x + s - 1, y + s, color);
        material.paintBody(g, x, y, s, s, CELL_RADIUS, color, SurfaceMaterial.Weight.SLOT);
    }
}
