/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.skill;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Items;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Foraging category: the foraging skill, Forest Whispers, the tree gifts of the Galatea update,
 * Hina's tasks and the harp songs.
 */
public final class ForagingPage implements PvPage {

    public enum Mode { OVERVIEW, TREES, HARP }

    private static final int ROW_H = 13;

    private final Mode mode;

    public ForagingPage(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject foraging = PvDraw.obj(ctx.profile, "foraging");
        if (foraging == null) {
            PvDraw.empty(g, ctx, "No foraging data.");
            return;
        }
        switch (mode) {
            case TREES -> drawTrees(g, ctx, foraging);
            case HARP -> drawHarp(g, ctx, foraging);
            default -> drawOverview(g, ctx, foraging);
        }
    }

    private void drawOverview(GuiGraphicsExtractor g, PvContext ctx, JsonObject foraging) {
        JsonObject whispers = PvDraw.obj(foraging, "whispers");
        JsonObject hina = PvDraw.obj(foraging, "hina");
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Whispers", "§b" + PvDraw.fmt(PvDraw.num(whispers, "current")), "current"},
                {"Spent", "§b" + PvDraw.fmt(PvDraw.num(whispers, "spent"))},
                {"Hina Tasks", "§b" + PvDraw.num(hina, "completed")},
                {"Hina Tier", "§b" + PvDraw.num(hina, "tier")}});

        int half = (ctx.width - 10) / 2;
        int ly = PvDraw.heading(g, ctx.font, ctx.x, y + 5, half, "Foraging Skill");
        JsonObject skills = PvDraw.obj(ctx.profile, "skills");
        if (skills != null && skills.has("foraging")) {
            ly = PvDraw.iconRow(g, ctx.font, ctx.x, ly, half, Items.JUNGLE_SAPLING, "Foraging",
                    skills.getAsJsonArray("foraging"));
            ly += 3;
        }
        if (whispers != null) {
            ly = PvDraw.heading(g, ctx.font, ctx.x, ly, half, "Forest Whispers");
            long total = Math.max(1, PvDraw.num(whispers, "total"));
            long spent = PvDraw.num(whispers, "spent");
            ly = PvDraw.keyValue(g, ctx.font, ctx.x, ly, half, "Earned lifetime",
                    "§f" + PvDraw.fmt(total));
            ly = PvDraw.keyValue(g, ctx.font, ctx.x, ly, half, "Spent",
                    "§f" + PvDraw.fmt(spent) + " §8" + Math.round(spent * 100.0 / total) + "%");
            PvDraw.bar(g, ctx.x, ly, half, spent / (double) total, SBSTheme.ACCENT);
        }

        int rx = ctx.x + half + 10;
        int rw = ctx.right() - rx;
        int ry = PvDraw.heading(g, ctx.font, rx, y + 5, rw, "Tree Gifts");
        ry = drawGifts(g, ctx, foraging, rx, ry, rw);
        String effect = PvDraw.str(foraging, "daily_effect");
        if (!effect.isEmpty() && ry + ctx.font.lineHeight * 2 <= ctx.bottom()) {
            ry = PvDraw.heading(g, ctx.font, rx, ry + 4, rw, "Daily Effect");
            PvDraw.line(g, ctx.font, rx, ry, "§b" + PvDraw.pretty(effect));
        }
    }

    private int drawGifts(GuiGraphicsExtractor g, PvContext ctx, JsonObject foraging,
                          int x, int y, int w) {
        JsonObject gifts = PvDraw.obj(foraging, "tree_gifts");
        if (gifts == null || gifts.isEmpty()) {
            return PvDraw.line(g, ctx.font, x, y, "§8No tree gifts");
        }
        long max = 1;
        for (var e : gifts.entrySet()) {
            max = Math.max(max, e.getValue().getAsLong());
        }
        for (var entry : gifts.entrySet()) {
            if (y + ctx.font.lineHeight + 6 > ctx.bottom()) {
                break;
            }
            long n = entry.getValue().getAsLong();
            y = PvDraw.keyValue(g, ctx.font, x, y, w, PvDraw.pretty(entry.getKey()),
                    "§f" + PvDraw.fmt(n));
            PvDraw.bar(g, x, y, w, n / (double) max, SBSTheme.TOGGLE_ON);
            y += 6;
        }
        return y;
    }

    private void drawTrees(GuiGraphicsExtractor g, PvContext ctx, JsonObject foraging) {
        int y = PvDraw.heading(g, ctx.font, ctx.x, ctx.y, ctx.width, "Tree Gifts");
        y = drawGifts(g, ctx, foraging, ctx.x, y, ctx.width);
        JsonObject bests = PvDraw.obj(foraging, "personal_bests");
        if (bests == null || bests.isEmpty()) {
            return;
        }
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 4, ctx.width, "Personal Bests");
        long max = 1;
        for (var e : bests.entrySet()) {
            max = Math.max(max, e.getValue().getAsLong());
        }
        for (var entry : bests.entrySet()) {
            if (y + ctx.font.lineHeight + 6 > ctx.bottom()) {
                break;
            }
            long value = entry.getValue().getAsLong();
            y = PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, PvDraw.pretty(entry.getKey()),
                    "§f" + PvDraw.fmt(value));
            PvDraw.bar(g, ctx.x, y, ctx.width, value / (double) max, PvDraw.SKILL_BAR);
            y += 6;
        }
    }

    /** The harp: every song with completions, perfect runs and whether it is fully cleared. */
    private void drawHarp(GuiGraphicsExtractor g, PvContext ctx, JsonObject foraging) {
        JsonObject harp = PvDraw.obj(foraging, "harp");
        if (harp == null || harp.isEmpty()) {
            PvDraw.empty(g, ctx, "No harp songs played.");
            return;
        }
        int nameW = Math.min(160, ctx.width - 130);
        int colW = 60;
        g.text(ctx.font, net.minecraft.network.chat.Component.literal("§8Song"), ctx.x, ctx.y,
                SBSTheme.TEXT_MUTED);
        for (int i = 0; i < 2; i++) {
            String h = i == 0 ? "§8Played" : "§8Perfect";
            g.text(ctx.font, net.minecraft.network.chat.Component.literal(h),
                    ctx.x + nameW + i * colW + colW - ctx.font.width(h) - 3, ctx.y,
                    SBSTheme.TEXT_MUTED);
        }
        int y = ctx.y + ctx.font.lineHeight + 2;
        g.fill(ctx.x, y - 1, ctx.x + nameW + colW * 2, y, SBSTheme.ACCENT_SOFT);
        y += 2;

        var songs = new java.util.ArrayList<>(harp.entrySet());
        int visibleRows = Math.max(1, (ctx.bottom() - y) / ROW_H);
        int scroll = ctx.rows(songs.size(), visibleRows);
        for (int i = scroll; i < songs.size() && i < scroll + visibleRows; i++) {
            var entry = songs.get(i);
            JsonObject song = entry.getValue().getAsJsonObject();
            long perfect = PvDraw.num(song, "perfect");
            g.text(ctx.font, net.minecraft.network.chat.Component.literal(PvDraw.trim(ctx.font,
                            (perfect > 0 ? "§d" : "§f") + PvDraw.pretty(entry.getKey()), nameW - 4)),
                    ctx.x, y, SBSTheme.TEXT);
            String played = String.valueOf(PvDraw.num(song, "completions"));
            g.text(ctx.font, net.minecraft.network.chat.Component.literal("§f" + played),
                    ctx.x + nameW + colW - ctx.font.width(played) - 3, y, SBSTheme.TEXT);
            String perf = perfect > 0 ? String.valueOf(perfect) : "-";
            g.text(ctx.font, net.minecraft.network.chat.Component.literal(perf),
                    ctx.x + nameW + colW * 2 - ctx.font.width(perf) - 3, y,
                    perfect > 0 ? SBSTheme.ACCENT : SBSTheme.CARD_BG_DISABLED);
            y += ROW_H;
        }
        if (songs.size() > visibleRows) {
            PvDraw.scrollbar(g, ctx.right() - 3, ctx.y + ctx.font.lineHeight + 4,
                    visibleRows * ROW_H, songs.size(), visibleRows, scroll);
        }
    }
}
