/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.List;

/**
 * The Collections category. One class serves every sub-page: the overview (all six Hypixel
 * categories with their unlocked/maxed counts) and each category's own item list.
 *
 * <p>Tier thresholds come from the backend, which reads Hypixel's collections resource – so a
 * SkyBlock update that adds a tier does not need a client change.
 */
public final class CollectionsPage implements PvPage {

    /** The Hypixel category key, or null for the overview across all of them. */
    private final String category;

    private static final int ROW_H = 20;
    /** Hypixel's category order, which is also the sidebar's. */
    private static final List<String> ORDER = List.of("FARMING", "MINING", "COMBAT",
            "FORAGING", "FISHING", "RIFT");

    public CollectionsPage(String category) {
        this.category = category;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject collections = PvDraw.obj(ctx.profile, "collections");
        if (collections == null || collections.isEmpty()) {
            PvDraw.empty(g, ctx, "No collection data.");
            return;
        }
        if (category == null) {
            drawOverview(g, ctx, collections);
            return;
        }
        JsonObject cat = PvDraw.obj(collections, category);
        if (cat == null) {
            PvDraw.empty(g, ctx, "No " + PvDraw.pretty(category) + " collections.");
            return;
        }
        drawCategory(g, ctx, cat);
    }

    // ------------------------------------------------------------------
    // Overview
    // ------------------------------------------------------------------

    /** Every category as a card: how many collections are started, and how many are maxed. */
    private void drawOverview(GuiGraphicsExtractor g, PvContext ctx, JsonObject collections) {
        int gap = 6;
        int cols = ctx.width >= 420 ? 3 : 2;
        int cardW = (ctx.width - gap * (cols - 1)) / cols;
        int cardH = 46;
        int i = 0;
        int maxedAll = 0;
        int totalAll = 0;
        for (String key : ORDER) {
            JsonObject cat = PvDraw.obj(collections, key);
            if (cat == null) {
                continue;
            }
            int x = ctx.x + (i % cols) * (cardW + gap);
            int y = ctx.y + (i / cols) * (cardH + gap);
            i++;
            maxedAll += (int) PvDraw.num(cat, "maxed");
            totalAll += (int) PvDraw.num(cat, "total");
            if (y + cardH > ctx.bottom()) {
                continue;
            }
            drawCategoryCard(g, ctx, cat, x, y, cardW, cardH);
        }
        int summaryY = ctx.y + ((i + cols - 1) / cols) * (cardH + gap) + 2;
        if (totalAll > 0 && summaryY + ctx.font.lineHeight <= ctx.bottom()) {
            g.text(ctx.font, Component.literal("§7Maxed overall §f" + maxedAll + "§8/" + totalAll),
                    ctx.x, summaryY, SBSTheme.TEXT);
        }
    }

    private void drawCategoryCard(GuiGraphicsExtractor g, PvContext ctx, JsonObject cat,
                                  int x, int y, int w, int h) {
        boolean hovered = ctx.hovered(x, y, w, h);
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        int pad = 6;
        long maxed = PvDraw.num(cat, "maxed");
        long total = PvDraw.num(cat, "total");
        long unlocked = PvDraw.num(cat, "unlocked");
        g.text(ctx.font, Component.literal("§b" + PvDraw.str(cat, "name")), x + pad, y + pad,
                SBSTheme.ACCENT_BRIGHT);
        String right = "§f" + maxed + "§8/" + total;
        g.text(ctx.font, Component.literal(right), x + w - pad - ctx.font.width(right), y + pad,
                SBSTheme.TEXT);
        int barY = y + pad + ctx.font.lineHeight + 4;
        PvDraw.bar(g, x + pad, barY, w - pad * 2, total > 0 ? maxed / (double) total : 0,
                SBSTheme.ACCENT);
        g.text(ctx.font, Component.literal("§8maxed  •  " + unlocked + " started"),
                x + pad, barY + 5, SBSTheme.TEXT_MUTED);
    }

    // ------------------------------------------------------------------
    // One category
    // ------------------------------------------------------------------

    /** Every collection of the category: icon, name, tier, amount and the bar to the next tier. */
    private void drawCategory(GuiGraphicsExtractor g, PvContext ctx, JsonObject cat) {
        JsonArray items = PvDraw.arr(cat, "items");
        if (items.isEmpty()) {
            PvDraw.empty(g, ctx, "No collections here.");
            return;
        }
        int headerH = ctx.font.lineHeight + 5;
        long maxed = PvDraw.num(cat, "maxed");
        long total = PvDraw.num(cat, "total");
        g.text(ctx.font, Component.literal("§7" + PvDraw.str(cat, "name") + " §8• §f"
                + maxed + "§8/" + total + " §7maxed"), ctx.x, ctx.y, SBSTheme.TEXT);
        int top = ctx.y + headerH;

        int visibleRows = Math.max(1, (ctx.bottom() - top) / ROW_H);
        int scroll = ctx.rows(items.size(), visibleRows);
        boolean scrollable = items.size() > visibleRows;
        int rowW = ctx.width - (scrollable ? 6 : 0);
        int y = top;
        for (int i = scroll; i < items.size() && i < scroll + visibleRows; i++) {
            drawRow(g, ctx, items.get(i).getAsJsonObject(), ctx.x, y, rowW);
            y += ROW_H;
        }
        if (scrollable) {
            PvDraw.scrollbar(g, ctx.x + ctx.width - 3, top, visibleRows * ROW_H,
                    items.size(), visibleRows, scroll);
        }
    }

    private void drawRow(GuiGraphicsExtractor g, PvContext ctx, JsonObject item,
                         int x, int y, int w) {
        int h = ROW_H - 2;
        boolean hovered = ctx.hovered(x, y, w, h);
        int tier = (int) PvDraw.num(item, "tier");
        int max = (int) PvDraw.num(item, "max");
        boolean isMaxed = max > 0 && tier >= max;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                isMaxed ? SBSTheme.ACCENT_SOFT : SBSTheme.CARD_BORDER);
        g.item(SkyBlockItemIcons.getInstance().icon(PvDraw.str(item, "id"), null, 1), x + 2, y + 1);

        // Tier hard right, amount left of it, name takes the rest – each in its own slot so no
        // pair can ever overlap however big the numbers get.
        String tierText = isMaxed ? "MAX" : tier + "§8/" + max;
        int tierW = Math.max(ctx.font.width(tierText), ctx.font.width("MAX"));
        int textY = y + (h - ctx.font.lineHeight) / 2;
        g.text(ctx.font, Component.literal(isMaxed ? "MAX" : "§f" + tierText),
                x + w - 4 - ctx.font.width(isMaxed ? "MAX" : tierText), textY,
                isMaxed ? PvDraw.chroma(x) : SBSTheme.TEXT);

        String amount = "§7" + PvDraw.fmt(PvDraw.num(item, "amount"));
        int amountX = x + w - 8 - tierW - ctx.font.width(amount);
        g.text(ctx.font, Component.literal(amount), amountX, textY, SBSTheme.TEXT_MUTED);

        int nameX = x + PvDraw.CELL + 3;
        int space = amountX - 4 - nameX;
        if (space > 8) {
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font,
                            "§f" + PvDraw.str(item, "name"), space)), nameX, textY, SBSTheme.TEXT);
        }
        // A hairline bar along the bottom edge shows progress toward the next tier.
        if (!isMaxed && item.has("next")) {
            long next = PvDraw.num(item, "next");
            long amountRaw = PvDraw.num(item, "amount");
            double frac = next > 0 ? Math.min(1.0, amountRaw / (double) next) : 0;
            g.fill(x + 1, y + h - 2, x + 1 + (int) ((w - 2) * frac), y + h - 1, PvDraw.SKILL_BAR);
        }
        if (hovered) {
            var tip = new java.util.ArrayList<Component>();
            tip.add(Component.literal("§f" + PvDraw.str(item, "name")));
            tip.add(Component.literal("§7Collected §f" + PvDraw.num(item, "amount")));
            tip.add(Component.literal("§7Tier §f" + tier + "§8/" + max));
            if (item.has("next")) {
                long remaining = PvDraw.num(item, "next") - PvDraw.num(item, "amount");
                tip.add(Component.literal("§7Next tier in §b" + PvDraw.fmt(Math.max(0, remaining))));
            } else if (isMaxed) {
                tip.add(Component.literal("§dMaxed"));
            }
            tip.add(Component.literal("§8" + PvDraw.str(item, "id")));
            PvDraw.tooltip(g, ctx.font, tip, ctx.mouseX, ctx.mouseY);
        }
    }
}
