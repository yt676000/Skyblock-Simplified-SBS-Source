/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Combat ▸ Slayer – one card per boss: level with its XP bar, total XP, and the kill count of every
 * tier. Cards are laid out in a grid that reflows to the panel width, so nothing overlaps when the
 * viewer is opened at a smaller GUI scale.
 */
public final class SlayerPage implements PvPage {

    private static final List<String> ORDER = List.of("zombie", "spider", "wolf",
            "enderman", "blaze", "vampire");
    private static final Map<String, Item> ICON = Map.of(
            "zombie", Items.ROTTEN_FLESH,
            "spider", Items.SPIDER_EYE,
            "wolf", Items.BONE,
            "enderman", Items.ENDER_PEARL,
            "blaze", Items.BLAZE_ROD,
            "vampire", Items.REDSTONE);
    private static final Map<String, String> NAME = Map.of(
            "zombie", "Revenant Horror", "spider", "Tarantula Broodfather", "wolf", "Sven Packmaster",
            "enderman", "Voidgloom Seraph", "blaze", "Inferno Demonlord", "vampire", "Riftstalker Bloodfiend");

    private static final int CARD_H = 60;
    private static final int GAP = 6;

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject slayers = PvDraw.obj(ctx.profile, "slayers");
        if (slayers == null || slayers.isEmpty()) {
            PvDraw.empty(g, ctx, "No slayer data.");
            return;
        }
        JsonObject detail = PvDraw.obj(ctx.profile, "slayer_detail");
        JsonObject bosses = PvDraw.obj(detail, "bosses");

        List<String> present = new ArrayList<>();
        for (String s : ORDER) {
            if (slayers.has(s)) {
                present.add(s);
            }
        }
        // Two columns whenever there is room for a card wide enough to hold the tier row.
        int cols = ctx.width >= 420 ? 2 : 1;
        int cardW = (ctx.width - GAP * (cols - 1)) / cols;
        int y = ctx.y;
        for (int i = 0; i < present.size(); i++) {
            int row = i / cols;
            int col = i % cols;
            int cardY = y + row * (CARD_H + GAP);
            if (cardY + CARD_H > ctx.bottom()) {
                break;
            }
            String slayer = present.get(i);
            drawCard(g, ctx, ctx.x + col * (cardW + GAP), cardY, cardW, slayer,
                    slayers.getAsJsonArray(slayer), PvDraw.obj(bosses, slayer));
        }
    }

    private void drawCard(GuiGraphicsExtractor g, PvContext ctx, int x, int y, int w,
                          String slayer, JsonArray bar, JsonObject boss) {
        SciFiRender.roundedRectWithBorder(g, x, y, w, CARD_H, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        int pad = 6;
        int level = bar.get(0).getAsInt();
        int promille = bar.size() > 1 ? bar.get(1).getAsInt() : 1000;
        boolean maxed = promille >= 1000;

        g.item(new ItemStack(ICON.getOrDefault(slayer, Items.BARRIER)), x + pad, y + pad - 1);

        // Level hard right, name trimmed against it.
        String lvl = maxed ? "MAX" : "Lvl " + level;
        int lvlW = ctx.font.width(lvl);
        g.text(ctx.font, Component.literal(lvl), x + w - pad - lvlW, y + pad,
                maxed ? PvDraw.chroma(x) : SBSTheme.ACCENT_BRIGHT);
        int nameX = x + pad + PvDraw.CELL + 2;
        int nameSpace = (x + w - pad - lvlW - 4) - nameX;
        if (nameSpace > 8) {
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font,
                            NAME.getOrDefault(slayer, PvDraw.pretty(slayer)), nameSpace)),
                    nameX, y + pad, SBSTheme.TEXT);
        }

        int barY = y + pad + ctx.font.lineHeight + 3;
        if (maxed) {
            PvDraw.maxedBar(g, x + pad, barY, w - pad * 2);
        } else {
            PvDraw.bar(g, x + pad, barY, w - pad * 2, promille / 1000.0, PvDraw.SKILL_BAR);
        }

        int infoY = barY + 5;
        if (boss != null && boss.has("xp")) {
            g.text(ctx.font, Component.literal("§7XP §f" + PvDraw.fmt(PvDraw.num(boss, "xp"))),
                    x + pad, infoY, SBSTheme.TEXT);
        }
        if (boss == null) {
            return;
        }
        // Tier kills: T1..T5 evenly spread on the last line, each its own slot so the numbers
        // cannot run together however large the counts get.
        JsonArray tiers = PvDraw.arr(boss, "tiers");
        if (tiers.isEmpty()) {
            return;
        }
        int tierY = infoY + ctx.font.lineHeight + 2;
        int slotW = (w - pad * 2) / Math.max(1, tiers.size());
        for (int t = 0; t < tiers.size(); t++) {
            String text = "§8T" + (t + 1) + " §f" + PvDraw.fmt(tiers.get(t).getAsLong());
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, text, slotW - 3)),
                    x + pad + t * slotW, tierY, SBSTheme.TEXT);
        }
    }
}
