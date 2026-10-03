/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Search results across every container of the profile. The shell swaps this in for the selected
 * Inventory sub-page whenever the search box has text, so "where is my Hyperion" is answered
 * without clicking through Ender Chest, backpacks and vault one by one.
 */
public final class ItemSearchPage implements PvPage {

    /** Where the shell's search box currently stands; read fresh every frame. */
    private final Supplier<String> query;

    /** Container key -> the label shown in a hit's tooltip. */
    private static final String[][] SOURCES = {
            {"inventory", "Inventory"}, {"ender_chest", "Ender Chest"},
            {"accessory_bag", "Accessory Bag"}, {"quiver", "Quiver"},
            {"potion_bag", "Potion Bag"}, {"fishing_bag", "Fishing Bag"},
            {"vault", "Personal Vault"}, {"armor", "Armor"}, {"equipment", "Equipment"}};

    public ItemSearchPage(Supplier<String> query) {
        this.query = query;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject invs = PvDraw.obj(ctx.profile, "inventories");
        if (invs == null) {
            PvDraw.empty(g, ctx, "No storage data.");
            return;
        }
        String q = query.get().trim().toLowerCase(Locale.ROOT);
        List<JsonObject> hits = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (String[] source : SOURCES) {
            collect(PvDraw.arr(invs, source[0]), source[1], q, hits, sources);
        }
        int packIndex = 1;
        for (JsonElement pack : PvDraw.arr(invs, "backpacks")) {
            collect(pack.getAsJsonArray(), "Backpack " + packIndex++, q, hits, sources);
        }
        // Saved loadouts hold real stored items too, so a piece parked in a set is findable
        // instead of looking lost.
        for (JsonElement element : PvDraw.arr(PvDraw.obj(ctx.profile, "loadouts"), "sets")) {
            JsonObject set = element.getAsJsonObject();
            String label = PvDraw.str(set, "name");
            collect(PvDraw.arr(set, "armor"), label, q, hits, sources);
            collect(PvDraw.arr(set, "equipment"), label, q, hits, sources);
        }
        if (hits.isEmpty()) {
            PvDraw.empty(g, ctx, "No matching items.");
            return;
        }

        int listBottom = ctx.bottom() - ctx.font.lineHeight - 2;
        int visibleRows = Math.max(1, (listBottom - ctx.y) / PvDraw.CELL);
        int totalRows = (hits.size() + PvDraw.GRID_COLS - 1) / PvDraw.GRID_COLS;
        int scroll = ctx.rows(totalRows, visibleRows);
        int y = ctx.y;
        for (int row = scroll; row < totalRows && row < scroll + visibleRows; row++) {
            for (int col = 0; col < PvDraw.GRID_COLS; col++) {
                int idx = row * PvDraw.GRID_COLS + col;
                int x = ctx.x + col * PvDraw.CELL;
                PvDraw.cell(g, x, y);
                if (idx >= hits.size()) {
                    continue;
                }
                JsonObject slot = hits.get(idx);
                ItemStack stack = PvDraw.iconFor(slot);
                g.item(stack, x + 1, y + 1);
                if (PvDraw.num(slot, "count") > 1) {
                    g.itemDecorations(ctx.font, stack, x + 1, y + 1);
                }
                if (ctx.hovered(x, y, PvDraw.CELL, PvDraw.CELL)) {
                    PvDraw.tooltip(g, ctx.font, PvDraw.slotTooltip(slot,
                                    "§8" + sources.get(idx) + " • " + PvDraw.str(slot, "id")),
                            ctx.mouseX, ctx.mouseY);
                }
            }
            y += PvDraw.CELL;
        }
        g.text(ctx.font, Component.literal("§8" + hits.size() + " hit"
                        + (hits.size() == 1 ? "" : "s") + " across all containers"),
                ctx.x, listBottom + 2, SBSTheme.TEXT_MUTED);
    }

    private void collect(JsonArray slots, String source, String q,
                         List<JsonObject> hits, List<String> sources) {
        for (JsonElement element : slots) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject slot = element.getAsJsonObject();
            // Strip color codes, otherwise they cut search hits apart inside the name.
            String hay = (PvDraw.str(slot, "name") + " " + PvDraw.str(slot, "id"))
                    .replaceAll("§.", "").toLowerCase(Locale.ROOT);
            if (hay.contains(q)) {
                hits.add(slot);
                sources.add(source);
            }
        }
    }
}
