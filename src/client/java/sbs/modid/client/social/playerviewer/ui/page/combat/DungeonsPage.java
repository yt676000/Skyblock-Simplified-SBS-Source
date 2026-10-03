/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Combat ▸ Dungeons – Catacombs and Master Mode as per-floor tables (runs, best time, best S and
 * S+, best score), with the Catacombs XP bar and the class spread above them.
 *
 * <p>The tables are laid out from a fixed column model rather than hand-placed offsets: every cell
 * is clipped to its own column, so a six-digit run count or a one-hour time can never bleed into
 * the neighbouring column.
 */
public final class DungeonsPage implements PvPage {

    /** Floor-name column; the five numeric columns share what is left. */
    private static final int COL_FLOOR = 54;
    /** Widest a numeric column is allowed to get – past this the table just looks sparse. */
    private static final int COL_NUM_MAX = 62;
    private static final String[] HEADERS = {"Floor", "Runs", "Best", "S", "S+", "Score"};

    /** Numeric column width for this frame, derived from the real content width. */
    private int colNum = COL_NUM_MAX;

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject dungeons = PvDraw.obj(ctx.profile, "dungeons");
        int y = drawSummary(g, ctx, dungeons);
        if (dungeons == null) {
            PvDraw.empty(g, ctx, "No dungeon data.");
            return;
        }
        // The table fits the content rather than a fixed width, so it cannot run past the page at
        // a small GUI scale; cells are then trimmed to their own (narrower) column.
        colNum = Math.min(COL_NUM_MAX, (ctx.width - COL_FLOOR) / (HEADERS.length - 1));
        int tableW = COL_FLOOR + colNum * (HEADERS.length - 1);
        y = drawTable(g, ctx, "Catacombs", PvDraw.arr(dungeons, "catacombs"), ctx.x, y + 4, tableW);
        drawTable(g, ctx, "Master Mode", PvDraw.arr(dungeons, "master"), ctx.x, y + 5, tableW);
    }

    /** Catacombs bar + the headline counters, above the tables. */
    private int drawSummary(GuiGraphicsExtractor g, PvContext ctx, JsonObject dungeons) {
        int barW = Math.min(220, ctx.width / 2 - 6);
        int y = ctx.y;
        if (ctx.profile.has("catacombs_bar")) {
            PvDraw.iconRow(g, ctx.font, ctx.x, y, barW, Items.DEEPSLATE_BRICKS, "Catacombs",
                    ctx.profile.getAsJsonArray("catacombs_bar"));
        }
        // The counters sit in the right half, so they never collide with the bar's level text.
        int cx = ctx.x + barW + 12;
        int cw = ctx.right() - cx;
        if (dungeons != null && cw > 60) {
            int cy = y;
            cy = PvDraw.keyValue(g, ctx.font, cx, cy, cw, "Total Runs",
                    "§f" + PvDraw.fmt(PvDraw.num(dungeons, "runs_total")));
            if (dungeons.has("secrets")) {
                PvDraw.keyValue(g, ctx.font, cx, cy, cw, "Secrets Found",
                        "§f" + PvDraw.fmt(PvDraw.num(dungeons, "secrets")));
            }
        }
        return y + PvDraw.CELL + 4;
    }

    /**
     * One floor table. Returns the y below it. Rows are drawn from the backend's floor objects, so
     * a floor the player never entered simply is not in the list.
     */
    private int drawTable(GuiGraphicsExtractor g, PvContext ctx, String title,
                          JsonArray floors, int x, int y, int w) {
        y = PvDraw.heading(g, ctx.font, x, y, w, title);
        if (floors.isEmpty()) {
            return PvDraw.line(g, ctx.font, x, y, "§8never entered");
        }
        // Header row.
        int hx = x;
        g.text(ctx.font, Component.literal("§8" + HEADERS[0]), hx, y, SBSTheme.TEXT_MUTED);
        hx += COL_FLOOR;
        for (int i = 1; i < HEADERS.length; i++) {
            String h = "§8" + HEADERS[i];
            g.text(ctx.font, Component.literal(h), hx + colNum - ctx.font.width(h) - 4, y,
                    SBSTheme.TEXT_MUTED);
            hx += colNum;
        }
        y += ctx.font.lineHeight + 2;
        g.fill(x, y - 1, x + w, y, SBSTheme.ACCENT_SOFT);
        y += 2;

        for (JsonElement element : floors) {
            if (y + ctx.font.lineHeight > ctx.bottom()) {
                break;
            }
            JsonObject floor = element.getAsJsonObject();
            int fx = x;
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font,
                            "§b" + PvDraw.str(floor, "name"), COL_FLOOR - 4)), fx, y, SBSTheme.ACCENT);
            fx += COL_FLOOR;
            fx = cell(g, ctx, fx, y, PvDraw.fmt(PvDraw.num(floor, "runs")), SBSTheme.TEXT);
            fx = cell(g, ctx, fx, y, timeOrDash(floor, "best"), SBSTheme.TEXT);
            fx = cell(g, ctx, fx, y, timeOrDash(floor, "s"), SBSTheme.TEXT);
            fx = cell(g, ctx, fx, y, timeOrDash(floor, "splus"), SBSTheme.TEXT);
            cell(g, ctx, fx, y, floor.has("score") ? String.valueOf(PvDraw.num(floor, "score")) : "-",
                    SBSTheme.TEXT);
            y += ctx.font.lineHeight + 2;
        }
        return y;
    }

    /** One right-aligned numeric cell, clipped to its own column; returns the next column's x. */
    private int cell(GuiGraphicsExtractor g, PvContext ctx, int x, int y, String text, int color) {
        String shown = PvDraw.trim(ctx.font, text, colNum - 4);
        g.text(ctx.font, Component.literal(shown), x + colNum - ctx.font.width(shown) - 4, y, color);
        return x + colNum;
    }

    private String timeOrDash(JsonObject floor, String key) {
        return floor.has(key) ? PvDraw.time(PvDraw.num(floor, key)) : "-";
    }
}
