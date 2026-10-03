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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.List;

/**
 * Museum ▸ Overview – the museum's appraised value and every donated piece as its real icon.
 *
 * <p>The museum is a separate Hypixel endpoint that only the selected profile is fetched for (one
 * extra API call per view), so switching to another profile intentionally shows nothing here.
 */
public final class MuseumPage implements PvPage {

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject museum = PvDraw.obj(ctx.profile, "museum");
        if (museum == null) {
            boolean selected = ctx.profile.has("selected")
                    && ctx.profile.get("selected").getAsBoolean();
            PvDraw.empty(g, ctx, selected
                    ? "No museum data - the museum API may be off for this player."
                    : "The museum is only loaded for the selected profile.");
            return;
        }
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Value", "§6" + PvDraw.fmt(PvDraw.num(museum, "value")), "appraised"},
                {"Donated", "§b" + PvDraw.num(museum, "donated"), "pieces"},
                {"Special", "§b" + PvDraw.num(museum, "special")},
                {"Appraisal", museum.has("appraisal") && museum.get("appraisal").getAsBoolean()
                        ? "§adone" : "§8no"}});

        JsonArray items = PvDraw.arr(museum, "items");
        if (items.isEmpty()) {
            return;
        }
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 5, ctx.width,
                "Donated Items §8" + items.size());
        int cols = Math.max(1, ctx.width / PvDraw.CELL);
        int visibleRows = Math.max(1, (ctx.bottom() - y) / PvDraw.CELL);
        int totalRows = (items.size() + cols - 1) / cols;
        int scroll = ctx.rows(totalRows, visibleRows);
        for (int row = scroll; row < totalRows && row < scroll + visibleRows; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= items.size()) {
                    break;
                }
                String id = items.get(idx).getAsString();
                int x = ctx.x + col * PvDraw.CELL;
                int cy = y + (row - scroll) * PvDraw.CELL;
                PvDraw.cell(g, x, cy);
                ItemStack icon = SkyBlockItemIcons.getInstance().icon(id, null, 1);
                g.item(icon.is(Items.BARRIER) ? new ItemStack(Items.PAPER) : icon, x + 1, cy + 1);
                if (ctx.hovered(x, cy, PvDraw.CELL, PvDraw.CELL)) {
                    PvDraw.tooltip(g, ctx.font, List.of(
                            Component.literal("§f" + PvDraw.pretty(id)),
                            Component.literal("§8donated to the museum")), ctx.mouseX, ctx.mouseY);
                }
            }
        }
        if (museum.has("borrowing") && PvDraw.num(museum, "borrowing") > 0) {
            g.text(ctx.font, Component.literal("§8" + PvDraw.num(museum, "borrowing")
                            + " currently borrowed out"),
                    ctx.x, ctx.bottom() - ctx.font.lineHeight, SBSTheme.TEXT_MUTED);
        }
    }
}
