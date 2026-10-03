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
 * One storage container as its real, slot-faithful 9-wide item grid – Inventory, Ender Chest,
 * Wardrobe, Accessory Bag, Personal Vault, or the Backpacks (which add a pager, since a profile has
 * many of them).
 *
 * <p>Each container is its own sub-page now, so the old middle "container selector" column is gone
 * and the grid gets that width back.
 */
public final class ContainerPage implements PvPage {

    /** The {@code inventories} key this page shows; {@code "backpacks"} selects the paged mode. */
    private final String key;
    private final String label;

    /** Selected backpack, only used in the paged mode. */
    private int pack;

    public ContainerPage(String key, String label) {
        this.key = key;
        this.label = label;
    }

    private boolean paged() {
        return "backpacks".equals(key);
    }

    @Override
    public void reset() {
        pack = 0;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject invs = PvDraw.obj(ctx.profile, "inventories");
        boolean apiOff = ctx.profile.has("inventory_api") && !ctx.profile.get("inventory_api").getAsBoolean();
        if (invs == null || !invs.has(key)) {
            PvDraw.empty(g, ctx, apiOff
                    ? "This player's Inventory API is disabled." : "No " + label + " data.");
            return;
        }
        int top = ctx.y;
        if (paged()) {
            top = drawPager(g, ctx, invs.getAsJsonArray(key));
        }
        drawGrid(g, ctx, slots(invs), top);
    }

    private JsonArray slots(JsonObject invs) {
        if (!paged()) {
            return invs.getAsJsonArray(key);
        }
        JsonArray packs = invs.getAsJsonArray(key);
        return pack >= 0 && pack < packs.size() ? packs.get(pack).getAsJsonArray() : new JsonArray();
    }

    /** The backpack selector: one numbered chip per backpack, wrapped over as many rows as needed. */
    private int drawPager(GuiGraphicsExtractor g, PvContext ctx, JsonArray packs) {
        if (pack >= packs.size()) {
            pack = 0;
        }
        int chip = 18;
        int gap = 3;
        int perRow = Math.max(1, (ctx.width + gap) / (chip + gap));
        int y = ctx.y;
        for (int i = 0; i < packs.size(); i++) {
            int cx = ctx.x + (i % perRow) * (chip + gap);
            int cy = y + (i / perRow) * (chip + gap);
            boolean sel = i == pack;
            boolean hov = ctx.hovered(cx, cy, chip, chip);
            SciFiRender.roundedRectWithBorder(g, cx, cy, chip, chip, 3,
                    sel || hov ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    sel ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            g.centeredText(ctx.font, Component.literal(String.valueOf(i + 1)),
                    cx + chip / 2, cy + (chip - ctx.font.lineHeight) / 2,
                    sel ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
        }
        int rows = (packs.size() + perRow - 1) / perRow;
        return y + rows * (chip + gap) + 3;
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

    @Override
    public boolean mouseClicked(PvContext ctx, double mouseX, double mouseY) {
        if (!paged()) {
            return false;
        }
        JsonObject invs = PvDraw.obj(ctx.profile, "inventories");
        if (invs == null || !invs.has(key)) {
            return false;
        }
        JsonArray packs = invs.getAsJsonArray(key);
        int chip = 18;
        int gap = 3;
        int perRow = Math.max(1, (ctx.width + gap) / (chip + gap));
        for (int i = 0; i < packs.size(); i++) {
            int cx = ctx.x + (i % perRow) * (chip + gap);
            int cy = ctx.y + (i / perRow) * (chip + gap);
            if (mouseX >= cx && mouseX < cx + chip && mouseY >= cy && mouseY < cy + chip) {
                pack = i;
                return true;
            }
        }
        return false;
    }
}
