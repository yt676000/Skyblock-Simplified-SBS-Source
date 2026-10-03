/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.combat;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.Map;

/**
 * Combat ▸ Kuudra – completions per tier as a bar chart plus the Crimson Isle progress (faction,
 * reputation, dojo) that surrounds the fight.
 */
public final class KuudraPage implements PvPage {

    private static final List<String> TIERS = List.of("none", "hot", "burning", "fiery", "infernal");
    /** Tier -> its in-game name and the color SkyBlock gives it. */
    private static final Map<String, String> TIER_NAME = Map.of(
            "none", "Basic", "hot", "Hot", "burning", "Burning",
            "fiery", "Fiery", "infernal", "Infernal");
    private static final Map<String, Integer> TIER_COLOR = Map.of(
            "none", 0xFF8FA9C8, "hot", 0xFFFFD64D, "burning", 0xFFE0A14D,
            "fiery", 0xFFE0605F, "infernal", 0xFFD060E0);

    private static final int ROW_H = 20;

    static String tierName(String tier) {
        return TIER_NAME.getOrDefault(tier, PvDraw.pretty(tier));
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject kuudra = PvDraw.obj(ctx.profile, "kuudra");
        if (kuudra == null || kuudra.isEmpty()) {
            PvDraw.empty(g, ctx, "No Kuudra completions.");
            return;
        }
        int left = Math.min(300, ctx.width * 3 / 5);
        drawTiers(g, ctx, kuudra, ctx.x, left);
        drawCrimson(g, ctx, ctx.x + left + 10, ctx.right() - (ctx.x + left + 10));
    }

    /** One row per tier: name, a bar scaled to the player's best tier, and the count. */
    private void drawTiers(GuiGraphicsExtractor g, PvContext ctx, JsonObject kuudra, int x, int w) {
        long total = 0;
        long max = 1;
        for (String tier : TIERS) {
            long runs = PvDraw.num(kuudra, tier);
            total += runs;
            max = Math.max(max, runs);
        }
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w, "Completions §8" + total + " total");
        for (String tier : TIERS) {
            if (y + ROW_H > ctx.bottom()) {
                break;
            }
            long runs = PvDraw.num(kuudra, tier);
            int color = TIER_COLOR.getOrDefault(tier, SBSTheme.ACCENT);
            String name = tierName(tier);
            String count = String.valueOf(runs);
            int countW = ctx.font.width(count);
            g.text(ctx.font, Component.literal(name), x, y, runs > 0 ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
            g.text(ctx.font, Component.literal(count), x + w - countW, y,
                    runs > 0 ? color : SBSTheme.TEXT_MUTED);
            int barY = y + ctx.font.lineHeight + 2;
            PvDraw.bar(g, x, barY, w, runs / (double) max, color);
            y += ROW_H;
        }
    }

    /** The Crimson Isle context: faction, reputation bar and dojo belt. */
    private void drawCrimson(GuiGraphicsExtractor g, PvContext ctx, int x, int w) {
        JsonObject crimson = PvDraw.obj(ctx.profile, "crimson");
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w, "Crimson Isle");
        if (crimson == null || crimson.isEmpty()) {
            PvDraw.line(g, ctx.font, x, y, "§8No Crimson Isle data");
            return;
        }
        String faction = PvDraw.str(crimson, "faction");
        if (!faction.isEmpty()) {
            boolean mage = faction.equalsIgnoreCase("mages");
            y = PvDraw.keyValue(g, ctx.font, x, y, w, "Faction",
                    (mage ? "§b" : "§c") + PvDraw.pretty(faction));
        }
        if (crimson.has("reputation")) {
            long rep = PvDraw.num(crimson, "reputation");
            y = PvDraw.keyValue(g, ctx.font, x, y, w, "Reputation", "§f" + PvDraw.fmt(rep));
            // 12 000 is the cap of the reputation track.
            PvDraw.bar(g, x, y, w, rep / 12000.0, SBSTheme.ACCENT);
            y += 6;
        }
        JsonObject dojo = PvDraw.obj(crimson, "dojo");
        if (dojo == null || dojo.isEmpty()) {
            return;
        }
        y = PvDraw.heading(g, ctx.font, x, y + 4, w, "Dojo §8" + PvDraw.num(crimson, "dojo_total"));
        for (var entry : dojo.entrySet()) {
            if (y + ctx.font.lineHeight > ctx.bottom()) {
                break;
            }
            y = PvDraw.keyValue(g, ctx.font, x, y, w, PvDraw.pretty(entry.getKey()),
                    "§f" + entry.getValue().getAsInt());
        }
    }
}
