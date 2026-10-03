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
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The Mining category: HOTM and the mining skill, the three powders, the Crystal Nucleus and the
 * Glacite tunnels.
 *
 * <p>The sub-pages follow what Hypixel actually stores – powder / crystals / glacite – rather than
 * menu names that would have no data behind them.
 */
public final class MiningPage implements PvPage {

    public enum Mode { OVERVIEW, POWDER, CRYSTALS, GLACITE }

    private static final List<String> POWDERS = List.of("mithril", "gemstone", "glacite");
    /** Powder -> the colour SkyBlock gives it. */
    private static final List<Integer> POWDER_COLOR = List.of(0xFF57D977, 0xFFD060E0, 0xFF3FB4FF);
    /** The twelve Crystal Hollows crystals, in the Nucleus order. */
    private static final List<String> CRYSTALS = List.of("jade", "amber", "amethyst", "sapphire",
            "topaz", "jasper", "ruby", "opal", "onyx", "citrine", "aquamarine", "peridot");

    private final Mode mode;

    public MiningPage(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject mining = PvDraw.obj(ctx.profile, "mining");
        if (mining == null) {
            PvDraw.empty(g, ctx, "No mining data.");
            return;
        }
        switch (mode) {
            case POWDER -> drawPowder(g, ctx, mining);
            case CRYSTALS -> drawCrystals(g, ctx, mining);
            case GLACITE -> drawGlacite(g, ctx, mining);
            default -> drawOverview(g, ctx, mining);
        }
    }

    private void drawOverview(GuiGraphicsExtractor g, PvContext ctx, JsonObject mining) {
        JsonObject powder = PvDraw.obj(mining, "powder");
        long powderTotal = 0;
        if (powder != null) {
            for (String p : POWDERS) {
                powderTotal += PvDraw.num(PvDraw.obj(powder, p), "total");
            }
        }
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"HOTM", "§b" + (mining.has("hotm") ? PvDraw.num(mining, "hotm") : "-")},
                {"Powder", "§d" + PvDraw.fmt(powderTotal)},
                {"Nucleus Runs", "§b" + (mining.has("nucleus_runs")
                        ? PvDraw.num(mining, "nucleus_runs") : "-")},
                {"Ores Mined", "§b" + PvDraw.fmt(PvDraw.num(mining, "ores_mined"))}});

        int half = (ctx.width - 10) / 2;
        int left = y + 5;
        int ly = PvDraw.heading(g, ctx.font, ctx.x, left, half, "Mining Skill");
        JsonObject skills = PvDraw.obj(ctx.profile, "skills");
        if (skills != null && skills.has("mining")) {
            ly = PvDraw.iconRow(g, ctx.font, ctx.x, ly, half, Items.STONE_PICKAXE, "Mining",
                    skills.getAsJsonArray("mining"));
            ly += 3;
        }
        ly = PvDraw.heading(g, ctx.font, ctx.x, ly, half, "Powder");
        drawPowderRows(g, ctx, mining, ctx.x, ly, half);

        int rx = ctx.x + half + 10;
        int ry = PvDraw.heading(g, ctx.font, rx, left, ctx.right() - rx, "Top Ores");
        for (var entry : PvDraw.arr(mining, "ores_top")) {
            if (ry + ctx.font.lineHeight > ctx.bottom()) {
                break;
            }
            var pair = entry.getAsJsonArray();
            ry = PvDraw.keyValue(g, ctx.font, rx, ry, ctx.right() - rx,
                    PvDraw.pretty(pair.get(0).getAsString()),
                    "§f" + PvDraw.fmt(pair.get(1).getAsLong()));
        }
    }

    private void drawPowder(GuiGraphicsExtractor g, PvContext ctx, JsonObject mining) {
        int y = PvDraw.heading(g, ctx.font, ctx.x, ctx.y, ctx.width, "Powder");
        y = drawPowderRows(g, ctx, mining, ctx.x, y, ctx.width);
        JsonObject powder = PvDraw.obj(mining, "powder");
        if (powder == null) {
            return;
        }
        // Spent vs. kept is the number that says how much HOTM is actually paid for.
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 4, ctx.width, "Spent vs. Kept");
        for (String p : POWDERS) {
            JsonObject body = PvDraw.obj(powder, p);
            if (body == null || y + ctx.font.lineHeight + 6 > ctx.bottom()) {
                continue;
            }
            long total = PvDraw.num(body, "total");
            long spent = PvDraw.num(body, "spent");
            y = PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, PvDraw.pretty(p),
                    "§7spent §f" + PvDraw.fmt(spent) + " §8of " + PvDraw.fmt(total));
            PvDraw.bar(g, ctx.x, y, ctx.width, total > 0 ? spent / (double) total : 0,
                    POWDER_COLOR.get(POWDERS.indexOf(p)));
            y += 6;
        }
    }

    /** One row per powder: current amount with a bar scaled against the biggest of the three. */
    private int drawPowderRows(GuiGraphicsExtractor g, PvContext ctx, JsonObject mining,
                               int x, int y, int w) {
        JsonObject powder = PvDraw.obj(mining, "powder");
        if (powder == null) {
            return PvDraw.line(g, ctx.font, x, y, "§8No powder data");
        }
        long max = 1;
        for (String p : POWDERS) {
            max = Math.max(max, PvDraw.num(PvDraw.obj(powder, p), "total"));
        }
        for (String p : POWDERS) {
            JsonObject body = PvDraw.obj(powder, p);
            if (body == null || y + ctx.font.lineHeight + 6 > ctx.bottom()) {
                continue;
            }
            int color = POWDER_COLOR.get(POWDERS.indexOf(p));
            y = PvDraw.keyValue(g, ctx.font, x, y, w, PvDraw.pretty(p),
                    "§f" + PvDraw.fmt(PvDraw.num(body, "current")));
            PvDraw.bar(g, x, y, w, PvDraw.num(body, "total") / (double) max, color);
            y += 6;
        }
        return y;
    }

    /** The Nucleus: every crystal with how often it was found and placed. */
    private void drawCrystals(GuiGraphicsExtractor g, PvContext ctx, JsonObject mining) {
        JsonObject crystals = PvDraw.obj(mining, "crystals");
        if (crystals == null) {
            PvDraw.empty(g, ctx, "No crystal data.");
            return;
        }
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 30, new String[][]{
                {"Nucleus Runs", "§b" + (mining.has("nucleus_runs")
                        ? PvDraw.num(mining, "nucleus_runs") : "-"), "lowest crystal placed"}});
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 5, ctx.width, "Crystals");
        int cols = ctx.width >= 400 ? 2 : 1;
        int colW = (ctx.width - 10) / cols;
        int i = 0;
        for (String name : CRYSTALS) {
            JsonObject body = PvDraw.obj(crystals, name);
            if (body == null) {
                continue;
            }
            int cx = ctx.x + (i % cols) * (colW + 10);
            int cy = y + (i / cols) * (ctx.font.lineHeight + 2);
            i++;
            if (cy + ctx.font.lineHeight > ctx.bottom()) {
                break;
            }
            long placed = PvDraw.num(body, "placed");
            String state = PvDraw.str(body, "state");
            // The state matters as much as the count: a crystal sitting NOT_FOUND is the one
            // blocking the next Nucleus run.
            String value = "§f" + placed + " §8" + PvDraw.pretty(state);
            PvDraw.keyValue(g, ctx.font, cx, cy, colW, PvDraw.pretty(name), value);
        }
    }

    private void drawGlacite(GuiGraphicsExtractor g, PvContext ctx, JsonObject mining) {
        JsonObject glacite = PvDraw.obj(mining, "glacite");
        if (glacite == null) {
            PvDraw.empty(g, ctx, "No Glacite data.");
            return;
        }
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Mineshafts", "§b" + PvDraw.fmt(PvDraw.num(glacite, "mineshafts"))},
                {"Fossil Dust", "§b" + PvDraw.fmt(PvDraw.num(glacite, "fossil_dust"))},
                {"Fossils", "§b" + PvDraw.num(glacite, "fossils_donated") + "§8/10"}});
        JsonObject corpses = PvDraw.obj(glacite, "corpses");
        if (corpses == null || corpses.isEmpty()) {
            return;
        }
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 5, ctx.width, "Corpses Looted");
        long max = 1;
        for (var e : corpses.entrySet()) {
            max = Math.max(max, e.getValue().getAsLong());
        }
        for (var entry : corpses.entrySet()) {
            if (y + ctx.font.lineHeight + 6 > ctx.bottom()) {
                break;
            }
            y = PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, PvDraw.pretty(entry.getKey()),
                    "§f" + entry.getValue().getAsLong());
            PvDraw.bar(g, ctx.x, y, ctx.width, entry.getValue().getAsLong() / (double) max,
                    SBSTheme.ACCENT);
            y += 6;
        }
    }
}
