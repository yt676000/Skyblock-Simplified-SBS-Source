/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.home;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;

import java.util.ArrayList;
import java.util.List;

/**
 * Home ▸ Stats – the "extra stats" page: economy, auctions, gathering counters, kills, deaths
 * and essence, in four columns.
 */
public final class HomeStatsPage implements PvPage {

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject ex = PvDraw.obj(ctx.profile, "extras");
        if (ex == null || ex.isEmpty()) {
            PvDraw.empty(g, ctx, "No extra stats available.");
            return;
        }
        int colW = (ctx.width - 18) / 4;
        int c0 = ctx.x;
        int c1 = ctx.x + colW + 6;
        int c2 = ctx.x + (colW + 6) * 2;
        int c3 = ctx.x + (colW + 6) * 3;

        drawEconomy(g, ctx, ex, c0, colW);
        drawAuctions(g, ctx, ex, c1, colW);
        drawGathering(g, ctx, ex, c2, colW);
        drawKills(g, ctx, ex, c3, colW);
    }

    private void drawEconomy(GuiGraphicsExtractor g, PvContext ctx, JsonObject ex, int x, int w) {
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w, "Economy");
        y = coin(g, ctx, x, y, w, ex, "bank", "Bank");
        y = coin(g, ctx, x, y, w, ex, "purse", "Purse");
        if (ex.has("days_joined")) {
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Joined", "§f" + PvDraw.num(ex, "days_joined") + "d ago");
        }
        if (ctx.profile.has("skill_average")) {
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Avg Skill",
                    "§b" + ctx.profile.get("skill_average").getAsDouble());
        }
        if (ex.has("fairy_souls")) {
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Fairy Souls", "§d" + PvDraw.num(ex, "fairy_souls"));
        }
        JsonObject essence = PvDraw.obj(ex, "essence");
        if (essence == null || essence.isEmpty()) {
            return;
        }
        y = PvDraw.heading(g, ctx.font, x, y + 4, w, "Essence");
        for (var entry : essence.entrySet()) {
            if (y + ctx.font.lineHeight > ctx.bottom()) {
                break;
            }
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, PvDraw.pretty(entry.getKey()),
                    "§f" + PvDraw.fmt(entry.getValue().getAsLong()));
        }
    }

    private void drawAuctions(GuiGraphicsExtractor g, PvContext ctx, JsonObject ex, int x, int w) {
        JsonObject auc = PvDraw.obj(ex, "auctions");
        if (auc == null) {
            return;
        }
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w, "Auctions");
        for (String[] row : new String[][]{{"bids", "Bids"}, {"highest_bid", "Highest Bid"},
                {"won", "Won"}, {"created", "Created"},
                {"gold_spent", "Gold Spent"}, {"gold_earned", "Gold Earned"}}) {
            if (!auc.has(row[0])) {
                continue;
            }
            boolean gold = row[0].startsWith("gold") || row[0].contains("bid");
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, row[1],
                    (gold ? "§6" : "§f") + PvDraw.fmt(PvDraw.num(auc, row[0])));
        }
    }

    private void drawGathering(GuiGraphicsExtractor g, PvContext ctx, JsonObject ex, int x, int w) {
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w, "Gathering");
        for (String[] row : new String[][]{{"ores_mined", "Ores Mined"}, {"sea_creatures", "Sea Creatures"},
                {"items_fished", "Items Fished"}, {"treasures_fished", "Treasures"},
                {"large_treasures", "Lg Treasures"}}) {
            if (ex.has(row[0])) {
                y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, row[1], "§f" + PvDraw.fmt(PvDraw.num(ex, row[0])));
            }
        }
    }

    /**
     * Kills over deaths, both complete. A big profile has hundreds of mob entries, so the two
     * lists share one scrolling column – headings included, so scrolling far enough walks from the
     * kills straight into the deaths rather than hitting a silent end.
     */
    private void drawKills(GuiGraphicsExtractor g, PvContext ctx, JsonObject ex, int x, int w) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{null, "Kills " + total(ex, "kills_total")});
        addPairs(rows, ex, "kills");
        rows.add(new String[]{null, "Deaths " + total(ex, "deaths_total")});
        addPairs(rows, ex, "deaths");

        int lineH = ctx.font.lineHeight + 2;
        int visible = Math.max(1, ctx.height / lineH);
        int scroll = ctx.rows(rows.size(), visible);
        int y = ctx.y;
        for (int i = scroll; i < rows.size() && i < scroll + visible; i++) {
            String[] row = rows.get(i);
            if (row[0] == null) {
                PvDraw.heading(g, ctx.font, x, y, w, row[1]);
            } else {
                PvDraw.keyValue(g, ctx.font, x, y, w - 4, row[0], row[1]);
            }
            y += lineH;
        }
        if (rows.size() > visible) {
            PvDraw.scrollbar(g, x + w - 3, ctx.y, visible * lineH, rows.size(), visible, scroll);
        }
    }

    private void addPairs(List<String[]> rows, JsonObject ex, String key) {
        for (JsonElement element : PvDraw.arr(ex, key)) {
            JsonArray pair = element.getAsJsonArray();
            rows.add(new String[]{PvDraw.pretty(pair.get(0).getAsString()),
                    "§f" + PvDraw.fmt(pair.get(1).getAsLong())});
        }
    }

    private String total(JsonObject ex, String key) {
        return ex.has(key) ? "§8" + PvDraw.fmt(PvDraw.num(ex, key)) : "";
    }

    private int coin(GuiGraphicsExtractor g, PvContext ctx, int x, int y, int w,
                     JsonObject ex, String key, String label) {
        return ex.has(key)
                ? PvDraw.keyValue(g, ctx.font, x, y, w - 4, label, "§6" + PvDraw.fmt(PvDraw.num(ex, key)))
                : y;
    }
}
