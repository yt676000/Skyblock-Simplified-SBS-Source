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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.ArrayList;
import java.util.List;

/**
 * Inventory ▸ Accessories – the whole accessory catalogue, owned ones lit and missing ones greyed,
 * grouped by rarity with each one's Magical Power contribution.
 *
 * <p>Two MP numbers are shown on purpose: the sum this page can prove from the bag's contents, and
 * the profile's all-time highest that Hypixel reports. They legitimately differ (Abicase contacts,
 * accessories carried outside the bag, a since-sold piece), and quoting only one of them would be
 * the misleading choice.
 */
public final class AccessoriesPage implements PvPage {

    private static final int SUMMARY_H = 30;
    /** Toggle chips: which half of the catalogue is listed. */
    private static final String[] TABS = {"Owned", "Missing", "All"};
    private static final int CHIP_H = 13;

    private int tab;
    private int chipY;
    private int chipW;

    @Override
    public void reset() {
        tab = 0;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject acc = PvDraw.obj(ctx.profile, "accessories");
        if (acc == null) {
            PvDraw.empty(g, ctx, "No accessory bag data.");
            return;
        }
        int y = drawSummary(g, ctx, acc);
        y = drawTabs(g, ctx, acc, y);
        drawGrid(g, ctx, entries(acc), y);
    }

    /** The MP headline plus the bag's own upgrades. */
    private int drawSummary(GuiGraphicsExtractor g, PvContext ctx, JsonObject acc) {
        SciFiRender.roundedRectWithBorder(g, ctx.x, ctx.y, ctx.width, SUMMARY_H,
                SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        int pad = 6;
        int y = ctx.y + 4;
        long owned = PvDraw.arr(acc, "owned").size();
        long total = PvDraw.num(acc, "catalogue_total");
        g.text(ctx.font, Component.literal("§7Owned §f" + owned + "§8/" + total), ctx.x + pad, y,
                SBSTheme.TEXT);

        // MP reads right-aligned; the two values are labelled so they can't be confused.
        String mp = "§7MP §d" + PvDraw.num(acc, "mp_computed")
                + (acc.has("mp_highest") ? " §8(peak " + PvDraw.num(acc, "mp_highest") + ")" : "");
        g.text(ctx.font, Component.literal(mp), ctx.right() - pad - ctx.font.width(mp), y,
                SBSTheme.TEXT);
        y += ctx.font.lineHeight + 3;
        PvDraw.bar(g, ctx.x + pad, y, ctx.width - pad * 2,
                total > 0 ? owned / (double) total : 0, SBSTheme.ACCENT);
        y += 4;
        String power = PvDraw.str(acc, "power");
        if (!power.isEmpty()) {
            g.text(ctx.font, Component.literal("§8Power: §7" + PvDraw.pretty(power)),
                    ctx.x + pad, y, SBSTheme.TEXT_MUTED);
        }
        if (acc.has("bag_upgrades")) {
            String up = "§8" + PvDraw.num(acc, "bag_upgrades") + " bag upgrades";
            g.text(ctx.font, Component.literal(up), ctx.right() - pad - ctx.font.width(up), y,
                    SBSTheme.TEXT_MUTED);
        }
        return ctx.y + SUMMARY_H + 4;
    }

    private int drawTabs(GuiGraphicsExtractor g, PvContext ctx, JsonObject acc, int y) {
        chipY = y;
        chipW = Math.min(70, ctx.width / 4);
        for (int i = 0; i < TABS.length; i++) {
            int x = ctx.x + i * (chipW + 4);
            boolean active = i == tab;
            boolean hovered = ctx.hovered(x, y, chipW, CHIP_H);
            SciFiRender.roundedRectWithBorder(g, x, y, chipW, CHIP_H, SBSTheme.CORNER_RADIUS,
                    active || hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            int count = switch (i) {
                case 0 -> PvDraw.arr(acc, "owned").size();
                case 1 -> PvDraw.arr(acc, "missing").size();
                default -> PvDraw.arr(acc, "owned").size() + PvDraw.arr(acc, "missing").size();
            };
            g.centeredText(ctx.font, Component.literal(TABS[i] + " §8" + count),
                    x + chipW / 2, y + (CHIP_H - ctx.font.lineHeight) / 2,
                    active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
        }
        return y + CHIP_H + 4;
    }

    /** The accessory list for the active chip, already rarity-sorted by the backend. */
    private List<JsonObject> entries(JsonObject acc) {
        List<JsonObject> out = new ArrayList<>();
        if (tab != 1) {
            for (JsonElement e : PvDraw.arr(acc, "owned")) {
                out.add(e.getAsJsonObject());
            }
        }
        if (tab != 0) {
            for (JsonElement e : PvDraw.arr(acc, "missing")) {
                out.add(e.getAsJsonObject());
            }
        }
        return out;
    }

    /** An icon grid: owned accessories in full colour, missing ones dimmed to a bare cell. */
    private void drawGrid(GuiGraphicsExtractor g, PvContext ctx, List<JsonObject> list, int top) {
        if (list.isEmpty()) {
            PvDraw.empty(g, ctx, tab == 1 ? "Nothing missing – the whole catalogue is owned."
                    : "No accessories.");
            return;
        }
        int cols = Math.max(1, ctx.width / PvDraw.CELL);
        int visibleRows = Math.max(1, (ctx.bottom() - top) / PvDraw.CELL);
        int totalRows = (list.size() + cols - 1) / cols;
        int scroll = ctx.rows(totalRows, visibleRows);
        int y = top;
        for (int row = scroll; row < totalRows && row < scroll + visibleRows; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= list.size()) {
                    break;
                }
                drawCell(g, ctx, list.get(idx), ctx.x + col * PvDraw.CELL, y);
            }
            y += PvDraw.CELL;
        }
    }

    /** Dark veil laid over a missing accessory's icon so it reads as "not collected" but stays legible. */
    private static final int MISSING_VEIL = 0xB00A1626;

    private void drawCell(GuiGraphicsExtractor g, PvContext ctx, JsonObject acc, int x, int y) {
        boolean owned = acc.has("owned") && acc.get("owned").getAsBoolean();
        boolean viaUpgrade = acc.has("via_upgrade") && acc.get("via_upgrade").getAsBoolean();
        String rarity = PvDraw.str(acc, "rarity");
        String color = PvDraw.TIER_COLOR.getOrDefault(rarity, "§f");
        // Both owned and missing cells now show the real icon; the rarity-coloured border keeps the
        // tier readable at a glance, and missing icons get a dark veil so the two never blur together.
        SciFiRender.roundedRectWithBorder(g, x, y, PvDraw.CELL - 1, PvDraw.CELL - 1, 2,
                owned ? SBSTheme.CARD_BG : SBSTheme.CARD_BG_DISABLED,
                owned ? SBSTheme.CARD_BORDER : rarityRgb(rarity));
        ItemStack icon = SkyBlockItemIcons.getInstance().icon(PvDraw.str(acc, "id"), null, 1);
        g.item(icon.is(Items.BARRIER) ? new ItemStack(Items.GOLD_NUGGET) : icon, x + 1, y + 1);
        if (!owned) {
            g.fill(x + 1, y + 1, x + PvDraw.CELL - 1, y + PvDraw.CELL - 1, MISSING_VEIL);
        } else if (acc.has("recomb") && acc.get("recomb").getAsBoolean()) {
            // A recombobulated piece is worth a rarity step more MP - mark it.
            g.fill(x + PvDraw.CELL - 4, y, x + PvDraw.CELL - 1, y + 3, 0xFFD060E0);
        }
        if (ctx.hovered(x, y, PvDraw.CELL, PvDraw.CELL)) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(color + PvDraw.str(acc, "name")));
            tip.add(Component.literal("§7" + PvDraw.pretty(rarity)
                    + (acc.has("recomb") && acc.get("recomb").getAsBoolean() ? " §d(recomb)" : "")));
            tip.add(Component.literal("§7Magical Power §d" + PvDraw.num(acc, "mp")));
            // "Owned via a higher tier" (you upgraded this one away) reads differently from a true miss.
            tip.add(Component.literal(owned
                    ? (viaUpgrade ? "§aowned §8(upgraded tier)" : "§aowned")
                    : "§cmissing"));
            tip.add(Component.literal("§8" + PvDraw.str(acc, "id")));
            PvDraw.tooltip(g, ctx.font, tip, ctx.mouseX, ctx.mouseY);
        }
    }

    private int rarityRgb(String rarity) {
        return switch (rarity) {
            case "UNCOMMON" -> 0xFF55FF55;
            case "RARE" -> 0xFF5555FF;
            case "EPIC" -> 0xFFAA00AA;
            case "LEGENDARY" -> 0xFFFFAA00;
            case "MYTHIC" -> 0xFFFF55FF;
            default -> 0xFF8FA9C8;
        };
    }

    @Override
    public boolean mouseClicked(PvContext ctx, double mouseX, double mouseY) {
        for (int i = 0; i < TABS.length; i++) {
            int x = ctx.x + i * (chipW + 4);
            if (mouseX >= x && mouseX < x + chipW && mouseY >= chipY && mouseY < chipY + CHIP_H) {
                tab = i;
                return true;
            }
        }
        return false;
    }
}
