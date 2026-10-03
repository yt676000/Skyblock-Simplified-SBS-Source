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
import net.minecraft.world.item.Items;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * Combat ▸ Overview – the four combat pillars side by side (Dungeons, Kuudra, Slayer, Diana), each
 * as a card with its headline numbers, so one glance answers "how much combat has this player done"
 * before drilling into a sub-page.
 */
public final class CombatOverviewPage implements PvPage {

    private static final List<String> SLAYER_ORDER = List.of("zombie", "spider", "wolf",
            "enderman", "blaze", "vampire");
    private static final List<String> KUUDRA_TIERS = List.of("none", "hot", "burning",
            "fiery", "infernal");

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        int gap = 6;
        int colW = (ctx.width - gap * 3) / 4;
        drawCombatLevel(g, ctx, ctx.x, colW);
        drawDungeons(g, ctx, ctx.x + colW + gap, colW);
        drawSlayers(g, ctx, ctx.x + (colW + gap) * 2, colW);
        drawEvents(g, ctx, ctx.x + (colW + gap) * 3, colW);
    }

    /** Combat skill + bestiary: the "raw damage" side of the profile. */
    private void drawCombatLevel(GuiGraphicsExtractor g, PvContext ctx, int x, int w) {
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w, "Combat");
        JsonObject skills = PvDraw.obj(ctx.profile, "skills");
        if (skills != null && skills.has("combat")) {
            y = PvDraw.iconRow(g, ctx.font, x, y, w, Items.IRON_SWORD, "Combat",
                    skills.getAsJsonArray("combat"));
            y += 2;
        }
        if (ctx.profile.has("bestiary_milestone")) {
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Bestiary",
                    "§f" + PvDraw.num(ctx.profile, "bestiary_milestone"));
        }
        JsonObject ex = PvDraw.obj(ctx.profile, "extras");
        if (ex != null && ex.has("kills_total")) {
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Total Kills",
                    "§f" + PvDraw.fmt(PvDraw.num(ex, "kills_total")));
        }
        if (ex != null && ex.has("deaths_total")) {
            PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Total Deaths",
                    "§f" + PvDraw.fmt(PvDraw.num(ex, "deaths_total")));
        }
    }

    private void drawDungeons(GuiGraphicsExtractor g, PvContext ctx, int x, int w) {
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w, "Dungeons");
        if (ctx.profile.has("catacombs_bar")) {
            y = PvDraw.iconRow(g, ctx.font, x, y, w, Items.DEEPSLATE_BRICKS, "Catacombs",
                    ctx.profile.getAsJsonArray("catacombs_bar"));
            y += 2;
        } else {
            y = PvDraw.line(g, ctx.font, x, y, "§8No dungeon data");
        }
        JsonObject dungeons = PvDraw.obj(ctx.profile, "dungeons");
        if (dungeons != null) {
            long runs = PvDraw.num(dungeons, "runs_total");
            if (runs > 0) {
                y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Runs", "§f" + PvDraw.fmt(runs));
            }
            long secrets = PvDraw.num(dungeons, "secrets");
            if (secrets > 0) {
                y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Secrets", "§f" + PvDraw.fmt(secrets));
            }
        }
        JsonObject classes = PvDraw.obj(ctx.profile, "classes");
        if (classes == null || classes.isEmpty()) {
            return;
        }
        y = PvDraw.heading(g, ctx.font, x, y + 4, w, "Classes");
        for (var entry : classes.entrySet()) {
            if (y + ctx.font.lineHeight > ctx.bottom()) {
                break;
            }
            var bar = entry.getValue().getAsJsonArray();
            boolean maxed = bar.size() > 1 && bar.get(1).getAsInt() >= 1000;
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, PvDraw.pretty(entry.getKey()),
                    (maxed ? "§d" : "§f") + bar.get(0).getAsInt());
        }
    }

    private void drawSlayers(GuiGraphicsExtractor g, PvContext ctx, int x, int w) {
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w, "Slayer");
        JsonObject slayers = PvDraw.obj(ctx.profile, "slayers");
        if (slayers == null || slayers.isEmpty()) {
            PvDraw.line(g, ctx.font, x, y, "§8No slayer data");
            return;
        }
        int total = 0;
        for (String s : SLAYER_ORDER) {
            if (slayers.has(s)) {
                total += slayers.getAsJsonArray(s).get(0).getAsInt();
            }
        }
        y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Total Level", "§b" + total);
        JsonObject detail = PvDraw.obj(ctx.profile, "slayer_detail");
        if (detail != null && detail.has("xp_total")) {
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Total XP",
                    "§f" + PvDraw.fmt(PvDraw.num(detail, "xp_total")));
        }
        if (detail != null && detail.has("bosses_total")) {
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Bosses",
                    "§f" + PvDraw.fmt(PvDraw.num(detail, "bosses_total")));
        }
        y += 3;
        for (String s : SLAYER_ORDER) {
            if (!slayers.has(s) || y + ctx.font.lineHeight > ctx.bottom()) {
                continue;
            }
            var bar = slayers.getAsJsonArray(s);
            boolean maxed = bar.size() > 1 && bar.get(1).getAsInt() >= 1000;
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, PvDraw.pretty(s),
                    (maxed ? "§d" : "§f") + bar.get(0).getAsInt());
        }
    }

    /** Kuudra + Diana share the last column: both are "event" grinds with a single headline number. */
    private void drawEvents(GuiGraphicsExtractor g, PvContext ctx, int x, int w) {
        int y = PvDraw.heading(g, ctx.font, x, ctx.y, w, "Kuudra");
        JsonObject kuudra = PvDraw.obj(ctx.profile, "kuudra");
        if (kuudra == null || kuudra.isEmpty()) {
            y = PvDraw.line(g, ctx.font, x, y, "§8No Kuudra runs");
        } else {
            long total = 0;
            for (var entry : kuudra.entrySet()) {
                total += entry.getValue().getAsLong();
            }
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Total Runs", "§6" + PvDraw.fmt(total));
            for (String tier : KUUDRA_TIERS) {
                if (!kuudra.has(tier) || y + ctx.font.lineHeight > ctx.bottom()) {
                    continue;
                }
                y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, KuudraPage.tierName(tier),
                        "§f" + PvDraw.fmt(PvDraw.num(kuudra, tier)));
            }
        }
        y = PvDraw.heading(g, ctx.font, x, y + 4, w, "Diana");
        if (ctx.profile.has("mythos_kills")) {
            y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Mythos Kills",
                    "§f" + PvDraw.fmt(PvDraw.num(ctx.profile, "mythos_kills")));
        }
        JsonObject diana = PvDraw.obj(ctx.profile, "diana");
        if (diana != null) {
            if (diana.has("burrows_dug")) {
                y = PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Burrows",
                        "§f" + PvDraw.fmt(PvDraw.num(diana, "burrows_dug")));
            }
            if (diana.has("chains")) {
                PvDraw.keyValue(g, ctx.font, x, y, w - 4, "Chains",
                        "§f" + PvDraw.fmt(PvDraw.num(diana, "chains")));
            }
        }
    }

}
