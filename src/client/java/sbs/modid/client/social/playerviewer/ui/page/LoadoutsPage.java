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

import java.util.ArrayList;
import java.util.List;

/**
 * Inventory ▸ Loadouts – the saved armour loadouts and, separately, the saved equipment sets.
 *
 * <p>This is what used to be the Wardrobe: Hypixel moved it to {@code member.loadout} and dropped
 * {@code wardrobe_contents}. Crucially, {@code armor} loadouts and {@code equipment} sets are
 * <b>independent</b> lists of different lengths – there is no "equipment set N belongs to armour set
 * N" mapping in the API (the player picks the combination live in-game). So the two are shown as two
 * sections, never paired row-for-row.
 */
public final class LoadoutsPage implements PvPage {

    private static final int ROW_H = 20;
    /** The four armour slots always get a cell each, so sets line up even with a piece missing. */
    private static final int ARMOR_SLOTS = 4;
    /** Equipment sets hold up to four pieces (necklace / cloak / belt / gloves). */
    private static final int EQUIP_SLOTS = 4;

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject loadouts = PvDraw.obj(ctx.profile, "loadouts");
        JsonArray armorSets = PvDraw.arr(loadouts, "sets");
        JsonArray equipSets = PvDraw.arr(loadouts, "equipment_sets");
        if (armorSets.isEmpty() && equipSets.isEmpty()) {
            PvDraw.empty(g, ctx, "No loadouts saved.");
            return;
        }
        int equippedArmor = (int) PvDraw.num(loadouts, "equipped_armor");
        int equippedEquip = (int) PvDraw.num(loadouts, "equipped_equipment");

        // One flat, scrollable list: the armour loadouts, then (if any) an "Equipment" header and the
        // equipment sets. Never zipped together – they are independent (see class doc).
        List<Row> rows = new ArrayList<>();
        for (JsonElement e : armorSets) {
            rows.add(Row.armor(e.getAsJsonObject()));
        }
        if (!equipSets.isEmpty()) {
            rows.add(Row.header("Equipment sets"));
            for (JsonElement e : equipSets) {
                rows.add(Row.equip(e.getAsJsonObject()));
            }
        }

        int headerH = ctx.font.lineHeight + 4;
        g.text(ctx.font, Component.literal("§7" + armorSets.size() + " loadouts §8• §7"
                        + equipSets.size() + " equipment sets §8• worn marked"),
                ctx.x, ctx.y, SBSTheme.TEXT_MUTED);
        int top = ctx.y + headerH;

        int visibleRows = Math.max(1, (ctx.bottom() - top) / ROW_H);
        int scroll = ctx.rows(rows.size(), visibleRows);
        boolean scrollable = rows.size() > visibleRows;
        int rowW = ctx.width - (scrollable ? 6 : 0);
        int y = top;
        for (int i = scroll; i < rows.size() && i < scroll + visibleRows; i++) {
            Row row = rows.get(i);
            if (row.header != null) {
                drawHeader(g, ctx, row.header, ctx.x, y);
            } else if (row.armor) {
                drawSet(g, ctx, row.set, equippedArmor, ctx.x, y, rowW, "armor", ARMOR_SLOTS);
            } else {
                drawSet(g, ctx, row.set, equippedEquip, ctx.x, y, rowW, "equipment", EQUIP_SLOTS);
            }
            y += ROW_H;
        }
        if (scrollable) {
            PvDraw.scrollbar(g, ctx.x + ctx.width - 3, top, visibleRows * ROW_H,
                    rows.size(), visibleRows, scroll);
        }
    }

    /** A small muted section divider between the armour loadouts and the equipment sets. */
    private void drawHeader(GuiGraphicsExtractor g, PvContext ctx, String label, int x, int y) {
        int midY = y + ROW_H / 2;
        g.text(ctx.font, Component.literal("§8" + label), x + 2,
                y + (ROW_H - ctx.font.lineHeight) / 2, SBSTheme.TEXT_MUTED);
        int labelW = ctx.font.width(label) + 8;
        g.fill(x + 2 + labelW, midY, x + ctx.width - 4, midY + 1, SBSTheme.ACCENT_SOFT);
    }

    /** One set row: name on the left, then its item cells (armour or equipment) hard right. */
    private void drawSet(GuiGraphicsExtractor g, PvContext ctx, JsonObject set, int equipped,
                         int x, int y, int w, String field, int slots) {
        int h = ROW_H - 2;
        boolean isWorn = PvDraw.num(set, "id") == equipped;
        boolean hovered = ctx.hovered(x, y, w, h);
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                isWorn || hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                isWorn ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        if (isWorn) {
            g.fill(x + 1, y + 2, x + 3, y + h - 2, SBSTheme.ACCENT);
        }

        JsonArray pieces = PvDraw.arr(set, field);
        // The cells sit hard right; the name takes whatever is left of them, so a long set name
        // can never push an item off its own row.
        int cellsW = slots * PvDraw.CELL;
        int cellX = x + w - 3 - cellsW;

        String name = (isWorn ? "§b" : "§f") + PvDraw.str(set, "name");
        int nameX = x + 6;
        int space = cellX - 4 - nameX;
        if (space > 8) {
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, name, space)), nameX,
                    y + (h - ctx.font.lineHeight) / 2, SBSTheme.TEXT);
        }

        int cx = cellX;
        for (int i = 0; i < slots; i++) {
            JsonElement piece = i < pieces.size() ? pieces.get(i) : null;
            PvDraw.slot(g, ctx.font, piece, cx, y + 1, ctx.mouseX, ctx.mouseY);
            cx += PvDraw.CELL;
        }
    }

    /** One row of the flat list: an armour set, an equipment set, or a section header. */
    private static final class Row {
        private final JsonObject set;
        private final boolean armor;
        private final String header;

        private Row(JsonObject set, boolean armor, String header) {
            this.set = set;
            this.armor = armor;
            this.header = header;
        }

        static Row armor(JsonObject set) {
            return new Row(set, true, null);
        }

        static Row equip(JsonObject set) {
            return new Row(set, false, null);
        }

        static Row header(String label) {
            return new Row(null, false, label);
        }
    }
}
