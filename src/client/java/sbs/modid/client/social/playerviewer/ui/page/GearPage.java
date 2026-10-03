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
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Inventory ▸ Overview – what the player is actually wearing: worn armor and equipment as named
 * rows (not a bare icon column, so a set reads without hovering), plus how full each container is.
 */
public final class GearPage implements PvPage {

    /** The containers summarised on the right, in the order the sidebar lists them. */
    private static final String[][] CONTAINERS = {
            {"inventory", "Inventory"}, {"ender_chest", "Ender Chest"},
            {"accessory_bag", "Accessory Bag"}, {"quiver", "Quiver"},
            {"potion_bag", "Potion Bag"}, {"fishing_bag", "Fishing Bag"},
            {"vault", "Personal Vault"}};

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject invs = PvDraw.obj(ctx.profile, "inventories");
        boolean apiOff = ctx.profile.has("inventory_api") && !ctx.profile.get("inventory_api").getAsBoolean();
        if (invs == null || invs.isEmpty()) {
            PvDraw.empty(g, ctx, apiOff
                    ? "This player's Inventory API is disabled." : "No storage data.");
            return;
        }
        int colW = Math.min(220, (ctx.width - 12) / 2);
        int y = ctx.y;
        y = drawSlots(g, ctx, invs, "armor", "Armor", ctx.x, y, colW);
        drawSlots(g, ctx, invs, "equipment", "Equipment", ctx.x, y + 4, colW);
        drawSummary(g, ctx, invs, ctx.x + colW + 12, ctx.y, ctx.right() - (ctx.x + colW + 12));
    }

    /** A labelled run of slots: icon cell plus the item's colored name, trimmed to the column. */
    private int drawSlots(GuiGraphicsExtractor g, PvContext ctx, JsonObject invs,
                          String key, String title, int x, int y, int w) {
        y = PvDraw.heading(g, ctx.font, x, y, w, title);
        JsonArray slots = PvDraw.arr(invs, key);
        if (slots.isEmpty()) {
            return PvDraw.line(g, ctx.font, x, y, "§8nothing equipped");
        }
        for (JsonElement element : slots) {
            if (y + PvDraw.CELL > ctx.bottom()) {
                break;
            }
            PvDraw.slot(g, ctx.font, element, x, y, ctx.mouseX, ctx.mouseY);
            String name = element.isJsonObject()
                    ? PvDraw.str(element.getAsJsonObject(), "name") : "§8empty";
            int nameX = x + PvDraw.CELL + 3;
            int space = (x + w) - nameX;
            if (space > 8) {
                g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, name, space)), nameX,
                        y + (PvDraw.CELL - ctx.font.lineHeight) / 2, SBSTheme.TEXT);
            }
            y += PvDraw.CELL + 1;
        }
        return y;
    }

    /** How many slots of each container are used – the "is it worth opening" answer. */
    private void drawSummary(GuiGraphicsExtractor g, PvContext ctx, JsonObject invs,
                             int x, int y, int w) {
        y = PvDraw.heading(g, ctx.font, x, y, w, "Containers");
        for (String[] c : CONTAINERS) {
            if (!invs.has(c[0]) || y + ctx.font.lineHeight > ctx.bottom()) {
                continue;
            }
            JsonArray slots = invs.getAsJsonArray(c[0]);
            y = PvDraw.keyValue(g, ctx.font, x, y, w, c[1], "§f" + filled(slots) + "§8/" + slots.size());
        }
        if (invs.has("backpacks")) {
            JsonArray packs = invs.getAsJsonArray("backpacks");
            int used = 0;
            int total = 0;
            for (JsonElement pack : packs) {
                used += filled(pack.getAsJsonArray());
                total += pack.getAsJsonArray().size();
            }
            y = PvDraw.keyValue(g, ctx.font, x, y, w, packs.size() + " Backpacks",
                    "§f" + used + "§8/" + total);
        }
        if (ctx.profile.has("magical_power")) {
            y = PvDraw.heading(g, ctx.font, x, y + 4, w, "Accessories");
            PvDraw.keyValue(g, ctx.font, x, y, w, "Magical Power",
                    "§d" + PvDraw.num(ctx.profile, "magical_power"));
        }
    }

    private int filled(JsonArray slots) {
        int n = 0;
        for (JsonElement element : slots) {
            if (element.isJsonObject()) {
                n++;
            }
        }
        return n;
    }
}
