/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The Fishing category: the fishing skill and catch counters, and the Trophy Fish table with every
 * species split by tier.
 */
public final class FishingPage implements PvPage {

    public enum Mode { OVERVIEW, TROPHY, STATS }

    private static final List<String> TIERS = List.of("Bronze", "Silver", "Gold", "Diamond");
    /** Tier -> its SkyBlock colour. */
    private static final List<Integer> TIER_COLOR = List.of(
            0xFFC77C3E, 0xFFC0C0C0, 0xFFFFD64D, 0xFF5BE3E3);
    private static final int ROW_H = 13;

    private final Mode mode;

    public FishingPage(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject fishing = PvDraw.obj(ctx.profile, "fishing");
        if (fishing == null) {
            PvDraw.empty(g, ctx, "No fishing data.");
            return;
        }
        switch (mode) {
            case TROPHY -> drawTrophy(g, ctx, fishing);
            case STATS -> drawStats(g, ctx, fishing);
            default -> drawOverview(g, ctx, fishing);
        }
    }

    private void drawOverview(GuiGraphicsExtractor g, PvContext ctx, JsonObject fishing) {
        JsonObject fished = PvDraw.obj(fishing, "items_fished");
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Items Fished", "§b" + PvDraw.fmt(PvDraw.num(fished, "total"))},
                {"Sea Creatures", "§b" + PvDraw.fmt(PvDraw.num(fishing, "sea_creatures"))},
                {"Trophy Fish", "§b" + PvDraw.fmt(PvDraw.num(fishing, "trophy_total"))},
                {"Treasures", "§b" + PvDraw.fmt(PvDraw.num(fished, "treasure"))}});

        int half = (ctx.width - 10) / 2;
        int ly = PvDraw.heading(g, ctx.font, ctx.x, y + 5, half, "Fishing Skill");
        JsonObject skills = PvDraw.obj(ctx.profile, "skills");
        if (skills != null && skills.has("fishing")) {
            ly = PvDraw.iconRow(g, ctx.font, ctx.x, ly, half, Items.FISHING_ROD, "Fishing",
                    skills.getAsJsonArray("fishing"));
            ly += 3;
        }
        ly = PvDraw.heading(g, ctx.font, ctx.x, ly, half, "Catches");
        for (String[] row : new String[][]{{"normal", "Normal"}, {"treasure", "Treasure"},
                {"large_treasure", "Large Treasure"}, {"trophy_fish", "Trophy Fish"}}) {
            if (fished != null && fished.has(row[0]) && ly + ctx.font.lineHeight <= ctx.bottom()) {
                ly = PvDraw.keyValue(g, ctx.font, ctx.x, ly, half, row[1],
                        "§f" + PvDraw.fmt(PvDraw.num(fished, row[0])));
            }
        }

        // Right half: how many species are caught in each tier – the Trophy Fish progress track.
        int rx = ctx.x + half + 10;
        int rw = ctx.right() - rx;
        int ry = PvDraw.heading(g, ctx.font, rx, y + 5, rw, "Trophy Tiers");
        JsonArray counts = PvDraw.arr(fishing, "trophy_tier_counts");
        int species = PvDraw.arr(fishing, "trophy").size();
        for (int i = 0; i < counts.size() && i < TIERS.size(); i++) {
            if (ry + ctx.font.lineHeight + 6 > ctx.bottom()) {
                break;
            }
            int caught = counts.get(i).getAsInt();
            ry = PvDraw.keyValue(g, ctx.font, rx, ry, rw, TIERS.get(i),
                    "§f" + caught + "§8/" + species);
            PvDraw.bar(g, rx, ry, rw, species > 0 ? caught / (double) species : 0,
                    TIER_COLOR.get(i));
            ry += 6;
        }
    }

    /**
     * Every species as a row: total plus one cell per tier. Zero-count tiers stay dim, so the gaps
     * – which are what you are hunting – are the thing that stands out.
     */
    private void drawTrophy(GuiGraphicsExtractor g, PvContext ctx, JsonObject fishing) {
        JsonArray fish = PvDraw.arr(fishing, "trophy");
        if (fish.isEmpty()) {
            PvDraw.empty(g, ctx, "No trophy fish caught.");
            return;
        }
        int nameW = 96;
        int colW = Math.min(52, (ctx.width - nameW) / (TIERS.size() + 1));
        int y = ctx.y;
        // Header.
        g.text(ctx.font, Component.literal("§8Species"), ctx.x, y, SBSTheme.TEXT_MUTED);
        int hx = ctx.x + nameW;
        for (int i = 0; i < TIERS.size(); i++) {
            String h = TIERS.get(i).substring(0, 1);
            g.text(ctx.font, Component.literal(h), hx + colW - ctx.font.width(h) - 3, y,
                    TIER_COLOR.get(i));
            hx += colW;
        }
        String totalHeader = "§8Total";
        g.text(ctx.font, Component.literal(totalHeader),
                hx + colW - ctx.font.width(totalHeader) - 3, y, SBSTheme.TEXT_MUTED);
        y += ctx.font.lineHeight + 2;
        g.fill(ctx.x, y - 1, ctx.x + nameW + colW * (TIERS.size() + 1), y, SBSTheme.ACCENT_SOFT);
        y += 2;

        int visibleRows = Math.max(1, (ctx.bottom() - y) / ROW_H);
        int scroll = ctx.rows(fish.size(), visibleRows);
        for (int i = scroll; i < fish.size() && i < scroll + visibleRows; i++) {
            JsonObject f = fish.get(i).getAsJsonObject();
            JsonArray tiers = PvDraw.arr(f, "tiers");
            // A species caught in every tier is "complete" – mark the name.
            boolean complete = tiers.size() == TIERS.size();
            for (int t = 0; complete && t < tiers.size(); t++) {
                complete = tiers.get(t).getAsLong() > 0;
            }
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font,
                            (complete ? "§d" : "§f") + PvDraw.pretty(PvDraw.str(f, "id")), nameW - 4)),
                    ctx.x, y, SBSTheme.TEXT);
            int cx = ctx.x + nameW;
            for (int t = 0; t < TIERS.size(); t++) {
                long n = t < tiers.size() ? tiers.get(t).getAsLong() : 0;
                String text = n > 0 ? PvDraw.fmt(n) : "-";
                g.text(ctx.font, Component.literal(text), cx + colW - ctx.font.width(text) - 3, y,
                        n > 0 ? TIER_COLOR.get(t) : SBSTheme.CARD_BG_DISABLED);
                cx += colW;
            }
            String total = PvDraw.fmt(PvDraw.num(f, "total"));
            g.text(ctx.font, Component.literal("§f" + total),
                    cx + colW - ctx.font.width(total) - 3, y, SBSTheme.TEXT);
            y += ROW_H;
        }
        if (fish.size() > visibleRows) {
            PvDraw.scrollbar(g, ctx.right() - 3, ctx.y + ctx.font.lineHeight + 4,
                    visibleRows * ROW_H, fish.size(), visibleRows, scroll);
        }
    }

    private void drawStats(GuiGraphicsExtractor g, PvContext ctx, JsonObject fishing) {
        JsonObject fished = PvDraw.obj(fishing, "items_fished");
        if (fished == null) {
            PvDraw.empty(g, ctx, "No fishing counters.");
            return;
        }
        int y = PvDraw.heading(g, ctx.font, ctx.x, ctx.y, ctx.width, "Catch Breakdown");
        long total = Math.max(1, PvDraw.num(fished, "total"));
        for (var entry : fished.entrySet()) {
            if (entry.getKey().equals("total") || y + ctx.font.lineHeight + 6 > ctx.bottom()) {
                continue;
            }
            long value = entry.getValue().getAsLong();
            y = PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, PvDraw.pretty(entry.getKey()),
                    "§f" + PvDraw.fmt(value) + " §8" + Math.round(value * 100.0 / total) + "%");
            PvDraw.bar(g, ctx.x, y, ctx.width, value / (double) total, SBSTheme.ACCENT);
            y += 6;
        }
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 4, ctx.width, "Totals");
        y = PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, "Items Fished",
                "§f" + PvDraw.fmt(PvDraw.num(fished, "total")));
        y = PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, "Sea Creature Kills",
                "§f" + PvDraw.fmt(PvDraw.num(fishing, "sea_creatures")));
        if (fishing.has("trophy_rewards")) {
            PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, "Trophy Rewards Claimed",
                    "§f" + PvDraw.num(fishing, "trophy_rewards"));
        }
    }
}
