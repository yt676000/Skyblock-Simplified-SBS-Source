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

import java.util.List;
import java.util.Map;

/**
 * The Farming category: the farming skill, Jacob's Contests (medals, brackets, personal bests) and
 * the Garden counters that live on the profile.
 *
 * <p>Garden level, crop milestones and visitors sit behind Hypixel's separate {@code /skyblock/garden}
 * endpoint rather than the profile, which is why the Garden sub-page is the thin one here.
 */
public final class FarmingPage implements PvPage {

    public enum Mode { OVERVIEW, CONTESTS, BESTS, GARDEN }

    /** Medal -> colour, weakest first. */
    private static final List<String> MEDALS = List.of("bronze", "silver", "gold", "platinum", "diamond");
    private static final Map<String, Integer> MEDAL_COLOR = Map.of(
            "bronze", 0xFFC77C3E, "silver", 0xFFC0C0C0, "gold", 0xFFFFD64D,
            "platinum", 0xFF5BE3E3, "diamond", 0xFF5BE3E3);

    private final Mode mode;

    public FarmingPage(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject farming = PvDraw.obj(ctx.profile, "farming");
        if (farming == null) {
            PvDraw.empty(g, ctx, "No farming data.");
            return;
        }
        switch (mode) {
            case CONTESTS -> drawContests(g, ctx, farming);
            case BESTS -> drawBests(g, ctx, farming);
            case GARDEN -> drawGarden(g, ctx, farming);
            default -> drawOverview(g, ctx, farming);
        }
    }

    private void drawOverview(GuiGraphicsExtractor g, PvContext ctx, JsonObject farming) {
        JsonObject medals = PvDraw.obj(farming, "medals");
        long medalTotal = 0;
        if (medals != null) {
            for (var e : medals.entrySet()) {
                medalTotal += e.getValue().getAsLong();
            }
        }
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Farming Lvl", "§b" + (farming.has("farming_level")
                        ? PvDraw.num(farming, "farming_level") : "-")},
                {"Contests", "§b" + PvDraw.fmt(PvDraw.num(farming, "contests"))},
                {"Medals", "§b" + medalTotal},
                {"Copper", "§b" + PvDraw.fmt(PvDraw.num(farming, "copper"))}});

        int half = (ctx.width - 10) / 2;
        int ly = PvDraw.heading(g, ctx.font, ctx.x, y + 5, half, "Farming Skill");
        JsonObject skills = PvDraw.obj(ctx.profile, "skills");
        if (skills != null && skills.has("farming")) {
            ly = PvDraw.iconRow(g, ctx.font, ctx.x, ly, half, Items.GOLDEN_HOE, "Farming",
                    skills.getAsJsonArray("farming"));
            ly += 3;
        }
        ly = PvDraw.heading(g, ctx.font, ctx.x, ly, half, "Medals");
        drawMedals(g, ctx, farming, ctx.x, ly, half);

        int rx = ctx.x + half + 10;
        int rw = ctx.right() - rx;
        int ry = PvDraw.heading(g, ctx.font, rx, y + 5, rw, "Perks");
        JsonObject perks = PvDraw.obj(farming, "perks");
        if (perks != null) {
            for (var entry : perks.entrySet()) {
                if (ry + ctx.font.lineHeight > ctx.bottom()) {
                    break;
                }
                ry = PvDraw.keyValue(g, ctx.font, rx, ry, rw, PvDraw.pretty(entry.getKey()),
                        "§f" + entry.getValue().getAsInt());
            }
        }
        ry = PvDraw.heading(g, ctx.font, rx, ry + 4, rw, "Garden");
        ry = PvDraw.keyValue(g, ctx.font, rx, ry, rw, "Copper",
                "§6" + PvDraw.fmt(PvDraw.num(farming, "copper")));
        ry = PvDraw.keyValue(g, ctx.font, rx, ry, rw, "Larva Consumed",
                "§f" + PvDraw.num(farming, "larva_consumed"));
        PvDraw.keyValue(g, ctx.font, rx, ry, rw, "Greenhouse Crops",
                "§f" + PvDraw.num(farming, "greenhouse_crops"));
    }

    /** One row per medal with a bar scaled against the largest stack. */
    private int drawMedals(GuiGraphicsExtractor g, PvContext ctx, JsonObject farming,
                           int x, int y, int w) {
        JsonObject medals = PvDraw.obj(farming, "medals");
        if (medals == null || medals.isEmpty()) {
            return PvDraw.line(g, ctx.font, x, y, "§8No medals");
        }
        long max = 1;
        for (var e : medals.entrySet()) {
            max = Math.max(max, e.getValue().getAsLong());
        }
        for (String medal : MEDALS) {
            if (!medals.has(medal) || y + ctx.font.lineHeight + 6 > ctx.bottom()) {
                continue;
            }
            long n = PvDraw.num(medals, medal);
            y = PvDraw.keyValue(g, ctx.font, x, y, w, PvDraw.pretty(medal), "§f" + n);
            PvDraw.bar(g, x, y, w, n / (double) max,
                    MEDAL_COLOR.getOrDefault(medal, SBSTheme.ACCENT));
            y += 6;
        }
        return y;
    }

    private void drawContests(GuiGraphicsExtractor g, PvContext ctx, JsonObject farming) {
        long entered = PvDraw.num(farming, "contests");
        long claimed = PvDraw.num(farming, "contests_claimed");
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Entered", "§b" + entered},
                {"Claimed", "§b" + claimed, "positions known"},
                {"Unclaimed", "§b" + Math.max(0, entered - claimed), "rewards waiting"}});

        int half = (ctx.width - 10) / 2;
        int ly = PvDraw.heading(g, ctx.font, ctx.x, y + 5, half, "Medals");
        drawMedals(g, ctx, farming, ctx.x, ly, half);

        int rx = ctx.x + half + 10;
        int rw = ctx.right() - rx;
        int ry = PvDraw.heading(g, ctx.font, rx, y + 5, rw, "Unique Brackets");
        JsonObject brackets = PvDraw.obj(farming, "unique_brackets");
        if (brackets == null || brackets.isEmpty()) {
            PvDraw.line(g, ctx.font, rx, ry, "§8No brackets reached");
            return;
        }
        // A bracket counts the distinct crops the player has ever placed in it.
        for (String medal : MEDALS) {
            if (!brackets.has(medal) || ry + ctx.font.lineHeight > ctx.bottom()) {
                continue;
            }
            ry = PvDraw.keyValue(g, ctx.font, rx, ry, rw, PvDraw.pretty(medal) + " crops",
                    "§f" + PvDraw.num(brackets, medal));
        }
    }

    /** Personal bests per crop, as a bar chart against the player's own best. */
    private void drawBests(GuiGraphicsExtractor g, PvContext ctx, JsonObject farming) {
        JsonObject bests = PvDraw.obj(farming, "personal_bests");
        if (bests == null || bests.isEmpty()) {
            PvDraw.empty(g, ctx, "No contest personal bests.");
            return;
        }
        int y = PvDraw.heading(g, ctx.font, ctx.x, ctx.y, ctx.width, "Contest Personal Bests");
        long max = 1;
        for (var e : bests.entrySet()) {
            max = Math.max(max, e.getValue().getAsLong());
        }
        var entries = new java.util.ArrayList<>(bests.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().getAsLong(), a.getValue().getAsLong()));
        for (var entry : entries) {
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

    private void drawGarden(GuiGraphicsExtractor g, PvContext ctx, JsonObject farming) {
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Copper", "§6" + PvDraw.fmt(PvDraw.num(farming, "copper"))},
                {"Larva Consumed", "§b" + PvDraw.num(farming, "larva_consumed")},
                {"Greenhouse", "§b" + PvDraw.num(farming, "greenhouse_crops"), "crops discovered"}});
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 5, ctx.width, "About");
        // Say why this page is thin rather than looking broken.
        y = PvDraw.line(g, ctx.font, ctx.x, y,
                "§7Garden level, crop milestones and visitors are not part");
        y = PvDraw.line(g, ctx.font, ctx.x, y,
                "§7of the profile payload - Hypixel keeps them behind a");
        PvDraw.line(g, ctx.font, ctx.x, y, "§7separate Garden endpoint.");
    }
}
