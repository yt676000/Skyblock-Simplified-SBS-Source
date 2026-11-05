/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * One storage container as its real, slot-faithful 9-wide item grid – Inventory, Accessory Bag,
 * Quiver, Potion Bag, Bait Sack, Personal Vault. The paged containers (Ender Chest, Backpacks) are
 * {@link StoragePagesPage}.
 *
 * <p>Each container is its own sub-page now, so the old middle "container selector" column is gone
 * and the grid gets that width back.
 */
public final class ContainerPage implements PvPage {

    /** The {@code inventories} key this page shows ({@link PvDraw#containerKey} resolves a renamed
     *  one to its legacy name). */
    private final String key;
    private final String label;

    public ContainerPage(String key, String label) {
        this.key = key;
        this.label = label;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject invs = PvDraw.obj(ctx.profile, "inventories");
        boolean apiOff = ctx.profile.has("inventory_api") && !ctx.profile.get("inventory_api").getAsBoolean();
        if (invs == null || !invs.has(PvDraw.containerKey(invs, key))) {
            PvDraw.empty(g, ctx, apiOff
                    ? "This player's Inventory API is disabled." : "No " + label + " data.");
            return;
        }
        drawGrid(g, ctx, invs.getAsJsonArray(PvDraw.containerKey(invs, key)), ctx.y);
    }

    private void drawGrid(GuiGraphicsExtractor g, PvContext ctx, JsonArray slots, int top) {
        int visibleRows = Math.max(1, (ctx.bottom() - top) / PvDraw.CELL);
        int totalRows = (slots.size() + PvDraw.GRID_COLS - 1) / PvDraw.GRID_COLS;
        int scroll = ctx.rows(totalRows, visibleRows);
        int y = top;
        for (int row = scroll; row < totalRows && row < scroll + visibleRows; row++) {
            for (int col = 0; col < PvDraw.GRID_COLS; col++) {
                int idx = row * PvDraw.GRID_COLS + col;
                PvDraw.slot(g, ctx.font, idx < slots.size() ? slots.get(idx) : null,
                        ctx.x + col * PvDraw.CELL, y, ctx.mouseX, ctx.mouseY);
            }
            y += PvDraw.CELL;
        }
        if (totalRows > visibleRows) {
            PvDraw.scrollbar(g, ctx.x + PvDraw.GRID_COLS * PvDraw.CELL + 4, top,
                    visibleRows * PvDraw.CELL, totalRows, visibleRows, scroll);
        }
    }
}
