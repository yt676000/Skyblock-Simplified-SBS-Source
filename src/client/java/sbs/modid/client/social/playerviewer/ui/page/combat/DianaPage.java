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
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * Combat ▸ Diana – the Mythological Ritual: burrow counters, the mythos mob kills that scale the
 * Beastmaster Crest, and the rare drops from the ritual.
 *
 * <p>Mythos kills is a <b>profile-wide</b> counter (the crest scales with the profile, not with the
 * item), which is why it reads off the profile view rather than any inventory.
 */
public final class DianaPage implements PvPage {

    private static final int CARD_H = 34;
    private static final int GAP = 6;

    /** The mythos mobs the ritual can spawn, in the order the Bestiary lists them. */
    private static final List<String> MOBS = List.of(
            "minotaur", "gaia_construct", "siamese_lynx", "minos_hunter",
            "minos_champion", "minos_inquisitor");

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject diana = PvDraw.obj(ctx.profile, "diana");
        boolean anyKills = ctx.profile.has("mythos_kills");
        if (diana == null && !anyKills) {
            PvDraw.empty(g, ctx, "No Diana data.");
            return;
        }
        int y = drawCards(g, ctx, diana);
        int left = Math.min(260, ctx.width / 2 - 5);
        drawMobs(g, ctx, diana, ctx.x, y + 4, left);
        drawBurrows(g, ctx, diana, ctx.x + left + 10, y + 4, ctx.right() - (ctx.x + left + 10));
    }

    /** The headline counters as a row of cards. */
    private int drawCards(GuiGraphicsExtractor g, PvContext ctx, JsonObject diana) {
        String[][] cards = {
                {"Mythos Kills", PvDraw.fmt(PvDraw.num(ctx.profile, "mythos_kills"))},
                {"Burrows Dug", diana != null ? PvDraw.fmt(PvDraw.num(diana, "burrows_dug")) : "-"},
                {"Chains", diana != null ? PvDraw.fmt(PvDraw.num(diana, "chains")) : "-"},
                {"Griffin Level", diana != null && diana.has("griffin_level")
                        ? String.valueOf(PvDraw.num(diana, "griffin_level")) : "-"}};
        for (String[] c : cards) {
            c[1] = "§b" + c[1];
        }
        return PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, CARD_H, cards);
    }

    /** Bestiary kills of the ritual's mobs – the Inquisitor line is what people actually look for. */
    private void drawMobs(GuiGraphicsExtractor g, PvContext ctx, JsonObject diana, int x, int y, int w) {
        y = PvDraw.heading(g, ctx.font, x, y, w, "Mythos Mobs");
        JsonObject kills = PvDraw.obj(diana, "mob_kills");
        if (kills == null || kills.isEmpty()) {
            PvDraw.line(g, ctx.font, x, y, "§8No bestiary data");
            return;
        }
        for (String mob : MOBS) {
            if (y + ctx.font.lineHeight > ctx.bottom()) {
                break;
            }
            // Inquisitors are the headline number of the whole event, so they get the accent.
            String color = mob.equals("minos_inquisitor") ? "§d" : "§f";
            y = PvDraw.keyValue(g, ctx.font, x, y, w, PvDraw.pretty(mob),
                    color + PvDraw.fmt(PvDraw.num(kills, mob)));
        }
    }

    /**
     * How the burrows were dug. Hypixel keeps no counters for the ritual's rare drops, so the
     * honest breakdown of the burrow chain is what this half shows instead.
     */
    private void drawBurrows(GuiGraphicsExtractor g, PvContext ctx, JsonObject diana,
                             int x, int y, int w) {
        y = PvDraw.heading(g, ctx.font, x, y, w, "Burrows");
        if (diana == null || !diana.has("burrows_dug")) {
            PvDraw.line(g, ctx.font, x, y, "§8No burrow data");
            return;
        }
        long total = PvDraw.num(diana, "burrows_dug");
        for (String[] row : new String[][]{{"burrows_combat", "Combat"},
                {"burrows_treasure", "Treasure"}, {"burrows_next", "Empty (Next)"}}) {
            if (!diana.has(row[0]) || y + ctx.font.lineHeight + 4 > ctx.bottom()) {
                continue;
            }
            long value = PvDraw.num(diana, row[0]);
            y = PvDraw.keyValue(g, ctx.font, x, y, w, row[1],
                    "§f" + PvDraw.fmt(value) + " §8" + percent(value, total));
            PvDraw.bar(g, x, y, w, total > 0 ? value / (double) total : 0, SBSTheme.ACCENT);
            y += 5;
        }
        if (diana.has("chains") && y + ctx.font.lineHeight <= ctx.bottom()) {
            PvDraw.keyValue(g, ctx.font, x, y + 2, w, "Chains Completed",
                    "§f" + PvDraw.fmt(PvDraw.num(diana, "chains")));
        }
    }

    private String percent(long value, long total) {
        return total > 0 ? Math.round(value * 100.0 / total) + "%" : "";
    }
}
