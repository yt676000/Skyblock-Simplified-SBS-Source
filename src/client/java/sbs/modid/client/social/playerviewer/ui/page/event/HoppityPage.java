/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The Hoppity category: the Chocolate Factory – chocolate totals, the rabbit collection, employees
 * and the Time Tower.
 */
public final class HoppityPage implements PvPage {

    public enum Mode { OVERVIEW, RABBITS, FACTORY }

    /** The factory's employees, in the order the menu lists them (cheapest first). */
    private static final List<String> EMPLOYEES = List.of("rabbit_bro", "rabbit_cousin",
            "rabbit_sis", "rabbit_father", "rabbit_grandma", "rabbit_uncle", "rabbit_dog");
    private static final int ROW_H = 13;

    private final Mode mode;

    public HoppityPage(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject hoppity = PvDraw.obj(ctx.profile, "hoppity");
        if (hoppity == null) {
            PvDraw.empty(g, ctx, "No Hoppity data - this profile never opened the factory.");
            return;
        }
        switch (mode) {
            case RABBITS -> drawRabbits(g, ctx, hoppity);
            case FACTORY -> drawFactory(g, ctx, hoppity);
            default -> drawOverview(g, ctx, hoppity);
        }
    }

    private void drawOverview(GuiGraphicsExtractor g, PvContext ctx, JsonObject hoppity) {
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Chocolate", "§6" + PvDraw.fmt(PvDraw.num(hoppity, "chocolate")), "current"},
                {"All Time", "§6" + PvDraw.fmt(PvDraw.num(hoppity, "total"))},
                {"Rabbits", "§b" + PvDraw.num(hoppity, "rabbits_unique"), "unique"},
                {"Barn", "§b" + PvDraw.num(hoppity, "barn_level")}});

        int half = (ctx.width - 10) / 2;
        int ly = PvDraw.heading(g, ctx.font, ctx.x, y + 5, half, "Chocolate");
        for (String[] row : new String[][]{{"chocolate", "Current"}, {"total", "All Time"},
                {"since_prestige", "Since Prestige"}}) {
            if (hoppity.has(row[0]) && ly + ctx.font.lineHeight <= ctx.bottom()) {
                ly = PvDraw.keyValue(g, ctx.font, ctx.x, ly, half, row[1],
                        "§6" + PvDraw.fmt(PvDraw.num(hoppity, row[0])));
            }
        }
        ly = PvDraw.heading(g, ctx.font, ctx.x, ly + 4, half, "Rabbits");
        ly = PvDraw.keyValue(g, ctx.font, ctx.x, ly, half, "Unique",
                "§f" + PvDraw.num(hoppity, "rabbits_unique"));
        ly = PvDraw.keyValue(g, ctx.font, ctx.x, ly, half, "Found total",
                "§f" + PvDraw.fmt(PvDraw.num(hoppity, "rabbits_total")));
        PvDraw.keyValue(g, ctx.font, ctx.x, ly, half, "Duplicates",
                "§8" + PvDraw.fmt(PvDraw.num(hoppity, "rabbits_duplicates")));

        int rx = ctx.x + half + 10;
        int rw = ctx.right() - rx;
        int ry = PvDraw.heading(g, ctx.font, rx, y + 5, rw, "Eggs Collected");
        JsonObject eggs = PvDraw.obj(hoppity, "eggs");
        if (eggs != null) {
            for (var entry : eggs.entrySet()) {
                if (ry + ctx.font.lineHeight > ctx.bottom()) {
                    break;
                }
                ry = PvDraw.keyValue(g, ctx.font, rx, ry, rw, PvDraw.pretty(entry.getKey()),
                        "§f" + PvDraw.fmt(entry.getValue().getAsLong()));
            }
        } else {
            ry = PvDraw.line(g, ctx.font, rx, ry, "§8No egg data");
        }
        JsonObject tower = PvDraw.obj(hoppity, "time_tower");
        if (tower != null && ry + ctx.font.lineHeight * 3 <= ctx.bottom()) {
            ry = PvDraw.heading(g, ctx.font, rx, ry + 4, rw, "Time Tower");
            ry = PvDraw.keyValue(g, ctx.font, rx, ry, rw, "Level", "§f" + PvDraw.num(tower, "level"));
            PvDraw.keyValue(g, ctx.font, rx, ry, rw, "Charges", "§f" + PvDraw.num(tower, "charges"));
        }
    }

    /**
     * The rabbit collection. Hypixel exposes no rabbit rarity or a total to collect, so this shows
     * what can be proven: uniques, duplicates, and which rabbits keep showing up.
     */
    private void drawRabbits(GuiGraphicsExtractor g, PvContext ctx, JsonObject hoppity) {
        long unique = PvDraw.num(hoppity, "rabbits_unique");
        long total = PvDraw.num(hoppity, "rabbits_total");
        long dupes = PvDraw.num(hoppity, "rabbits_duplicates");
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Unique", "§b" + unique},
                {"Found", "§b" + PvDraw.fmt(total)},
                {"Duplicates", "§b" + PvDraw.fmt(dupes),
                        total > 0 ? Math.round(dupes * 100.0 / total) + "% of finds" : null}});

        JsonArray list = PvDraw.arr(hoppity, "rabbits_list");
        if (list.isEmpty()) {
            return;
        }
        // Every rabbit, most-found first, in two scrolling columns – 464 of them do not fit in one.
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 5, ctx.width,
                "All Rabbits §8" + list.size() + " • scroll for more");
        long max = 1;
        for (var e : list) {
            max = Math.max(max, e.getAsJsonArray().get(1).getAsLong());
        }
        int cols = Math.max(1, ctx.width / 150);
        int colW = (ctx.width - (cols - 1) * 8) / cols;
        int rowsPerCol = Math.max(1, (ctx.bottom() - y) / ROW_H);
        int scroll = ctx.rows((list.size() + cols - 1) / cols, rowsPerCol);
        int start = scroll * cols;
        for (int i = start; i < list.size() && i < start + cols * rowsPerCol; i++) {
            int idx = i - start;
            int cx = ctx.x + (idx % cols) * (colW + 8);
            int cy = y + (idx / cols) * ROW_H;
            var pair = list.get(i).getAsJsonArray();
            long n = pair.get(1).getAsLong();
            PvDraw.keyValue(g, ctx.font, cx, cy, colW,
                    PvDraw.pretty(pair.get(0).getAsString()), "§f" + n + "§8x");
            PvDraw.bar(g, cx, cy + ctx.font.lineHeight + 1, colW, n / (double) max, PvDraw.SKILL_BAR);
        }
    }

    /** The factory: employee levels as bars, plus the barn and tower. */
    private void drawFactory(GuiGraphicsExtractor g, PvContext ctx, JsonObject hoppity) {
        JsonObject employees = PvDraw.obj(hoppity, "employees");
        JsonObject tower = PvDraw.obj(hoppity, "time_tower");
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Employee Lvls", "§b" + PvDraw.num(hoppity, "employees_total"), "combined"},
                {"Barn Level", "§b" + PvDraw.num(hoppity, "barn_level")},
                {"Time Tower", "§b" + PvDraw.num(tower, "level"),
                        PvDraw.num(tower, "charges") + " charges"}});
        if (employees == null || employees.isEmpty()) {
            return;
        }
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 5, ctx.width, "Employees");
        long max = 1;
        for (var e : employees.entrySet()) {
            max = Math.max(max, e.getValue().getAsLong());
        }
        for (String name : EMPLOYEES) {
            if (!employees.has(name) || y + ctx.font.lineHeight + 6 > ctx.bottom()) {
                continue;
            }
            long level = PvDraw.num(employees, name);
            y = PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, PvDraw.pretty(name),
                    "§f" + level);
            PvDraw.bar(g, ctx.x, y, ctx.width, level / (double) max, SBSTheme.ACCENT);
            y += 6;
        }
    }
}
