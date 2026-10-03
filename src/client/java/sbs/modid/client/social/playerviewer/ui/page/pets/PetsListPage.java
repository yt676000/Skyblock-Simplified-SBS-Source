/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.pets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.ArrayList;
import java.util.List;

/**
 * Pets ▸ Overview – every pet as a full row (icon, tier-colored name, level, active marker, held
 * item) instead of a bare icon grid, so nothing has to be hovered to be read. Clicking a pet opens
 * a detail card beside the list.
 */
public final class PetsListPage implements PvPage {

    private static final int ROW_H = 20;
    /** Width of the pet detail card opened by clicking a pet. */
    private static final int DETAIL_W = 150;

    private int selected = -1;
    /** List width from the last frame, so clicks map to the same rows that were drawn. */
    private int listW;

    @Override
    public void reset() {
        selected = -1;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonArray pets = PvDraw.arr(ctx.profile, "pets");
        if (pets.isEmpty()) {
            PvDraw.empty(g, ctx, "No pets.");
            listW = 0;
            return;
        }
        if (selected >= pets.size()) {
            selected = -1;
        }
        // With a pet selected the list narrows to make room for the detail card.
        int detailW = selected >= 0 ? DETAIL_W : 0;
        listW = ctx.width - detailW - (detailW > 0 ? 6 : 0);

        int listBottom = ctx.bottom() - ctx.font.lineHeight - 2;   // last line is the total
        int visibleRows = Math.max(1, (listBottom - ctx.y) / ROW_H);
        int scroll = ctx.rows(pets.size(), visibleRows);

        boolean scrollable = pets.size() > visibleRows;
        int rowW = listW - (scrollable ? 6 : 0);
        int y = ctx.y;
        for (int i = scroll; i < pets.size() && i < scroll + visibleRows; i++) {
            drawRow(g, ctx, pets.get(i).getAsJsonObject(), i, ctx.x, y, rowW);
            y += ROW_H;
        }
        if (scrollable) {
            PvDraw.scrollbar(g, ctx.x + listW - 3, ctx.y, visibleRows * ROW_H,
                    pets.size(), visibleRows, scroll);
        }
        g.text(ctx.font, Component.literal("§8" + pets.size() + " pets  •  scroll for more"),
                ctx.x, listBottom + 2, SBSTheme.TEXT_MUTED);
        if (selected >= 0) {
            drawDetail(g, ctx, pets.get(selected).getAsJsonObject(), ctx.x + listW + 6, detailW);
        }
    }

    /** One pet row: icon, active dot, tier-colored name, held-item icon and the level. */
    private void drawRow(GuiGraphicsExtractor g, PvContext ctx, JsonObject pet, int index,
                         int x, int y, int w) {
        int h = ROW_H - 2;
        boolean isSelected = index == selected;
        boolean hovered = ctx.hovered(x, y, w, h);
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                isSelected || hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                isSelected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.item(PvDraw.petIcon(PvDraw.str(pet, "type")), x + 2, y + 1);

        String color = PvDraw.TIER_COLOR.getOrDefault(PvDraw.str(pet, "tier"), "§f");
        int textY = y + (h - ctx.font.lineHeight) / 2;
        int tx = x + 22;
        if (pet.has("active") && pet.get("active").getAsBoolean()) {
            g.text(ctx.font, Component.literal("§a•"), tx, textY, SBSTheme.TEXT);
            tx += ctx.font.width("• ");
        }

        // Level sits hard right, the held item just left of it; the name gets what is left and is
        // trimmed rather than allowed to run under them.
        int rx = x + w - 4;
        String lvl = color + "[" + pet.get("level").getAsInt() + "]";
        rx -= ctx.font.width(lvl);
        g.text(ctx.font, Component.literal(lvl), rx, textY, SBSTheme.TEXT);
        boolean held = PvDraw.has(pet, "item");
        if (held) {
            rx -= PvDraw.CELL;
            g.item(SkyBlockItemIcons.getInstance().icon(PvDraw.str(pet, "item"), null, 1), rx, y + 1);
        }
        String name = color + PvDraw.pretty(PvDraw.str(pet, "type"));
        int nameSpace = rx - 4 - tx;
        if (nameSpace > 8) {
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, name, nameSpace)), tx, textY,
                    SBSTheme.TEXT);
        }

        if (hovered && selected < 0) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(color + PvDraw.pretty(PvDraw.str(pet, "type"))));
            tip.add(Component.literal("§7Tier §f" + PvDraw.pretty(PvDraw.str(pet, "tier"))));
            tip.add(Component.literal("§7Level §f" + pet.get("level").getAsInt()));
            if (held) {
                tip.add(Component.literal("§7Held §f" + PvDraw.pretty(PvDraw.str(pet, "item"))));
            }
            tip.add(Component.literal("§8click for details"));
            PvDraw.tooltip(g, ctx.font, tip, ctx.mouseX, ctx.mouseY);
        }
    }

    /** The detail card for a clicked pet: framed icon, tier-colored name, level, held item. */
    private void drawDetail(GuiGraphicsExtractor g, PvContext ctx, JsonObject pet, int x, int w) {
        SciFiRender.roundedRectWithBorder(g, x, ctx.y, w, ctx.height, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        int cx = x + w / 2;
        String tier = PvDraw.str(pet, "tier");
        String color = PvDraw.TIER_COLOR.getOrDefault(tier, "§f");
        int iconY = ctx.y + 8;
        SciFiRender.roundedRectWithBorder(g, cx - 12, iconY, 24, 24, 3,
                SBSTheme.CARD_BG_DISABLED, SBSTheme.CARD_BORDER);
        g.item(PvDraw.petIcon(PvDraw.str(pet, "type")), cx - 8, iconY + 4);
        int y = iconY + 30;
        g.centeredText(ctx.font, Component.literal(color + PvDraw.pretty(PvDraw.str(pet, "type"))),
                cx, y, SBSTheme.TEXT);
        y += ctx.font.lineHeight + 2;
        g.centeredText(ctx.font, Component.literal(color + tier), cx, y, SBSTheme.TEXT);
        y += ctx.font.lineHeight + 5;
        y = PvDraw.line(g, ctx.font, x + 8, y, "§7Level §f" + pet.get("level").getAsInt());
        boolean active = pet.has("active") && pet.get("active").getAsBoolean();
        y = PvDraw.line(g, ctx.font, x + 8, y, "§7Active: " + (active ? "§ayes" : "§8no"));
        y = PvDraw.heading(g, ctx.font, x + 8, y + 4, w - 16, "Held Item");
        if (PvDraw.has(pet, "item")) {
            String heldId = PvDraw.str(pet, "item");
            PvDraw.cell(g, x + 8, y);
            g.item(SkyBlockItemIcons.getInstance().icon(heldId, null, 1), x + 9, y + 1);
            int nameX = x + 8 + PvDraw.CELL + 2;
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, "§f" + PvDraw.pretty(heldId),
                            x + w - 8 - nameX)),
                    nameX, y + (PvDraw.CELL - ctx.font.lineHeight) / 2, SBSTheme.TEXT);
        } else {
            g.text(ctx.font, Component.literal("§8none"), x + 8, y, SBSTheme.TEXT_MUTED);
        }
        g.centeredText(ctx.font, Component.literal("§8click again to close"),
                cx, ctx.bottom() - ctx.font.lineHeight - 2, SBSTheme.TEXT_MUTED);
    }

    @Override
    public boolean mouseClicked(PvContext ctx, double mouseX, double mouseY) {
        JsonArray pets = PvDraw.arr(ctx.profile, "pets");
        if (mouseX >= ctx.x && mouseX < ctx.x + listW
                && mouseY >= ctx.y && mouseY < ctx.bottom()) {
            int idx = ctx.scroll + (int) ((mouseY - ctx.y) / ROW_H);
            if (idx >= 0 && idx < pets.size()) {
                selected = (idx == selected) ? -1 : idx;   // toggle
                return true;
            }
        }
        selected = -1;   // click elsewhere closes the detail card
        return false;
    }
}
