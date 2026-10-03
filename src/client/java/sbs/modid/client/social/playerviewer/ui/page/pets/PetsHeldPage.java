/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.pets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pets ▸ Held Items – which pet items the player owns and which pets carry them. Pet items are
 * expensive and easy to forget on a shelved pet, so this groups by item and names every carrier.
 */
public final class PetsHeldPage implements PvPage {

    private static final int ROW_H = 20;

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonArray pets = PvDraw.arr(ctx.profile, "pets");
        // item id -> the pets holding it, strongest first (the pet list arrives pre-sorted).
        Map<String, List<JsonObject>> byItem = new LinkedHashMap<>();
        for (JsonElement element : pets) {
            JsonObject pet = element.getAsJsonObject();
            if (PvDraw.has(pet, "item")) {
                byItem.computeIfAbsent(PvDraw.str(pet, "item"), k -> new ArrayList<>()).add(pet);
            }
        }
        if (byItem.isEmpty()) {
            PvDraw.empty(g, ctx, "No pet items equipped.");
            return;
        }
        int visibleRows = Math.max(1, ctx.height / ROW_H);
        int scroll = ctx.rows(byItem.size(), visibleRows);
        List<Map.Entry<String, List<JsonObject>>> entries = new ArrayList<>(byItem.entrySet());

        boolean scrollable = entries.size() > visibleRows;
        int rowW = ctx.width - (scrollable ? 6 : 0);
        int y = ctx.y;
        for (int i = scroll; i < entries.size() && i < scroll + visibleRows; i++) {
            drawRow(g, ctx, entries.get(i).getKey(), entries.get(i).getValue(), ctx.x, y, rowW);
            y += ROW_H;
        }
        if (scrollable) {
            PvDraw.scrollbar(g, ctx.x + ctx.width - 3, ctx.y, visibleRows * ROW_H,
                    entries.size(), visibleRows, scroll);
        }
    }

    /** One item row: the item icon and name, then the pets carrying it, trimmed to fit. */
    private void drawRow(GuiGraphicsExtractor g, PvContext ctx, String itemId,
                         List<JsonObject> carriers, int x, int y, int w) {
        int h = ROW_H - 2;
        boolean hovered = ctx.hovered(x, y, w, h);
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        g.item(SkyBlockItemIcons.getInstance().icon(itemId, null, 1), x + 2, y + 1);
        int textY = y + (h - ctx.font.lineHeight) / 2;

        // The carrier list is right-aligned and the item name takes what is left, so a pet with a
        // long name can never push the item name off its own row.
        StringBuilder carried = new StringBuilder();
        for (JsonObject pet : carriers) {
            String color = PvDraw.TIER_COLOR.getOrDefault(PvDraw.str(pet, "tier"), "§f");
            if (!carried.isEmpty()) {
                carried.append("§8, ");
            }
            carried.append(color).append(PvDraw.pretty(PvDraw.str(pet, "type")));
        }
        String right = PvDraw.trim(ctx.font, carried.toString(), w / 2);
        g.text(ctx.font, Component.literal(right), x + w - 4 - ctx.font.width(right), textY,
                SBSTheme.TEXT);

        int nameX = x + PvDraw.CELL + 4;
        int space = (x + w - 8 - ctx.font.width(right)) - nameX;
        if (space > 8) {
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font,
                            "§f" + PvDraw.pretty(itemId), space)), nameX, textY, SBSTheme.TEXT);
        }
        if (hovered) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("§f" + PvDraw.pretty(itemId)));
            tip.add(Component.literal("§8held by " + carriers.size()
                    + (carriers.size() == 1 ? " pet" : " pets")));
            for (JsonObject pet : carriers) {
                String color = PvDraw.TIER_COLOR.getOrDefault(PvDraw.str(pet, "tier"), "§f");
                tip.add(Component.literal("§7• " + color + PvDraw.pretty(PvDraw.str(pet, "type"))
                        + " §7[" + pet.get("level").getAsInt() + "]"));
            }
            PvDraw.tooltip(g, ctx.font, tip, ctx.mouseX, ctx.mouseY);
        }
    }
}
